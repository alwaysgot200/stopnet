package com.example.stopnet

import java.util.concurrent.CopyOnWriteArrayList

object VpnStateStore {
    @Volatile
    private var state: Boolean? = null
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun set(isRunning: Boolean) {
        state = isRunning
        listeners.forEach { listener ->
            try { listener(isRunning) } catch (_: Exception) { }
        }
    }

    fun current(): Boolean? = state

    fun addListener(listener: (Boolean) -> Unit) {
        // 立即回放当前状态（若已有）
        state?.let {
            try { listener(it) } catch (_: Exception) { }
        }
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}