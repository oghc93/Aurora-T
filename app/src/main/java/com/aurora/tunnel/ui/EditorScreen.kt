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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings

@Composable
fun EditorScreen(initial: Profile?, onClose: () -> Unit) {
    val p = LocalAurora.current
    val ctx = LocalContext.current
    var d by remember { mutableStateOf(initial ?: Profile()) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            if (text != null) d = d.copy(ovpnConfig = text)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle(if (initial == null) "Profil baru" else "Edit profil")
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
                    TransportSection(d) { d = it }
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
                Mode.XRAY -> {
                    SectionTitle("Xray")
                    Field("Link vmess:// vless:// trojan:// ss:// atau JSON Xray", d.xrayLink,
                        singleLine = false, minLines = 4) { d = d.copy(xrayLink = it.trim()) }
                    ToggleRow("TLS fragment", d.xrayFragment, "Memecah ClientHello TLS untuk melewati filter SNI") { d = d.copy(xrayFragment = it) }
                    ToggleRow("Mux", d.xrayMux, "Multiplexing (tidak dipakai bila flow vision)") { d = d.copy(xrayMux = it) }
                }
                Mode.OVPN -> {
                    SectionTitle("OpenVPN")
                    Text("Mode ini belum aktif di versi ini. Config tetap bisa disimpan.",
                        color = p.warn, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                    GhostButton("Pilih file .ovpn") { picker.launch(arrayOf("*/*")) }
                    Field("Isi config .ovpn", d.ovpnConfig, singleLine = false, minLines = 6) { d = d.copy(ovpnConfig = it) }
                }
            }
            if (d.mode != Mode.OVPN && d.mode != Mode.XRAY) {
                NumField("Batas waktu konek (detik)", d.connectTimeoutSec) { d = d.copy(connectTimeoutSec = it) }
            }
        }
        error?.let { Text(it, color = p.err, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuroraButton("Simpan") {
                error = validate(d)
                if (error == null) {
                    ProfileStore.upsert(d)
                    if (Settings.selectedProfileId == null) Settings.select(d.id)
                    onClose()
                }
            }
            GhostButton("Batal") { onClose() }
        }
    }
}

private fun validate(d: Profile): String? {
    if (d.name.isBlank()) return "Nama profil wajib diisi"
    return when (d.mode) {
        Mode.SSH, Mode.UDPCUSTOM ->
            if (d.host.isBlank() || d.username.isBlank()) "Host dan username wajib diisi" else null
        Mode.SLOWDNS ->
            if (d.dnsNs.isBlank() || d.dnsPubkey.isBlank() || d.username.isBlank()) "NS domain, public key, dan username wajib diisi" else null
        Mode.XRAY -> if (d.xrayLink.isBlank()) "Link Xray wajib diisi" else null
        Mode.OVPN -> null
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
private fun TransportSection(d: Profile, set: (Profile) -> Unit) {
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
        Text("Placeholder: [host] [port] [host_port] [ws_host] [sni] [path] [ws_key] [crlf] [cr] [lf] [split] [rotate=a;b]",
            color = LocalAurora.current.sub, fontSize = 11.sp)
        ToggleRow("Tunggu respons HTTP", d.payloadExpectResponse, "Matikan bila server langsung mengirim banner SSH") {
            set(d.copy(payloadExpectResponse = it))
        }
    }
}

@Composable
private fun UdpgwSection(d: Profile, forced: Boolean, set: (Profile) -> Unit) {
    SectionTitle("UDP")
    if (!forced) ToggleRow("udpgw", d.enableUdpgw, "UDP lewat badvpn-udpgw. Mati: hanya DNS yang diteruskan") { set(d.copy(enableUdpgw = it)) }
    if (forced || d.enableUdpgw) {
        Field("udpgw host (di sisi server)", d.udpgwHost) { set(d.copy(udpgwHost = it.trim())) }
        NumField("udpgw port", d.udpgwPort) { set(d.copy(udpgwPort = it)) }
    }
}
