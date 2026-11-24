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

    private fun readFully(i: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var r = 0
        while (r < n) { val x = i.read(b, r, n - r); if (x < 0) throw java.io.EOFException(); r += x }
        return b
    }

    /** Ping melalui tunnel: SOCKS5 CONNECT ke host:443 lewat port lokal. ms atau -1. */
    suspend fun viaSocks(port: Int, host: String, timeoutMs: Int = 6000): Long = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                s.soTimeout = timeoutMs
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                if (readFully(i, 2)[1].toInt() != 0) return@withContext -1L
                val hb = host.toByteArray()
                o.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte()) + hb + byteArrayOf(0x01, 0xBB.toByte())); o.flush()
                val h = readFully(i, 4)
                if (h[1].toInt() != 0) return@withContext -1L
            }
            (System.nanoTime() - t0) / 1_000_000
        } catch (_: Exception) { -1L }
    }

    /** Uji UDP lewat SOCKS5 UDP ASSOCIATE: kirim query DNS ke 8.8.8.8 dan tunggu jawaban. */
    suspend fun udpDiag(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                s.soTimeout = 4000
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                if (readFully(i, 2)[1].toInt() != 0) return@withContext false
                o.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                val h = readFully(i, 4)
                if (h[1].toInt() != 0) return@withContext false
                val addr = readFully(i, if (h[3].toInt() == 4) 16 else 4)
                val pb = readFully(i, 2)
                val relayPort = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
                val relayHost = if (addr.size == 4 && addr.all { it.toInt() == 0 }) "127.0.0.1"
                    else java.net.InetAddress.getByAddress(addr).hostAddress!!
                val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                    6, 'g'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'g'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                    3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
                val pkt = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53) + q
                java.net.DatagramSocket().use { ds ->
                    ds.soTimeout = 4000
                    ds.send(java.net.DatagramPacket(pkt, pkt.size, java.net.InetAddress.getByName(relayHost), relayPort))
                    val buf = ByteArray(1500)
                    val rp = java.net.DatagramPacket(buf, buf.size)
                    ds.receive(rp)
                    rp.length > 14
                }
            }
        } catch (_: Exception) { false }
    }
}
