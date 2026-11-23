package com.aurora.tunnel.engine

import android.util.Base64
import com.aurora.tunnel.data.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder

data class XNode(
    val protocol: String = "vless",   // vmess | vless | trojan | shadowsocks
    val name: String = "",
    val address: String = "",
    val port: Int = 443,
    val id: String = "",              // uuid / password
    val alterId: Int = 0,
    val security: String = "auto",    // vmess cipher / ss method
    val flow: String = "",
    val network: String = "ws",       // tcp | ws | grpc | httpupgrade | h2
    val tls: String = "tls",          // none | tls | reality
    val sni: String = "",
    val host: String = "",
    val path: String = "/",
    val fp: String = "",
    val alpn: String = "",
    val pbk: String = "",
    val sid: String = "",
    val serviceName: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", protocol); put("name", name); put("address", address); put("port", port)
        put("id", id); put("alterId", alterId); put("security", security); put("flow", flow)
        put("network", network); put("tls", tls); put("sni", sni); put("host", host); put("path", path)
        put("fp", fp); put("alpn", alpn); put("pbk", pbk); put("sid", sid); put("serviceName", serviceName)
    }

    companion object {
        fun fromJson(j: JSONObject?): XNode {
            if (j == null) return XNode()
            val d = XNode()
            return XNode(
                j.optString("protocol", d.protocol), j.optString("name", ""), j.optString("address", ""),
                j.optInt("port", 443), j.optString("id", ""), j.optInt("alterId", 0),
                j.optString("security", "auto"), j.optString("flow", ""), j.optString("network", d.network),
                j.optString("tls", d.tls), j.optString("sni", ""), j.optString("host", ""),
                j.optString("path", "/"), j.optString("fp", ""), j.optString("alpn", ""),
                j.optString("pbk", ""), j.optString("sid", ""), j.optString("serviceName", "")
            )
        }
    }
}

object XrayConfig {

    fun parse(link: String): XNode {
        val l = link.trim()
        return when {
            l.startsWith("vmess://", true) -> parseVmess(l)
            l.startsWith("vless://", true) -> parseUri(l, "vless")
            l.startsWith("trojan://", true) -> parseUri(l, "trojan")
            l.startsWith("ss://", true) -> parseSs(l)
            else -> throw IllegalArgumentException("Format link tidak dikenal (pakai vmess/vless/trojan/ss atau JSON Xray)")
        }
    }

    fun isJson(s: String) = s.trim().startsWith("{")

    /** Host:port tujuan untuk ping. */
    fun endpoint(p: Profile): Pair<String, Int>? = runCatching {
        if (isJson(p.xrayLink)) null else nodeOf(p).let { Rotate.first(it.address) to it.port }
    }.getOrNull()

    /** Node dari form (p.x), atau dari link lama bila form kosong. */
    fun nodeOf(p: Profile): XNode =
        if (p.x.address.isNotBlank()) p.x else parse(p.xrayLink)

