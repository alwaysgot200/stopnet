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

        try {
            val p1 = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_running", true).apply()
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

        if (!userStopped) {
            try {
                val am = getSystemService(android.app.AlarmManager::class.java)
                val intent = Intent("com.example.phonenet.ACTION_RESTART_VPN").apply {
                    `package` = packageName
                }
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
                val canExact = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    am?.canScheduleExactAlarms() == true
                } else true

                if (canExact) {
                    val pi = android.app.PendingIntent.getBroadcast(this, 1001, intent, flags)
                    am?.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi)
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        scheduleJobRestart()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // 当用户在最近任务里清理你的任务时立即自恢复（需与主进程同进程）
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val userStopped = prefs.getBoolean("vpn_user_stop", false)

        if (!userStopped) {
            for (i in 0..2) {
                try {
                    val serviceIntent = Intent(this, FirewallVpnService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } catch (_: Exception) { }
            }
            scheduleMultipleRestarts()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                scheduleJobRestart()
            }
        }
    }

    private fun scheduleMultipleRestarts() {
        try {
            val am = getSystemService(android.app.AlarmManager::class.java)
            val intent = Intent("com.example.phonenet.ACTION_RESTART_VPN").apply {
                `package` = packageName
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)

            val canExact = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                am?.canScheduleExactAlarms() == true
            } else true

            if (canExact) {
                val restartTimes = longArrayOf(3000, 10000, 30000, 60000, 120000)
                restartTimes.forEachIndexed { index, delay ->
                    val pi = android.app.PendingIntent.getBroadcast(this, 1010 + index, intent, flags)
                    am?.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pi)
                }
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    scheduleJobRestart()
                }
            }
        } catch (_: Exception) { }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
    private fun scheduleJobRestart() {
        try {
            val jobScheduler = getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            val jobInfo = android.app.job.JobInfo.Builder(2001, android.content.ComponentName(this, KeepAliveJobService::class.java))
                .setMinimumLatency(5000)
                .setOverrideDeadline(15000)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .build()
            jobScheduler.schedule(jobInfo)
        } catch (_: Exception) { }
    }

    private fun setupAndStartVpn() {
        if (vpnInterface != null && workerThread?.isAlive == true) return

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

        val builder = Builder().setSession("PhoneNet Firewall")

        builder.addAddress("10.0.0.2", 32)
        builder.addRoute("0.0.0.0", 0)

        try {
            builder.addAddress("fd00:1:1::2", 128)
            builder.addRoute("::", 0)
        } catch (_: Exception) { }

        whitelist.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: Exception) { }
        }

        vpnInterface = builder.establish()

        vpnInterface?.fileDescriptor?.let { fd ->
            val input = FileInputStream(fd)
            val buffer = ByteBuffer.allocate(32767)
            workerThread = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val length = input.read(buffer.array())
                        if (length > 0) {
                            buffer.clear()
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
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "PhoneNet VPN", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "PhoneNet 网络管控服务 - 请勿关闭"
            channel.setShowBadge(false)
            channel.enableLights(false)
            channel.enableVibration(false)
            channel.setSound(null, null)
            nm?.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = android.app.PendingIntent.getActivity(this, 0, intent, flags)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PhoneNet 网络管控运行中")
            .setContentText("正在保护设备网络安全，请勿关闭此通知")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .build()

        startForeground(1001, notification)
    }

    override fun onRevoke() {
        super.onRevoke()
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
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