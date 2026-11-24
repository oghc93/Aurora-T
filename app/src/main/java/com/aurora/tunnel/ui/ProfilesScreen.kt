package com.aurora.tunnel.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.Importer
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ping
import com.aurora.tunnel.engine.Rotate
import com.aurora.tunnel.engine.XrayConfig
import kotlinx.coroutines.launch
import java.util.UUID

fun protoLabel(p: Profile): String =
    if (p.mode == Mode.XRAY) (runCatching { XrayConfig.nodeOf(p).protocol }.getOrDefault("xray")).uppercase()
    else p.mode.label.uppercase()

fun transportLabel(p: Profile): String = when (p.mode) {
    Mode.XRAY -> runCatching {
        val n = XrayConfig.nodeOf(p)
        n.network.uppercase() + if (n.tls != "none") "+" + n.tls.uppercase() else ""
    }.getOrDefault("Xray")
    Mode.SSH, Mode.UDPCUSTOM -> buildList {
        add("SSH")
        if (p.enableSni) add("TLS")
        if (p.enablePayload) add("PAYLOAD")
        if (p.proxyHost.isNotBlank()) add("PROXY")
    }.joinToString("+")
    Mode.SLOWDNS -> "DNSTT+SSH"
    Mode.OVPN -> buildString {
        append(com.aurora.tunnel.engine.Ovpn.proto(p.ovpnConfig).uppercase())
        if (p.enableSni) append("+TLS")
        if (p.enablePayload) append("+PAYLOAD")
        if (p.proxyHost.isNotBlank()) append("+PROXY")
    }
}

fun shareText(p: Profile): String = when (p.mode) {
    Mode.XRAY -> if (XrayConfig.isJson(p.xrayLink)) p.xrayLink
        else runCatching { XrayConfig.toLink(XrayConfig.nodeOf(p)) }.getOrDefault("")
    Mode.SSH, Mode.UDPCUSTOM -> "${p.host}:${p.port}@${p.username}:${p.password}"
    Mode.OVPN -> p.ovpnConfig.ifBlank { p.name }
    Mode.SLOWDNS -> "ns=${p.dnsNs}\npubkey=${p.dnsPubkey}\nuser=${p.username}:${p.password}"
}

