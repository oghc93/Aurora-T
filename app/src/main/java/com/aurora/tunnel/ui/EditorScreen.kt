package com.aurora.tunnel.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.aurora.tunnel.data.Importer
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ovpn
import com.aurora.tunnel.engine.Ping
import com.aurora.tunnel.engine.XNode

@Composable
fun EditorScreen(initial: Profile?, onClose: () -> Unit) {
    val p = LocalAurora.current
    val ctx = LocalContext.current
    var d by remember { mutableStateOf(initial ?: Profile()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPayloadGen by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            if (text != null) d = d.copy(ovpnConfig = text)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle(if (initial == null) "Profil baru" else "Edit profil", d.mode.label)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Field("Nama profil", d.name) { d = d.copy(name = it) }

            SectionTitle("Mode")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.values().forEach { m -> FilterPill(m.label, d.mode == m) { d = d.copy(mode = m) } }
            }

            when (d.mode) {
                Mode.SSH, Mode.UDPCUSTOM -> {
                    if (d.mode == Mode.UDPCUSTOM)
                        Text("UDP Custom memakai SSH + udpgw: UDP diteruskan lewat badvpn-udpgw di server.",
                            color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    SectionTitle("Server SSH")
                    Field("Host / IP", d.host) { d = d.copy(host = it.trim()) }
                    NumField("Port", d.port) { d = d.copy(port = it) }
                    Field("Username", d.username) { d = d.copy(username = it) }
                    Field("Password", d.password, password = true) { d = d.copy(password = it) }
                    TransportSection(d, { showPayloadGen = true }) { d = it }
                    UdpgwSection(d, forced = d.mode == Mode.UDPCUSTOM) { d = it }
                }
                Mode.SLOWDNS -> {
                    SectionTitle("SlowDNS (dnstt)")
                    Field("DNS resolver (IP, doh://host/path, atau dot://host:853)", d.dnsServer) { d = d.copy(dnsServer = it.trim()) }
                    Field("NS domain", d.dnsNs) { d = d.copy(dnsNs = it.trim()) }
                    Field("Public key (hex)", d.dnsPubkey) { d = d.copy(dnsPubkey = it.trim()) }
                    SectionTitle("Akun SSH di dalam tunnel")
                    Field("Username", d.username) { d = d.copy(username = it) }
                    Field("Password", d.password, password = true) { d = d.copy(password = it) }
                    UdpgwSection(d, forced = false) { d = it }
                }
                Mode.XRAY -> XraySection(d, onImport = { showImport = true }) { d = it }
                Mode.OVPN -> OvpnSection(d, onPayloadGen = { showPayloadGen = true },
                    onPick = { picker.launch(arrayOf("*/*")) }) { d = it }
            }
            if (d.mode == Mode.SSH || d.mode == Mode.UDPCUSTOM || d.mode == Mode.SLOWDNS) {
                NumField("Batas waktu konek (detik)", d.connectTimeoutSec) { d = d.copy(connectTimeoutSec = it) }
            }
            ToggleRow("Ikut Load Balance (LB)", d.lb, "Dipakai bila Load Balance aktif di Pengaturan") { d = d.copy(lb = it) }
        }
        error?.let { Text(it, color = p.err, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuroraButton("Simpan") {
                error = validate(d)
                if (error == null) {
                    val out = when (d.mode) {
                        Mode.XRAY -> d.copy(host = d.x.address, port = d.x.port)
                        Mode.OVPN -> Ovpn.endpoint(d.ovpnConfig)?.let { d.copy(host = it.first, port = it.second) } ?: d
                        else -> d
                    }
                    ProfileStore.upsert(out)
                    if (Settings.selectedProfileId == null) Settings.select(out.id)
                    onClose()
                }
            }
            GhostButton("Batal") { onClose() }
        }
    }

    if (showPayloadGen) PayloadGenDialog(onUse = { d = d.copy(payload = it, enablePayload = true) }) { showPayloadGen = false }
    if (showImport) InputDialog("Impor link Xray", "", "Tempel link vmess:// vless:// trojan:// ss://",
        onOk = { t ->
            Importer.fromText(t)?.takeIf { it.mode == Mode.XRAY }?.let { imp ->
                d = d.copy(x = imp.x, name = if (d.name == "Profil baru" || d.name.isBlank()) imp.name else d.name)
            } ?: run { error = "Link tidak dikenali" }
        }) { showImport = false }
}

private fun validate(d: Profile): String? {
    if (d.name.isBlank()) return "Nama profil wajib diisi"
    return when (d.mode) {
        Mode.SSH, Mode.UDPCUSTOM ->
            if (d.host.isBlank() || d.username.isBlank()) "Host dan username wajib diisi" else null
        Mode.SLOWDNS ->
            if (d.dnsNs.isBlank() || d.dnsPubkey.isBlank() || d.username.isBlank()) "NS domain, public key, dan username wajib diisi" else null
        Mode.XRAY ->
            if (d.xrayLink.isBlank() && (d.x.address.isBlank() || d.x.id.isBlank())) "Alamat server dan ID/password wajib diisi" else null
        Mode.OVPN ->
            if (d.ovpnConfig.isBlank()) "Config .ovpn wajib diisi (pilih file atau tempel isinya)"
            else if (Ovpn.endpoint(d.ovpnConfig) == null) "Config tidak punya baris 'remote host port'" else null
    }
}

@Composable
private fun NumField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0) "" else value.toString()) }
    Field(label, text, keyboard = KeyboardType.Number) {
        text = it.filter { c -> c.isDigit() }.take(6)
        onChange(text.toIntOrNull() ?: 0)
    }
}

