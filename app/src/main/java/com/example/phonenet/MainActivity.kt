package com.example.phonenet

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleVpn: Button
    private lateinit var btnSettings: Button
    private lateinit var btnIgnoreBattery: Button

    private var didShowPin = false
    private var isPinDialogShowing = false

    companion object {
        private const val PREPARE_VPN_REQ = 1001
        private const val REQUEST_NOTIF = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        btnSettings = findViewById(R.id.btnSettings)
        btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)

        btnToggleVpn.setOnClickListener { toggleVpn() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        updateBatteryButtonState()
        updateToggleButtonState()

        // 本次会话仅在应用启动时验证一次 PIN 验证
        showPinVerification()
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
            updateToggleButtonState()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            // 无论授予与否，执行自动启动逻辑或刷新
            handleAutoStartLogic()
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryButtonState()
        updateToggleButtonState()
        requestExactAlarmPermissionIfNeeded()
        checkAndRestartServiceIfNeeded()
    }

    private fun showPinVerification() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("pin", null)

        if (didShowPin) {
            handleAutoStartLogic()
            return
        }

        if (saved.isNullOrEmpty()) {
            showSetPinDialog {
                didShowPin = true
                handleAutoStartLogic()
            }
        } else {
            showEnterPinDialog(saved) {
                didShowPin = true
                handleAutoStartLogic()
            }
        }
    }

    private fun handleAutoStartLogic() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_on_boot", true)

        if (autoStart) {
            attemptStartVpnService()
        } else {
            checkAndRestartServiceIfNeeded()
        }
        updateToggleButtonState()
    }

    private fun attemptStartVpnService() {
        // Android 13+ 需要通知权限
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
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        updateToggleButtonState()
    }

    private fun toggleVpn() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)
        val savedPin = prefs.getString("pin", "") ?: ""

        // 按钮点击不再重复弹 PIN；仅在未验证且存在 PIN 时弹一次
        if (!didShowPin && savedPin.isNotEmpty()) {
            showEnterPinDialog(savedPin) {
                didShowPin = true
                if (running) stopVpn() else startVpn()
            }
            return
        }

        if (running) stopVpn() else startVpn()
    }

    private fun startVpn() {
        // 通知权限（Android 13+）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
                return
            }
        }
        // 引导忽略电池优化
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val pkg = packageName
            if (pm?.isIgnoringBatteryOptimizations(pkg) == false) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$pkg")
                    }
                    startActivity(intent)
                    android.widget.Toast.makeText(this, getString(R.string.battery_opt_desc), android.widget.Toast.LENGTH_SHORT).show()
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
            updateToggleButtonState()
        }
    }

    private fun stopVpn() {
        try {
            val p1 = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_user_stop", true).apply()
            p1.edit().putBoolean("vpn_running", false).apply()
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_user_stop", true).apply()
            dps.edit().putBoolean("vpn_running", false).apply()
        } catch (_: Exception) { }

        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        stopService(serviceIntent)

        updateToggleButtonState()
    }

    private fun updateToggleButtonState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)
        btnToggleVpn.text = getString(if (running) R.string.stop_vpn else R.string.start_vpn)
        val bg = if (running) "#F44336" else "#4CAF50"
        btnToggleVpn.setBackgroundColor(android.graphics.Color.parseColor(bg))
        btnToggleVpn.setTextColor(android.graphics.Color.WHITE)
    }

    private fun updateAutoStartButtonState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", true)
        // This button and its update logic are not fully implemented in the UI.
        // For now, this function is a placeholder.
    }

    private fun updateAutoStartButtonState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", true)
        // This button and its update logic are not fully implemented in the UI.
        // For now, this function is a placeholder.
    }

    private fun updateAutoStartButtonState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", true)
        // This button and its update logic are not fully implemented in the UI.
        // For now, this function is a placeholder.
    }

    private fun updateBatteryButtonState() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val ignored = pm?.isIgnoringBatteryOptimizations(packageName) == true
            btnIgnoreBattery.text = getString(R.string.ignore_battery_optimization)
            val bg = if (ignored) "#4CAF50" else "#F44336"
            btnIgnoreBattery.setBackgroundColor(android.graphics.Color.parseColor(bg))
            btnIgnoreBattery.setTextColor(android.graphics.Color.WHITE)
        }
    }

    private fun updateAutoStartButtonState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", false) // 默认关闭
        if (autoStartEnabled) {
            btnAutoStart.setBackgroundColor(android.graphics.Color.GREEN)
            btnAutoStart.text = "自动启动：已启用"
        } else {
            btnAutoStart.setBackgroundColor(android.graphics.Color.RED)
            btnAutoStart.text = "自动启动：已禁用"
        }
    }

    private fun requestAutoStartPermission() {
        // 切换期望的状态并保存
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val currentStatus = prefs.getBoolean("auto_start_on_boot", false)
        prefs.edit().putBoolean("auto_start_on_boot", !currentStatus).apply()
        updateAutoStartButtonState() // 更新按钮状态

        // 尝试跳转到厂商的自启动管理页面
        val intents = arrayOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.htc.pitroad", "com.htc.pitroad.landingpage.activity.LandingPageActivity")),
            Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"))
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                android.widget.Toast.makeText(this, "请在列表中找到 PhoneNet 并允许自动启动", android.widget.Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // continue
            }
        }
        // 如果都失败，则打开通用设置
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            android.widget.Toast.makeText(this, "无法自动打开自启动设置，请手动查找", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "无法打开设置，请手动操作", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val pkg = packageName
            if (pm?.isIgnoringBatteryOptimizations(pkg) == true) {
                android.widget.Toast.makeText(this, "已忽略电池优化", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val intents = arrayOf(
                // 标准
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$pkg")
                },
                // 小米
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
                // 三星
                Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                // 华为
                Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                // OPPO
                Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(android.content.ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
                // VIVO
                Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                // 其他
                Intent().setComponent(android.content.ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")),
                Intent().setComponent(android.content.ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),
                Intent().setComponent(android.content.ComponentName("com.htc.pitroad", "com.htc.pitroad.landingpage.activity.LandingPageActivity"))
            )

            for (intent in intents) {
                try {
                    startActivity(intent)
                    android.widget.Toast.makeText(this, "请在列表中找到 PhoneNet，并允许后台运行或设置为\"无限制\"", android.widget.Toast.LENGTH_LONG).show()
                    return
                } catch (_: Exception) {
                    // continue
                }
            }

            // 如果都失败，则打开通用设置
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                android.widget.Toast.makeText(this, "请在电池优化列表中将 PhoneNet 设置为\"不要优化\"", android.widget.Toast.LENGTH_LONG).show()
            } catch (__: Exception) {
                android.widget.Toast.makeText(this, "无法自动打开电池优化设置，请在系统设置中手动查找", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(this, "当前系统版本无需此设置", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRestartServiceIfNeeded() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val shouldBeRunning = prefs.getBoolean("vpn_running", false)
        val userStopped = prefs.getBoolean("vpn_user_stop", false)

        if (shouldBeRunning && !userStopped) {
            val vpnService = VpnService.prepare(this)
            if (vpnService == null) {
                val serviceIntent = Intent(this, FirewallVpnService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val am = getSystemService(android.app.AlarmManager::class.java)
                if (am?.canScheduleExactAlarms() == false) {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            } catch (_: Exception) { }
        }
    }

    private fun showEnterPinDialog(saved: String, onSuccess: () -> Unit) {
        if (isPinDialogShowing) return
        isPinDialogShowing = true

        val input = android.widget.EditText(this).apply {
            hint = "请输入PIN密码"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(32, 32, 32, 32)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("PIN验证")
            .setMessage("请输入PIN密码以继续操作")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text?.toString()?.trim() ?: ""
                if (entered == saved) {
                    isPinDialogShowing = false
                    onSuccess()
                } else {
                    isPinDialogShowing = false
                    android.widget.Toast.makeText(this, "PIN密码错误", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                isPinDialogShowing = false
            }
            .create()

        dialog.setOnDismissListener {
            if (isPinDialogShowing) isPinDialogShowing = false
        }

        dialog.show()

        input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showSetPinDialog(onSuccess: () -> Unit) {
        if (isPinDialogShowing) return
        isPinDialogShowing = true

        val input1 = android.widget.EditText(this).apply {
            hint = "设置PIN密码（至少4位数字）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val input2 = android.widget.EditText(this).apply {
            hint = "确认PIN密码"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val container = androidx.appcompat.widget.LinearLayoutCompat(this).apply {
            orientation = androidx.appcompat.widget.LinearLayoutCompat.VERTICAL
            setPadding(32, 32, 32, 16)
            addView(input1)
            addView(android.widget.Space(this@MainActivity).apply { minimumHeight = 24 })
            addView(input2)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置PIN密码")
            .setMessage("首次使用需要设置PIN密码，用于保护应用安全")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("确定") { _, _ ->
                val p1 = input1.text?.toString()?.trim() ?: ""
                val p2 = input2.text?.toString()?.trim() ?: ""
                if (p1.length < 4) {
                    isPinDialogShowing = false
                    android.widget.Toast.makeText(this, "PIN密码至少需要4位数字", android.widget.Toast.LENGTH_SHORT).show()
                } else if (p1 != p2) {
                    isPinDialogShowing = false
                    android.widget.Toast.makeText(this, "两次输入的PIN密码不一致", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("pin", p1).apply()
                    isPinDialogShowing = false
                    android.widget.Toast.makeText(this, "PIN密码设置成功", android.widget.Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                isPinDialogShowing = false
            }
            .create()

        dialog.setOnDismissListener {
            if (isPinDialogShowing) isPinDialogShowing = false
        }

        dialog.show()

        input1.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input1, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
}