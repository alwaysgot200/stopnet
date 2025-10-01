package com.example.phonenet.mail

import android.content.Context
import android.content.Intent
import androidx.core.app.JobIntentService
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import java.util.Properties

class MailJobIntentService : JobIntentService() {

    companion object {
        private const val JOB_ID = 1001
        fun enqueue(context: Context, to: String, subject: String, body: String) {
            val intent = Intent(context, MailJobIntentService::class.java).apply {
                putExtra("to", to)
                putExtra("subject", subject)
                putExtra("body", body)
            }
            enqueueWork(context, MailJobIntentService::class.java, JOB_ID, intent)
        }
    }

    override fun onHandleWork(intent: Intent) {
        val ctx = applicationContext

        val prefs = ctx.getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val dpsPrefs = ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)

        fun prefString(key: String): String? {
            return prefs.getString(key, dpsPrefs.getString(key, null))
        }
        fun prefInt(key: String, def: Int): Int {
            val v = prefs.getInt(key, -1)
            return if (v > 0) v else dpsPrefs.getInt(key, def)
        }
        fun prefBool(key: String, def: Boolean): Boolean {
            return prefs.getBoolean(key, dpsPrefs.getBoolean(key, def))
        }

        val host = prefString("smtp_host")
        // 默认端口：SSL=465，STARTTLS=587（如果未配置时根据模式选择）
        val ssl = prefBool("smtp_ssl", false)
        val port = prefInt("smtp_port", if (ssl) 465 else 587)
        val tls = prefBool("smtp_tls", !ssl)
        val user = prefString("smtp_user")
        val pass = prefString("smtp_pass")
        val from = prefString("smtp_from")

        val to = intent.getStringExtra("to")
        val subject = intent.getStringExtra("subject") ?: ""
        val body = intent.getStringExtra("body") ?: ""

        if (host.isNullOrBlank() || port <= 0 || user.isNullOrBlank() || pass.isNullOrBlank() || from.isNullOrBlank() || to.isNullOrBlank()) {
            return
        }

        try {
            val props = Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
                put("mail.smtp.writetimeout", "10000")
                if (ssl) {
                    put("mail.smtp.ssl.enable", "true")
                } else {
                    put("mail.smtp.starttls.enable", tls.toString())
                }
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(user, pass)
                }
            })
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(from))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject, "UTF-8")
                setText(body, "UTF-8")
            }
            Transport.send(message)
        } catch (_: Exception) {
            // 可按需增加日志或重试
        }
    }
}