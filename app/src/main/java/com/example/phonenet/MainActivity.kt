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
    private lateinit var btnToggleVpn: Button
    private lateinit var btnSettings: Button
    private lateinit var btnIgnoreBattery: Button
    private var hasResumedOnce = false
    private var pendingShowPinAfterFlow = false
    private var didShowPin = false
    // 新增：修复未定义错误
    private var isPinDialogShowing = false
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 合并按钮：不再使用 btnStart/btnStop
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        btnSettings = findViewById(R.id.btnSettings)
        btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)
    
        btnToggleVpn.setOnClickListener { toggleVpn() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
    
        // 冷启动自动尝试启动管控；按策略处理 PIN
        attemptAutoStartOnLaunch()
        updateBatteryButtonState()
        updateToggleButtonState()
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
            // 如果是设备所有者（DO），进入锁定任务（Kiosk）
            try {
                val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
                if (dpm?.isDeviceOwnerApp(packageName) == true) {
                    startLockTask()
                }
            } catch (_: Exception) { }
            updateToggleButtonState()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PREPARE_VPN_REQ) {
            if (resultCode == RESULT_OK) {
                val serviceIntent = Intent(this, FirewallVpnService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                updateToggleButtonState()
            }
            // 仅在未开启“自动启动”时，才在权限流程后弹 PIN
            val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)
            if (!autoStart) {
                showPinIfNeeded()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 按“自动启动”策略继续
                attemptAutoStartOnLaunch()
            } else {
                // 未开启自动启动时，继续 PIN 门禁一次
                val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)
                if (!autoStart) {
                    showPinIfNeeded()
                }
            }
        }
    }

    private fun toggleVpn() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)
        if (running) {
            stopVpn()
        } else {
            startVpn()
        }
    }

    private fun stopVpn() {
        // 可选：写入“用户手动停止”标记（若服务端已实现不自恢复逻辑）
        try {
            val p1 = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_user_stop", true).apply()
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_user_stop", true).apply()
        } catch (_: Exception) { }

        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        stopService(serviceIntent)

        updateToggleButtonState()
        // 停止后刷新按钮显示
        updateButtonsState()
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
        // 不二次弹 PIN；刷新电池优化与按钮状态
        updateBatteryButtonState()
        updateToggleButtonState()
        hasResumedOnce = true
    }
    private fun attemptAutoStartOnLaunch() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_on_boot", true)

        if (autoStart) {
            // 自动启动：不弹 PIN，直接按权限流程尝试启动
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
                    return
                }
            }
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, PREPARE_VPN_REQ)
                return
            }
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateToggleButtonState()
        } else {
            // 不自动启动：保持之前状态，先进行一次 PIN 验证
            updateToggleButtonState()
            showPinIfNeeded()
        }
    }
    private fun showPinIfNeeded() {
        if (didShowPin) return
        didShowPin = true
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("pin", null)
        if (saved.isNullOrEmpty()) {
            showSetPinDialog()
        } else {
            showEnterPinDialog(saved)
        }
    }
    private fun updateBatteryButtonState() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val ignored = pm?.isIgnoringBatteryOptimizations(packageName) == true
            if (!ignored) {
                btnIgnoreBattery.text = getString(R.string.ignore_battery_optimization) + "（未忽略，建议设置）"
                btnIgnoreBattery.setTextColor(android.graphics.Color.RED)
            } else {
                btnIgnoreBattery.text = getString(R.string.ignore_battery_optimization)
                btnIgnoreBattery.setTextColor(android.graphics.Color.parseColor("#222222"))
            }
        }
    }

    // 根据运行状态，区分按钮颜色与禁用状态
    private fun updateButtonsState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)

        // 启动按钮（未运行：绿色可点击；运行中：灰色禁用）
        btnStart.isEnabled = !running
        btnStart.setBackgroundColor(android.graphics.Color.parseColor(if (running) "#9E9E9E" else "#4CAF50"))
        btnStart.setTextColor(android.graphics.Color.WHITE)

        // 停止按钮（运行中：红色可点击；未运行：灰色禁用）
        btnStop.isEnabled = running
        btnStop.setBackgroundColor(android.graphics.Color.parseColor(if (running) "#F44336" else "#9E9E9E"))
        btnStop.setTextColor(android.graphics.Color.WHITE)
    }
}

private fun updateToggleButtonState() {
    val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
    val running = prefs.getBoolean("vpn_running", false)
    btnToggleVpn.text = getString(if (running) R.string.stop_vpn else R.string.start_vpn)
    val bg = if (running) "#F44336" else "#4CAF50" // 运行中=红色；未运行=绿色
    btnToggleVpn.setBackgroundColor(android.graphics.Color.parseColor(bg))
    btnToggleVpn.setTextColor(android.graphics.Color.WHITE)
}