@Composable
private fun XraySection(d: Profile, onImport: () -> Unit, set: (Profile) -> Unit) {
    val p = LocalAurora.current
    val x = d.x
    fun upd(n: XNode) = set(d.copy(x = n))

    SectionTitle("Pengaturan ${x.protocol.uppercase()}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("vless" to "VLESS", "vmess" to "VMESS", "trojan" to "TROJAN", "shadowsocks" to "SS").forEach { (k, l) ->
            FilterPill(l, x.protocol == k) {
                upd(when (k) {
                    "shadowsocks" -> x.copy(protocol = k, network = "tcp", tls = "none", security = "aes-256-gcm")
                    "trojan" -> x.copy(protocol = k, tls = if (x.tls == "none") "tls" else x.tls)
                    else -> x.copy(protocol = k)
                })
            }
        }
    }
    GhostButton("Impor dari link", Modifier.padding(top = 8.dp)) { onImport() }

    Field("Alamat server (IP, domain, atau [rotate=a;b])", x.address) { upd(x.copy(address = it.trim())) }
    NumField("Port server (bawaan 443)", x.port) { upd(x.copy(port = it)) }
    Field(if (x.protocol == "trojan" || x.protocol == "shadowsocks") "Password" else "ID (UUID)", x.id) { upd(x.copy(id = it.trim())) }
    if (x.protocol == "vmess") {
        NumField("Alter ID", x.alterId) { upd(x.copy(alterId = it)) }
        Field("Cipher (auto, aes-128-gcm, chacha20-poly1305, none)", x.security) { upd(x.copy(security = it.trim())) }
    }
    if (x.protocol == "shadowsocks") Field("Metode enkripsi", x.security) { upd(x.copy(security = it.trim())) }
    if (x.protocol == "vless") Field("Flow (mis. xtls-rprx-vision, opsional)", x.flow) { upd(x.copy(flow = it.trim())) }
    ToggleRow("Mux", d.xrayMux, "Multiplexing (tidak dipakai bila memakai flow vision)") { set(d.copy(xrayMux = it)) }

    SectionTitle("Transport")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("tcp", "ws", "grpc", "httpupgrade", "h2").forEach { n ->
            FilterPill(n.uppercase(), x.network == n) { upd(x.copy(network = n)) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("none" to "Tanpa TLS", "tls" to "TLS", "reality" to "Reality").forEach { (k, l) ->
            FilterPill(l, x.tls == k) { upd(x.copy(tls = k)) }
        }
    }
    if (x.tls == "tls") {
        ToggleRow("Verifikasi sertifikat ke domain server", d.xVerify,
            "Xray terbaru tidak punya Allow Insecure. Bila SNI beda dari server (bug host), sertifikat dicek ke domain server.") {
            set(d.copy(xVerify = it))
        }
    }
    ToggleRow("TLS Fragment", d.xrayFragment, "Pecah ClientHello TLS (melewati DPI SNI)") { set(d.copy(xrayFragment = it)) }
    if (x.network == "ws" || x.network == "httpupgrade" || x.network == "h2")
        Field("Host (header Host atau [rotate=bug1;bug2])", x.host) { upd(x.copy(host = it.trim())) }
    if (x.network == "ws" || x.network == "httpupgrade" || x.network == "h2")
        Field("Path", x.path) { upd(x.copy(path = it.trim())) }
    if (x.network == "grpc") Field("Nama service gRPC", x.serviceName) { upd(x.copy(serviceName = it.trim())) }
    if (x.tls != "none") {
        Field("SNI (nama server atau [rotate=bug1;bug2])", x.sni) { upd(x.copy(sni = it.trim())) }
        Field("Fingerprint (chrome, firefox, safari, random…)", x.fp) { upd(x.copy(fp = it.trim())) }
    }
    if (x.tls == "reality") {
        Field("Public key Reality", x.pbk) { upd(x.copy(pbk = it.trim())) }
        Field("Short ID", x.sid) { upd(x.copy(sid = it.trim())) }
    }

    SectionTitle("Lanjutan")
    Field("Config JSON kustom (opsional, menimpa form di atas)", d.xrayLink, singleLine = false, minLines = 3) {
        set(d.copy(xrayLink = it.trim()))
    }
    Text("Isi hanya bila ingin memakai config Xray sendiri; inbound SOCKS dipasang otomatis.",
        color = p.sub, fontSize = 11.sp)
}

