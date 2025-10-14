package com.example.stopnet

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

    companion object {
        const val ACTION_VPN_STATE_CHANGED = "com.example.stopnet.VPN_STATE_CHANGED"
        const val EXTRA_VPN_STATE = "vpn_state"
        const val ACTION_STOP_VPN = "com.example.stopnet.ACTION_STOP_VPN"
        // 新增：白名单重载
        const val ACTION_RELOAD_WHITELIST = "com.example.stopnet.ACTION_RELOAD_WHITELIST"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("FirewallVpnService", "onStartCommand - action: ${intent?.action}")
        
        // 处理显式 STOP：及时广播 false，清理资源并停止服务，避免重启
        if (intent?.action == ACTION_STOP_VPN) {
            try {
                val p1 = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
                p1.edit().putBoolean("vpn_user_stop", true).apply()
                p1.edit().putBoolean("vpn_running", false).apply()
                val dps = createDeviceProtectedStorageContext()
                    .getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
                dps.edit().putBoolean("vpn_user_stop", true).apply()
                dps.edit().putBoolean("vpn_running", false).apply()
            } catch (_: Exception) { }

            val broadcastIntent = Intent(ACTION_VPN_STATE_CHANGED).apply {
                setPackage(packageName)
                putExtra(EXTRA_VPN_STATE, false)
            }
            sendBroadcast(broadcastIntent)
            // 同步内存态
            VpnStateStore.set(false)

            // 新增：取消所有已安排的重启闹钟与 Job，防止服务被再次拉起
            try {
                val am = getSystemService(android.app.AlarmManager::class.java)
                val restart = Intent("com.example.stopnet.ACTION_RESTART_VPN").apply { `package` = packageName }
                val flagsPi = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)

                // 取消 onDestroy 安排的 1001，以及批量安排的 1010..1014
                val ids = intArrayOf(1001, 1010, 1011, 1012, 1013, 1014)
                ids.forEach { id ->
                    try {
                        val pi = android.app.PendingIntent.getBroadcast(this, id, restart, flagsPi)
                        am?.cancel(pi)
                    } catch (_: Exception) { }
                }

                // 取消保活 Job（2001）
                try {
                    val js = getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
                    js.cancel(2001)
                } catch (_: Exception) { }
            } catch (_: Exception) { }

            try { workerThread?.interrupt() } catch (_: Exception) { }
            workerThread = null
            try { vpnInterface?.close() } catch (_: Exception) { }
            vpnInterface = null
            // 替换弃用 API
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // 新增：白名单重载时关闭旧隧道以便重建
        if (intent?.action == ACTION_RELOAD_WHITELIST) {
            try { workerThread?.interrupt() } catch (_: Exception) { }
            workerThread = null
            try { vpnInterface?.close() } catch (_: Exception) { }
            vpnInterface = null
        }

        startForegroundNotification()
        setupAndStartVpn()

        try {
            val p1 = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_running", true).apply()
            p1.edit().putBoolean("vpn_user_stop", false).apply()
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_running", true).apply()
            dps.edit().putBoolean("vpn_user_stop", false).apply()
            android.util.Log.d("FirewallVpnService", "已更新运行状态")
        } catch (e: Exception) {
            android.util.Log.e("FirewallVpnService", "更新状态失败: ${e.message}")
        }

        val broadcastIntent = Intent(ACTION_VPN_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_VPN_STATE, true)
        }
        sendBroadcast(broadcastIntent)
        // 同步内存态
        VpnStateStore.set(true)
        
        // 启动后安排定期检查Job，保证服务持续运行
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            scheduleJobRestart()
        }
        android.util.Log.d("FirewallVpnService", "VPN服务启动完成")

        return START_STICKY
    }

    override fun onDestroy() {
        // 发送广播通知 UI 更新
        val broadcastIntent = Intent(ACTION_VPN_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_VPN_STATE, false)
        }
        sendBroadcast(broadcastIntent)
        // 同步内存态
        VpnStateStore.set(false)

        super.onDestroy()
        workerThread?.interrupt()
        workerThread = null
        vpnInterface?.close()
        vpnInterface = null
        // 替换弃用 API
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }

        var userStopped = false
        try {
            val p1 = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            p1.edit().putBoolean("vpn_running", false).apply()
            userStopped = userStopped || p1.getBoolean("vpn_user_stop", false)
            val dps = createDeviceProtectedStorageContext()
                .getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            dps.edit().putBoolean("vpn_running", false).apply()
            userStopped = userStopped || dps.getBoolean("vpn_user_stop", false)
        } catch (_: Exception) { }

        if (!userStopped) {
            try {
                val am = getSystemService(android.app.AlarmManager::class.java)
                val intent = Intent("com.example.stopnet.ACTION_RESTART_VPN").apply {
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

        val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        val userStopped = prefs.getBoolean("vpn_user_stop", false)

        if (!userStopped) {
            // 新增：短暂持有唤醒锁，确保下面的重启/调度不被 Doze 影响
            val pm = getSystemService(android.os.PowerManager::class.java)
            val wl = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "stopnet:restart")
            try {
                wl?.acquire(5000)

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
            } finally {
                try { wl?.release() } catch (_: Exception) { }
            }
        }
    }

    private fun scheduleMultipleRestarts() {
        try {
            val am = getSystemService(android.app.AlarmManager::class.java)
            val intent = Intent("com.example.stopnet.ACTION_RESTART_VPN").apply {
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
                    dpsCtx.getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
                } else {
                    getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
                }
            } else {
                getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            }
        }

        val whitelist = prefs.getStringSet("whitelist_packages", emptySet()) ?: emptySet()

        val builder = Builder().setSession("StopNet Firewall")

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
        val channelId = "stopnet_vpn_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "StopNet VPN", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "StopNet 网络管控服务 - 请勿关闭"
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
            .setContentTitle("StopNet 网络管控运行中")
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

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1001,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1001, notification)
        }
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
            val channelId = "stopnet_vpn_alert"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(channelId, "StopNet VPN 警示", NotificationManager.IMPORTANCE_HIGH))
            }
            val settingsIntent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
            val pi = PendingIntent.getActivity(
                this, 1, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val notif = NotificationCompat.Builder(this, channelId)
                .setContentTitle("VPN 已断开")
                .setContentText("请在系统 VPN 设置中将 StopNet 设为“始终开启”，并启用“无 VPN 不允许连接”。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(1002, notif)
        }
    }
}