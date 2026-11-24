package com.aurora.tunnel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.tunnel.data.CctFile
import com.aurora.tunnel.data.Locks
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings

private class Sec(val title: String, val modes: Set<Mode>, val items: List<Pair<String, String>>)

private val SECTIONS = listOf(
    Sec("Umum", setOf(Mode.SSH, Mode.UDPCUSTOM, Mode.OVPN), listOf(
        Locks.PAYLOAD to "Payload",
        Locks.PROXY to "Remote Proxy",
        Locks.SNI to "Indikasi Nama Server (SNI)"
    )),
    Sec("Secure Shell (SSH)", setOf(Mode.SSH, Mode.UDPCUSTOM), listOf(
        Locks.SSH_HOST to "Host dan Port SSH",
        Locks.SSH_AUTH to "Pengguna dan Sandi SSH"
    )),
    Sec("SlowDNS (dnstt)", setOf(Mode.SLOWDNS), listOf(
        Locks.DNS to "NS Domain, Public Key, dan DNS",
        Locks.DNS_AUTH to "Pengguna dan Sandi SSH"
    )),
    Sec("OpenVPN", setOf(Mode.OVPN), listOf(
        Locks.OVPN_CFG to "Config OpenVPN (.ovpn)",
        Locks.OVPN_AUTH to "Pengguna dan Sandi OpenVPN"
    )),
    Sec("Xray (V2Ray)", setOf(Mode.XRAY), listOf(
        Locks.XRAY_SRV to "Server dan Port",
        Locks.XRAY_AUTH to "ID / Sandi",
        Locks.XRAY_EDIT to "Config JSON kustom"
    ))
)

/** Halaman Ekspor Config: pilih protokol, kunci, dan field yang dikunci, lalu simpan sebagai file .cct. */
@Composable
fun ExportScreen(onClose: () -> Unit) {
    val p = LocalAurora.current
    val ctx = LocalContext.current
    val profiles by ProfileStore.profiles.collectAsState()
    val selected = profiles.firstOrNull { it.id == Settings.selectedProfileId } ?: profiles.firstOrNull()

    var fileName by remember { mutableStateOf("config") }
    val proto = remember { mutableStateMapOf<Mode, Boolean>().also { m -> Mode.values().forEach { m[it] = true } } }
    var onlySelected by remember { mutableStateOf(false) }
    var lockMaster by remember { mutableStateOf(false) }
    val locks = remember { mutableStateListOf<String>() }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    var info by remember { mutableStateOf("") }

    val allKeys = SECTIONS.flatMap { s -> s.items.map { it.first } }
    val allLocked = allKeys.all { it in locks }

    fun pool() = profiles.filter {
        proto[it.mode] == true && (!onlySelected || it.id == selected?.id)
    }
    val exportable = pool().filter { !it.locked }
    val skipped = pool().size - exportable.size

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            info = runCatching {
                val protect = lockMaster || locks.isNotEmpty()
                val list = exportable.map { it.copy(locked = lockMaster, locks = locks.joinToString(",")) }
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(CctFile.encode(list, protect).toByteArray()) }
                "Tersimpan: ${list.size} profil" + if (protect) " (terkunci)" else ""
            }.getOrElse { "Gagal menyimpan: ${it.message}" }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(p)
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("←", color = p.text, fontSize = 24.sp, modifier = Modifier.clickable { onClose() }.padding(end = 14.dp, top = 4.dp, bottom = 4.dp))
                    Text("Ekspor Config", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Field("Nama file config", fileName) { fileName = it }
                    Text("Disimpan sebagai ${CctFile.fileName(fileName)}", color = p.sub, fontSize = 11.sp)

                    SectionTitle("Ekspor Config")
                    AuroraCard(Modifier.fillMaxWidth()) {
                        Mode.values().forEach { m ->
                            CheckRow(m.label, proto[m] == true, true) { proto[m] = it }
                        }
                        if (selected != null) {
                            CheckRow("Hanya profil terpilih (${selected.name})", onlySelected, true) { onlySelected = it }
                        }
                    }
                    Text(
                        "${exportable.size} profil akan diekspor" +
                            if (skipped > 0) " • $skipped profil terkunci dilewati (config terkunci tidak bisa diekspor ulang)" else "",
                        color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                    )

                    SectionTitle("Keamanan / Penguncian")
                    AuroraCard(Modifier.fillMaxWidth()) {
                        CheckRow("Kunci config dan cegah pengeditan", lockMaster, true) { lockMaster = it }
                        CheckRow("Kunci semua pilihan di bawah ini", allLocked, true) { on ->
                            locks.clear(); if (on) locks.addAll(allKeys)
                        }
                        Text(
                            "Field yang dikunci disembunyikan dan tidak bisa dilihat atau diubah di aplikasi penerima, tetapi tetap dipakai saat konek. " +
                                "File yang memakai kunci disimpan terenkripsi.",
                            color = p.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    SECTIONS.filter { s -> s.modes.any { proto[it] == true } }.forEach { s ->
                        val isOpen = open[s.title] != false
                        VSpace(10)
                        AuroraCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { open[s.title] = !isOpen }, verticalAlignment = Alignment.CenterVertically) {
                                Text(s.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(if (isOpen) "▴" else "▾", color = p.sub, fontSize = 16.sp)
                            }
                            if (isOpen) s.items.forEach { (k, label) ->
                                CheckRow(label, k in locks, true) { on -> if (on) { if (k !in locks) locks.add(k) } else locks.remove(k) }
                            }
                        }
                    }
                    VSpace(20)
                }
                if (info.isNotEmpty()) Text(info, color = p.ok, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AuroraButton("Ekspor", Modifier.weight(1f), enabled = exportable.isNotEmpty()) {
                        saver.launch(CctFile.fileName(fileName))
                    }
                    GhostButton("Tutup", Modifier.weight(0.6f)) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalAurora.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked, onCheckedChange = { onChange(it) }, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.sub)
        )
        Text(label, color = p.text, fontSize = 14.sp)
    }
}
