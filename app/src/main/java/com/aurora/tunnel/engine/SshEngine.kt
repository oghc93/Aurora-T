package com.aurora.tunnel.engine

import android.content.Context
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.log.AppLog
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SshEngine(private val forceUdpgw: Boolean = false) : Engine {
    private var session: Session? = null
    private var server: Socks5Server? = null

    override suspend fun start(p: Profile, ctx: Context, port: Int) = withContext(Dispatchers.IO) {
        if (p.host.isBlank() || p.username.isBlank()) error("Host dan username SSH wajib diisi")
        AppLog.i("SSH", "Menghubungkan ke ${p.host}:${p.port}, batas waktu ${p.connectTimeoutSec} detik")

        val jsch = JSch()
        val s = jsch.getSession(p.username, p.host, p.port)
        s.setPassword(p.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // Banyak server SSH komunitas memakai Dropbear/OpenSSH lama: izinkan algoritma legacy
        legacy(s, "kex", "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1")
        legacy(s, "server_host_key", "ssh-rsa,ssh-dss")
        legacy(s, "cipher.s2c", "aes128-cbc,aes256-cbc,3des-cbc")
        legacy(s, "cipher.c2s", "aes128-cbc,aes256-cbc,3des-cbc")
        legacy(s, "mac.s2c", "hmac-sha1,hmac-md5")
        legacy(s, "mac.c2s", "hmac-sha1,hmac-md5")
        val ka = Settings.keepAliveSec.v
        if (ka > 0) { s.setServerAliveInterval(ka * 1000); s.setServerAliveCountMax(4) }
        if (Settings.compression.v) {
            s.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            s.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            s.setConfig("compression_level", "6")
        }
        s.userInfo = object : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = p.password
            override fun promptPassword(m: String?) = true
            override fun promptPassphrase(m: String?) = false
            override fun promptYesNo(m: String?) = true
            override fun showMessage(m: String?) { if (!m.isNullOrBlank()) AppLog.banner(m) }
            override fun promptKeyboardInteractive(d: String?, n: String?, i: String?, pr: Array<out String>?, e: BooleanArray?): Array<String> =
                Array(pr?.size ?: 1) { p.password }
        }
        s.setSocketFactory(SshTransport(p))

        try {
            s.connect(p.connectTimeoutSec.coerceIn(5, 120) * 1000)
        } catch (e: Exception) {
            runCatching { s.disconnect() }
            throw IllegalStateException("SSH gagal: ${e.message}", e)
        }
        AppLog.s("SSH", "Berhasil terhubung")
        session = s

        val badvpn = Settings.tunnelEngine.v == "badvpn"
        val useUdpgw = Settings.udpEnabled.v && (forceUdpgw || p.enableUdpgw || badvpn)
        val auto = !badvpn
        val ports = if (auto) listOf(7300, 7200, 7100, 7400, 7500) else listOf(p.udpgwPort)
        val up = SshUpstream(s, useUdpgw, p.udpgwHost, ports)
        val srv = Socks5Server(port, up)
        srv.start()
        server = srv
        AppLog.d("SSH", "SOCKS5 lokal 127.0.0.1:$port" + if (useUdpgw) " · BadVPN UDPGW ${p.udpgwHost}:${ports.joinToString("/")}" else " · UDP hanya DNS")
    }

    private fun legacy(s: Session, key: String, extra: String) {
        runCatching {
            val cur = JSch.getConfig(key) ?: ""
            val merged = (cur.split(',') + extra.split(',')).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            s.setConfig(key, merged.joinToString(","))
        }
    }

    override fun stop() {
        runCatching { server?.stop() }
        runCatching { session?.disconnect() }
        server = null; session = null
        AppLog.i("SSH", "Sesi ditutup")
    }

    override fun isAlive() = session?.isConnected == true
}

private class SshUpstream(
    private val s: Session,
    private val udpgw: Boolean,
    private val gwHost: String,
    private val gwPorts: List<Int>
) : Upstream {
    @Volatile private var detected: Int? = null
    @Volatile private var warned = false

    override fun openTcp(host: String, port: Int): TcpConn {
        val ch = s.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
        ch.setHost(host)
        ch.setPort(port)
        val inp = ch.getInputStream()
        val out = ch.getOutputStream()
        ch.connect(15_000)
        return object : TcpConn {
            override val input = inp
            override val output = out
            override fun close() { runCatching { ch.disconnect() } }
        }
    }

    override fun openUdp(): UdpRelay? {
        if (!udpgw) return DnsTcpRelay(this)
        val order = detected?.let { d -> listOf(d) + gwPorts.filter { it != d } } ?: gwPorts
        var last: Exception? = null
        for (pt in order) {
            try {
                val r = UdpGwRelay(openTcp(gwHost, pt))
                if (detected != pt) { detected = pt; AppLog.i("UDPGW", "BadVPN UDPGW aktif di $gwHost:$pt") }
                return r
            } catch (e: Exception) { last = e }
        }
        if (!warned) {
            warned = true
            AppLog.w("UDPGW", "BadVPN UDPGW tidak ditemukan di port ${gwPorts.joinToString("/")} (${last?.message}), UDP hanya DNS")
        }
        return DnsTcpRelay(this)
    }
}
