package com.example.stopnet

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stopnet.admin.MyDeviceAdminReceiver

class SettingsActivity : AppCompatActivity() {

    private lateinit var etParentEmail: EditText
    private lateinit var rvApps: RecyclerView
    private lateinit var btnOpenVpnSettings: Button
    private lateinit var btnTestSmtp: Button
    private lateinit var swSmtpSsl: android.widget.Switch
    private lateinit var swSmtpTls: android.widget.Switch
    private lateinit var etSmtpHost: EditText
    private lateinit var etSmtpPort: EditText
    private lateinit var etSmtpUser: EditText
    private lateinit var etSmtpPass: EditText
    private lateinit var etSmtpFrom: EditText

    private val prefs by lazy { getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE) }
    private val dpsPrefs by lazy { createDeviceProtectedStorageContext().getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE) }
    private val appItems = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter
    private var isPinDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etParentEmail = findViewById(R.id.etParentEmail)
        rvApps = findViewById(R.id.rvApps)
        btnOpenVpnSettings = findViewById(R.id.btnOpenVpnSettings)
        btnTestSmtp = findViewById(R.id.btnTestSmtp)
        swSmtpSsl = findViewById(R.id.swSmtpSsl)
        swSmtpTls = findViewById(R.id.swSmtpTls)
        etSmtpHost = findViewById(R.id.etSmtpHost)
        etSmtpPort = findViewById(R.id.etSmtpPort)
        etSmtpUser = findViewById(R.id.etSmtpUser)
        etSmtpPass = findViewById(R.id.etSmtpPass)
        etSmtpFrom = findViewById(R.id.etSmtpFrom)

        btnOpenVpnSettings.setOnClickListener { openSystemVpnSettings() }
        btnTestSmtp.setOnClickListener { sendTestEmail() }

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
            persistSmtpSettings()
        }
        swSmtpTls.setOnCheckedChangeListener { _, _ ->
            persistSmtpSettings()
        }

        // 自动保存：家长邮箱实时保存
        etParentEmail.addTextChangedListener(simpleWatcher {
            prefs.edit().putString("parent_email", etParentEmail.text?.toString()?.trim() ?: "").apply()
        })

        // 自动保存：SMTP 文本输入实时保存
        val persist = { persistSmtpSettings() }
        etSmtpHost.addTextChangedListener(simpleWatcher(persist))
        etSmtpPort.addTextChangedListener(simpleWatcher(persist))
        etSmtpUser.addTextChangedListener(simpleWatcher(persist))
        etSmtpPass.addTextChangedListener(simpleWatcher(persist))
        etSmtpFrom.addTextChangedListener(simpleWatcher(persist))

        // DO（设备所有者）强保护策略（保留，按钮入口已移除）
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                // 仅启用“始终开启”，不启用锁定模式（lockdown=false）
                dpm.setAlwaysOnVpnPackage(admin, packageName, false)
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CONFIG_VPN)
                dpm.setUninstallBlocked(admin, packageName, true)
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
            }
        } catch (_: Exception) {
            // 安全忽略（非 DO 或设备不支持时不会崩溃）
        }

        // 列表与白名单
        adapter = AppAdapter(appItems) { persistWhitelist() }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter
        loadLaunchableApps()
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
        adapter.notifyDataSetChanged()
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
            com.example.stopnet.mail.MailJobIntentService.enqueue(this, to, subject, body)
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

    // 勾选变化后立即持久化白名单（供适配器调用；存在即可）
    fun persistWhitelist() {
        val selected = adapter.getSelectedPackages().toMutableSet()
        // 启动时确保本应用在白名单中（避免自身网络被拦截）
        try {
            val existing = prefs.getStringSet("whitelist_packages", emptySet()) ?: emptySet()
            if (!existing.contains(packageName)) {
                val updated = existing.toMutableSet().apply { add(packageName) }
                prefs.edit().putStringSet("whitelist_packages", updated).apply()
                dpsPrefs.edit().putStringSet("whitelist_packages", updated).apply()
                // 通知 VPN 重载一次，使新增白名单即时生效
                val intent = Intent(this, FirewallVpnService::class.java).apply {
                    action = FirewallVpnService.ACTION_RELOAD_WHITELIST
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (_: Exception) { }
        // 将本应用加入白名单，避免自身网络被拦截
        selected.add(packageName)
        prefs.edit().putStringSet("whitelist_packages", selected).apply()
        dpsPrefs.edit().putStringSet("whitelist_packages", selected).apply()
        // 保存后重载 VPN 以即时生效
        try {
            val intent = Intent(this, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_RELOAD_WHITELIST
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) { }
    }

    // 统一持久化 SMTP 配置（正常存储 + DPS 存储）
    private fun persistSmtpSettings() {
        val host = etSmtpHost.text?.toString()?.trim() ?: ""
        val port = etSmtpPort.text?.toString()?.toIntOrNull()
            ?: if (swSmtpSsl.isChecked) 465 else 587
        val tls = swSmtpTls.isChecked
        val ssl = swSmtpSsl.isChecked
        val user = etSmtpUser.text?.toString()?.trim() ?: ""
        val pass = etSmtpPass.text?.toString()?.trim() ?: ""
        val from = etSmtpFrom.text?.toString()?.trim() ?: ""

        prefs.edit().apply {
            putString("smtp_host", host)
            putInt("smtp_port", port)
            putBoolean("smtp_tls", tls)
            putBoolean("smtp_ssl", ssl)
            putString("smtp_user", user)
            putString("smtp_pass", pass)
            putString("smtp_from", from)
        }.apply()

        dpsPrefs.edit().apply {
            putString("smtp_host", host)
            putInt("smtp_port", port)
            putBoolean("smtp_tls", tls)
            putBoolean("smtp_ssl", ssl)
            putString("smtp_user", user)
            putString("smtp_pass", pass)
            putString("smtp_from", from)
        }.apply()
    }

    // Simple TextWatcher to auto-save on text change
    private fun simpleWatcher(onChange: () -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { onChange() }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }
}

data class AppItem(val label: String, val packageName: String, var checked: Boolean)

class AppAdapter(private val items: List<AppItem>, private val onSelectionChanged: () -> Unit) : RecyclerView.Adapter<AppAdapter.VH>() {
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cb: CheckBox = view.findViewById(android.R.id.checkbox)
        val tv: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
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
        val ctx = holder.itemView.context
        holder.tv.text = item.label
        holder.cb.setOnCheckedChangeListener(null)

        // 本应用固定允许：禁用复选框防误操作
        if (item.packageName == ctx.packageName) {
            item.checked = true
            holder.cb.isChecked = true
            holder.cb.isEnabled = false
            return
        }

        holder.cb.isEnabled = true
        holder.cb.isChecked = item.checked
        holder.cb.setOnCheckedChangeListener { _, checked ->
            item.checked = checked
            onSelectionChanged.invoke()
        }
    }

    override fun getItemCount(): Int = items.size

    fun getSelectedPackages(): List<String> = items.filter { it.checked }.map { it.packageName }
}