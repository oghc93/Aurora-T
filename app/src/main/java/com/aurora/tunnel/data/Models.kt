package com.aurora.tunnel.data

import com.aurora.tunnel.engine.XNode
import org.json.JSONObject
import java.util.UUID

enum class Mode(val label: String) {
    SSH("SSH"),
    SLOWDNS("SlowDNS"),
    UDPCUSTOM("UDP Custom"),
    OVPN("OpenVPN"),
    XRAY("Xray")
}

const val DEFAULT_PAYLOAD =
    "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Profil baru",
    val mode: Mode = Mode.SSH,
    // SSH / UDP Custom / SlowDNS (kredensial SSH)
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val connectTimeoutSec: Int = 20,
    // Transport: proxy HTTP, SNI/TLS, payload
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val enableSni: Boolean = false,
    val sni: String = "",
    val enablePayload: Boolean = false,
    val payload: String = DEFAULT_PAYLOAD,
    val payloadExpectResponse: Boolean = true,
    val allowInsecure: Boolean = true,
    // UDP lewat udpgw (badvpn-udpgw di sisi server)
    val enableUdpgw: Boolean = true,
    val udpgwHost: String = "127.0.0.1",
    val udpgwPort: Int = 7300,
    // SlowDNS (dnstt)
    val dnsServer: String = "8.8.8.8",
    val dnsNs: String = "",
    val dnsPubkey: String = "",
    // Xray
    val xrayLink: String = "",       // JSON kustom, atau link lama
    val x: XNode = XNode(),          // form Xray
    val xVerify: Boolean = true,     // verifikasi sertifikat ke domain server (pengganti allowInsecure)
    val lb: Boolean = false,         // ikut Load Balance
    val xrayFragment: Boolean = false,
    val xrayMux: Boolean = false,
    // OpenVPN
    val ovpnConfig: String = "",
    // Penguncian config (dari file .cct): tidak bisa diedit / diekspor ulang, field tertentu disembunyikan
    val locked: Boolean = false,
    val locks: String = ""            // kunci grup field, dipisah koma (lihat Locks)
) {
    fun isLocked(key: String): Boolean = locks.isNotEmpty() && locks.split(',').contains(key)
    fun anyLocked(vararg keys: String): Boolean = keys.any { isLocked(it) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("mode", mode.name)
        put("host", host); put("port", port)
        put("username", username); put("password", password)
        put("connectTimeoutSec", connectTimeoutSec)
        put("proxyHost", proxyHost); put("proxyPort", proxyPort)
        put("enableSni", enableSni); put("sni", sni)
        put("enablePayload", enablePayload); put("payload", payload)
        put("payloadExpectResponse", payloadExpectResponse)
        put("allowInsecure", allowInsecure)
        put("enableUdpgw", enableUdpgw)
        put("udpgwHost", udpgwHost); put("udpgwPort", udpgwPort)
        put("dnsServer", dnsServer); put("dnsNs", dnsNs); put("dnsPubkey", dnsPubkey)
        put("xrayLink", xrayLink); put("x", x.toJson()); put("xVerify", xVerify); put("lb", lb); put("xrayFragment", xrayFragment); put("xrayMux", xrayMux)
        put("ovpnConfig", ovpnConfig)
        put("locked", locked); put("locks", locks)
    }

    companion object {
        fun fromJson(j: JSONObject): Profile {
            val d = Profile()
            return Profile(
                id = j.optString("id", d.id),
                name = j.optString("name", d.name),
                mode = runCatching { Mode.valueOf(j.optString("mode", "SSH")) }.getOrDefault(Mode.SSH),
                host = j.optString("host", ""),
                port = j.optInt("port", 22),
                username = j.optString("username", ""),
                password = j.optString("password", ""),
                connectTimeoutSec = j.optInt("connectTimeoutSec", 20),
                proxyHost = j.optString("proxyHost", ""),
                proxyPort = j.optInt("proxyPort", 0),
                enableSni = j.optBoolean("enableSni", false),
                sni = j.optString("sni", ""),
                enablePayload = j.optBoolean("enablePayload", false),
                payload = j.optString("payload", DEFAULT_PAYLOAD),
                payloadExpectResponse = j.optBoolean("payloadExpectResponse", true),
                allowInsecure = j.optBoolean("allowInsecure", true),
                enableUdpgw = j.optBoolean("enableUdpgw", true),
                udpgwHost = j.optString("udpgwHost", "127.0.0.1"),
                udpgwPort = j.optInt("udpgwPort", 7300),
                dnsServer = j.optString("dnsServer", "8.8.8.8"),
                dnsNs = j.optString("dnsNs", ""),
                dnsPubkey = j.optString("dnsPubkey", ""),
                xrayLink = j.optString("xrayLink", ""),
                x = XNode.fromJson(j.optJSONObject("x")),
                xVerify = j.optBoolean("xVerify", true),
                lb = j.optBoolean("lb", false),
                xrayFragment = j.optBoolean("xrayFragment", false),
                xrayMux = j.optBoolean("xrayMux", false),
                ovpnConfig = j.optString("ovpnConfig", ""),
                locked = j.optBoolean("locked", false),
                locks = j.optString("locks", "")
            )
        }
    }
}


/** Grup field yang bisa dikunci saat ekspor config. */
object Locks {
    const val PAYLOAD = "payload"
    const val PROXY = "proxy"
    const val SNI = "sni"
    const val SSH_HOST = "ssh_host"
    const val SSH_AUTH = "ssh_auth"
    const val DNS = "dns"
    const val DNS_AUTH = "dns_auth"
    const val OVPN_CFG = "ovpn_cfg"
    const val OVPN_AUTH = "ovpn_auth"
    const val XRAY_SRV = "xray_srv"
    const val XRAY_AUTH = "xray_auth"
    const val XRAY_EDIT = "xray_edit"

    /** Alamat server disembunyikan di tampilan bila salah satu ini terkunci. */
    val HIDE_TARGET = arrayOf(SSH_HOST, DNS, OVPN_CFG, XRAY_SRV)
}

/** Teks alamat server untuk ditampilkan (disamarkan bila dikunci). */
fun Profile.targetText(t: Pair<String, Int>?): String =
    if (anyLocked(*Locks.HIDE_TARGET)) "••••••" else t?.let { "${it.first}:${it.second}" } ?: "—"
