package com.aurora.tunnel.engine

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * OpenVPN memakai binary OpenVPN (libovpnexec.so dari proyek ics-openvpn) yang dijalankan sebagai proses.
 * Aplikasi bicara dengannya lewat management interface (unix socket): memberi username/password,
 * melindungi socket, dan membuat TUN lewat VpnService lalu mengirim fd-nya ke OpenVPN.
 * Untuk OpenVPN TCP, payload/proxy/SNI dipasang lewat forwarder lokal.
 */
class OvpnEngine : Engine {
    @Volatile private var proc: Process? = null
    @Volatile private var mgmt: LocalSocket? = null
    private var bound: LocalSocket? = null
    private var server: LocalServerSocket? = null
    private var fwd: OvpnForwarder? = null
    @Volatile private var stopping = false
    @Volatile var rx = 0L
    @Volatile var tx = 0L
    private val result = LinkedBlockingQueue<String>()
    private var tun = TunConfig()
    private val fdList = java.util.ArrayDeque<FileDescriptor>()
    private var user = ""
    private var pass = ""

    override suspend fun start(p: Profile, ctx: Context, port: Int) {
        withContext(Dispatchers.IO) { startBlocking(p, ctx) }
    }

    private fun startBlocking(p: Profile, ctx: Context) {
        stopping = false
        result.clear(); rx = 0; tx = 0
        tun = TunConfig(); fdList.clear()
        user = p.username; pass = p.password
        val host = OvpnBridge.host ?: throw IllegalStateException("Layanan VPN belum siap")

        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libovpnexec.so")
        if (!exe.exists()) throw IllegalStateException(
            "Binary OpenVPN (libovpnexec.so) tidak ada di APK. Pakai scripts/fetch-deps.sh & workflow yang terbaru."
        )
        val cfg = p.ovpnConfig
        if (cfg.isBlank()) throw IllegalStateException("Config .ovpn belum diisi")
        val ep = Ovpn.endpoint(cfg) ?: throw IllegalStateException("Config tidak punya baris 'remote host port'")
        val proto = Ovpn.proto(cfg)
        AppLog.i("OVPN", "Server ${ep.first}:${ep.second} (${proto.uppercase()})")

        // ---- Forwarder lokal untuk payload / proxy / SNI (hanya TCP) ----
        var fport: Int? = null
        val wrap = p.enablePayload || p.enableSni || p.proxyHost.isNotBlank()
        if (wrap) {
            if (proto != "tcp") {
                AppLog.w("OVPN", "Payload/Proxy/SNI hanya untuk OpenVPN TCP. Config ini UDP, pengaturan tersebut diabaikan.")
            } else {
                val ip = InetAddress.getByName(ep.first).hostAddress ?: ep.first
                val proxyIp = if (p.proxyHost.isNotBlank()) (InetAddress.getByName(p.proxyHost).hostAddress ?: p.proxyHost) else ""
                val dp = p.copy(host = ep.first, port = ep.second, proxyHost = proxyIp)
                val f = OvpnForwarder(SshTransport(dp, ep.first, ep.second, connectIp = ip), ep.first, ep.second)
                f.start()
                fwd = f
                fport = f.localPort
                val layers = buildList {
                    if (proxyIp.isNotBlank()) add("proxy")
                    if (p.enableSni) add("TLS/SNI ${p.sni}")
                    if (p.enablePayload) add("payload")
                }.joinToString(" + ")
                AppLog.i("OVPN", "Transport: $layers lewat forwarder 127.0.0.1:$fport")
            }
        }

        // ---- Management socket ----
        val sockPath = File(ctx.cacheDir, "mgmtsocket")
        runCatching { sockPath.delete() }
        val ls = LocalSocket()
        ls.bind(LocalSocketAddress(sockPath.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        bound = ls
        server = LocalServerSocket(ls.fileDescriptor)

        // ---- Proses OpenVPN ----
        val text = Ovpn.build(cfg, fport, p.username.isNotBlank(), sockPath.absolutePath)
        val pb = ProcessBuilder(exe.absolutePath, "--config", "stdin")
        val env = pb.environment()
        val nat = ctx.applicationInfo.nativeLibraryDir
        env["LD_LIBRARY_PATH"] = nat + (env["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        pb.redirectErrorStream(true)
        val pr = pb.start()
        proc = pr
        pr.outputStream.use { it.write(text.toByteArray()); it.flush() }

        Thread({ readOutput(pr) }, "ovpn-out").apply { isDaemon = true }.start()
        Thread({ manage(host) }, "ovpn-mgmt").apply { isDaemon = true }.start()

        val r = result.poll(90, TimeUnit.SECONDS)
        when {
            r == null -> { stop(); throw IllegalStateException("OpenVPN: batas waktu menyambung (90 detik)") }
            r.startsWith("ERR:") -> { val m = r.removePrefix("ERR:"); stop(); throw IllegalStateException(m) }
        }
        AppLog.s("OVPN", "OpenVPN tersambung")
    }

    override fun stop() {
        stopping = true
        runCatching { mgmt?.outputStream?.apply { write("signal SIGINT\n".toByteArray()); flush() } }
        runCatching { proc?.destroy() }
        val pr = proc
        if (pr != null) {
            runCatching {
                var n = 0
                while (n < 20 && runCatching { pr.exitValue() }.isFailure) { Thread.sleep(100); n++ }
            }
        }
        proc = null
        runCatching { mgmt?.close() }; mgmt = null
        runCatching { server?.close() }; server = null
        runCatching { bound?.close() }; bound = null
        runCatching { fwd?.stop() }; fwd = null
        runCatching { OvpnBridge.host?.closeOvpnTun() }
    }

    override fun isAlive(): Boolean = proc?.isAlive == true

    // ------------------------------------------------------------------------------------------

    private fun readOutput(pr: Process) {
        val re = Regex("^(\\d+)\\.(\\d+) ([0-9a-fA-F]+) (.*)$")
        try {
            pr.inputStream.bufferedReader().forEachLine { line ->
                val m = re.find(line)
                if (m == null) {
                    if (line.isNotBlank()) AppLog.w("OVPN", line.trim())
                } else {
                    val flags = m.groupValues[3].toLongOrNull(16) ?: 0
                    val msg = m.groupValues[4].trim()
                    when {
                        flags and 0x10L != 0L -> AppLog.e("OVPN", msg)
                        flags and 0x60L != 0L -> AppLog.w("OVPN", msg)
                        flags and 0x80L != 0L -> AppLog.d("OVPN", msg)
                        else -> AppLog.i("OVPN", msg)
                    }
                }
            }
        } catch (_: IOException) {
        }
        val code = runCatching { pr.waitFor() }.getOrDefault(-1)
        if (!stopping) {
            AppLog.w("OVPN", "Proses OpenVPN berhenti (kode $code)")
            result.offer("ERR:Proses OpenVPN berhenti (kode $code). Lihat log OVPN untuk sebabnya.")
        }
        runCatching { server?.close() }
    }

    private fun send(s: String) {
        runCatching { mgmt?.outputStream?.apply { write(s.toByteArray()); flush() } }
    }

    private fun manage(host: TunHost) {
        try {
            val sock = server?.accept() ?: return
            mgmt = sock
            runCatching { server?.close() }
            send("version 3\n")
            val buf = ByteArray(2048)
            var pending = ""
            val ins = sock.inputStream
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                runCatching { sock.ancillaryFileDescriptors }.getOrNull()?.forEach { fdList.addLast(it) }
                pending += String(buf, 0, n, Charsets.UTF_8)
                while (true) {
                    val i = pending.indexOf('\n')
                    if (i < 0) break
                    val line = pending.substring(0, i).trimEnd('\r')
                    pending = pending.substring(i + 1)
                    handle(line, sock, host)
                }
            }
        } catch (e: IOException) {
            if (!stopping) AppLog.d("OVPN", "Management terputus: ${e.message}")
        }
    }

    private fun handle(line: String, sock: LocalSocket, host: TunHost) {
        if (line.startsWith(">") && line.contains(":")) {
            val cmd = line.substring(1, line.indexOf(':'))
            val arg = line.substring(line.indexOf(':') + 1)
            when (cmd) {
                "HOLD" -> { send("hold release\n"); send("bytecount 2\n"); send("state on\n") }
                "NEED-OK" -> need(arg, sock, host)
                "PASSWORD" -> password(arg)
                "STATE" -> state(arg)
                "BYTECOUNT" -> {
                    val c = arg.indexOf(',')
                    if (c > 0) { rx = arg.substring(0, c).toLongOrNull() ?: rx; tx = arg.substring(c + 1).toLongOrNull() ?: tx }
                }
                "PROXY" -> send("proxy NONE\n")
                "FATAL" -> { AppLog.e("OVPN", arg); result.offer("ERR:$arg") }
                else -> {}
            }
        } else if (line.startsWith("PROTECTFD: ")) {
            fdList.pollFirst()?.let { protect(it, host) }
        }
    }

    private fun protect(fd: FileDescriptor, host: TunHost) {
        try {
            val pfd = ParcelFileDescriptor.dup(fd)
            if (!host.protectFd(pfd.fd)) AppLog.w("OVPN", "Gagal melindungi socket dari VPN")
            pfd.close()
            Os.close(fd)
        } catch (e: Exception) {
            AppLog.w("OVPN", "protect fd: ${e.message}")
        }
    }

    private fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun password(arg: String) {
        if (arg.startsWith("Auth-Token:")) return
        val p1 = arg.indexOf('\''); val p2 = arg.indexOf('\'', p1 + 1)
        if (p1 < 0 || p2 < 0) return
        val needed = arg.substring(p1 + 1, p2)
        if (arg.startsWith("Verification Failed")) {
            AppLog.e("OVPN", "Autentikasi gagal ($needed). Periksa username dan password.")
            result.offer("ERR:Autentikasi OpenVPN gagal. Periksa username dan password.")
            return
        }
        when (needed) {
            "Auth" -> {
                if (user.isBlank()) {
                    result.offer("ERR:Server meminta username/password, isi akun di profil OpenVPN.")
                } else {
                    send("username 'Auth' ${esc(user)}\n")
                    send("password 'Auth' ${esc(pass)}\n")
                }
            }
            else -> result.offer("ERR:OpenVPN meminta '$needed' (password private key belum didukung)")
        }
    }

    private fun state(arg: String) {
        val a = arg.split(",", limit = 3)
        if (a.size < 2) return
        val st = a[1]
        val rest = a.getOrElse(2) { "" }
        val label = when (st) {
            "CONNECTING" -> "Menghubungkan ke server"
            "TCP_CONNECT" -> "Membuka koneksi TCP"
            "WAIT" -> "Menunggu respons server"
            "AUTH" -> "Autentikasi TLS"
            "GET_CONFIG" -> "Mengambil konfigurasi dari server"
            "ASSIGN_IP" -> "Menetapkan IP virtual"
            "ADD_ROUTES" -> "Memasang rute"
            "RESOLVE" -> "Resolve nama server"
            "RECONNECTING" -> "Menyambung ulang"
            "EXITING" -> "Keluar"
            else -> st
        }
        if (st != "CONNECTED") AppLog.i("OVPN", "$label…")
        when (st) {
            "CONNECTED" -> result.offer("OK")
            "EXITING" -> if (!stopping) result.offer("ERR:OpenVPN keluar: ${rest.trim(',')}")
            "RECONNECTING" -> if (rest.contains("auth-failure")) result.offer("ERR:Autentikasi OpenVPN gagal")
        }
    }

    // ---- NEED-OK ----

    private fun need(arg: String, sock: LocalSocket, host: TunHost) {
        val p1 = arg.indexOf('\''); val p2 = arg.indexOf('\'', p1 + 1)
        if (p1 < 0 || p2 < 0) return
        val needed = arg.substring(p1 + 1, p2)
        val extra = arg.split(":", limit = 2).getOrElse(1) { "" }
        var status = "ok"
        when (needed) {
            "PROTECTFD" -> fdList.pollFirst()?.let { protect(it, host) }
            "DNSSERVER", "DNS6SERVER" -> tun.dns += extra
            "DNSDOMAIN" -> tun.domains += extra
            "ROUTE" -> route(extra)
            "ROUTE6" -> route6(extra)
            "IFCONFIG" -> ifconfig(extra)
            "IFCONFIG6" -> {
                val a = extra.split(" ")
                a.getOrNull(1)?.toIntOrNull()?.let { tun.mtu = it }
                tun.ip6 = a.getOrNull(0)
            }
            "PERSIST_TUN_ACTION" -> status = "OPEN_BEFORE_CLOSE"
            "HTTPPROXY" -> {}
            "OPENTUN" -> {
                if (extra.trim() != "tun") { send("needok 'OPENTUN' cancel\n"); return }
                val pfd = host.openOvpnTun(tun)
                if (pfd == null) {
                    AppLog.e("OVPN", "Gagal membuat interface VPN (izin VPN diberikan?)")
                    send("needok 'OPENTUN' cancel\n")
                } else {
                    try {
                        sock.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                        send("needok 'OPENTUN' ok\n")
                        sock.setFileDescriptorsForSend(null)
                        AppLog.i("OVPN", "TUN dibuka: ${tun.ip}/${tun.prefix}, MTU ${tun.mtu}, DNS ${tun.dns.joinToString(",").ifEmpty { "-" }}")
                        AppLog.i("OVPN", "Rute (${tun.routes.size}): " + tun.routes.joinToString(" ") { "${it.first}/${it.second}" })
                    } catch (e: IOException) {
                        AppLog.e("OVPN", "Gagal mengirim fd TUN: ${e.message}")
                    }
                }
                return
            }
            else -> return
        }
        send("needok '$needed' $status\n")
    }

    private fun ipInt(s: String): Long {
        val p = s.trim().split(".")
        if (p.size != 4) return 0
        var v = 0L
        for (x in p) v = (v shl 8) or ((x.toLongOrNull() ?: 0) and 0xFF)
        return v
    }

    private fun maskLen(mask: String): Int {
        var m = ipInt(mask) + (1L shl 32)
        var zeros = 0
        while (m and 1L == 0L) { zeros++; m = m shr 1 }
        return 32 - zeros
    }

    private fun netOf(ip: String, len: Int): String {
        val m = if (len == 0) 0L else (0xFFFFFFFFL shl (32 - len)) and 0xFFFFFFFFL
        val n = ipInt(ip) and m
        return "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}"
    }

    private fun ifconfig(extra: String) {
        val a = extra.split(" ")
        if (a.size < 4) return
        val local = a[0]; val nm = a[1]; val mode = a[3]
        tun.ip = local
        tun.mtu = a[2].toIntOrNull() ?: 1500
        var len = maskLen(nm)
        if (len == 32 && nm != "255.255.255.255") {
            val masklen: Int; val mask: Long
            if (mode == "net30") { masklen = 30; mask = 0xfffffffcL } else { masklen = 31; mask = 0xfffffffeL }
            len = if ((ipInt(nm) and mask) == (ipInt(local) and mask)) masklen else 32
        }
        tun.prefix = len
        tun.remoteGw = nm
        if (len <= 31) tun.routes += netOf(local, len) to len
    }

    private fun route(extra: String) {
        val a = extra.split(" ")
        if (a.size < 3) return
        val dest = a[0]; val mask = a[1]; val gw = a[2]
        val dev = if (a.size >= 5) a[4] else null
        var include = dev != null && (dev.startsWith("tun") || dev == "(null)" || dev == "vpnservice-tun")
        val local = tun.ip ?: return
        val l = tun.prefix
        val lm = if (l == 0) 0L else (0xFFFFFFFFL shl (32 - l)) and 0xFFFFFFFFL
        if ((ipInt(gw) and lm) == (ipInt(local) and lm)) include = true
        if (gw == "255.255.255.255" || gw == tun.remoteGw) include = true
        if (!include) return
        val len = maskLen(mask)
        tun.routes += netOf(dest, len) to len
    }

    private fun route6(extra: String) {
        val a = extra.split(" ")
        val dev = a.getOrNull(1)
        val included = dev == null || dev.startsWith("tun") || dev == "(null)" || dev == "vpnservice-tun"
        if (!included) return
        val cidr = a[0].split("/")
        if (cidr.size == 2) cidr[1].toIntOrNull()?.let { tun.routes6 += cidr[0] to it }
    }
}

/** Forwarder TCP lokal: OpenVPN konek ke sini, lalu diteruskan lewat proxy / TLS-SNI / payload. */
class OvpnForwarder(private val transport: SshTransport, private val host: String, private val port: Int) {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    @Volatile private var closed = false
    val localPort: Int get() = server.localPort

    fun start() {
        Thread({
            while (!closed) {
                val c = try { server.accept() } catch (_: IOException) { break }
                Thread({ handle(c) }, "ovpn-fwd").apply { isDaemon = true }.start()
            }
        }, "ovpn-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        closed = true
        runCatching { server.close() }
    }

    private fun handle(c: Socket) {
        val up: Socket
        try {
            c.tcpNoDelay = true
            up = transport.createSocket(host, port)
        } catch (e: Exception) {
            AppLog.e("OVPN", "Forwarder gagal membuka server: ${e.message}")
            runCatching { c.close() }
            return
        }
        val sent = java.util.concurrent.atomic.AtomicLong()
        val recv = java.util.concurrent.atomic.AtomicLong()
        val t = Thread({
            val r = pipe(c, up, sent)
            if (!closed) AppLog.e("OVPN", "Forwarder: OpenVPN menutup koneksi ($r) ↑${sent.get()}B ↓${recv.get()}B")
        }, "ovpn-up").apply { isDaemon = true }
        t.start()
        val r = pipe(up, c, recv)
        if (!closed) AppLog.e("OVPN", "Forwarder: server menutup koneksi ($r) ↑${sent.get()}B ↓${recv.get()}B")
    }

    private fun pipe(from: Socket, to: Socket, count: java.util.concurrent.atomic.AtomicLong): String {
        val buf = ByteArray(16 * 1024)
        var reason = "EOF"
        try {
            val i = from.getInputStream(); val o = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n); o.flush()
                count.addAndGet(n.toLong())
            }
        } catch (e: IOException) {
            reason = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { from.close() }; runCatching { to.close() }
        }
        return reason
    }
}

object Ovpn {
    /** Ambil 'remote host port' pertama untuk ping. */
    fun endpoint(cfg: String): Pair<String, Int>? {
        val m = Regex("(?m)^\\s*remote\\s+(\\S+)\\s+(\\d+)").find(cfg) ?: return null
        return m.groupValues[1] to m.groupValues[2].toInt()
    }

    /** "tcp" atau "udp". */
    fun proto(cfg: String): String {
        val p = Regex("(?m)^\\s*proto\\s+(\\S+)").find(cfg)?.groupValues?.get(1)
            ?: Regex("(?m)^\\s*remote\\s+\\S+\\s+\\d+\\s+(\\S+)").find(cfg)?.groupValues?.get(1)
            ?: "udp"
        return if (p.lowercase().startsWith("tcp")) "tcp" else "udp"
    }

    fun summary(cfg: String): String {
        val e = endpoint(cfg) ?: return "Belum ada config"
        return "${e.first}:${e.second} • ${proto(cfg).uppercase()}"
    }

    private val dropAlways = setOf(
        "management", "management-client", "management-query-passwords", "management-hold", "management-signal",
        "management-forget-disconnect", "management-log-cache", "management-up-down", "management-client-auth",
        "management-query-proxy", "management-external-key", "management-client-user", "management-client-group",
        "script-security", "up", "down", "route-up", "route-pre-down", "ipchange", "tls-verify", "client-connect",
        "client-disconnect", "learn-address", "auth-user-pass-verify", "plugin", "daemon", "log", "log-append",
        "writepid", "user", "group", "chroot", "persist-tun", "dev-node", "ifconfig-noexec", "route-noexec",
        "up-restart", "up-delay", "verb", "mute", "syslog", "machine-readable-output", "dev", "dev-type"
    )
    private val dropRemote = setOf(
        "remote", "proto", "port", "rport", "http-proxy", "http-proxy-option", "http-proxy-user-pass",
        "http-proxy-retry", "http-proxy-timeout", "socks-proxy", "socks-proxy-retry", "remote-random-hostname"
    )

    /** Susun config akhir: bersihkan opsi yang tidak cocok untuk Android dan tambah management. */
    fun build(cfg: String, forwardPort: Int?, hasCreds: Boolean, mgmtPath: String): String {
        val out = StringBuilder()
        var block: String? = null
        var sawAuth = false
        for (raw in cfg.lineSequence()) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            if (block != null) {
                out.append(line).append('\n')
                if (t.equals("</$block>", true)) block = null
                continue
            }
            if (t.startsWith("<") && t.endsWith(">") && !t.startsWith("</")) {
                block = t.substring(1, t.length - 1)
                out.append(line).append('\n'); continue
            }
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) { out.append(line).append('\n'); continue }
            val key = t.split(Regex("\\s+"))[0].lowercase()
            if (key in dropAlways) continue
            if (forwardPort != null && key in dropRemote) continue
            if (key == "auth-user-pass") { sawAuth = true; out.append("auth-user-pass\n"); continue }
            out.append(line).append('\n')
        }
        if (forwardPort != null) {
            out.append("remote 127.0.0.1 $forwardPort\n")
            out.append("proto tcp-client\n")
        }
        if (!sawAuth && hasCreds) out.append("auth-user-pass\n")
        out.append("dev tun\n")
        out.append("management $mgmtPath unix\n")
        out.append("management-client\n")
        out.append("management-query-passwords\n")
        out.append("management-hold\n")
        out.append("setenv IV_GUI_VER \"de.cocote.tunnel 1.0\"\n")
        out.append("machine-readable-output\n")
        out.append("ifconfig-nowarn\n")
        out.append("verb 3\n")
        out.append("connect-retry 5 10\n")
        out.append("resolv-retry 60\n")
        return out.toString()
    }
}
