package com.aurora.tunnel.engine

import android.content.Context
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.log.AppLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Load balance: menjalankan beberapa engine (mis. Xray + SSH), masing-masing dengan SOCKS5 lokalnya,
 * lalu membagi koneksi TCP baru secara bergiliran. UDP hanya DNS (lewat DNS-over-TCP).
 */
class LbEngine(private val nodes: List<Profile>) : Engine {
    private class Backend(val engine: Engine, val port: Int, val name: String)

    private val backends = mutableListOf<Backend>()
    private var server: Socks5Server? = null
    private val rr = AtomicInteger(0)

    override suspend fun start(p: Profile, ctx: Context, port: Int) {
        for (n in nodes) {
            val e = EngineFactory.create(n.mode)
            val bp = freeLocalPort()
            try {
                AppLog.i("LB", "Node “${n.name}” (${n.mode.label})…")
                e.start(n, ctx, bp)
                backends += Backend(e, bp, n.name)
            } catch (ex: Exception) {
                AppLog.w("LB", "Node “${n.name}” gagal: ${ex.message}")
                runCatching { e.stop() }
            }
        }
        if (backends.isEmpty()) throw IllegalStateException("Semua node Load Balance gagal")
        val up = BalancerUpstream()
        val srv = Socks5Server(port, up)
        srv.start()
        server = srv
        AppLog.s("LB", "Load balancer aktif: ${backends.size}/${nodes.size} node")
    }

    override fun stop() {
        runCatching { server?.stop() }
        backends.forEach { runCatching { it.engine.stop() } }
        backends.clear()
        server = null
    }

    override fun isAlive() = backends.any { it.engine.isAlive() }

    private inner class BalancerUpstream : Upstream {
        override fun openTcp(host: String, port: Int): TcpConn {
            val alive = backends.filter { it.engine.isAlive() }.ifEmpty { backends }
            val start = rr.getAndIncrement()
            var last: Exception? = null
            for (k in alive.indices) {
                val b = alive[(start + k) % alive.size]
                try { return socksConnect(b.port, host, port) } catch (e: Exception) { last = e }
            }
            throw last ?: IllegalStateException("Tidak ada node aktif")
        }

        override fun openUdp(): UdpRelay = DnsTcpRelay(this)
    }

    private fun socksConnect(backendPort: Int, host: String, port: Int): TcpConn {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", backendPort), 3000)
            s.soTimeout = 20000
            val o = s.getOutputStream(); val i = s.getInputStream()
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val g = ByteArray(2); readFully(i, g)
            if (g[1].toInt() != 0) throw java.io.IOException("SOCKS auth ditolak")
            val ip = runCatching {
                if (host.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) InetAddress.getByName(host).address else null
            }.getOrNull()
            val req = if (ip != null) byteArrayOf(5, 1, 0, if (ip.size == 4) 1 else 4) + ip
            else { val hb = host.toByteArray(); byteArrayOf(5, 1, 0, 3, hb.size.toByte()) + hb }
            o.write(req + byteArrayOf((port shr 8).toByte(), port.toByte())); o.flush()
            val h = ByteArray(4); readFully(i, h)
            if (h[1].toInt() != 0) throw java.io.IOException("SOCKS gagal (kode ${h[1]})")
            val rest = when (h[3].toInt()) { 1 -> 6; 4 -> 18; else -> { val l = i.read(); l + 2 } }
            readFully(i, ByteArray(rest))
            s.soTimeout = 0
            return object : TcpConn {
                override val input = i
                override val output = o
                override fun close() { runCatching { s.close() } }
            }
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    private fun readFully(i: java.io.InputStream, b: ByteArray) {
        var r = 0
        while (r < b.size) { val x = i.read(b, r, b.size - r); if (x < 0) throw java.io.EOFException(); r += x }
    }
}