@Composable
private fun TransportSection(d: Profile, onPayloadGen: () -> Unit, set: (Profile) -> Unit) {
    SectionTitle("Transport")
    Field("Proxy HTTP host (opsional)", d.proxyHost) { set(d.copy(proxyHost = it.trim())) }
    if (d.proxyHost.isNotBlank()) NumField("Proxy port", d.proxyPort) { set(d.copy(proxyPort = it)) }
    ToggleRow("SNI / TLS", d.enableSni, "Bungkus koneksi dengan TLS memakai SNI kustom") { set(d.copy(enableSni = it)) }
    if (d.enableSni) {
        Field("SNI hostname", d.sni) { set(d.copy(sni = it.trim())) }
        ToggleRow("Izinkan sertifikat tidak valid", d.allowInsecure) { set(d.copy(allowInsecure = it)) }
    }
    ToggleRow("Payload", d.enablePayload, "Kirim request HTTP/WebSocket sebelum SSH") { set(d.copy(enablePayload = it)) }
    if (d.enablePayload) {
        Field("Payload", d.payload, singleLine = false, minLines = 3) { set(d.copy(payload = it)) }
        GhostButton("Payload Gen", Modifier.padding(vertical = 4.dp)) { onPayloadGen() }
        Text("Placeholder: [host] [port] [host_port] [ws_host] [sni] [path] [ws_key] [crlf] [cr] [lf] [split] [rotate=a;b]",
            color = LocalAurora.current.sub, fontSize = 11.sp)
        ToggleRow("Tunggu respons HTTP", d.payloadExpectResponse, "Matikan bila server langsung mengirim banner SSH") {
            set(d.copy(payloadExpectResponse = it))
        }
    }
}

@Composable
private fun UdpgwSection(d: Profile, forced: Boolean, set: (Profile) -> Unit) {
    SectionTitle("BadVPN (UDPGW)")
    if (!forced) ToggleRow("BadVPN UDPGW", d.enableUdpgw, "UDP (game, panggilan, QUIC) lewat badvpn-udpgw di server. Mati: hanya DNS yang diteruskan") { set(d.copy(enableUdpgw = it)) }
    if (forced || d.enableUdpgw) {
        Field("BadVPN host (di sisi server)", d.udpgwHost) { set(d.copy(udpgwHost = it.trim())) }
        NumField("BadVPN port", d.udpgwPort) { set(d.copy(udpgwPort = it)) }
    }
}