    fun toLink(n: XNode): String {
        val q = StringBuilder()
        fun add(k: String, v: String) { if (v.isNotEmpty()) q.append(if (q.isEmpty()) "" else "&").append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8")) }
        val name = java.net.URLEncoder.encode(n.name.ifEmpty { n.address }, "UTF-8")
        return when (n.protocol) {
            "vmess" -> {
                val j = JSONObject().put("v", "2").put("ps", n.name).put("add", n.address).put("port", n.port.toString())
                    .put("id", n.id).put("aid", n.alterId.toString()).put("scy", n.security).put("net", n.network)
                    .put("host", n.host).put("path", n.path).put("tls", if (n.tls == "tls") "tls" else "").put("sni", n.sni)
                "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
            }
            "shadowsocks" -> "ss://" + Base64.encodeToString("${n.security}:${n.id}".toByteArray(), Base64.NO_WRAP) +
                "@${n.address}:${n.port}#$name"
            else -> {
                add("type", n.network); add("security", n.tls); add("sni", n.sni); add("host", n.host)
                add("path", n.path); add("flow", n.flow); add("fp", n.fp); add("pbk", n.pbk); add("sid", n.sid)
                if (n.protocol == "vless") q.insert(0, "encryption=none&")
                "${n.protocol}://${java.net.URLEncoder.encode(n.id, "UTF-8")}@${n.address}:${n.port}?$q#$name"
            }
        }
    }

    private fun parseVmess(l: String): XNode {
        val b64 = l.substring(8).substringBefore('#')
        val j = JSONObject(String(Base64.decode(pad(b64), Base64.DEFAULT or Base64.URL_SAFE)))
        return XNode(
            protocol = "vmess", name = j.optString("ps", "vmess"),
            address = j.getString("add"), port = j.get("port").toString().toInt(),
            id = j.getString("id"), alterId = j.optString("aid", "0").toIntOrNull() ?: 0,
            security = j.optString("scy", "auto").ifEmpty { "auto" },
            network = j.optString("net", "tcp").ifEmpty { "tcp" },
            tls = if (j.optString("tls") == "tls") "tls" else "none",
            sni = j.optString("sni"), host = j.optString("host"), path = j.optString("path"),
            fp = j.optString("fp"), alpn = j.optString("alpn"),
            serviceName = j.optString("path")
        )
    }

    private fun parseUri(l: String, proto: String): XNode {
        val u = URI(l)
        val q = query(u.rawQuery)
        val userInfo = URLDecoder.decode(u.rawUserInfo ?: "", "UTF-8")
        val sec = q["security"] ?: "none"
        return XNode(
            protocol = proto, name = URLDecoder.decode(u.rawFragment ?: proto, "UTF-8"),
            address = u.host, port = u.port.takeIf { it > 0 } ?: 443,
            id = userInfo, flow = q["flow"] ?: "",
            network = q["type"] ?: "tcp",
            tls = if (proto == "trojan" && sec == "none") "tls" else sec,
            sni = q["sni"] ?: q["peer"] ?: "", host = q["host"] ?: "", path = q["path"] ?: "",
            fp = q["fp"] ?: "", alpn = q["alpn"] ?: "", pbk = q["pbk"] ?: "", sid = q["sid"] ?: "",
            serviceName = q["serviceName"] ?: ""
        )
    }

    private fun parseSs(l: String): XNode {
        val body = l.substring(5)
        val name = URLDecoder.decode(body.substringAfter('#', "shadowsocks"), "UTF-8")
        val main = body.substringBefore('#').substringBefore('?')
        val method: String; val pass: String; val hostPort: String
        if (main.contains('@')) {
            val cred = main.substringBefore('@')
            val decoded = runCatching { String(Base64.decode(pad(cred), Base64.DEFAULT or Base64.URL_SAFE)) }
                .getOrElse { URLDecoder.decode(cred, "UTF-8") }
            method = decoded.substringBefore(':'); pass = decoded.substringAfter(':')
            hostPort = main.substringAfter('@')
        } else {
            val decoded = String(Base64.decode(pad(main), Base64.DEFAULT or Base64.URL_SAFE))
            method = decoded.substringBefore(':'); pass = decoded.substringAfter(':').substringBefore('@')
            hostPort = decoded.substringAfter('@')
        }
        return XNode("shadowsocks", name, hostPort.substringBeforeLast(':'),
            hostPort.substringAfterLast(':').toInt(), id = pass, security = method,
            network = "tcp", tls = "none", path = "")
    }

    private fun pad(s: String) = s + "=".repeat((4 - s.length % 4) % 4)

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    // ---- Penyusun config ------------------------------------------------------------------------

    fun build(p: Profile, socksPort: Int): String {
        val inbound = JSONObject()
            .put("tag", "socks-in").put("listen", "127.0.0.1").put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))

        if (isJson(p.xrayLink)) {
            val j = JSONObject(p.xrayLink)
            j.put("inbounds", JSONArray().put(inbound))
            return j.toString()
        }

