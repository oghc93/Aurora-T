package com.aurora.tunnel.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.data.ThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class Dlg {
    object None : Dlg()
    object Mode : Dlg()
    object Tls : Dlg()
    class Num(val title: String, val hint: String?, val cur: Int, val ok: (Int) -> Unit) : Dlg()
    class Str(val title: String, val hint: String?, val cur: String, val ok: (String) -> Unit) : Dlg()
    object Apps : Dlg()
    object Reset : Dlg()
    object Dns : Dlg()
    object Engine : Dlg()
    object Buffer : Dlg()
    object Theme : Dlg()
    object LogClear : Dlg()
    object Lang : Dlg()
}

@Composable
fun SettingsScreen() {
    val p = LocalAurora.current
    val st = Settings
    val profiles by ProfileStore.profiles.collectAsState()
    var dlg by remember { mutableStateOf<Dlg>(Dlg.None) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Pengaturan")

        SectionTitle("Opsi")
        AuroraCard(Modifier.fillMaxWidth()) {
            SettingItem("Mode", if (st.vpnMode.v) "VPN (TUN seluruh perangkat)" else "Proxy (SOCKS5 lokal 127.0.0.1:${st.proxyPort.v})") { dlg = Dlg.Mode }
            if (!st.vpnMode.v) SettingItem("Port proxy", st.proxyPort.v.toString()) {
                dlg = Dlg.Num("Port proxy", "Port SOCKS5 lokal (1024-65535)", st.proxyPort.v) { st.proxyPort.set(it.coerceIn(1024, 65535)) }
            }
            val dnsList = st.dns.v.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            SettingItem("DNS", if (dnsList.isEmpty()) "Otomatis (diselesaikan lewat terowongan)" else dnsList.joinToString(" · ")) { dlg = Dlg.Dns }
            SettingItem("Tunnel Engine / UDPGW",
                if (!st.udpEnabled.v) "UDP & DNS tunneling dimatikan"
                else if (st.tunnelEngine.v == "badvpn") "BadVPN UDPGW (port manual dari profil)"
                else "HevSocks5Tunnel (UDP & DNS otomatis • tanpa konfigurasi)") { dlg = Dlg.Engine }
            SettingItem("Memaksa TLS", when (st.forceTls.v) { "1.2" -> "TLSv1.2"; "1.3" -> "TLSv1.3"; else -> "Otomatis" }) { dlg = Dlg.Tls }
            SettingItem("MTU kustom", "${st.mtu.v}" + if (st.mtu.v == 1400) " (bawaan)" else "") {
                dlg = Dlg.Num("MTU kustom", "576 - 9000. Turunkan bila koneksi sering macet.", st.mtu.v) { st.mtu.set(it.coerceIn(576, 9000)) }
            }
            SettingItem("Bypass LAN lokal", "Kecualikan Wi-Fi lokal, hotspot, dan IP privat dari VPN",
                checked = st.bypassLan.v, onCheck = { st.bypassLan.set(it) })
        }

        SectionTitle("Sambung ulang otomatis")
        AuroraCard(Modifier.fillMaxWidth()) {
            SettingItem("Sambung ulang otomatis",
                (if (st.autoReconnect.v) "Aktif" else "Mati") + " • Batas ping gagal: ${st.pingTimeouts.v}x",
                checked = st.autoReconnect.v, onCheck = { st.autoReconnect.set(it) })
            SettingItem("Batas ping gagal", "${st.pingTimeouts.v}x berturut-turut sebelum menyambung ulang") {
                dlg = Dlg.Num("Batas ping gagal", null, st.pingTimeouts.v) { st.pingTimeouts.set(it.coerceIn(1, 20)) }
            }
            SettingItem("Jeda sambung ulang", "${st.reconnectDelaySec.v} detik (bertambah tiap percobaan)") {
                dlg = Dlg.Num("Jeda sambung ulang (detik)", null, st.reconnectDelaySec.v) { st.reconnectDelaySec.set(it.coerceIn(1, 60)) }
            }
        }

        SectionTitle("Auto Ping")
        AuroraCard(Modifier.fillMaxWidth()) {
            SettingItem("Auto Ping",
                (if (st.autoPing.v) "Aktif" else "Mati") + " • ${st.autoPingSec.v} dtk (tujuan: ${st.autoPingHost.v})",
                checked = st.autoPing.v, onCheck = { st.autoPing.set(it) })
            SettingItem("Interval ping", "${st.autoPingSec.v} detik") {
                dlg = Dlg.Num("Interval ping (detik)", "Minimal 5", st.autoPingSec.v) { st.autoPingSec.set(it.coerceIn(5, 300)) }
            }
            SettingItem("Tujuan ping", st.autoPingHost.v) {
                dlg = Dlg.Str("Tujuan ping", "Host yang dihubungi lewat terowongan (port 443)", st.autoPingHost.v) { if (it.isNotBlank()) st.autoPingHost.set(it) }
            }
        }

        SectionTitle("Lanjutan")
        AuroraCard(Modifier.fillMaxWidth()) {
            SettingItem("Kompresi data", if (st.compression.v) "Aktif (zlib pada SSH)" else "Mati",
                checked = st.compression.v, onCheck = { st.compression.set(it) })
            SettingItem("Mode debug", if (st.debugLog.v) "Aktif • log detail teknis" else "Mati",
                checked = st.debugLog.v, onCheck = { st.setDebug(it) })
            SettingItem("Hapus log otomatis", when (st.logClearMin.v) { 0 -> "Mati"; else -> "Setiap ${st.logClearMin.v} menit" } +
                if (st.logClearOnConnect.v) " • dan saat konek" else "") { dlg = Dlg.LogClear }
            SettingItem("Render banner HTML", if (st.htmlBanner.v) "Aktif • banner server tampil berwarna" else "Mati • banner tampil teks biasa",
                checked = st.htmlBanner.v, onCheck = { st.htmlBanner.set(it) })
            SettingItem("Ukuran buffer",
                if (!st.bufferOn.v) "Nonaktif (bawaan)\nUbah ukuran buffer socket; jangan diubah bila ragu"
                else "Kirim ${st.sendBuf.v} • Terima ${st.recvBuf.v}") { dlg = Dlg.Buffer }
            SettingItem("SSH Keep-Alive",
                if (st.keepAliveSec.v == 0) "Mati (hemat baterai maksimum)" else "${st.keepAliveSec.v} detik\nKirim heartbeat berkala agar tidak diputus saat idle") {
                dlg = Dlg.Num("SSH Keep-Alive (detik)", "0 = mati", st.keepAliveSec.v) { st.keepAliveSec.set(it.coerceIn(0, 300)) }
            }
        }

        SectionTitle("Load Balance")
        AuroraCard(Modifier.fillMaxWidth()) {
            val n = profiles.count { it.lb }
            SettingItem("Load Balance",
                (if (st.loadBalance.v) "Aktif" else "Mati") + " • $n profil ditandai LB (profil terpilih ikut otomatis)\n" +
                    "Koneksi TCP dibagi bergiliran ke semua node, UDP hanya DNS. Perlu minimal 2 node.",
                checked = st.loadBalance.v, onCheck = { st.loadBalance.set(it) })
        }

        SectionTitle("Umum")
        AuroraCard(Modifier.fillMaxWidth()) {
            SettingItem("Profil", "Tampilkan daftar profil di Beranda",
                checked = st.showProfilesHome.v, onCheck = { st.showProfilesHome.set(it) })
            SettingItem("Mode hemat daya", "Matikan notifikasi dinamis, durasi, statistik data, dan beban latar belakang",
                checked = st.powerSaving.v, onCheck = { st.powerSaving.set(it) })
            SettingItem("Per-app proxy",
                if (st.allowedApps.isEmpty()) "Nonaktif (semua aplikasi)" else "${st.allowedApps.size} aplikasi dipilih") { dlg = Dlg.Apps }
        }

        SectionTitle("Tema")
        AuroraCard(Modifier.fillMaxWidth()) {
            SettingItem("Tema", st.theme.label) { dlg = Dlg.Theme }
        }

        SectionTitle("Bahasa")
        AuroraCard(Modifier.fillMaxWidth()) {
            SettingItem("Bahasa / Language", if (st.lang.v == "en") "English" else "Indonesia") { dlg = Dlg.Lang }
        }

        SectionTitle("Lainnya")
        AuroraCard(Modifier.fillMaxWidth()) {
            Column(Modifier.clickable { dlg = Dlg.Reset }) {
                Text("Reset pengaturan", color = p.err, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Kembalikan semua pengaturan ke bawaan (profil tidak dihapus)", color = p.sub, fontSize = 12.sp)
            }
        }
        AuroraCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Cocote Tunnel 1.0.0", color = p.text, fontWeight = FontWeight.SemiBold)
            Text("SSH (payload/SNI/proxy), SlowDNS, UDP Custom (udpgw), Xray, Load Balance. OpenVPN menyusul.",
                color = p.sub, fontSize = 12.sp)
        }
        Box(Modifier.height(24.dp))
    }

    when (val d = dlg) {
        Dlg.None -> {}
        Dlg.Mode -> ChoiceDialog("Mode", listOf("VPN (TUN seluruh perangkat)", "Proxy (SOCKS5 lokal)"),
            if (st.vpnMode.v) 0 else 1, { st.vpnMode.set(it == 0) }) { dlg = Dlg.None }
        Dlg.Tls -> ChoiceDialog("Memaksa TLS", listOf("Otomatis", "TLSv1.2", "TLSv1.3"),
            when (st.forceTls.v) { "1.2" -> 1; "1.3" -> 2; else -> 0 },
            { st.forceTls.set(listOf("auto", "1.2", "1.3")[it]) }) { dlg = Dlg.None }
        is Dlg.Num -> InputDialog(d.title, d.cur.toString(), d.hint, numeric = true,
            onOk = { v -> v.toIntOrNull()?.let(d.ok) }) { dlg = Dlg.None }
        is Dlg.Str -> InputDialog(d.title, d.cur, d.hint, onOk = d.ok) { dlg = Dlg.None }
        Dlg.Apps -> AppPicker { dlg = Dlg.None }
        Dlg.Theme -> ChoiceDialog("Tema", ThemeId.values().map { it.label }, st.theme.ordinal,
            { st.applyTheme(ThemeId.values()[it]) }) { dlg = Dlg.None }
        Dlg.LogClear -> LogClearDialog { dlg = Dlg.None }
        Dlg.Lang -> ChoiceDialog("Bahasa / Language", listOf("Indonesia", "English"),
            if (st.lang.v == "en") 1 else 0, { st.lang.set(if (it == 1) "en" else "id") }) { dlg = Dlg.None }
        Dlg.Dns -> DnsDialog { dlg = Dlg.None }
        Dlg.Engine -> EngineDialog { dlg = Dlg.None }
        Dlg.Buffer -> BufferDialog { dlg = Dlg.None }
        Dlg.Reset -> AlertDialog(
            onDismissRequest = { dlg = Dlg.None },
            containerColor = if (p.glass) Color(0xFF241A4A) else Color(0xFF111833),
            title = { Text("Reset pengaturan?", color = p.text) },
            text = { Text("Semua pengaturan kembali ke bawaan. Profil tetap ada.", color = p.sub) },
            confirmButton = { TextButton({ st.resetAll(); dlg = Dlg.None }) { Text("Reset", color = p.err) } },
            dismissButton = { TextButton({ dlg = Dlg.None }) { Text("Batal", color = p.sub) } }
        )
    }
}

