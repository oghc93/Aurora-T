package com.aurora.tunnel.engine

import com.aurora.tunnel.log.AppLog
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

interface TcpConn : Closeable {
    val input: InputStream
    val output: OutputStream
}

interface UdpRelay : Closeable {
    var onReply: ((host: String, port: Int, data: ByteArray) -> Unit)?
    fun send(host: String, port: Int, data: ByteArray)
}

interface Upstream {
    fun openTcp(host: String, port: Int): TcpConn
    fun openUdp(): UdpRelay?
}

/** Server SOCKS5 lokal (CONNECT + UDP ASSOCIATE) yang meneruskan ke [Upstream]. */
class Socks5Server(private val port: Int, private val up: Upstream) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "socks5").apply { isDaemon = true } }
    @Volatile private var running = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 256)
        server = ss
        running = true
        pool.execute {
            while (running) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        try {
            c.tcpNoDelay = true
            val i = c.getInputStream()
            val o = c.getOutputStream()
            if (i.read() != 5) return
            skip(i, i.read())
            o.write(byteArrayOf(5, 0)); o.flush()

            val h = readN(i, 4)
            val cmd = h[1].toInt()
            val host = when (h[3].toInt()) {
                1 -> InetAddress.getByAddress(readN(i, 4)).hostAddress!!
                3 -> String(readN(i, i.read()))
                4 -> InetAddress.getByAddress(readN(i, 16)).hostAddress!!
                else -> { reply(o, 8); return }
            }
            val port = (i.read() shl 8) or i.read()
            when (cmd) {
                1 -> connect(c, i, o, host, port)
                3 -> udpAssociate(c, i, o)
                else -> reply(o, 7)
            }
        } catch (_: EOFException) {
        } catch (e: Exception) {
            AppLog.d("SOCKS", "Handler berhenti: ${e.message}")
        } finally {
            runCatching { c.close() }
        }
    }

    private fun connect(c: Socket, i: InputStream, o: OutputStream, host: String, port: Int) {
        val conn = try {
            up.openTcp(host, port)
        } catch (e: Exception) {
            AppLog.w("SOCKS", "Gagal membuka $host:$port (${e.message})")
            reply(o, 5)
            return
        }
        try {
            reply(o, 0)
            val t = Thread {
                try { conn.input.copyTo(o, 16384) } catch (_: Exception) {}
                runCatching { c.close() }
            }
            t.isDaemon = true
            t.start()
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    conn.output.write(buf, 0, n)
                    conn.output.flush()
                }
            } catch (_: Exception) {}
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun udpAssociate(c: Socket, i: InputStream, o: OutputStream) {
        val relay = up.openUdp()
        if (relay == null) { reply(o, 7); return }
        val ds = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        var client: InetSocketAddress? = null
        relay.onReply = { host, port, data ->
            val cl = client
            if (cl != null) {
                val pkt = buildUdpHeader(host, port) + data
                runCatching { ds.send(DatagramPacket(pkt, pkt.size, cl)) }
            }
        }
        val rep = ByteArray(10)
        rep[0] = 5; rep[3] = 1
        rep[4] = 127; rep[7] = 1
        rep[8] = (ds.localPort shr 8).toByte(); rep[9] = ds.localPort.toByte()
        o.write(rep); o.flush()

        val t = Thread {
            val buf = ByteArray(65535)
            while (!ds.isClosed) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    ds.receive(dp)
                    client = InetSocketAddress(dp.address, dp.port)
                    parseUdp(buf, dp.length)?.let { (h, p, d) -> relay.send(h, p, d) }
                } catch (_: Exception) { break }
            }
        }
        t.isDaemon = true
        t.start()
        // Asosiasi hidup selama koneksi kontrol TCP terbuka
        try { while (i.read() >= 0) { /* tunggu */ } } catch (_: Exception) {}
        runCatching { ds.close() }
        runCatching { relay.close() }
    }

    private fun parseUdp(b: ByteArray, len: Int): Triple<String, Int, ByteArray>? {
        if (len < 10 || b[2].toInt() != 0) return null
        var p = 4
        val host = when (b[3].toInt()) {
            1 -> { val a = b.copyOfRange(p, p + 4); p += 4; InetAddress.getByAddress(a).hostAddress!! }
            3 -> { val l = b[p].toInt() and 0xff; p++; val s = String(b, p, l); p += l; s }
            4 -> { val a = b.copyOfRange(p, p + 16); p += 16; InetAddress.getByAddress(a).hostAddress!! }
            else -> return null
        }
        val port = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
        p += 2
        return Triple(host, port, b.copyOfRange(p, len))
    }

    private fun buildUdpHeader(host: String, port: Int): ByteArray {
        val addr: ByteArray
        val atyp: Int
        val ip = runCatching { if (isIpLiteral(host)) InetAddress.getByName(host).address else null }.getOrNull()
        if (ip != null) { addr = ip; atyp = if (ip.size == 4) 1 else 4 }
        else { val hb = host.toByteArray(); addr = byteArrayOf(hb.size.toByte()) + hb; atyp = 3 }
        return byteArrayOf(0, 0, 0, atyp.toByte()) + addr +
            byteArrayOf((port shr 8).toByte(), port.toByte())
    }

    private fun isIpLiteral(h: String) =
        h.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)

    private fun reply(o: OutputStream, code: Int) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var r = 0
        while (r < n) {
            val x = i.read(b, r, n - r)
            if (x < 0) throw EOFException()
            r += x
        }
        return b
    }

    private fun skip(i: InputStream, n: Int) { if (n > 0) readN(i, n) }
}
