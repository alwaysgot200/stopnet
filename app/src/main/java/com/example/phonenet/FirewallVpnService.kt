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

        // 标记服务运行中（普通存储与 DPS 都写入，兼容未解锁阶段）
        try {
            val p1 = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_running", true).apply()
            // 启动时清除“用户手动停止”标记
            p1.edit().putBoolean("vpn_user_stop", false).apply()

            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_running", true).apply()
            dps.edit().putBoolean("vpn_user_stop", false).apply()
        } catch (_: Exception) { }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        workerThread?.interrupt()
        workerThread = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)

        // 清除运行标记
        var userStopped = false
        try {
            val p1 = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_running", false).apply()
            userStopped = userStopped || p1.getBoolean("vpn_user_stop", false)

            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_running", false).apply()
            userStopped = userStopped || dps.getBoolean("vpn_user_stop", false)
        } catch (_: Exception) { }

        // 若不是手动停止，安排自恢复；否则不重启
        if (!userStopped) {
            try {
                val am = getSystemService(android.app.AlarmManager::class.java)
                val intent = Intent("com.example.phonenet.ACTION_RESTART_VPN").apply {
                    `package` = packageName
                }
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
                val pi = android.app.PendingIntent.getBroadcast(this, 1001, intent, flags)
                am?.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi)
            } catch (_: Exception) { }
        }
    }

    // 当用户在最近任务里清理你的任务时，某些系统会尝试停止服务；这里立即自恢复
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
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

    // 当用户/系统撤销了你的 VPN 连接或权限时回调
    override fun onRevoke() {
        super.onRevoke()
        // 如果权限仍被授予（prepare 返回 null），尝试自动重连
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            // 权限被撤销或被要求重新授权：发通知引导家长去系统 VPN 设置打开 Always-on + “无 VPN 不允许连接”
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "phonenet_vpn_alert"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(channelId, "PhoneNet VPN 警示", NotificationManager.IMPORTANCE_HIGH))
            }
            val settingsIntent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
            val pi = PendingIntent.getActivity(
                this, 1, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val notif = NotificationCompat.Builder(this, channelId)
                .setContentTitle("VPN 已断开")
                .setContentText("请在系统 VPN 设置中将 PhoneNet 设为“始终开启”，并启用“无 VPN 不允许连接”。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(1002, notif)
        }
    }
}