@Composable
private fun ThemePreview(t: ThemeId, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = paletteFor(t)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) pal.accent else pal.text.copy(0.25f), shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxWidth().height(84.dp)
                .background(Brush.linearGradient(listOf(pal.bgTop, pal.bgBottom)))
        ) {
            Box(Modifier.padding(12.dp).fillMaxWidth().height(28.dp).clip(RoundedCornerShape(10.dp))
                .background(if (pal.glass) pal.text.copy(0.18f) else pal.card)
                .border(1.dp, pal.cardBorder, RoundedCornerShape(10.dp)))
            Box(Modifier.align(Alignment.BottomStart).padding(12.dp).height(8.dp).fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(pal.accent, pal.accent2))))
        }
        Text(t.label, color = LocalAurora.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun AppPicker(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalAurora.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var chosen by remember { mutableStateOf(Settings.allowedApps) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != ctx.packageName }
                .sortedBy { it.second.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (p.glass) Color(0xFF241A4A) else Color(0xFF111833),
        title = { Text("Per-app proxy", color = p.text) },
        text = {
            val list = apps
            if (list == null) Text("Memuat…", color = p.sub)
            else Column {
                Text("Kosongkan semua centang agar tunnel berlaku untuk seluruh aplikasi.", color = p.sub, fontSize = 12.sp)
                LazyColumn(Modifier.height(360.dp)) {
                    items(list, key = { it.first }) { (pkg, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            chosen = if (pkg in chosen) chosen - pkg else chosen + pkg
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(pkg in chosen, { chosen = if (it) chosen + pkg else chosen - pkg },
                                colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = Color(0xFF04131A)))
                            Text(label, color = p.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ Settings.applyAllowedApps(chosen); onDismiss() }) { Text("Simpan", color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Batal", color = p.sub) } }
    )
}

@Composable
private fun dlgColor(): Color {
    val p = LocalAurora.current
    return if (p.glass) Color(0xFF241A4A) else Color(0xFF111833)
}

private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

@Composable
private fun DnsDialog(onDismiss: () -> Unit) {
    val p = LocalAurora.current
    val cur = Settings.dns.v.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    var a by remember { mutableStateOf(cur.getOrElse(0) { "" }) }
    var b by remember { mutableStateOf(cur.getOrElse(1) { "" }) }
    val bad = (a.isNotBlank() && !IPV4.matches(a.trim())) || (b.isNotBlank() && !IPV4.matches(b.trim()))
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = dlgColor(),
        title = { Text("Konfigurasi DNS", color = p.text) },
        text = {
            Column {
                Field("DNS utama (IPv4)", a, keyboard = KeyboardType.Number) { a = it }
                Field("DNS cadangan (IPv4)", b, keyboard = KeyboardType.Number) { b = it }
                Text(
                    if (bad) "Alamat DNS harus berformat IPv4, misalnya 1.1.1.1" else
                        "Diterapkan ke antarmuka VPN dan dialirkan lewat terowongan. Kosongkan keduanya untuk DNS otomatis lewat terowongan.",
                    color = if (bad) p.err else p.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton({
                if (!bad) {
                    Settings.dns.set(listOf(a, b).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))
                    onDismiss()
                }
            }) { Text("Simpan", color = if (bad) p.sub else p.accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Batal", color = p.sub) } }
    )
}

@Composable
private fun EngineDialog(onDismiss: () -> Unit) {
    val p = LocalAurora.current
    var on by remember { mutableStateOf(Settings.udpEnabled.v) }
    var eng by remember { mutableStateOf(Settings.tunnelEngine.v) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = dlgColor(),
        title = { Text("Tunnel Engine & UDPGW", color = p.text) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { on = !on }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { on = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = Color(0xFF04131A), uncheckedColor = p.sub))
                    Text("Aktifkan UDP & DNS Tunneling", color = p.text, fontSize = 14.sp)
                }
                Text("Pilih Tunnel Engine", color = p.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("hev" to "HevSocks5Tunnel\n(Otomatis • Tanpa port)", "badvpn" to "BadVPN UDPGW\n(Port manual)").forEach { (id, label) ->
                        val sel = eng == id
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) p.accent.copy(0.18f) else p.text.copy(0.06f))
                                .border(1.dp, if (sel) p.accent else p.text.copy(0.15f), RoundedCornerShape(10.dp))
                                .clickable { eng = id }.padding(vertical = 14.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(label, color = if (sel) p.text else p.sub, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    }
                }
                Text(
                    if (eng == "hev")
                        "HevSocks5Tunnel (disarankan): tanpa konfigurasi. TUN→SOCKS5 selalu lewat hev-socks5-tunnel; pilihan engine ini mengatur relay UDP. TCP dan DNS otomatis, dan port UDPGW server dideteksi otomatis (7300/7200/7100/7400/7500) tanpa mengatur port manual."
                    else
                        "BadVPN UDPGW (port manual): UDP selalu lewat badvpn-udpgw di server memakai host dan port yang diisi di editor profil (bawaan 7300).",
                    color = p.sub, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = { TextButton({ Settings.udpEnabled.set(on); Settings.tunnelEngine.set(eng); onDismiss() }) { Text("Simpan", color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Batal", color = p.sub) } }
    )
}

@Composable
private fun BufferDialog(onDismiss: () -> Unit) {
    val p = LocalAurora.current
    var on by remember { mutableStateOf(Settings.bufferOn.v) }
    var snd by remember { mutableStateOf(Settings.sendBuf.v.toString()) }
    var rcv by remember { mutableStateOf(Settings.recvBuf.v.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = dlgColor(),
        title = { Text("Ukuran Buffer", color = p.text) },
        text = {
            Column {
                Text("Masukkan ukuran buffer baru. Terlalu kecil menurunkan kecepatan transfer, terlalu besar mengurangi akselerasi transfer. Bingung? Tekan Reset saja.",
                    color = p.text, fontSize = 12.sp, lineHeight = 16.sp)
                Row(Modifier.fillMaxWidth().clickable { on = !on }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { on = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = Color(0xFF04131A), uncheckedColor = p.sub))
                    Text("Aktifkan ukuran buffer kustom", color = p.text, fontSize = 14.sp)
                }
                Field("Buffer kirim (byte)", snd, keyboard = KeyboardType.Number, enabled = on) { snd = it.filter(Char::isDigit) }
                Field("Buffer terima (byte)", rcv, keyboard = KeyboardType.Number, enabled = on) { rcv = it.filter(Char::isDigit) }
            }
        },
        confirmButton = {
            Row {
                TextButton({ Settings.bufferOn.set(false); Settings.sendBuf.set(16384); Settings.recvBuf.set(32768); onDismiss() }) { Text("Reset", color = p.text) }
                TextButton(onDismiss) { Text("Batal", color = p.sub) }
                TextButton({
                    Settings.bufferOn.set(on)
                    Settings.sendBuf.set((snd.toIntOrNull() ?: 16384).coerceIn(1024, 4194304))
                    Settings.recvBuf.set((rcv.toIntOrNull() ?: 32768).coerceIn(1024, 4194304))
                    onDismiss()
                }) { Text("Simpan", color = p.accent) }
            }
        }
    )
}


@Composable
private fun LogClearDialog(onDismiss: () -> Unit) {
    val p = LocalAurora.current
    val st = Settings
    val opts = listOf(0, 5, 15, 30, 60)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = dialogColor(),
        title = { Text("Hapus log otomatis", color = p.text) },
        text = {
            Column {
                opts.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { st.logClearMin.set(m) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = st.logClearMin.v == m, onClick = { st.logClearMin.set(m) },
                            colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.sub)
                        )
                        Text(if (m == 0) "Mati" else "Setiap $m menit", color = p.text, fontSize = 14.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { st.logClearOnConnect.set(!st.logClearOnConnect.v) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = st.logClearOnConnect.v, onCheckedChange = { st.logClearOnConnect.set(it) },
                        colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.sub)
                    )
                    Text("Hapus juga setiap kali konek", color = p.text, fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Tutup", color = p.accent) } }
    )
}
