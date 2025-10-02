package com.example.phonenet

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.phonenet.admin.MyDeviceAdminReceiver

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnEnableAdmin: Button
    private lateinit var btnSaveEmail: Button
    private lateinit var etParentEmail: EditText
    private lateinit var rvApps: RecyclerView
    private lateinit var btnOpenVpnSettings: Button
    private lateinit var btnIgnoreBattery: Button

    // SMTP 配置
    private lateinit var etSmtpHost: EditText
    private lateinit var etSmtpPort: EditText
    private lateinit var swSmtpTls: android.widget.Switch
    private lateinit var swSmtpSsl: android.widget.Switch
    private lateinit var etSmtpUser: EditText
    private lateinit var etSmtpPass: EditText
    private lateinit var etSmtpFrom: EditText
    private lateinit var btnTestSmtp: Button

    // 开机自启（如果你已增加该开关）
    private lateinit var swAutoStart: android.widget.Switch

    private val prefs by lazy { getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE) }
    private val dpsPrefs by lazy { createDeviceProtectedStorageContext().getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE) }
    private val appItems = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter
    private var isPinDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnEnableAdmin = findViewById(R.id.btnEnableAdmin)
        btnSaveEmail = findViewById(R.id.btnSaveEmail)
        etParentEmail = findViewById(R.id.etParentEmail)
        rvApps = findViewById(R.id.rvApps)

        // 绑定 SMTP 控件
        etSmtpHost = findViewById(R.id.etSmtpHost)
        etSmtpPort = findViewById(R.id.etSmtpPort)
        swSmtpTls = findViewById(R.id.swSmtpTls)
        swSmtpSsl = findViewById(R.id.swSmtpSsl)
        etSmtpUser = findViewById(R.id.etSmtpUser)
        etSmtpPass = findViewById(R.id.etSmtpPass)
        etSmtpFrom = findViewById(R.id.etSmtpFrom)
        btnTestSmtp = findViewById(R.id.btnTestSmtp)
        btnOpenVpnSettings = findViewById(R.id.btnOpenVpnSettings)
        btnOpenVpnSettings.setOnClickListener { openSystemVpnSettings() }

        // 移除设置页的“忽略电池优化”按钮及监听
        // btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)
        // btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        // 如存在开机自启控件
        swAutoStart = findViewById(R.id.swAutoStart)
        // 预填开机自启状态（默认：已开启）
        swAutoStart.isChecked = prefs.getBoolean("auto_start_on_boot", true)
        // 当开关变化时，立即持久化到普通存储与 DPS（兼容未解锁阶段读取）
        swAutoStart.setOnCheckedChangeListener { _, checked ->
            try {
                prefs.edit().putBoolean("auto_start_on_boot", checked).apply()
                dpsPrefs.edit().putBoolean("auto_start_on_boot", checked).apply()
            } catch (_: Exception) { }
            if (checked) {
                android.widget.Toast.makeText(this, "已开启：开机自动启动", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "已关闭：开机自动启动", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // PIN 门禁
        checkAndGateByPin()

        // 预填家长邮箱
        etParentEmail.setText(prefs.getString("parent_email", ""))

        // 预填 SMTP 配置（正常存储优先，DPS 作为回退）
        etSmtpHost.setText(prefs.getString("smtp_host", dpsPrefs.getString("smtp_host", "")))
        val defPort = if (prefs.getBoolean("smtp_ssl", dpsPrefs.getBoolean("smtp_ssl", false))) 465 else 587
        etSmtpPort.setText((prefs.getInt("smtp_port", dpsPrefs.getInt("smtp_port", defPort))).toString())
        swSmtpSsl.isChecked = prefs.getBoolean("smtp_ssl", dpsPrefs.getBoolean("smtp_ssl", false))
        swSmtpTls.isChecked = prefs.getBoolean("smtp_tls", dpsPrefs.getBoolean("smtp_tls", !swSmtpSsl.isChecked))

        // SSL/TLS 互斥：选了 SSL 则关闭 TLS
        swSmtpSsl.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                swSmtpTls.isChecked = false
                if ((etSmtpPort.text?.toString()?.toIntOrNull() ?: 0) == 0) {
                    etSmtpPort.setText("465")
                }
            } else {
                if ((etSmtpPort.text?.toString()?.toIntOrNull() ?: 0) == 465) {
                    etSmtpPort.setText("587")
                }
            }
        }

        // 如果本应用是设备所有者（DO），自动应用强保护策略：
        // 1) 设置 Always-on VPN 并启用 lockdown（所有网络必须经过 VPN）
        // 2) 禁止用户修改 VPN 设置
        // 3) 禁止卸载本应用
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                // Always-on VPN + lockdown
                dpm.setAlwaysOnVpnPackage(admin, packageName, true)
                // 禁止修改 VPN 设置
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CONFIG_VPN)
                // 禁止卸载本应用（DO 才有效）
                dpm.setUninstallBlocked(admin, packageName, true)
                // 允许本应用进入锁定任务（Kiosk）模式，避免随意退出
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                // 如设备支持，可进一步限制状态栏（部分厂商支持）
                // try { dpm.setStatusBarDisabled(admin, true) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // 安全忽略（非 DO 或设备不支持时不会崩溃）
        }

        btnEnableAdmin.setOnClickListener { requestDeviceAdmin() }
        btnSaveEmail.setOnClickListener { saveSettings() }
        btnTestSmtp.setOnClickListener { sendTestEmail() }
        btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)
        btnIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        // PIN 门禁：设置页不再弹 PIN
        // checkAndGateByPin()

        // 初始化电池优化状态指示
        updateBatteryButtonState()
    }

    private fun checkAndGateByPin() {
        // 设置页不再进行 PIN 验证；仅在 APP 打开时（MainActivity）验证一次
    }

    private fun showEnterPinDialog(saved: String) {
        isPinDialogShowing = true
        val input = EditText(this)
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
        val input1 = EditText(this)
        input1.hint = getString(R.string.set_pin)
        val input2 = EditText(this)
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

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于家长控制网络")
        }
        startActivity(intent)
    }

    private fun loadLaunchableApps() {
        val pm: PackageManager = packageManager
        val whitelist = prefs.getStringSet("whitelist_packages", emptySet()) ?: emptySet()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val packages = resolveInfos.map { it.activityInfo.packageName }.toSet()

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName in packages }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        appItems.clear()
        apps.forEach { ai ->
            val label = ai.loadLabel(pm).toString()
            val pkg = ai.packageName
            appItems.add(AppItem(label, pkg, whitelist.contains(pkg)))
        }
    }

    private fun saveSettings() {
        val email = etParentEmail.text?.toString()?.trim()
        val selected = adapter.getSelectedPackages().toMutableSet()

        // 读取 SMTP 输入
        val host = etSmtpHost.text?.toString()?.trim() ?: ""
        val port = etSmtpPort.text?.toString()?.toIntOrNull() ?: if (swSmtpSsl.isChecked) 465 else 587
        val tls = swSmtpTls.isChecked
        val ssl = swSmtpSsl.isChecked
        val user = etSmtpUser.text?.toString()?.trim() ?: ""
        val pass = etSmtpPass.text?.toString()?.trim() ?: ""
        val from = etSmtpFrom.text?.toString()?.trim() ?: ""

        // 正常存储
        prefs.edit().apply {
            putString("parent_email", email)
            putStringSet("whitelist_packages", selected)
            putString("smtp_host", host)
            putInt("smtp_port", port)
            putBoolean("smtp_tls", tls)
            putBoolean("smtp_ssl", ssl)
            putString("smtp_user", user)
            putString("smtp_pass", pass)
            putString("smtp_from", from)
            val autoStart = swAutoStart.isChecked
            prefs.edit().apply {
                putBoolean("auto_start_on_boot", autoStart)
            }.apply()
        }.apply()

        // DPS 存储（未解锁阶段需要的配置）
        dpsPrefs.edit().apply {
            putStringSet("whitelist_packages", selected)
            putString("smtp_host", host)
            putInt("smtp_port", port)
            putBoolean("smtp_tls", tls)
            putBoolean("smtp_ssl", ssl)
            putString("smtp_user", user)
            putString("smtp_pass", pass)
            putString("smtp_from", from)
        }.apply()
    }

    private fun sendTestEmail() {
        val to = etParentEmail.text?.toString()?.trim()
        if (to.isNullOrEmpty()) {
            android.widget.Toast.makeText(this, "请先填写家长邮箱", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val subject = getString(R.string.smtp_test_subject)
        val body = getString(R.string.smtp_test_body)
        try {
            com.example.phonenet.mail.MailJobIntentService.enqueue(this, to, subject, body)
            android.widget.Toast.makeText(this, "测试邮件已发送（请查看收件箱）", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "发送失败，请检查配置", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemVpnSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "无法打开系统 VPN 设置", android.widget.Toast.LENGTH_SHORT).show()
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
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$pkg")
                }
                startActivity(intent)
                android.widget.Toast.makeText(this, getString(R.string.battery_opt_desc), android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                try {
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

    override fun onResume() {
        super.onResume()
        // 返回设置页不再刷新电池优化提示（主界面负责）
        // 移除：updateBatteryButtonState()
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

data class AppItem(val label: String, val packageName: String, var checked: Boolean)

class AppAdapter(private val items: List<AppItem>) : RecyclerView.Adapter<AppAdapter.VH>() {
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cb: CheckBox = view.findViewById(android.R.id.checkbox)
        val tv: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // 动态构建行布局：CheckBox + TextView
        val ctx = parent.context
        val root = LayoutInflater.from(ctx).inflate(android.R.layout.simple_list_item_multiple_choice, parent, false)
        // simple_list_item_multiple_choice 使用 CheckedTextView，不含 CheckBox；为简化，我们自定义一个容器
        val container = androidx.appcompat.widget.LinearLayoutCompat(ctx).apply {
            orientation = androidx.appcompat.widget.LinearLayoutCompat.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        val cb = CheckBox(ctx).apply { id = android.R.id.checkbox }
        val tv = TextView(ctx).apply { id = android.R.id.text1 }
        container.addView(cb)
        container.addView(tv)
        return VH(container)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tv.text = item.label
        // 先清除监听，避免复用导致的回调触发
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = item.checked
        holder.cb.setOnCheckedChangeListener { _, checked ->
            item.checked = checked
        }
    }

    override fun getItemCount(): Int = items.size

    fun getSelectedPackages(): List<String> = items.filter { it.checked }.map { it.packageName }
}
