package com.example.phonenet

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class KeepAliveJobService : JobService() {
    
    override fun onStartJob(params: JobParameters?): Boolean {
        // 检查VPN服务是否需要重启
        val prefs = getSharedPreferences("phonenet_prefs", Context.MODE_PRIVATE)
        val shouldBeRunning = prefs.getBoolean("vpn_running", false)
        val userStopped = prefs.getBoolean("vpn_user_stop", false)
        
        if (shouldBeRunning && !userStopped) {
            // 尝试重启VPN服务
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (_: Exception) { }
        }
        
        // 重新调度下一次检查
        scheduleNextCheck()
        
        jobFinished(params, false)
        return false
    }
    
    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }
    
    private fun scheduleNextCheck() {
        try {
            val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            val jobInfo = android.app.job.JobInfo.Builder(2001, android.content.ComponentName(this, KeepAliveJobService::class.java))
                .setMinimumLatency(60000) // 1分钟后再次检查
                .setOverrideDeadline(120000) // 最多2分钟内执行
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .build()
            jobScheduler.schedule(jobInfo)
        } catch (_: Exception) { }
    }
}