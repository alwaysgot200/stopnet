package com.example.phonenet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.nio.ByteBuffer

class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        setupAndStartVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        workerThread?.interrupt()
        workerThread = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
    }

    private fun setupAndStartVpn() {
        // 防止重复启动导致资源重复占用
        if (vpnInterface != null && workerThread?.isAlive == true) return

        // 根据是否解锁选择读取的存储（DPS 在未解锁）
        val prefs = run {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val um = getSystemService(android.os.UserManager::class.java)
                if (um?.isUserUnlocked == false) {
                    val dpsCtx = createDeviceProtectedStorageContext()
                    dpsCtx.getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
                } else {
                    getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
                }
            } else {
                getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            }
        }

        val whitelist = prefs.getStringSet("whitelist_packages", emptySet()) ?: emptySet()

        val builder = Builder()
            .setSession("PhoneNet Firewall")
            // .setBlocking(true) // 兼容性考虑：API 29+ 才有，minSdk 24 下移除

        // 让全部 IPv4 流量进入 VPN
        builder.addAddress("10.0.0.2", 32)
        builder.addRoute("0.0.0.0", 0)

        // IPv6（可选，设备不支持时忽略）
        try {
            builder.addAddress("fd00:1:1::2", 128)
            builder.addRoute("::", 0)
        } catch (_: Exception) { }

        // 白名单应用不经 VPN（可正常上网）
        whitelist.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: Exception) { }
        }

        vpnInterface = builder.establish()

        // 黑洞线程：读入并丢弃所有包
        vpnInterface?.fileDescriptor?.let { fd ->
            val input = FileInputStream(fd)
            val buffer = ByteBuffer.allocate(32767)
            workerThread = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val length = input.read(buffer.array())
                        if (length > 0) {
                            buffer.clear() // 丢弃数据，不回写
                        } else {
                            Thread.sleep(10)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { input.close() } catch (_: Exception) {}
                }
            }.also { it.start() }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "phonenet_vpn_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "PhoneNet VPN", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.vpn_running))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }
}