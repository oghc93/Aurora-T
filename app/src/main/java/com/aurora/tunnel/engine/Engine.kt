package com.aurora.tunnel.engine

import android.content.Context
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile

/**
 * Engine membuka jalur ke server lalu menyediakan SOCKS5 di 127.0.0.1:port.
 * Lapisan TUN (hev-socks5-tunnel) menyambung ke port itu.
 */
interface Engine {
    /** Mulai dan kembali ketika SOCKS5 lokal siap dipakai. Lempar exception bila gagal. */
    suspend fun start(p: Profile, ctx: Context, port: Int)
    fun stop()
    fun isAlive(): Boolean
}

object EngineFactory {
    fun create(mode: Mode): Engine = when (mode) {
        Mode.SSH -> SshEngine()
        Mode.UDPCUSTOM -> SshEngine(forceUdpgw = true)
        Mode.SLOWDNS -> SlowDnsEngine()
        Mode.XRAY -> XrayEngine()
        Mode.OVPN -> OvpnEngine()
    }
}
