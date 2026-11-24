package com.aurora.tunnel.data

import com.aurora.tunnel.engine.XrayConfig

/** Membuat profil dari teks yang ditempel (link Xray atau format host:port@user:pass). */
object Importer {
    fun fromText(text: String): Profile? {
        val s = text.trim()
        if (s.isEmpty()) return null
        if (Regex("^(vmess|vless|trojan|ss)://", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            return runCatching {
                val n = XrayConfig.parse(s)
                Profile(name = n.name.ifBlank { n.address }, mode = Mode.XRAY, xrayLink = "", x = n,
                    host = n.address, port = n.port)
            }.getOrNull()
        }
        Regex("^([^:@\\s]+):(\\d+)@([^:\\s]+):(.+)$").matchEntire(s)?.let {
            val (h, p, u, pw) = it.destructured
            return Profile(name = h, mode = Mode.SSH, host = h, port = p.toInt(), username = u, password = pw)
        }
        return null
    }
}
