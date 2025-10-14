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
            // 在后台启动前台服务（VPN），随后由服务侧建立隧道
            val serviceIntent = Intent(context, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}