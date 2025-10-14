package com.example.stopnet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "收到广播: $action")
        
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "设备启动完成")
                // 清除开机前的运行状态，强制重新启动
                clearBootStates(context)
                startIfEnabledUnlocked(context)
                // 安排多次重试以应对vivo系统的后台限制
                scheduleMultipleRetries(context)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.d(TAG, "设备锁定启动完成")
                clearBootStates(context)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    val um = context.getSystemService(android.os.UserManager::class.java)
                    if (um?.isUserUnlocked == true) {
                        startIfEnabledUnlocked(context)
                    } else {
                        startIfEnabledLocked(context)
                    }
                } else {
                    startIfEnabledUnlocked(context)
                }
            }
            Intent.ACTION_USER_UNLOCKED -> {
                Log.d(TAG, "用户解锁")
                startIfEnabledUnlocked(context)
            }
            "com.example.stopnet.ACTION_RESTART_VPN" -> {
                Log.d(TAG, "接收到重启VPN请求")
                startIfEnabledUnlocked(context)
            }
        }
    }

    // 清除开机前的状态标记，确保重新启动
    private fun clearBootStates(context: Context) {
        try {
            // 清除普通存储的运行状态
            val prefs = context.getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("vpn_running", false)
                putBoolean("vpn_user_stop", false)
                apply()
            }
            
            // 清除设备保护存储的运行状态
            val dpsCtx = context.createDeviceProtectedStorageContext()
            val dpsPrefs = dpsCtx.getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
            dpsPrefs.edit().apply {
                putBoolean("vpn_running", false)
                putBoolean("vpn_user_stop", false)
                apply()
            }
            Log.d(TAG, "已清除启动状态")
        } catch (e: Exception) {
            Log.e(TAG, "清除状态失败: ${e.message}")
        }
    }

    // 针对vivo系统安排多次重试
    private fun scheduleMultipleRetries(context: Context) {
        try {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            val restartIntent = Intent("com.example.stopnet.ACTION_RESTART_VPN").apply {
                `package` = context.packageName
            }
            val flagsPi = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)

            // 在开机后10秒、30秒、60秒、120秒各尝试一次
            val delays = longArrayOf(10000, 30000, 60000, 120000)
            delays.forEachIndexed { index, delay ->
                try {
                    val pi = android.app.PendingIntent.getBroadcast(
                        context,
                        2000 + index,
                        restartIntent,
                        flagsPi
                    )
                    am?.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delay,
                        pi
                    )
                    Log.d(TAG, "已安排第${index + 1}次重试，延迟${delay}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "安排重试失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "安排多次重试失败: ${e.message}")
        }
    }

    private fun startIfEnabledLocked(context: Context) {
        val dpsCtx = context.createDeviceProtectedStorageContext()
        val prefs = dpsCtx.getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("auto_start_on_boot", true)
        
        Log.d(TAG, "锁定模式启动检查 - 自启动开关: $enabled")
        
        // 开机自启动：忽略 vpn_running 状态（可能是上次遗留的）
        if (!enabled) {
            Log.d(TAG, "自启动已关闭，跳过")
            return
        }
        
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent == null) {
            Log.d(TAG, "VPN已授权，启动服务（锁定模式）")
            val serviceIntent = Intent(context, FirewallVpnService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "服务启动成功")
            } catch (e: Exception) {
                Log.e(TAG, "启动服务失败: ${e.message}")
            }
        } else {
            Log.w(TAG, "VPN未授权，无法在锁定模式启动")
        }
    }

    private fun startIfEnabledUnlocked(context: Context) {
        val prefs = context.getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        // 读取"开机自启 StopNet"开关：系统层允许自启时通常为 true
        val enabled = prefs.getBoolean("auto_start_on_boot", true)
        
        Log.d(TAG, "解锁模式启动检查 - 自启动开关: $enabled")
        
        // 开机自启动：不再检查 vpn_running 状态，因为已在 clearBootStates 中清除
        if (!enabled) {
            Log.d(TAG, "自启动已关闭，跳过")
            return
        }
        
        // 持有唤醒锁确保启动流程不被打断（vivo系统容易杀后台）
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val wl = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "stopnet:boot")
        try {
            wl?.acquire(30000) // 持有30秒
            
            // 系统 VPN 授权检查：返回 null 表示已授权，可后台启动
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent == null) {
                Log.d(TAG, "VPN已授权，启动服务")
                // 已授权：直接后台启动前台服务
                val serviceIntent = Intent(context, FirewallVpnService::class.java)
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "服务启动成功")
                } catch (e: Exception) {
                    Log.e(TAG, "启动服务失败: ${e.message}")
                    // 启动失败，安排重试
                    scheduleRetry(context, 5000)
                }
            } else {
                Log.w(TAG, "VPN未授权，尝试启动MainActivity")
                // 未授权：后台尝试拉起主界面，引导授权/启动
                try {
                    val launch = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("from_boot", true)
                    }
                    context.startActivity(launch)
                    Log.d(TAG, "已尝试启动MainActivity")
                } catch (e: Exception) {
                    Log.e(TAG, "启动MainActivity失败: ${e.message}")
                }

                // 安排一次短延时重试
                scheduleRetry(context, 5000)
            }
        } finally {
            try {
                wl?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放唤醒锁失败: ${e.message}")
            }
        }
    }

    private fun scheduleRetry(context: Context, delayMs: Long) {
        try {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            val retryIntent = Intent("com.example.stopnet.ACTION_RESTART_VPN").apply {
                `package` = context.packageName
            }
            val flagsPi = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = android.app.PendingIntent.getBroadcast(context, 1010, retryIntent, flagsPi)
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pi
            )
            Log.d(TAG, "已安排重试，延迟${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "安排重试失败: ${e.message}")
        }
    }
}
