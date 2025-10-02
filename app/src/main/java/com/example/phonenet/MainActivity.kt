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
    // 修复未定义：PIN 对话框状态标记
    private var isPinDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化UI组件
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        btnSettings = findViewById(R.id.btnSettings)
        btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)

        btnToggleVpn.setOnClickListener { toggleVpn() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        updateBatteryButtonState()
        
        // 立即显示PIN验证，不等待任何其他操作
        showPinVerification()
    }
    
    private fun showPinVerification() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("pin", null)
        
        if (saved.isNullOrEmpty()) {
            // 首次使用，设置PIN
            showSetPinDialog { 
                // PIN设置完成后，执行启动逻辑
                handleAutoStartLogic()
            }
        } else {
            // 已有PIN，验证PIN
            showEnterPinDialog(saved) { 
                // PIN验证成功后，执行启动逻辑
                handleAutoStartLogic()
            }
        }
    }
    
    private fun handleAutoStartLogic() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_on_boot", true)
        
        if (autoStart) {
            // 设置了自动启动：直接启动VPN
            attemptStartVpnService()
        } else {
            // 未设置自动启动：保持之前的状态
            checkAndRestartServiceIfNeeded()
        }
        
        // 更新UI状态
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
            // 需要VPN权限，静默请求
            startActivityForResult(intent, PREPARE_VPN_REQ)
        } else {
            // 已有权限，直接启动
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
                // 按"自动启动"策略继续
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

    // MainActivity 内新增：切换与颜色刷新
    private fun toggleVpn() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)
        
        if (running) {
            // 停止VPN需要PIN验证
            showEnterPinDialog(prefs.getString("pin", "")) { 
                stopVpn()
            }
        } else {
            // 启动VPN需要PIN验证
            showEnterPinDialog(prefs.getString("pin", "")) { 
                startVpn()
            }
        }
    }
    
    private fun updateToggleButtonState() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)
        btnToggleVpn.text = getString(if (running) R.string.stop_vpn else R.string.start_vpn)
        val bg = if (running) "#F44336" else "#4CAF50" // 运行中=红；未运行=绿
        btnToggleVpn.setBackgroundColor(android.graphics.Color.parseColor(bg))
        btnToggleVpn.setTextColor(android.graphics.Color.WHITE)
    }

    private fun stopVpn() {
        // 可选：写入"手动停止"标记，服务不自恢复
        try {
            val p1 = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_user_stop", true).apply()
            // 立即更新运行状态为false
            p1.edit().putBoolean("vpn_running", false).apply()
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_user_stop", true).apply()
            dps.edit().putBoolean("vpn_running", false).apply()
        } catch (_: Exception) { }
    
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        stopService(serviceIntent)
    
        // 立即更新按钮状态
        updateToggleButtonState()
        
        // 延迟再次检查状态，确保UI同步
        btnToggleVpn.postDelayed({
            updateToggleButtonState()
        }, 1000)
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
                    // 兜底：直接打开系统"电池优化"列表，让家长手动将 PhoneNet 设为"不优化"
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    android.widget.Toast.makeText(this, "请在电池优化列表中将 PhoneNet 设置为\"不要优化\"", android.widget.Toast.LENGTH_LONG).show()
                } catch (__: Exception) {
                    android.widget.Toast.makeText(this, "无法打开电池优化设置，请在系统设置中手动查找\"电池优化\"", android.widget.Toast.LENGTH_SHORT).show()
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
                    onSuccess() // PIN正确，执行回调
                } else {
                    isPinDialogShowing = false
                    android.widget.Toast.makeText(this, "PIN密码错误", android.widget.Toast.LENGTH_SHORT).show()
                    finish() // PIN错误，退出应用
                }
            }
            .setNegativeButton("取消") { _, _ -> 
                isPinDialogShowing = false
                finish() 
            }
            .create()
            
        dialog.setOnDismissListener { 
            if (isPinDialogShowing) {
                isPinDialogShowing = false
                finish()
            }
        }
        
        dialog.show()
        
        // 自动弹出键盘
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
            addView(android.widget.Space(this@MainActivity).apply { 
                minimumHeight = 24 
            })
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
                    finish()
                } else if (p1 != p2) {
                    isPinDialogShowing = false
                    android.widget.Toast.makeText(this, "两次输入的PIN密码不一致", android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("pin", p1).apply()
                    isPinDialogShowing = false
                    android.widget.Toast.makeText(this, "PIN密码设置成功", android.widget.Toast.LENGTH_SHORT).show()
                    onSuccess() // PIN设置成功，执行回调
                }
            }
            .setNegativeButton("取消") { _, _ -> 
                isPinDialogShowing = false
                finish() 
            }
            .create()
            
        dialog.setOnDismissListener { 
            if (isPinDialogShowing) {
                isPinDialogShowing = false
                finish()
            }
        }
        
        dialog.show()
        
        // 自动弹出键盘
        input1.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input1, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    override fun onResume() {
        super.onResume()
        
        // 只有在PIN验证通过后才更新状态
        if (didShowPin) {
            updateBatteryButtonState()
            updateToggleButtonState()
            checkAndRestartServiceIfNeeded()
        }
        
        hasResumedOnce = true
    }
    
    // 移除原来的showPinIfNeeded方法，用showPinVerification替代
    // 移除原来的attemptAutoStartOnLaunch方法，逻辑合并到handleAutoStartLogic
    private fun checkAndRestartServiceIfNeeded() {
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val shouldBeRunning = prefs.getBoolean("vpn_running", false)
        val userStopped = prefs.getBoolean("vpn_user_stop", false)
        
        if (shouldBeRunning && !userStopped) {
            // 检查VPN是否真的在运行
            val vpnService = VpnService.prepare(this)
            if (vpnService == null) {
                // VPN权限已授予，但可能服务已停止，尝试重启
                val serviceIntent = Intent(this, FirewallVpnService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }
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
}