        val n0 = nodeOf(p)
        val n = n0.copy(address = Rotate.pick(n0.address), host = Rotate.pick(n0.host), sni = Rotate.pick(n0.sni))
        // Resolve di sisi aplikasi (aplikasi dikecualikan dari VPN) supaya Xray tidak butuh resolver sistem
        val ip = runCatching { InetAddress.getByName(n.address).hostAddress }.getOrNull() ?: n.address
        val tlsName = n.sni.ifEmpty { if (n.host.isNotEmpty() && n.network == "ws") n.host else n.address }

        val proxy = JSONObject().put("tag", "proxy").put("protocol", n.protocol)
        when (n.protocol) {
            "vmess" -> proxy.put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", ip).put("port", n.port).put("users", JSONArray().put(
                    JSONObject().put("id", n.id).put("alterId", n.alterId).put("security", n.security))))))
            "vless" -> proxy.put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", ip).put("port", n.port).put("users", JSONArray().put(
                    JSONObject().put("id", n.id).put("encryption", "none")
                        .apply { if (n.flow.isNotEmpty()) put("flow", n.flow) })))))
            "trojan" -> proxy.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", ip).put("port", n.port).put("password", n.id))))
            "shadowsocks" -> proxy.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", ip).put("port", n.port).put("method", n.security).put("password", n.id))))
        }

        val stream = JSONObject().put("network", n.network)
        when (n.tls) {
            "tls" -> stream.put("security", "tls").put("tlsSettings", JSONObject()
                .put("serverName", tlsName)
                .apply {
                    // allowInsecure sudah dihapus di Xray terbaru. Bila SNI berbeda dari domain server
                    // (bug host), verifikasi sertifikat dilakukan terhadap domain server yang asli.
                    val isIp = Regex("^[0-9.]+$").matches(n.address) || n.address.contains(':')
                    if (p.xVerify && !isIp && !tlsName.equals(n.address, true)) put("verifyPeerCertByName", n.address)
                    when (com.aurora.tunnel.data.Settings.forceTls.v) {
                        "1.2" -> { put("minVersion", "1.2"); put("maxVersion", "1.2") }
                        "1.3" -> { put("minVersion", "1.3"); put("maxVersion", "1.3") }
                    }
                    if (n.fp.isNotEmpty()) put("fingerprint", n.fp)
                    if (n.alpn.isNotEmpty()) put("alpn", JSONArray(n.alpn.split(',')))
                })
            "reality" -> stream.put("security", "reality").put("realitySettings", JSONObject()
                .put("serverName", tlsName).put("fingerprint", n.fp.ifEmpty { "chrome" })
                .put("publicKey", n.pbk).put("shortId", n.sid))
            else -> stream.put("security", "none")
        }
        when (n.network) {
            "ws" -> stream.put("wsSettings", JSONObject().put("path", n.path.ifEmpty { "/" })
                .put("headers", JSONObject().put("Host", n.host.ifEmpty { n.address })))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", n.path.ifEmpty { "/" })
                .put("host", n.host.ifEmpty { n.address }))
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", n.serviceName.ifEmpty { n.path }))
            "h2", "http" -> stream.put("httpSettings", JSONObject().put("path", n.path.ifEmpty { "/" })
                .put("host", JSONArray().put(n.host.ifEmpty { n.address })))
        }
        if (p.xrayFragment) stream.put("sockopt", JSONObject().put("dialerProxy", "fragment"))
        proxy.put("streamSettings", stream)
        if (p.xrayMux && n.flow.isEmpty()) proxy.put("mux", JSONObject().put("enabled", true).put("concurrency", 8))

        val outbounds = JSONArray().put(proxy)
        if (p.xrayFragment) outbounds.put(JSONObject().put("tag", "fragment").put("protocol", "freedom")
            .put("settings", JSONObject().put("fragment", JSONObject()
                .put("packets", "tlshello").put("length", "100-200").put("interval", "10-20")))
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tcpNoDelay", true))))
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .toString(2)
    }
}
