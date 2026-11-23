package com.aurora.tunnel.engine

import android.content.Context
import com.aurora.tunnel.data.Profile

/**
 * OpenVPN belum ikut di build ini. Butuh binary/library OpenVPN native (mis. dari ics-openvpn)
 * dan jembatan TUN via management interface, jadi dikerjakan sebagai tahap berikutnya.
 */
class OvpnEngine : Engine {
    override suspend fun start(p: Profile, ctx: Context, port: Int) {
        throw IllegalStateException("Mode OpenVPN belum tersedia di versi ini (tahap 2).")
    }
    override fun stop() {}
    override fun isAlive() = false
}

object Ovpn {
    /** Ambil 'remote host port' pertama untuk ping. */
    fun endpoint(cfg: String): Pair<String, Int>? {
        val m = Regex("(?m)^\\s*remote\\s+(\\S+)\\s+(\\d+)").find(cfg) ?: return null
        return m.groupValues[1] to m.groupValues[2].toInt()
    }
}
