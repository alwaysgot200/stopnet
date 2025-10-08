package com.example.phonenet

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var btnAutoStart: Button

    private var didShowPin = false
    private var isPinDialogShowing: Boolean = false
    private lateinit var appPrefs: android.content.SharedPreferences

    private val prefsChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "vpn_running") {
                updateStatus()
            }
        }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FirewallVpnService.ACTION_VPN_STATE_CHANGED) {
                val state = intent.getBooleanExtra(FirewallVpnService.EXTRA_VPN_STATE, false)
                updateStatus(state)
            }
        }
    }

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
        btnAutoStart = findViewById(R.id.btnAutoStart)

        btnToggleVpn.setOnClickListener { toggleVpn() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        btnAutoStart.setOnClickListener { requestAutoStartPermission() }

        // 注册服务状态广播
        val filter = IntentFilter(FirewallVpnService.ACTION_VPN_STATE_CHANGED)
        registerReceiver(vpnStateReceiver, filter)

        // 监听 vpn_running 变化
        appPrefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        appPrefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)

        updateBatteryButtonState()
        updateStatus()

        // 启动时进行一次 PIN 验证
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
            updateStatus(true)
            updateStatusSoon()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) {
            // 无论授予与否，执行自动启动逻辑或刷新
            handleAutoStartLogic()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateAutoStartButtonState()
        updateBatteryButtonState()

        val filter = IntentFilter(FirewallVpnService.ACTION_VPN_STATE_CHANGED)
        registerReceiver(vpnStateReceiver, filter)
    }

    override fun onDestroy() {
        try {
            appPrefs.unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        } catch (_: Exception) { }
        try {
            unregisterReceiver(vpnStateReceiver)
        } catch (_: Exception) { }
        super.onDestroy()
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
        updateStatus()
        updateStatusSoon()
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
            updateStatus(true)
        }
        updateStatus()
        updateStatusSoon()
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        updateStatus(true)
        updateStatusSoon()
    }

    private fun toggleVpn() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)
        val savedPin = prefs.getString("pin", "") ?: ""

        // 未验证且存在 PIN 时先弹一次
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
        // Android 13+ 通知权限
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
            updateStatus(true)
            updateStatusSoon()
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

        requestStopVpn()
        updateStatus(false)
        updateStatusSoon()
    }

    private fun requestStopVpn() {
        val stopIntent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_STOP_VPN
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(stopIntent)
            } else {
                startService(stopIntent)
            }
        } catch (_: Exception) { }
    }

    private fun updateStatus(running: Boolean? = null) {
        val isRunning = running ?: run {
            val p1 = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            p1.getBoolean("vpn_running", false) || dps.getBoolean("vpn_running", false)
        }
        btnToggleVpn.text = getString(if (isRunning) R.string.stop_vpn else R.string.start_vpn)
        val bg = if (isRunning) "#F44336" else "#4CAF50"
        btnToggleVpn.setBackgroundColor(android.graphics.Color.parseColor(bg))
        btnToggleVpn.setTextColor(android.graphics.Color.WHITE)
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

    private fun requestAutoStartPermission() {
        // 切换期望状态并保存
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val currentStatus = prefs.getBoolean("auto_start_on_boot", false)
        prefs.edit().putBoolean("auto_start_on_boot", !currentStatus).apply()
        updateAutoStartButtonState()

        // 厂商自启动管理页面
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
            } catch (_: Exception) { /* continue */ }
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            android.widget.Toast.makeText(this, "无法自动打开自启动设置，请手动查找", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
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

            val standard = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$pkg")
            }
            if (standard.resolveActivity(packageManager) != null) {
                try {
                    startActivity(standard)
                    android.widget.Toast.makeText(this, "请允许忽略电池优化以提升稳定性", android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Exception) { /* continue */ }
            }

            val tried = startFirstResolvedIntent(
                Intent("android.intent.action.POWER_USAGE_SUMMARY"),
                Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),

                // VIVO / OriginOS
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")),
                Intent().setComponent(ComponentName("com.vivo.settings", "com.vivo.settings.Battery")),

                // 其他厂商入口
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")),
                Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),
                Intent().setComponent(ComponentName("com.htc.pitroad", "com.htc.pitroad.landingpage.activity.LandingPageActivity"))
            )

            if (tried) {
                android.widget.Toast.makeText(this, "请在电池/后台管理中为 PhoneNet 设为“无限制”或“允许后台运行”", android.widget.Toast.LENGTH_LONG).show()
                return
            }

            if (startFirstResolvedIntent(
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$pkg")
                    }
                )
            ) {
                android.widget.Toast.makeText(this, "请在电池优化或应用详情中将 PhoneNet 设置为“不要优化/无限制”", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(this, "无法自动打开电池相关设置，请在系统设置中手动查找", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(this, "当前系统版本无需此设置", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFirstResolvedIntent(vararg intents: Intent): Boolean {
        for (intent in intents) {
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivity(intent)
                    return true
                } catch (_: Exception) { /* continue */ }
            }
        }
        return false
    }

    private fun checkAndRestartServiceIfNeeded() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val shouldBeRunning = prefs.getBoolean("vpn_running", false)
        val userStopped = prefs.getBoolean("vpn_user_stop", false)

        if (shouldBeRunning && !userStopped) {
            val prep = VpnService.prepare(this)
            if (prep == null) {
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

    private fun updateAutoStartButtonState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", false)

        if (autoStartEnabled) {
            btnAutoStart.setBackgroundColor(ContextCompat.getColor(this, R.color.colorGreen))
            btnAutoStart.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            btnAutoStart.setBackgroundColor(ContextCompat.getColor(this, R.color.colorRed))
            btnAutoStart.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
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

    // 延迟再次刷新状态，避免服务写入偏晚导致首次 UI 不更新
    private fun updateStatusSoon() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateStatus() }, 400)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateStatus() }, 1200)
    }
}