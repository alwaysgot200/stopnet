package com.example.stopnet.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "设备管理已启用", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val prefs = context.getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val email = prefs.getString("parent_email", null)
        if (!email.isNullOrBlank()) {
            val subject = "【PhoneNet】设备管理停用提醒"
            val body = "设备管理权限正在被停用，可能即将卸载应用。请注意孩子的上网行为。"
            try {
                com.example.stopnet.mail.MailJobIntentService.enqueue(context, email, subject, body)
            } catch (_: Exception) {
                // 忽略发送失败
            }
        }
        return "停用设备管理将允许卸载本应用，并可能解除网络管控。"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "设备管理已停用", Toast.LENGTH_SHORT).show()
    }
}