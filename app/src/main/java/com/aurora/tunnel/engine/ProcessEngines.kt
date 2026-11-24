package com.aurora.tunnel.engine

import android.content.Context
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Menjalankan binary native (dari nativeLibraryDir) dan mengalirkan outputnya ke AppLog. */
class ProcessRunner(private val tag: String) {
    private var proc: Process? = null

    fun start(ctx: Context, lib: String, args: List<String>, env: Map<String, String> = emptyMap()) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, lib)
        if (!bin.exists()) throw IllegalStateException(
            "Binary $lib tidak ada di APK. Pastikan scripts/fetch-deps.sh berjalan sebelum build.")
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            .directory(ctx.filesDir)
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        val pr = pb.start()
        proc = pr
        Thread({
            try {
                pr.inputStream.bufferedReader().forEachLine { AppLog.ingest(tag, it) }
            } catch (_: Exception) {}
            AppLog.w(tag, "Proses berhenti")
        }, "$tag-log").apply { isDaemon = true; start() }
    }

    fun isAlive(): Boolean {
        val p = proc ?: return false
        return try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }

    fun stop() {
        val p = proc ?: return
        p.destroy()
        Thread.sleep(200)
        if (isAlive()) p.destroyForcibly()
        proc = null
    }
}

suspend fun waitPort(port: Int, timeoutMs: Long, alive: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (!alive()) return false
        val ok = withContext(Dispatchers.IO) {
            runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; true }.getOrDefault(false)
        }
        if (ok) return true
        delay(150)
    }
    return false
}

class XrayEngine : Engine {
    private val runner = ProcessRunner("XRAY")

    override suspend fun start(p: Profile, ctx: Context, port: Int) = withContext(Dispatchers.IO) {
        if (p.xrayLink.isBlank() && p.x.address.isBlank()) error("Akun Xray belum diisi")
        val cfg = try { XrayConfig.build(p, port) } catch (e: Exception) {
            throw IllegalStateException("Link Xray tidak valid: ${e.message}", e)
        }
        val file = File(ctx.filesDir, "xray-$port.json").apply { writeText(cfg) }
        if (!XrayConfig.isJson(p.xrayLink)) {
            val n = XrayConfig.nodeOf(p)
            AppLog.i("XRAY", "${n.protocol.uppercase()} ${Rotate.first(n.address)}:${n.port} · ${n.network}/${n.tls}" +
                (if (p.xrayFragment) " · fragment" else "") + (if (p.xrayMux) " · mux" else ""))
        } else AppLog.i("XRAY", "Memakai config JSON kustom")
        runner.start(ctx, "libxray.so", listOf("run", "-c", file.absolutePath),
            mapOf("XRAY_LOCATION_ASSET" to ctx.filesDir.absolutePath))
        if (!waitPort(port, 8000) { runner.isAlive() }) {
            runner.stop()
            throw IllegalStateException("Xray tidak siap (cek log di atas)")
        }
        AppLog.s("XRAY", "Xray siap")
    }

    override fun stop() { runner.stop() }
    override fun isAlive() = runner.isAlive()
}

/** SlowDNS = dnstt-client (DNS tunnel) + SSH di dalamnya. */
class SlowDnsEngine : Engine {
    private val runner = ProcessRunner("DNSTT")
    private val ssh = SshEngine()

    override suspend fun start(p: Profile, ctx: Context, port: Int) {
        if (p.dnsNs.isBlank() || p.dnsPubkey.isBlank()) error("NS domain dan public key SlowDNS wajib diisi")
        val local = freeLocalPort()
        val resolver = p.dnsServer.trim().ifEmpty { "8.8.8.8" }
        val args = mutableListOf<String>()
        when {
            resolver.startsWith("doh://") -> { args += "-doh"; args += "https://" + resolver.removePrefix("doh://") }
            resolver.startsWith("dot://") -> { args += "-dot"; args += resolver.removePrefix("dot://") }
            else -> { args += "-udp"; args += if (resolver.contains(':')) resolver else "$resolver:53" }
        }
        args += listOf("-pubkey", p.dnsPubkey.trim(), p.dnsNs.trim(), "127.0.0.1:$local")
        AppLog.i("DNSTT", "Resolver $resolver · NS ${p.dnsNs.trim()}")
        withContext(Dispatchers.IO) { runner.start(ctx, "libdnstt.so", args) }
        if (!waitPort(local, 5000) { runner.isAlive() }) {
            runner.stop()
            throw IllegalStateException("dnstt-client gagal start (cek log)")
        }
        AppLog.d("DNSTT", "Listener dnstt di 127.0.0.1:$local")
        val inner = p.copy(
            host = "127.0.0.1", port = local,
            enableSni = false, enablePayload = false, proxyHost = "", proxyPort = 0,
            connectTimeoutSec = maxOf(p.connectTimeoutSec, 40)
        )
        try { ssh.start(inner, ctx, port) } catch (e: Exception) { runner.stop(); throw e }
    }

    override fun stop() { ssh.stop(); runner.stop() }
    override fun isAlive() = runner.isAlive() && ssh.isAlive()
}

fun freeLocalPort(): Int = java.net.ServerSocket(0).use { it.localPort }
