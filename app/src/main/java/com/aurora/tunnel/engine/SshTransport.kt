package com.aurora.tunnel.engine

import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.log.AppLog
import com.jcraft.jsch.SocketFactory
import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Membuat socket menuju server SSH dengan lapisan opsional:
 *   [proxy HTTP] -> [TLS dengan SNI kustom] -> [payload HTTP/WebSocket] -> SSH
 */
class SshTransport(
    private val p: Profile,
    private val endpointHost: String = p.host,
    private val endpointPort: Int = p.port
) : SocketFactory {

    private val rotateCounter = AtomicInteger(0)

    override fun createSocket(host: String, port: Int): Socket {
        val useProxy = p.proxyHost.isNotBlank() && p.proxyPort > 0
        val connHost = if (useProxy) p.proxyHost else host
        val connPort = if (useProxy) p.proxyPort else port
        val timeoutMs = p.connectTimeoutSec.coerceIn(5, 120) * 1000

        AppLog.d("NET", "Membuka koneksi TCP ke $connHost:$connPort" + if (useProxy) " (proxy)" else "")
        var sock = Socket()
        sock.tcpNoDelay = true
        sock.keepAlive = true
        Settings.bufferKb.v.takeIf { it > 0 }?.let { kb ->
            runCatching { sock.receiveBufferSize = kb * 1024; sock.sendBufferSize = kb * 1024 }
        }
        try {
            sock.connect(InetSocketAddress(InetAddress.getByName(connHost), connPort), timeoutMs)
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw IOException("Gagal konek ke $connHost:$connPort (${e.message})", e)
        }

        if (p.enableSni && p.sni.isNotBlank()) {
            sock = wrapTls(sock, p.sni.trim(), connPort, timeoutMs)
        }

        when {
            p.enablePayload -> sendPayload(sock, host, port, timeoutMs)
            useProxy -> httpConnect(sock, host, port, timeoutMs)
        }
        return sock
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    // ---- TLS / SNI -------------------------------------------------------------------------

    private fun wrapTls(raw: Socket, sni: String, port: Int, timeoutMs: Int): Socket {
        AppLog.d("NET", "Handshake TLS dengan SNI $sni")
        val ctx = SSLContext.getInstance("TLS")
        if (p.allowInsecure) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        } else {
            ctx.init(null, null, null)
        }
        val ssl = ctx.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(sni))
        ssl.sslParameters = params
        when (Settings.forceTls.v) {
            "1.2" -> ssl.enabledProtocols = arrayOf("TLSv1.2")
            "1.3" -> ssl.enabledProtocols = arrayOf("TLSv1.3")
        }
        ssl.soTimeout = timeoutMs
        try {
            ssl.startHandshake()
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw IOException("Handshake TLS gagal: ${e.message}", e)
        }
        ssl.soTimeout = 0
        AppLog.d("NET", "TLS siap (${ssl.session.protocol})")
        return ssl
    }

    // ---- HTTP CONNECT (proxy tanpa payload kustom) -------------------------------------------

    private fun httpConnect(s: Socket, host: String, port: Int, timeoutMs: Int) {
        AppLog.d("NET", "HTTP CONNECT $host:$port via proxy")
        val req = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n"
        s.getOutputStream().apply { write(req.toByteArray()); flush() }
        readResponse(s, timeoutMs, strict = true)
    }

    // ---- Payload ------------------------------------------------------------------------------

    private fun sendPayload(s: Socket, host: String, port: Int, timeoutMs: Int) {
        val chunks = buildPayload(p.payload, host, port)
        AppLog.d("NET", "Mengirim payload (${chunks.size} bagian)")
        val out = s.getOutputStream()
        chunks.forEachIndexed { idx, c ->
            out.write(c); out.flush()
            if (idx < chunks.lastIndex) Thread.sleep(200)
        }
        if (p.payloadExpectResponse) readResponse(s, timeoutMs, strict = false)
    }

    private fun buildPayload(raw: String, host: String, port: Int): List<ByteArray> {
        var s = raw
        // [rotate=a;b;c] -> pilih bergantian tiap koneksi
        val rot = Regex("\\[rotate=([^\\]]*)]")
        s = rot.replace(s) {
            val opts = it.groupValues[1].split(';').filter { o -> o.isNotEmpty() }
            if (opts.isEmpty()) "" else opts[rotateCounter.getAndIncrement() % opts.size]
        }
        val wsHost = if (p.enableSni && p.sni.isNotBlank()) p.sni else host
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        s = s.replace("\\r", "\r").replace("\\n", "\n")
            .replace("[host_port]", "$host:$port")
            .replace("[host]", host)
            .replace("[port]", port.toString())
            .replace("[ws_host]", wsHost)
            .replace("[sni]", p.sni)
            .replace("[path]", "/")
            .replace("[ws_key]", Base64.encodeToString(key, Base64.NO_WRAP))
            .replace("[crlf]", "\r\n")
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
        return s.split("[split]").filter { it.isNotEmpty() }.map { it.toByteArray(Charsets.ISO_8859_1) }
    }

    /** Baca header respons HTTP bita-per-bita supaya tidak memakan byte SSH sesudahnya. */
    private fun readResponse(s: Socket, timeoutMs: Int, strict: Boolean) {
        val inp = s.getInputStream()
        val old = s.soTimeout
        s.soTimeout = minOf(timeoutMs, 15000)
        val sb = StringBuilder()
        try {
            while (sb.length < 8192) {
                val b = inp.read()
                if (b < 0) throw IOException("Koneksi ditutup saat menunggu respons payload")
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n")) break
            }
        } catch (e: SocketTimeoutException) {
            AppLog.w("NET", "Tidak ada respons payload dalam batas waktu, lanjut ke SSH")
            s.soTimeout = old
            return
        }
        s.soTimeout = old
        val first = sb.lineSequence().firstOrNull()?.trim().orEmpty()
        AppLog.d("NET", "Respons: $first")
        val code = Regex("HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(first)?.groupValues?.get(1)?.toIntOrNull()
        if (code != null && code >= 400) throw IOException("Server menolak: $first")
        if (strict && code != null && code != 200) throw IOException("Proxy menolak CONNECT: $first")
    }
}
