package com.example.phonenet

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStartVpn)
        btnStop = findViewById(R.id.btnStopVpn)
        btnSettings = findViewById(R.id.btnSettings)

        btnStart.setOnClickListener { startVpn() }
        btnStop.setOnClickListener { stopVpn() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    companion object {
        private const val PREPARE_VPN_REQ = 1001
        private const val REQUEST_NOTIF = 2001
    }

    private fun startVpn() {
        // Android 13+ 需要通知权限用于前台服务通知
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
                return
            }
        }

        val intent = VpnService.prepare(this)
        if (intent != null) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.vpn_permission_required))
                .setPositiveButton("OK") { _, _ ->
                    startActivityForResult(intent, PREPARE_VPN_REQ)
                }
                .show()
        } else {
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PREPARE_VPN_REQ && resultCode == RESULT_OK) {
            val serviceIntent = Intent(this, FirewallVpnService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startVpn()
            } else {
                android.widget.Toast.makeText(this, "需要通知权限以启动前台服务", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopVpn() {
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
        stopService(serviceIntent)
    }
}