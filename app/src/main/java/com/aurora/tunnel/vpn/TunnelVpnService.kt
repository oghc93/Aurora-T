package com.aurora.tunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import com.aurora.tunnel.core.Status
import com.aurora.tunnel.core.Tunnel
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Engine
import com.aurora.tunnel.engine.EngineFactory
import com.aurora.tunnel.engine.LbEngine
import com.aurora.tunnel.engine.Ping
import com.aurora.tunnel.engine.freeLocalPort
import com.aurora.tunnel.log.AppLog
import com.aurora.tunnel.ui.MainActivity
import hev.htproxy.TProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

object TunnelCtl {
    fun start(ctx: Context, profileId: String) {
        val i = Intent(ctx, TunnelVpnService::class.java)
            .setAction(TunnelVpnService.ACTION_START)
            .putExtra(TunnelVpnService.EXTRA_PROFILE, profileId)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_STOP))
    }
}

class TunnelVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.aurora.tunnel.START"
        const val ACTION_STOP = "com.aurora.tunnel.STOP"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL = "cocote_vpn"
        private const val NOTIF_ID = 7
        private const val TUN_ADDR = "198.18.0.1"
        private const val DNS_ADDR = "198.18.0.2"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var engine: Engine? = null
    private var tun: ParcelFileDescriptor? = null
    private var tunRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_PROFILE)
                if (!Tunnel.isActive) beginConnect(id)
            }
            ACTION_STOP -> shutdown("Diputus oleh pengguna")
        }
        return START_NOT_STICKY
    }

    private fun buildEngine(sel: Profile): Engine {
        if (Settings.loadBalance.v) {
            val nodes = ProfileStore.profiles.value.filter { it.lb }
            if (nodes.size >= 2) return LbEngine(nodes)
            AppLog.w("LB", "Load Balance aktif tapi node bertanda LB kurang dari 2, memakai profil terpilih")
        }
        return EngineFactory.create(sel.mode)
    }

    private fun beginConnect(profileId: String?) {
        startForeground(NOTIF_ID, notification("Menghubungkan…"))
        Tunnel.resetStats()
        Tunnel.error.value = null
        Tunnel.status.value = Status.CONNECTING
        tileRefresh()
        job = scope.launch {
            try {
                val p = ProfileStore.get(profileId) ?: error("Profil tidak ditemukan")
                Tunnel.activeProfileId.value = p.id
                AppLog.quiet = false
                AppLog.i("APP", "Berjalan di ${Build.MANUFACTURER.uppercase()} (${Build.MODEL})")
                AppLog.i("APP", "Android ${Build.VERSION.RELEASE} · API-${Build.VERSION.SDK_INT} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})")
                AppLog.i("APP", "Memulai ${if (Settings.loadBalance.v) "Load Balance" else p.mode.label}…")

                val vpn = Settings.vpnMode.v
                val port = if (vpn) freeLocalPort() else Settings.proxyPort.v
                val eng = buildEngine(p)
                engine = eng
                eng.start(p, applicationContext, port)

                if (vpn) {
                    openTun(port)
                    if (Settings.dns.v.isBlank()) AppLog.i("APP", "DNS lewat terowongan aktif")
                    else AppLog.i("APP", "DNS: ${Settings.dns.v}")
                    AppLog.i("APP", "Mesin SOCKS aktif: HevSocks5Tunnel")
                } else {
                    AppLog.i("APP", "Mode proxy: SOCKS5 di 127.0.0.1:$port")
                }
                if (eng is LbEngine || p.enableUdpgw || p.mode == Mode.UDPCUSTOM || p.mode == Mode.XRAY) {
                    AppLog.i("APP", "UDP bridge aktif")
                }
                Tunnel.connectedAt.value = System.currentTimeMillis()
                Tunnel.status.value = Status.CONNECTED
                tileRefresh()
                if (!Settings.powerSaving.v) updateNotification("Terhubung · ${p.name}")
                AppLog.s("APP", "Selamat berselancar")
                AppLog.quiet = true
                launch {
                    if (Ping.udpDiag(port)) AppLog.s("APP", "Diagnostik UDP OK")
                    else AppLog.w("APP", "Diagnostik UDP gagal (UDP mungkin tidak diteruskan server)")
                }
                monitor(p.id, port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("APP", e.message ?: e.javaClass.simpleName)
                Tunnel.error.value = e.message ?: e.javaClass.simpleName
                failAndStop()
            }
        }
    }

    private suspend fun monitor(profileId: String, port: Int) {
        var lastRx = 0L; var lastTx = 0L
        var tick = 0
        var retries = 0
        var pingFail = 0
        while (scope.isActive) {
            delay(1000)
            tick++
            if (tunRunning && !Settings.powerSaving.v) {
                val st = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                if (st != null && st.size >= 4) {
                    Tunnel.txBytes.value = st[1]; Tunnel.rxBytes.value = st[3]
                    Tunnel.txRate.value = (st[1] - lastTx).coerceAtLeast(0)
                    Tunnel.rxRate.value = (st[3] - lastRx).coerceAtLeast(0)
                    lastTx = st[1]; lastRx = st[3]
                }
            }

            var lost = false
            if (tick % 3 == 0 && engine?.isAlive() == false) {
                AppLog.w("APP", "Koneksi engine terputus")
                lost = true
            }
            val every = Settings.autoPingSec.v.coerceAtLeast(5)
            if (!lost && Settings.autoPing.v && tick % every == 0) {
                val ms = Ping.viaSocks(port, Settings.autoPingHost.v.ifBlank { "www.google.com" })
                if (ms >= 0) {
                    pingFail = 0
                    if (ms <= 150) AppLog.s("PING", "Auto Ping: $ms ms") else AppLog.w("PING", "Auto Ping: $ms ms")
                } else {
                    pingFail++
                    val limit = Settings.pingTimeouts.v.coerceAtLeast(1)
                    AppLog.w("PING", "Auto Ping: timeout ($pingFail/$limit)")
                    if (pingFail >= limit) { AppLog.w("APP", "Ping timeout $limit kali berturut-turut"); lost = true }
                }
            }

            if (lost) {
                if (Settings.autoReconnect.v && retries < 5) {
                    retries++
                    pingFail = 0
                    Tunnel.status.value = Status.CONNECTING
                    if (!Settings.powerSaving.v) updateNotification("Menyambung ulang ($retries/5)…")
                    AppLog.quiet = false
                    AppLog.i("APP", "Menyambung ulang ($retries/5)…")
                    engine?.stop()
                    delay(Settings.reconnectDelaySec.v.coerceAtLeast(1) * 1000L * retries)
                    try {
                        val p = ProfileStore.get(profileId) ?: error("Profil hilang")
                        engine?.start(p, applicationContext, port)
                        Tunnel.status.value = Status.CONNECTED
                        if (!Settings.powerSaving.v) updateNotification("Terhubung · ${p.name}")
                        AppLog.s("APP", "Tersambung kembali")
                        AppLog.quiet = true
                        retries = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLog.e("APP", "Gagal sambung ulang: ${e.message}")
                    }
                } else {
                    Tunnel.error.value = "Koneksi terputus"
                    failAndStop()
                    return
                }
            }
        }
    }

    private fun openTun(socksPort: Int) {
        val customDns = Settings.dns.v.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val mtu = Settings.mtu.v.coerceIn(576, 9000)

        val b = Builder()
            .setSession("Cocote Tunnel")
            .setMtu(mtu)
            .addAddress(TUN_ADDR, 32)
            .addAddress("fc00::1", 128)
            .addRoute("::", 0)
        if (Settings.bypassLan.v) {
            Routes.excluding(Routes.LAN).forEach { (a, pfx) -> b.addRoute(a, pfx) }
            AppLog.i("TUN", "Bypass LAN: jaringan lokal tidak melewati VPN")
        } else {
            b.addRoute("0.0.0.0", 0)
        }
        if (customDns.isEmpty()) b.addDnsServer(DNS_ADDR) else customDns.forEach { runCatching { b.addDnsServer(it) } }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val allowed = Settings.allowedApps
        if (allowed.isNotEmpty()) {
            var n = 0
            allowed.forEach { pkg ->
                if (pkg != packageName) runCatching { b.addAllowedApplication(pkg); n++ }
            }
            if (n == 0) b.addDisallowedApplication(packageName)
            AppLog.i("TUN", "Per-app proxy: $n aplikasi dialihkan")
        } else {
            // Aplikasi ini (dan engine di dalamnya) harus keluar langsung agar tidak loop
            b.addDisallowedApplication(packageName)
        }

        val pfd = b.establish() ?: throw IOException("Gagal membuat interface VPN (izin VPN belum diberikan?)")
        tun = pfd

        val mapdns = if (customDns.isEmpty()) """
            mapdns:
              address: $DNS_ADDR
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
        """.trimIndent() else ""
        val cfg = File(filesDir, "tun.yml")
        cfg.writeText(
            "tunnel:\n  mtu: $mtu\n  ipv4: $TUN_ADDR\n  ipv6: 'fc00::1'\n" +
                "socks5:\n  port: $socksPort\n  address: 127.0.0.1\n  udp: 'udp'\n" +
                mapdns + "\nmisc:\n  log-level: warn\n"
        )
        if (!TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)) {
            throw IOException("tun2socks gagal dijalankan")
        }
        tunRunning = true
    }

    private fun failAndStop() {
        AppLog.quiet = false
        Tunnel.status.value = Status.ERROR
        cleanup()
        tileRefresh()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown(reason: String) {
        if (Tunnel.status.value == Status.IDLE && engine == null && tun == null) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        AppLog.quiet = false
        AppLog.i("APP", reason)
        Tunnel.status.value = Status.DISCONNECTING
        job?.cancel()
        cleanup()
        Tunnel.status.value = Status.IDLE
        Tunnel.connectedAt.value = 0
        Tunnel.rxRate.value = 0; Tunnel.txRate.value = 0
        tileRefresh()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        if (tunRunning) { runCatching { TProxyService.TProxyStopService() }; tunRunning = false }
        runCatching { tun?.close() }
        tun = null
        runCatching { engine?.stop() }
        engine = null
    }

    override fun onRevoke() { shutdown("Izin VPN dicabut sistem") }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        if (Tunnel.status.value != Status.ERROR) Tunnel.status.value = Status.IDLE
        super.onDestroy()
    }

    // ---- Notifikasi & tile --------------------------------------------------------------------

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Status VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle("Cocote Tunnel").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Putuskan", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun tileRefresh() {
        runCatching { TileService.requestListeningState(this, ComponentName(this, TunnelTileService::class.java)) }
    }
}
