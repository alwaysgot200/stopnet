package com.example.stopnet

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

    // 全局状态监听器：收到变更直接刷新 UI
    private val onVpnStateChanged: (Boolean) -> Unit = { state ->
        updateStatus(state)
    }

    // 仅用于接收服务广播并同步全局状态（主来源仍为服务侧的 set）
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FirewallVpnService.ACTION_VPN_STATE_CHANGED) {
                val state = intent.getBooleanExtra(FirewallVpnService.EXTRA_VPN_STATE, false)
                VpnStateStore.set(state)
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
        btnSettings.setOnClickListener { openSettings() }
        btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        btnAutoStart.setOnClickListener { requestAutoStartPermission() }

        // 统一用 app 级偏好，仅用于回退读取
        appPrefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)

        updateBatteryButtonState()
        updateStatus()

        // 若为"从开机广播拉起"的场景，进行自动化处理
        val fromBoot = intent?.getBooleanExtra("from_boot", false) == true
        if (fromBoot) {
            android.util.Log.d("MainActivity", "从开机广播启动")
            // 开机自启动场景：后台启动VPN，不显示界面，也不需要PIN验证
            handleBootAutoStart()
            return
        }

        // 正常启动路径：首次安装时设置PIN，然后根据"默认自动启动VPN"选项决定是否启动VPN
        handleNormalAppStart()
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
            // 预设为“运行中”，由服务广播或写入统一纠正
            VpnStateStore.set(true)
            updateStatus(true)
        }
        updateStatus()
        // 移除延迟刷新，避免回退覆盖
        updateStatusSoon()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) {
            // 通知权限结果回调，仅刷新状态
            // 不自动启动VPN，由PIN验证后的逻辑控制
            updateStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        // 注册广播（避免与 onCreate/onResume 重复注册）
        val filter = IntentFilter(FirewallVpnService.ACTION_VPN_STATE_CHANGED)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            vpnStateReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 注册全局状态监听
        VpnStateStore.addListener(onVpnStateChanged)
        // 启动时同步一次
        updateStatus()
    }

    override fun onStop() {
        // 注销监听，避免重复回调与内存泄漏
        try { unregisterReceiver(vpnStateReceiver) } catch (_: Exception) { }
        VpnStateStore.removeListener(onVpnStateChanged)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 移除重复 registerReceiver，保留轻量刷新
        updateStatus()
        updateAutoStartButtonState()
        updateBatteryButtonState()
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

    // 开机自动启动处理：后台启动VPN，不需要PIN，并立即关闭界面
    private fun handleBootAutoStart() {
        // Android 13+ 先请求通知权限，保证前台服务 10s 内通知稳定展示
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                android.util.Log.d("MainActivity", "请求通知权限")
                // 开机启动时如果没有通知权限，直接关闭界面，不弹权限请求
                // 用户首次手动打开时会请求权限
                finish()
                return
            }
        }
        
        // 后台自动启动 VPN 服务
        android.util.Log.d("MainActivity", "开机后台启动VPN服务")
        val intent = VpnService.prepare(this)
        if (intent == null) {
            // VPN已授权，直接启动
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                android.util.Log.d("MainActivity", "VPN服务启动成功")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "启动VPN服务失败: ${e.message}")
            }
        } else {
            android.util.Log.w("MainActivity", "VPN未授权，开机无法自动启动")
            // 未授权时不弹授权框，用户首次手动打开时会引导授权
        }
        
        // 关闭界面，不干扰用户
        finish()
    }

    // 正常打开APP的处理逻辑
    private fun handleNormalAppStart() {
        val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("pin", null)

        // 首次安装：没有PIN，引导设置PIN
        if (saved.isNullOrEmpty()) {
            showSetPinDialog {
                // PIN设置完成后，直接检查是否自动启动VPN（无需再次验证PIN）
                checkAndAutoStartVpn()
            }
            return
        }

        // 已有PIN：直接检查是否自动启动VPN，不弹PIN验证框
        // PIN验证推迟到用户点击按钮时才进行
        checkAndAutoStartVpn()
    }

    // 检查并根据"默认自动启动VPN"选项决定是否自动启动VPN
    private fun checkAndAutoStartVpn() {
        val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        val defaultAutoStart = prefs.getBoolean("default_auto_start_vpn", true)
        
        android.util.Log.d("MainActivity", "检查自动启动VPN - 选项: $defaultAutoStart")
        
        if (defaultAutoStart) {
            // 已选中"默认自动启动VPN"：立即自动启动VPN（无需PIN验证）
            attemptStartVpnService()
        } else {
            // 未选中：不自动启动，显示主界面，等待用户手动操作
            updateStatus()
        }
    }

    // 打开设置页：需要PIN验证
    private fun openSettings() {
        val savedPin = appPrefs.getString("pin", "") ?: ""
        
        // 没有PIN或已验证过PIN，直接打开
        if (savedPin.isEmpty() || didShowPin) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        
        // 有PIN且未验证，先验证
        showEnterPinDialog(savedPin) {
            didShowPin = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
        VpnStateStore.set(true)
        updateStatus(true)
        // 移除延迟刷新
    }

    private fun toggleVpn() {
        // 优先使用全局内存态，其次回退到偏好
        val current = VpnStateStore.current() ?: run {
            val p1 = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            p1.getBoolean("vpn_running", false) || dps.getBoolean("vpn_running", false)
        }
        val savedPin = appPrefs.getString("pin", "") ?: ""

        // 没有PIN或已验证过PIN，直接执行操作
        if (savedPin.isEmpty() || didShowPin) {
            if (current) stopVpn() else startVpn()
            return
        }

        // 有PIN且未验证，先验证
        showEnterPinDialog(savedPin) {
            didShowPin = true
            if (current) stopVpn() else startVpn()
        }
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
            // 预先设置运行中，防止 UI 回退
            VpnStateStore.set(true)
            updateStatus(true)
        }
        // 移除延迟刷新
    }

    private fun stopVpn() {
        try {
            val p1 = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_user_stop", true).apply()
            p1.edit().putBoolean("vpn_running", false).apply()
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_user_stop", true).apply()
            dps.edit().putBoolean("vpn_running", false).apply()
        } catch (_: Exception) { }

        requestStopVpn()
        // 立即更新内存态与 UI 为未运行
        VpnStateStore.set(false)
        updateStatus(false)
        // 移除延迟刷新
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
        val isRunning = running ?: VpnStateStore.current() ?: run {
            val p1 = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
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
        val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
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
                android.widget.Toast.makeText(this, "请在列表中找到 StopNet 并允许自动启动", android.widget.Toast.LENGTH_LONG).show()
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
                android.widget.Toast.makeText(this, "请在电池/后台管理中为 StopNet 设为“无限制”或“允许后台运行”", android.widget.Toast.LENGTH_LONG).show()
                return
            }

            if (startFirstResolvedIntent(
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$pkg")
                    }
                )
            ) {
                android.widget.Toast.makeText(this, "请在电池优化或应用详情中将 StopNet 设置为“不要优化/无限制”", android.widget.Toast.LENGTH_LONG).show()
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
        val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
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
        val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
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
                    val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
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

    // 删除延迟刷新方法，消除 UI 回退覆盖
    private fun updateStatusSoon() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateStatus() }, 400)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateStatus() }, 1200)
    }
}
