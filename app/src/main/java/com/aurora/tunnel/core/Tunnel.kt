package com.aurora.tunnel.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Status { IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

/** Status tunnel yang dibaca UI dan Quick Settings tile. */
object Tunnel {
    val status = MutableStateFlow(Status.IDLE)
    val error = MutableStateFlow<String?>(null)
    val connectedAt = MutableStateFlow(0L)
    val rxBytes = MutableStateFlow(0L)
    val txBytes = MutableStateFlow(0L)
    val rxRate = MutableStateFlow(0L)
    val txRate = MutableStateFlow(0L)
    val activeProfileId = MutableStateFlow<String?>(null)

    val isActive: Boolean
        get() = status.value == Status.CONNECTED || status.value == Status.CONNECTING

    fun resetStats() {
        rxBytes.value = 0; txBytes.value = 0; rxRate.value = 0; txRate.value = 0
    }
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val k = b / 1024.0
    if (k < 1024) return "%.1f KB".format(k)
    val m = k / 1024.0
    if (m < 1024) return "%.1f MB".format(m)
    return "%.2f GB".format(m / 1024.0)
}

fun formatRate(b: Long): String = formatBytes(b) + "/s"

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