/** Pembuat payload HTTP & WebSocket sederhana. */
@Composable
private fun PayloadGenDialog(onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalAurora.current
    var method by remember { mutableStateOf("GET") }
    var host by remember { mutableStateOf("[host]") }
    var path by remember { mutableStateOf("/") }
    var ws by remember { mutableStateOf(true) }
    var split by remember { mutableStateOf(false) }
    var onlineHost by remember { mutableStateOf(false) }
    var ua by remember { mutableStateOf("") }

    val target = if (method == "CONNECT") "[host_port]" else path
    val payload = buildString {
        append("$method $target HTTP/1.1[crlf]")
        if (split) append("[split]")
        append("Host: $host[crlf]")
        if (onlineHost) append("X-Online-Host: $host[crlf]")
        if (ws) append("Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Key: [ws_key][crlf]Sec-WebSocket-Version: 13[crlf]")
        else append("Connection: keep-alive[crlf]")
        if (ua.isNotBlank()) append("User-Agent: $ua[crlf]")
        append("[crlf]")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (p.glass) Color(0xFF241A4A) else Color(0xFF111833),
        title = { Text("Payload Gen", color = p.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("GET", "POST", "HEAD", "CONNECT", "PATCH").forEach { m -> FilterPill(m, method == m) { method = m } }
                }
                Field("Host", host) { host = it }
                if (method != "CONNECT") Field("Path", path) { path = it }
                Field("User-Agent (opsional)", ua) { ua = it }
                ToggleRow("WebSocket upgrade", ws) { ws = it }
                ToggleRow("Header X-Online-Host", onlineHost) { onlineHost = it }
                ToggleRow("Split setelah baris pertama", split) { split = it }
                Text("Hasil:", color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text(payload, color = p.text, fontSize = 12.sp, lineHeight = 16.sp)
            }
        },
        confirmButton = { TextButton({ onUse(payload); onDismiss() }) { Text("Pakai", color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Batal", color = p.sub) } }
    )
}


@Composable
private fun OvpnSection(d: Profile, onPayloadGen: () -> Unit, onPick: () -> Unit, set: (Profile) -> Unit) {
    val p = LocalAurora.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var check by remember { mutableStateOf("") }
    val tcp = Ovpn.proto(d.ovpnConfig) == "tcp"

    SectionTitle("Akun")
    Field("Username (opsional)", d.username) { set(d.copy(username = it)) }
    Field("Password", d.password, password = true) { set(d.copy(password = it)) }
    Text("Dipakai bila server meminta auth-user-pass.", color = p.sub, fontSize = 11.sp)

    SectionTitle("Payload")
    ToggleRow("Payload", d.enablePayload, "Kirim request HTTP/WebSocket sebelum OpenVPN (khusus TCP)") { set(d.copy(enablePayload = it)) }
    if (d.enablePayload) {
        Field("Payload", d.payload, singleLine = false, minLines = 3) { set(d.copy(payload = it)) }
        GhostButton("Payload Gen", Modifier.padding(vertical = 4.dp)) { onPayloadGen() }
        ToggleRow("Tunggu respons HTTP", d.payloadExpectResponse) { set(d.copy(payloadExpectResponse = it)) }
    }

    SectionTitle("Proxy")
    var proxyOn by remember { mutableStateOf(d.proxyHost.isNotBlank()) }
    ToggleRow("Proxy HTTP", proxyOn, "Sambung ke server lewat proxy HTTP CONNECT (khusus TCP)") {
        proxyOn = it
        if (!it) set(d.copy(proxyHost = "", proxyPort = 0))
    }
    if (proxyOn) {
        Field("Proxy host", d.proxyHost) { set(d.copy(proxyHost = it.trim())) }
        NumField("Proxy port", d.proxyPort) { set(d.copy(proxyPort = it)) }
    }

    SectionTitle("SNI")
    ToggleRow("SNI / TLS", d.enableSni, "Bungkus koneksi dengan TLS memakai SNI kustom (khusus TCP)") { set(d.copy(enableSni = it)) }
    if (d.enableSni) {
        Field("SNI hostname", d.sni) { set(d.copy(sni = it.trim())) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "TLS Otomatis", "1.2" to "TLSv1.2", "1.3" to "TLSv1.3").forEach { (k, l) ->
                FilterPill(l, Settings.forceTls.v == k) { Settings.forceTls.set(k) }
            }
        }
        ToggleRow("Izinkan sertifikat tidak valid", d.allowInsecure) { set(d.copy(allowInsecure = it)) }
    }

    SectionTitle("OpenVPN Config")
    Text(
        if (d.ovpnConfig.isBlank()) "Belum ada config" else "config.ovpn (${Ovpn.summary(d.ovpnConfig)})",
        color = p.text, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)
    )
    GhostButton("Pilih file .ovpn") { onPick() }
    Field("Isi config .ovpn (bisa ditempel)", d.ovpnConfig, singleLine = false, minLines = 6) { set(d.copy(ovpnConfig = it)) }
    if (!tcp && (d.enablePayload || d.enableSni || d.proxyHost.isNotBlank())) {
        Text("Config ini UDP: Payload/Proxy/SNI hanya berlaku untuk OpenVPN TCP dan akan diabaikan.", color = p.warn, fontSize = 12.sp)
    }

    SectionTitle("Cek Koneksi")
    GhostButton("Tes koneksi ke server") {
        val ep = Ovpn.endpoint(d.ovpnConfig)
        if (ep == null) { check = "Config belum valid" } else {
            check = "Memeriksa ${ep.first}:${ep.second}…"
            scope.launch {
                val host = if (d.proxyHost.isNotBlank()) d.proxyHost else ep.first
                val port = if (d.proxyHost.isNotBlank()) d.proxyPort else ep.second
                val ms = if (tcp || d.proxyHost.isNotBlank()) Ping.tcp(host, port) else -2L
                check = when {
                    ms == -2L -> "Server UDP: tidak bisa dites lewat TCP, coba langsung konek"
                    ms >= 0 -> "Server $host:$port merespons ($ms ms)"
                    else -> "Server $host:$port tidak merespons"
                }
            }
        }
    }
    if (check.isNotEmpty()) Text(check, color = p.sub, fontSize = 12.sp)
}
