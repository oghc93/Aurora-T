package com.aurora.tunnel.engine

import android.os.ParcelFileDescriptor
import java.net.Socket

/** Konfigurasi TUN yang dikumpulkan dari perintah management OpenVPN. */
class TunConfig {
    var ip: String? = null
    var prefix: Int = 32
    var mtu: Int = 1500
    var ip6: String? = null
    val dns = LinkedHashSet<String>()
    val domains = LinkedHashSet<String>()
    val routes = LinkedHashSet<Pair<String, Int>>()
    val routes6 = LinkedHashSet<Pair<String, Int>>()
    var remoteGw: String? = null
}

/** Jembatan ke VpnService: melindungi socket dari VPN dan membuat TUN untuk OpenVPN. */
interface TunHost {
    fun protectSocket(s: Socket)
    fun protectFd(fd: Int): Boolean
    fun openOvpnTun(c: TunConfig): ParcelFileDescriptor?
    fun closeOvpnTun()
}

object OvpnBridge {
    @Volatile var host: TunHost? = null
}
