package com.aurora.tunnel.engine

import android.content.Context
import com.aurora.tunnel.data.Profile
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
        AppLog.i("SSH", "Menghubungkan ke ${p.host}:${p.port} sebagai ${p.username}")

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
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(4)
        s.setSocketFactory(SshTransport(p))

        try {
            s.connect(p.connectTimeoutSec.coerceIn(5, 120) * 1000)
        } catch (e: Exception) {
            runCatching { s.disconnect() }
            throw IllegalStateException("SSH gagal: ${e.message}", e)
        }
        AppLog.i("SSH", "Login berhasil, sesi SSH aktif")
        session = s

        val useUdpgw = forceUdpgw || p.enableUdpgw
        val up = SshUpstream(s, useUdpgw, p.udpgwHost, p.udpgwPort)
        val srv = Socks5Server(port, up)
        srv.start()
        server = srv
        AppLog.i("SSH", "SOCKS5 lokal siap di 127.0.0.1:$port" + if (useUdpgw) " · UDP via udpgw ${p.udpgwHost}:${p.udpgwPort}" else " · UDP: hanya DNS")
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
        AppLog.i("SSH", "Sesi SSH ditutup")
    }

    override fun isAlive() = session?.isConnected == true
}

private class SshUpstream(
    private val s: Session,
    private val udpgw: Boolean,
    private val gwHost: String,
    private val gwPort: Int
) : Upstream {

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

    override fun openUdp(): UdpRelay? =
        if (udpgw) {
            try { UdpGwRelay(openTcp(gwHost, gwPort)) }
            catch (e: Exception) {
                AppLog.w("UDPGW", "udpgw $gwHost:$gwPort tidak bisa dibuka (${e.message}), pakai DNS-over-TCP")
                DnsTcpRelay(this)
            }
        } else DnsTcpRelay(this)
}
