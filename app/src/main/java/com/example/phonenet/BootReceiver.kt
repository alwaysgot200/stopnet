package com.example.stopnet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                startIfEnabledUnlocked(context)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
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
                startIfEnabledUnlocked(context)
            }
            "com.example.stopnet.ACTION_RESTART_VPN" -> {
                startIfEnabledUnlocked(context)
            }
        }
    }

    private fun startIfEnabledLocked(context: Context) {
        val dpsCtx = context.createDeviceProtectedStorageContext()
        val prefs = dpsCtx.getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("auto_start_on_boot", true)
        val running = prefs.getBoolean("vpn_running", false)
        // 移除 userStopped 的影响：设备重启后仍自动开启
        if (!enabled || running) return
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent == null) {
            val serviceIntent = Intent(context, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    private fun startIfEnabledUnlocked(context: Context) {
        val prefs = context.getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        // 读取“开机自启 StopNet”开关：系统层允许自启时通常为 true
        val enabled = prefs.getBoolean("auto_start_on_boot", true)
        // 当前 VPN 是否已运行（避免重复拉起）
        val running = prefs.getBoolean("vpn_running", false)
        // 若未开启自启或已在运行，直接返回
        if (!enabled || running) return
        // 系统 VPN 授权检查：返回 null 表示已授权，可后台启动
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent == null) {
            // 已授权：直接后台启动前台服务
            val serviceIntent = Intent(context, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            // 未授权：后台尝试拉起主界面，引导授权/启动
            try {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("from_boot", true)
                }
                // 即使未授予悬浮窗，也尝试启动；部分 ROM 在允许“自启动/关联启动”时会放行
                context.startActivity(launch)
            } catch (_: Exception) { }

            // 安排一次短延时重试（广播到自身）
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
                    System.currentTimeMillis() + 5000,
                    pi
                )
            } catch (_: Exception) { }
        }
    }
}