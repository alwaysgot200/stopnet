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
    private lateinit var swAutoStart: android.widget.Switch

    private val prefs by lazy { getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE) }
    private val dpsPrefs by lazy { createDeviceProtectedStorageContext().getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE) }
    private val appItems = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnEnableAdmin = findViewById(R.id.btnEnableAdmin)
        btnSaveEmail = findViewById(R.id.btnSaveEmail)
        etParentEmail = findViewById(R.id.etParentEmail)
        rvApps = findViewById(R.id.rvApps)
        swAutoStart = findViewById(R.id.swAutoStart)
        // PIN 门禁
        checkAndGateByPin()
        // 预填家长邮箱
        etParentEmail.setText(prefs.getString("parent_email", ""))

        // 预填开机自启开关（正常存储优先，DPS 作为回退）
        swAutoStart.isChecked = prefs.getBoolean("auto_start_on_boot", dpsPrefs.getBoolean("auto_start_on_boot", true))

        // 加载应用列表
        loadLaunchableApps()
        adapter = AppAdapter(appItems)
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        btnEnableAdmin.setOnClickListener { requestDeviceAdmin() }
        btnSaveEmail.setOnClickListener { saveSettings() }
    }

    private fun checkAndGateByPin() {
        val saved = prefs.getString("pin", null)
        if (saved.isNullOrEmpty()) {
            showSetPinDialog()
        } else {
            showEnterPinDialog(saved)
        }
    }

    private fun showEnterPinDialog(saved: String) {
        val input = EditText(this)
        input.hint = getString(R.string.enter_pin)
        androidx.appcompat.app.AlertDialog.Builder(this)
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
            .show()
    }

    private fun showSetPinDialog() {
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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
            .show()
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
        // 同步写入正常存储与设备保护存储（保证未解锁阶段可读取）
        prefs.edit().apply {
            putString("parent_email", email)
            putStringSet("whitelist_packages", selected)
            putBoolean("auto_start_on_boot", swAutoStart.isChecked)
        }.apply()
        dpsPrefs.edit().apply {
            putStringSet("whitelist_packages", selected)
            putBoolean("auto_start_on_boot", swAutoStart.isChecked)
        }.apply()
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