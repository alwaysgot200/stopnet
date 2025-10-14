package com.example.stopnet

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJobService"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "KeepAliveJobService 开始执行")
        
        val prefs = getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE)
        // 检查是否应该运行（开机自启动开关）
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", true)
        val userStopped = prefs.getBoolean("vpn_user_stop", false)
        val shouldRun = autoStartEnabled && !userStopped

        Log.d(TAG, "自启动: $autoStartEnabled, 用户停止: $userStopped, 应该运行: $shouldRun")

        if (shouldRun) {
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d(TAG, "VPN服务启动成功")
            } catch (e: Exception) {
                Log.e(TAG, "VPN服务启动失败: ${e.message}")
            }
        }

        // 立即安排下一次检查
        scheduleNextCheck()
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "KeepAliveJobService 被停止")
        return true // 表示需要重试
    }

    private fun scheduleNextCheck() {
        try {
            val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            // 建立周期性任务，每15分钟检查一次
            val jobInfo = android.app.job.JobInfo.Builder(2001, android.content.ComponentName(this, KeepAliveJobService::class.java))
                .setPeriodic(15 * 60 * 1000) // 15分钟
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true) // 设备重启后仍然保持
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .build()
            val result = jobScheduler.schedule(jobInfo)
            Log.d(TAG, "安排下一次检查: ${if (result == android.app.job.JobScheduler.RESULT_SUCCESS) "成功" else "失败"}")
        } catch (e: Exception) {
            Log.e(TAG, "安排下一次检查失败: ${e.message}")
        }
    }
}