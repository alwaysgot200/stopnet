package com.example.stopnet

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object PinLockManager : DefaultLifecycleObserver {
    @Volatile
    private var registered: Boolean = false

    @Volatile
    private var requirePinNextForeground: Boolean = false

    fun init() {
        if (registered) return
        registered = true
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        } catch (_: Exception) { /* safe */ }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App 进入后台：标记下次回到前台需要 PIN
        requirePinNextForeground = true
    }

    fun peekRequire(): Boolean = requirePinNextForeground

    fun consumeRequire(): Boolean {
        return if (requirePinNextForeground) {
            requirePinNextForeground = false
            true
        } else {
            false
        }
    }
}