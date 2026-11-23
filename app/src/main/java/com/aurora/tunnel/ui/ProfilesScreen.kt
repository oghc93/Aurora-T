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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
    Mode.OVPN -> "OVPN"
}

fun shareText(p: Profile): String = when (p.mode) {
    Mode.XRAY -> if (XrayConfig.isJson(p.xrayLink)) p.xrayLink
        else runCatching { XrayConfig.toLink(XrayConfig.nodeOf(p)) }.getOrDefault("")
    Mode.SSH, Mode.UDPCUSTOM -> "${p.host}:${p.port}@${p.username}:${p.password}"
    else -> p.name
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
            GhostButton("Ping semua") { profiles.forEach { ping(it) } }
            AuroraButton("+ Tambah") { onEdit(null) }
        }
        msg?.let { Text(it, color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }

        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val sshLike = selected?.mode == Mode.SSH || selected?.mode == Mode.UDPCUSTOM
            AuroraCard(Modifier.weight(1f), onClick = { selected?.let { onEdit(it) } }) {
                Text("Payload", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (!sshLike) "—" else if (selected!!.enablePayload) "ON" else "OFF",
                    color = if (sshLike && selected!!.enablePayload) p.ok else p.sub, fontSize = 13.sp)
            }
            AuroraCard(Modifier.weight(1f), onClick = { selected?.let { onEdit(it) } }) {
                Text("Proxy", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val has = sshLike && selected!!.proxyHost.isNotBlank()
                Text(if (has) "${selected!!.proxyHost}:${selected.proxyPort}" else if (sshLike) "OFF" else "—",
                    color = if (has) p.ok else p.sub, fontSize = 13.sp)
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Semua", filter == null) { filter = null }
            Mode.values().forEach { m -> FilterPill(m.label, filter == m) { filter = if (filter == m) null else m } }
        }

        if (shown.isEmpty()) {
            AuroraCard(Modifier.fillMaxWidth()) {
                Text("Belum ada profil. Ketuk “+ Tambah”, atau salin link Xray lalu pilih “Tempel”.",
                    color = p.sub, fontSize = 13.sp)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { pr ->
                AuroraCard(Modifier.fillMaxWidth(), selected = pr.id == selectedId, onClick = { Settings.select(pr.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pr.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            val t = Ping.target(pr)
                            Text(transportLabel(pr) + " • " + (t?.let { "${it.first}:${it.second}" } ?: "—"),
                                color = p.sub, fontSize = 12.sp)
                        }
                        Pill(protoLabel(pr), p.accent2)
                        if (pr.lb) Pill("LB", p.accent, Modifier.padding(start = 6.dp))
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ms = pings[pr.id]
                        Text(
                            when { ms == null -> "Ping —"; ms < 0 -> "Timeout"; else -> "$ms ms" },
                            color = when { ms == null -> p.sub; ms < 0 -> p.err; ms < 300 -> p.ok; else -> p.warn },
                            fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        IconButton({
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(pr))
                            ctx.startActivity(Intent.createChooser(i, "Bagikan profil").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, Modifier.size(38.dp)) { Icon(Icons.Filled.Share, "Bagikan", tint = p.sub) }
                        IconButton({ onEdit(pr) }, Modifier.size(38.dp)) { Icon(Icons.Filled.Edit, "Edit", tint = p.sub) }
                        IconButton({
                            ProfileStore.delete(pr.id)
                            if (Settings.selectedProfileId == pr.id) Settings.select(null)
                        }, Modifier.size(38.dp)) { Icon(Icons.Filled.Delete, "Hapus", tint = p.err) }
                        Box {
                            IconButton({ menuFor = pr.id }, Modifier.size(38.dp)) { Icon(Icons.Filled.MoreVert, "Lainnya", tint = p.sub) }
                            DropdownMenu(expanded = menuFor == pr.id, onDismissRequest = { menuFor = null }) {
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
