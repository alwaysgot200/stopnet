package com.example.phonenet

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Context

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSettings: Button
    private lateinit var btnIgnoreBattery: Button
    private var hasResumedOnce = false
    private var isPinDialogShowing = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStartVpn)
        btnStop = findViewById(R.id.btnStopVpn)
        btnSettings = findViewById(R.id.btnSettings)

        btnStart.setOnClickListener { startVpn() }
        btnStop.setOnClickListener { stopVpn() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)
        btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        // 首次启动进行 PIN 检查
        checkAndGateByPin()
    }

    companion object {
        private const val PREPARE_VPN_REQ = 1001
        private const val REQUEST_NOTIF = 2001
    }

    private fun startVpn() {
        // Android 13+ 需要通知权限用于前台服务通知
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
                return
            }
        }
    
        // 引导忽略电池优化，减少后台被系统回收的概率
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val pkg = packageName
            if (pm?.isIgnoringBatteryOptimizations(pkg) == false) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$pkg")
                    }
                    startActivity(intent)
                    android.widget.Toast.makeText(this, "请允许忽略电池优化以提升服务稳定性", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { }
            }
        }
    
        val intent = VpnService.prepare(this)
        if (intent != null) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.vpn_permission_required))
                .setPositiveButton("OK") { _, _ ->
                    startActivityForResult(intent, PREPARE_VPN_REQ)
                }
                .show()
        } else {
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // 如果本应用是设备所有者（DO），进入锁定任务（Kiosk）以防止随意退出与设置篡改
            try {
                val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
                if (dpm?.isDeviceOwnerApp(packageName) == true) {
                    startLockTask()
                }
            } catch (_: Exception) {
                // 安全忽略
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PREPARE_VPN_REQ && resultCode == RESULT_OK) {
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startVpn()
            } else {
                android.widget.Toast.makeText(this, "需要通知权限以启动前台服务", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopVpn() {
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        stopService(serviceIntent)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val pkg = packageName
            if (pm?.isIgnoringBatteryOptimizations(pkg) == true) {
                android.widget.Toast.makeText(this, "已忽略电池优化", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$pkg")
                }
                startActivity(intent)
                android.widget.Toast.makeText(this, "请允许忽略电池优化以提升服务稳定性", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                try {
                    // 兜底：直接打开系统“电池优化”列表，让家长手动将 PhoneNet 设为“不优化”
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    android.widget.Toast.makeText(this, "请在电池优化列表中将 PhoneNet 设置为“不要优化”", android.widget.Toast.LENGTH_LONG).show()
                } catch (__: Exception) {
                    android.widget.Toast.makeText(this, "无法打开电池优化设置，请在系统设置中手动查找“电池优化”", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            android.widget.Toast.makeText(this, "当前系统版本无需此设置", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    private fun checkAndGateByPin() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("pin", null)
        if (isPinDialogShowing) return
        if (saved.isNullOrEmpty()) {
            showSetPinDialog()
        } else {
            showEnterPinDialog(saved)
        }
    }

    private fun showEnterPinDialog(saved: String) {
        isPinDialogShowing = true
        val input = android.widget.EditText(this)
        input.hint = getString(R.string.enter_pin)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_pin))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val entered = input.text?.toString() ?: ""
                if (entered != saved) {
                    android.widget.Toast.makeText(this, getString(R.string.wrong_pin), android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .create()
        dialog.setOnDismissListener { isPinDialogShowing = false }
        dialog.show()
    }

    private fun showSetPinDialog() {
        isPinDialogShowing = true
        val input1 = android.widget.EditText(this)
        input1.hint = getString(R.string.set_pin)
        val input2 = android.widget.EditText(this)
        input2.hint = getString(R.string.confirm_pin)
        val container = androidx.appcompat.widget.LinearLayoutCompat(this).apply {
            orientation = androidx.appcompat.widget.LinearLayoutCompat.VERTICAL
            setPadding(24, 24, 24, 0)
            addView(input1)
            addView(input2)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_pin))
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val p1 = input1.text?.toString()?.trim() ?: ""
                val p2 = input2.text?.toString()?.trim() ?: ""
                if (p1.isNotEmpty() && p1 == p2) {
                    val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("pin", p1).apply()
                } else {
                    android.widget.Toast.makeText(this, getString(R.string.wrong_pin), android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .create()
        dialog.setOnDismissListener { isPinDialogShowing = false }
        dialog.show()
    }
    override fun onResume() {
        super.onResume()
        // 首次启动的 onResume 不再重复检查；之后每次回到前台都检查
        if (hasResumedOnce) {
            checkAndGateByPin()
        }
        hasResumedOnce = true
    }
}