@Composable
fun ProfilesScreen(onEdit: (Profile?) -> Unit) {
    val p = LocalAurora.current
    val ctx = LocalContext.current
    val profiles by ProfileStore.profiles.collectAsState()
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val pings = remember { mutableStateMapOf<String, Long>() }
    var msg by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<Mode?>(null) }
    var showLink by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val selectedId = Settings.selectedProfileId ?: profiles.firstOrNull()?.id
    val selected = profiles.firstOrNull { it.id == selectedId }
    val shown = profiles.filter { filter == null || it.mode == filter }
    val lbCount = profiles.count { it.lb }

    fun doImport(text: String) {
        val prof = Importer.fromText(text)
        if (prof == null) msg = "Teks bukan link vmess/vless/trojan/ss atau host:port@user:pass"
        else { ProfileStore.upsert(prof); msg = "Diimpor: ${prof.name}" }
    }

    val cr = ctx.contentResolver
    val importFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            msg = runCatching {
                val text = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val name = cr.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }.orEmpty()
                val list = com.aurora.tunnel.data.CctFile.decode(text)
                if (list != null) {
                    list.forEach { ProfileStore.upsert(it) }
                    "Diimpor: ${list.size} profil dari $name"
                } else if (Regex("(?m)^\\s*remote\\s+\\S+\\s+\\d+").containsMatchIn(text)) {
                    val ep = com.aurora.tunnel.engine.Ovpn.endpoint(text)
                    val pr = Profile(name = name.substringBeforeLast('.').ifBlank { "OpenVPN" }, mode = Mode.OVPN,
                        host = ep?.first.orEmpty(), port = ep?.second ?: 1194, ovpnConfig = text)
                    ProfileStore.upsert(pr)
                    "Diimpor: ${pr.name} (OpenVPN)"
                } else {
                    val prof = Importer.fromText(text)
                    if (prof != null) { ProfileStore.upsert(prof); "Diimpor: ${prof.name}" }
                    else "File tidak dikenali (butuh .cct, .ovpn, atau teks link)"
                }
            }.getOrElse { "Gagal membaca file: ${it.message}" }
        }
    }
    val exportAll = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            msg = runCatching {
                cr.openOutputStream(uri)?.use { it.write(com.aurora.tunnel.data.CctFile.encode(profiles).toByteArray()) }
                "Tersimpan: ${profiles.size} profil"
            }.getOrElse { "Gagal menyimpan: ${it.message}" }
        }
    }

    fun ping(pr: Profile) {
        val t = Ping.target(pr) ?: return
        scope.launch { pings[pr.id] = Ping.tcp(t.first, t.second) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle(
            "Profil",
            "${filter?.label ?: "Semua"} (${shown.size}) • LB ${if (Settings.loadBalance.v) "Mix $lbCount" else "mati"}"
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            FilterPill("LB", Settings.loadBalance.v) { Settings.loadBalance.set(!Settings.loadBalance.v) }
            GhostButton("Link") { showLink = true }
            GhostButton("Tempel") { doImport(clip.getText()?.text.orEmpty()) }
            GhostButton("Impor file") { importFile.launch(arrayOf("*/*")) }
            GhostButton("Ekspor semua") {
                if (profiles.isEmpty()) msg = "Belum ada profil" else exportAll.launch("cocote-profiles.cct")
            }
            GhostButton("Ping semua") { profiles.forEach { ping(it) } }
            AuroraButton("+ Tambah") { onEdit(null) }
        }
        msg?.let { Text(it, color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }

        val sshLike = selected?.mode == Mode.SSH || selected?.mode == Mode.UDPCUSTOM
        val wrapLike = sshLike || selected?.mode == Mode.OVPN
        val payOn = wrapLike && selected?.enablePayload == true
        val proxyHost = selected?.proxyHost.orEmpty()
        val proxyOn = wrapLike && proxyHost.isNotBlank()
        val gwOn = sshLike && selected?.enableUdpgw == true
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuroraCard(Modifier.weight(1f), onClick = { selected?.let { onEdit(it) } }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), shapeRadius = 14.dp) {
                Text("Payload", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (!wrapLike) "—" else if (payOn) "ON" else "OFF", color = if (payOn) p.ok else p.sub, fontSize = 13.sp)
            }
            AuroraCard(Modifier.weight(1f), onClick = { selected?.let { onEdit(it) } }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), shapeRadius = 14.dp) {
                Text("Proxy", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (proxyOn) "$proxyHost:${selected?.proxyPort}" else if (wrapLike) "OFF" else "—",
                    color = if (proxyOn) p.ok else p.sub, fontSize = 13.sp)
            }
            AuroraCard(Modifier.weight(1f), onClick = { selected?.let { onEdit(it) } }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), shapeRadius = 14.dp) {
                Text("BadVPN", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (!sshLike) "—" else if (gwOn) "${selected?.udpgwPort}" else "OFF",
                    color = if (gwOn) p.ok else p.sub, fontSize = 13.sp)
            }
        }


        if (shown.isEmpty()) {
            AuroraCard(Modifier.fillMaxWidth()) {
                Text("Belum ada profil. Ketuk “+ Tambah”, atau salin link Xray lalu pilih “Tempel”.",
                    color = p.sub, fontSize = 13.sp)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { pr ->
                AuroraCard(
                    Modifier.fillMaxWidth(), selected = pr.id == selectedId, onClick = { Settings.select(pr.id) },
                    contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), shapeRadius = 16.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(pr.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                MiniChip(protoLabel(pr), false, p.accent2, null)
                                MiniChip("LB", pr.lb, p.accent) { ProfileStore.upsert(pr.copy(lb = !pr.lb)) }
                            }
                            val t = Ping.target(pr)
                            val ms = pings[pr.id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(transportLabel(pr) + " • " + (t?.let { "${it.first}:${it.second}" } ?: "—"),
                                    color = p.sub, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                Text(
                                    "  " + when { ms == null -> "—"; ms < 0 -> "Timeout"; else -> "$ms ms" },
                                    color = when { ms == null -> p.sub; ms < 0 -> p.err; ms < 300 -> p.ok; else -> p.warn },
                                    fontSize = 11.sp, maxLines = 1
                                )
                            }
                        }
                        IconButton({ onEdit(pr) }, Modifier.size(34.dp)) { Icon(Icons.Filled.Edit, "Edit", tint = p.sub, modifier = Modifier.size(19.dp)) }
                        IconButton({
                            ProfileStore.delete(pr.id)
                            if (Settings.selectedProfileId == pr.id) Settings.select(null)
                        }, Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, "Hapus", tint = p.err, modifier = Modifier.size(19.dp)) }
                        Box {
                            IconButton({ menuFor = pr.id }, Modifier.size(34.dp)) { Icon(Icons.Filled.MoreVert, "Lainnya", tint = p.sub, modifier = Modifier.size(19.dp)) }
                            DropdownMenu(expanded = menuFor == pr.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("Bagikan") }, onClick = {
                                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(pr))
                                    ctx.startActivity(Intent.createChooser(i, "Bagikan profil").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    menuFor = null
                                })
                                DropdownMenuItem(
                                    text = { Text(if (pr.lb) "Keluarkan dari LB" else "Tandai LB") },
                                    onClick = { ProfileStore.upsert(pr.copy(lb = !pr.lb)); menuFor = null })
                                DropdownMenuItem(text = { Text("Tes ping") }, onClick = { ping(pr); menuFor = null })
                                DropdownMenuItem(text = { Text("Duplikat") }, onClick = {
                                    ProfileStore.upsert(pr.copy(id = UUID.randomUUID().toString(), name = pr.name + " (salinan)"))
                                    menuFor = null
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLink) InputDialog("Impor link", "", "Tempel link vmess:// vless:// trojan:// ss:// atau host:port@user:pass",
        onOk = { if (it.isNotBlank()) doImport(it) }) { showLink = false }
}


/** Label kecil di samping nama profil (protokol / tanda LB). */
@Composable
private fun MiniChip(text: String, on: Boolean, c: Color, onClick: (() -> Unit)?) {
    val p = LocalAurora.current
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier.padding(start = 6.dp).clip(shape)
        .background(if (on) c.copy(alpha = 0.25f) else c.copy(alpha = 0.10f))
        .border(1.dp, if (on) c else p.cardBorder, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Text(text, color = if (on) c else if (onClick == null) c else p.sub, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = m.padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1)
}
