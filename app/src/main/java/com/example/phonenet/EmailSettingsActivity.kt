package com.example.stopnet

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class EmailSettingsActivity : AppCompatActivity() {

    private lateinit var etParentEmail: EditText
    private lateinit var btnTestSmtp: Button
    private lateinit var swSmtpSsl: android.widget.Switch
    private lateinit var swSmtpTls: android.widget.Switch
    private lateinit var etSmtpHost: EditText
    private lateinit var etSmtpPort: EditText
    private lateinit var etSmtpUser: EditText
    private lateinit var etSmtpPass: EditText
    private lateinit var etSmtpFrom: EditText

    private val prefs by lazy { getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE) }
    private val dpsPrefs by lazy { createDeviceProtectedStorageContext().getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE) }
    private var isPinDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_settings)

        // 初始化前后台监听（仅注册一次）
        PinLockManager.init()

        etParentEmail = findViewById(R.id.etParentEmail)
        btnTestSmtp = findViewById(R.id.btnTestSmtp)
        swSmtpSsl = findViewById(R.id.swSmtpSsl)
        swSmtpTls = findViewById(R.id.swSmtpTls)
        etSmtpHost = findViewById(R.id.etSmtpHost)
        etSmtpPort = findViewById(R.id.etSmtpPort)
        etSmtpUser = findViewById(R.id.etSmtpUser)
        etSmtpPass = findViewById(R.id.etSmtpPass)
        etSmtpFrom = findViewById(R.id.etSmtpFrom)

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
    }

    override fun onResume() {
        super.onResume()
        // 应用从后台回到前台时，强制进行一次 PIN 验证
        PinLockManager.init()
        if (PinLockManager.peekRequire() && !isPinDialogShowing) {
            val saved = prefs.getString("pin", null)
            if (saved.isNullOrEmpty()) {
                showSetPinDialog {
                    PinLockManager.consumeRequire()
                }
            } else {
                showEnterPinDialog(saved) {
                    PinLockManager.consumeRequire()
                }
            }
        }
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

    private fun showEnterPinDialog(saved: String, onSuccess: (() -> Unit)? = null) {
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
                } else {
                    onSuccess?.invoke()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .create()
        dialog.setOnDismissListener { isPinDialogShowing = false }
        dialog.show()
    }

    private fun showSetPinDialog(onSuccess: (() -> Unit)? = null) {
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
                    prefs.edit().putString("pin", p1).apply()
                    onSuccess?.invoke()
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

    // Simple TextWatcher to auto-save on text change
    private fun simpleWatcher(onChange: () -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { onChange() }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }
}