package com.aurora.tunnel.engine

import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object Ping {
    /** Titik yang di-ping untuk sebuah profil (host, port). */
    fun target(p: Profile): Pair<String, Int>? = when (p.mode) {
        Mode.SSH, Mode.UDPCUSTOM ->
            if (p.proxyHost.isNotBlank() && p.proxyPort > 0) p.proxyHost to p.proxyPort
            else p.host.takeIf { it.isNotBlank() }?.let { it to p.port }
        Mode.SLOWDNS -> p.dnsServer.substringBefore(':').takeIf { it.isNotBlank() && !it.contains("//") }?.let { it to 53 }
        Mode.XRAY -> XrayConfig.endpoint(p)
        Mode.OVPN -> Ovpn.endpoint(p.ovpnConfig)
    }

    /** Waktu konek TCP dalam ms, atau -1 bila gagal. */
    suspend fun tcp(host: String, port: Int, timeoutMs: Int = 4000): Long = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - t0) / 1_000_000
        } catch (_: Exception) { -1L }
    }
}
