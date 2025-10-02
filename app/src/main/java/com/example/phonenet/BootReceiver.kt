package com.example.phonenet

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
        }
    }

    private fun startIfEnabledLocked(context: Context) {
        val dpsCtx = context.createDeviceProtectedStorageContext()
        val prefs = dpsCtx.getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        // 开机自启开关
        val enabled = prefs.getBoolean("auto_start_on_boot", true)
        // 运行状态
        val running = prefs.getBoolean("vpn_running", false)
        // 仅当白名单存在且非空时，才在未解锁阶段启动
        val whitelist = prefs.getStringSet("whitelist_packages", null)
        if (!enabled || running || whitelist == null || whitelist.isEmpty()) return

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
        val prefs = context.getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("auto_start_on_boot", true)
        val running = prefs.getBoolean("vpn_running", false)
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
}