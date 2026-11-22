package com.aurora.tunnel.engine

import com.aurora.tunnel.log.AppLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Klien protokol badvpn-udpgw lewat satu channel SSH ke udpgw server.
 * Frame: [len LE16][flags][conid LE16][ip 4|16][port BE16][data]
 */
class UdpGwRelay(private val conn: TcpConn) : UdpRelay {
    override var onReply: ((String, Int, ByteArray) -> Unit)? = null
    private val ids = ConcurrentHashMap<String, Int>()
    private var nextId = 1
    @Volatile private var closed = false

    init {
        val t = Thread({ readLoop(conn.input) }, "udpgw-read")
        t.isDaemon = true
        t.start()
    }

    override fun send(host: String, port: Int, data: ByteArray) {
        if (closed || data.size > 65000) return
        val ip = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return
        val key = "$host:$port"
        val id = ids.getOrPut(key) { synchronized(this) { nextId = if (nextId >= 65535) 1 else nextId + 1; nextId } }
        val flags = if (ip.size == 16) 0x08 else 0x00
        val body = ByteArray(3 + ip.size + 2 + data.size)
        body[0] = flags.toByte()
        body[1] = (id and 0xff).toByte(); body[2] = ((id shr 8) and 0xff).toByte()
        System.arraycopy(ip, 0, body, 3, ip.size)
        val o = 3 + ip.size
        body[o] = ((port shr 8) and 0xff).toByte(); body[o + 1] = (port and 0xff).toByte()
        System.arraycopy(data, 0, body, o + 2, data.size)
        val frame = ByteArray(2 + body.size)
        frame[0] = (body.size and 0xff).toByte(); frame[1] = ((body.size shr 8) and 0xff).toByte()
        System.arraycopy(body, 0, frame, 2, body.size)
        try {
            synchronized(conn.output) { conn.output.write(frame); conn.output.flush() }
        } catch (e: Exception) {
            AppLog.w("UDPGW", "Gagal kirim: ${e.message}")
        }
    }

    private fun readLoop(i: InputStream) {
        try {
            while (!closed) {
                val h = readN(i, 2)
                val len = (h[0].toInt() and 0xff) or ((h[1].toInt() and 0xff) shl 8)
                if (len < 5) { readN(i, len); continue }
                val b = readN(i, len)
                val flags = b[0].toInt() and 0xff
                if (flags and 0x01 != 0) continue // keepalive
                val alen = if (flags and 0x08 != 0) 16 else 4
                if (len < 3 + alen + 2) continue
                val ip = InetAddress.getByAddress(b.copyOfRange(3, 3 + alen)).hostAddress!!
                val po = 3 + alen
                val port = ((b[po].toInt() and 0xff) shl 8) or (b[po + 1].toInt() and 0xff)
                onReply?.invoke(ip, port, b.copyOfRange(po + 2, len))
            }
        } catch (e: Exception) {
            if (!closed) AppLog.w("UDPGW", "Channel udpgw terputus: ${e.message}")
        }
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var r = 0
        while (r < n) {
            val x = i.read(b, r, n - r)
            if (x < 0) throw java.io.EOFException()
            r += x
        }
        return b
    }

    override fun close() { closed = true; runCatching { conn.close() } }
}

/** Cadangan bila udpgw tidak aktif: hanya DNS (port 53) lewat DNS-over-TCP. */
class DnsTcpRelay(private val up: Upstream) : UdpRelay {
    override var onReply: ((String, Int, ByteArray) -> Unit)? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dns-tcp").apply { isDaemon = true } }
    private var warned = false

    override fun send(host: String, port: Int, data: ByteArray) {
        if (port != 53) {
            if (!warned) { warned = true; AppLog.i("UDP", "UDP non-DNS dibuang (udpgw nonaktif)") }
            return
        }
        pool.execute {
            try {
                up.openTcp(host, 53).use { c ->
                    val q = ByteArray(2 + data.size)
                    q[0] = (data.size shr 8).toByte(); q[1] = data.size.toByte()
                    System.arraycopy(data, 0, q, 2, data.size)
                    c.output.write(q); c.output.flush()
                    val lh = ByteArray(2)
                    readFully(c.input, lh)
                    val len = ((lh[0].toInt() and 0xff) shl 8) or (lh[1].toInt() and 0xff)
                    val resp = ByteArray(len)
                    readFully(c.input, resp)
                    onReply?.invoke(host, 53, resp)
                }
            } catch (e: Exception) {
                AppLog.d("DNS", "Query DNS gagal: ${e.message}")
            }
        }
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var r = 0
        while (r < b.size) {
            val x = i.read(b, r, b.size - r)
            if (x < 0) throw java.io.EOFException()
            r += x
        }
    }

    override fun close() { pool.shutdownNow() }
}
