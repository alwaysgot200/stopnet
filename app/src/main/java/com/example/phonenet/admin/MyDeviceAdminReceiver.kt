package com.example.phonenet.admin

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
            val subject = Uri.encode("【PhoneNet】设备管理停用提醒")
            val body = Uri.encode("设备管理权限正在被停用，可能即将卸载应用。请注意孩子的上网行为。")
            val mailUri = Uri.parse("mailto:$email?subject=$subject&body=$body")
            val mailIntent = Intent(Intent.ACTION_SENDTO, mailUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(mailIntent)
            } catch (_: Exception) {
                // 没有邮件客户端时忽略
            }
        }
        return "停用设备管理将允许卸载本应用，并可能解除网络管控。"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "设备管理已停用", Toast.LENGTH_SHORT).show()
    }
}