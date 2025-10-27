package com.example.stopnet

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class GeneralSettingsActivity : AppCompatActivity() {

    private lateinit var swDefaultAutoStartVpn: android.widget.Switch
    private val prefs by lazy { getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE) }
    private val dpsPrefs by lazy { createDeviceProtectedStorageContext().getSharedPreferences("stopnet_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_settings)

        // 初始化前后台监听（仅注册一次）
        PinLockManager.init()

        swDefaultAutoStartVpn = findViewById(R.id.swDefaultAutoStartVpn)
        val auto = prefs.getBoolean("default_auto_start_vpn", dpsPrefs.getBoolean("default_auto_start_vpn", true))
        swDefaultAutoStartVpn.isChecked = auto
        swDefaultAutoStartVpn.setOnCheckedChangeListener { _, checked ->
            // 双写：普通存储与设备保护存储
            prefs.edit().putBoolean("default_auto_start_vpn", checked).apply()
            dpsPrefs.edit().putBoolean("default_auto_start_vpn", checked).apply()
        }
    }
}