package com.aurora.tunnel.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.core.Status
import com.aurora.tunnel.core.Tunnel
import com.aurora.tunnel.core.formatDuration
import com.aurora.tunnel.core.formatRate
import com.aurora.tunnel.data.Importer
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ping
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Menu utama bergaya XVEON: tab mode, daftar profil, dan panel Konek di bawah. */
@Composable
fun HomeScreen(
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: (String) -> Unit,
    onEdit: (Profile?) -> Unit
) {
    val p = LocalAurora.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val status by Tunnel.status.collectAsState()
    val err by Tunnel.error.collectAsState()
    val since by Tunnel.connectedAt.collectAsState()
    val rxRate by Tunnel.rxRate.collectAsState()
    val txRate by Tunnel.txRate.collectAsState()
    val profiles by ProfileStore.profiles.collectAsState()

    val selectedId = Settings.selectedProfileId ?: profiles.firstOrNull()?.id
    val selected = profiles.firstOrNull { it.id == selectedId }

    var tab by remember { mutableStateOf(selected?.mode ?: Mode.SSH) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showLink by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var checkText by remember { mutableStateOf("Ketuk untuk tes") }
    var checking by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val shown = profiles.filter { it.mode == tab }
    val active = Tunnel.isActive

    fun pickTab(m: Mode) {
        tab = m
        if (!active && selected?.mode != m) profiles.firstOrNull { it.mode == m }?.let { Settings.select(it.id) }
    }

    fun doImport(text: String) {
        val prof = Importer.fromText(text)
        if (prof == null) msg = "Teks bukan link vmess/vless/trojan/ss atau host:port@user:pass"
        else { ProfileStore.upsert(prof); tab = prof.mode; Settings.select(prof.id); msg = "Diimpor: ${prof.name}" }
    }

    val barColor = if (p.blobs.isEmpty()) p.card else p.card.copy(alpha = if (p.glass) 0.16f else 0.62f)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Koneksi", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf(Mode.SSH, Mode.SLOWDNS, Mode.UDPCUSTOM).forEach { m ->
                    ModeTab(m.label, tab == m, Modifier.weight(1f)) { pickTab(m) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder.copy(0.6f)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf(Mode.OVPN, Mode.XRAY).forEach { m ->
                    ModeTab(m.label, tab == m, Modifier.weight(1f)) { pickTab(m) }
                }
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder.copy(0.6f)))

            Text("Profil ${tab.label} (${shown.size})", color = p.sub, fontSize = 14.sp,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallBtn("LB", Settings.loadBalance.v) { Settings.loadBalance.set(!Settings.loadBalance.v) }
                SmallBtn("Link") { showLink = true }
                SmallBtn("Tempel") { doImport(clip.getText()?.text.orEmpty()) }
                Spacer(Modifier.weight(1f))
                SmallBtn("+ Tambah", primary = true) { onEdit(null) }
            }
            msg?.let { Text(it, color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val sshLike = selected?.mode == Mode.SSH || selected?.mode == Mode.UDPCUSTOM
                val payOn = sshLike && selected?.enablePayload == true
                val proxyHost = selected?.proxyHost.orEmpty()
                val proxyOn = sshLike && proxyHost.isNotBlank()
                AuroraCard(Modifier.weight(1f), onClick = { selected?.let { onEdit(it) } }) {
                    Text("Payload", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (!sshLike) "—" else if (payOn) "ON" else "OFF", color = if (payOn) p.ok else p.sub, fontSize = 13.sp)
                }
                AuroraCard(Modifier.weight(1f), onClick = { selected?.let { onEdit(it) } }) {
                    Text("Proxy", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (proxyOn) "$proxyHost:${selected?.proxyPort}" else if (sshLike) "OFF" else "—",
                        color = if (proxyOn) p.ok else p.sub, fontSize = 13.sp)
                }
            }

            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (shown.isEmpty()) {
                    AuroraCard(Modifier.fillMaxWidth()) {
                        Text("Belum ada profil ${tab.label}. Ketuk “+ Tambah”, atau salin link lalu pilih “Tempel”.",
                            color = p.sub, fontSize = 13.sp)
                    }
                }
                shown.forEach { pr ->
                    AuroraCard(Modifier.fillMaxWidth(), selected = pr.id == selectedId,
                        onClick = { if (!active) Settings.select(pr.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pr.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                val t = Ping.target(pr)
                                Text(transportLabel(pr) + " • " + (t?.let { "${it.first}:${it.second}" } ?: "—"),
                                    color = p.sub, fontSize = 12.sp)
                            }
                            Pill(protoLabel(pr), p.accent2)
                            if (pr.lb) Pill("LB", p.accent, Modifier.padding(start = 4.dp))
                            IconButton({
                                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(pr))
                                ctx.startActivity(Intent.createChooser(i, "Bagikan profil").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }, Modifier.size(34.dp)) { Icon(Icons.Filled.Share, "Bagikan", tint = p.sub) }
                            IconButton({ onEdit(pr) }, Modifier.size(34.dp)) { Icon(Icons.Filled.Edit, "Edit", tint = p.sub) }
                            IconButton({
                                ProfileStore.delete(pr.id)
                                if (Settings.selectedProfileId == pr.id) Settings.select(null)
                            }, Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, "Hapus", tint = p.sub) }
                            Box {
                                IconButton({ menuFor = pr.id }, Modifier.size(34.dp)) { Icon(Icons.Filled.MoreVert, "Lainnya", tint = p.sub) }
                                DropdownMenu(expanded = menuFor == pr.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text(if (pr.lb) "Keluarkan dari LB" else "Tandai LB") },
                                        onClick = { ProfileStore.upsert(pr.copy(lb = !pr.lb)); menuFor = null })
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
            Spacer(Modifier.height(12.dp))
        }

        // ---- Panel bawah ----
        Column(Modifier.fillMaxWidth().background(barColor).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder.copy(0.7f)))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable {
                        val t = selected?.let { Ping.target(it) }
                        if (t == null) checkText = "Pilih profil dulu"
                        else if (!checking) scope.launch {
                            checking = true; checkText = "Mengukur…"
                            val ms = Ping.tcp(t.first, t.second)
                            checkText = if (ms < 0) "Timeout" else "$ms ms"
                            checking = false
                        }
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚡", color = p.accent, fontSize = 18.sp)
                    Text("Cek Koneksi • $checkText", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                }
                Box(Modifier.width(1.dp).height(30.dp).background(p.cardBorder))
                PowerGlyph(
                    if (active) p.ok else p.err,
                    Modifier.clickable {
                        if (active) onDisconnect() else selected?.let { onConnect(it.id) }
                    }.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder.copy(0.7f)))

            val (label, color) = when (status) {
                Status.CONNECTED -> "TERHUBUNG" to p.ok
                Status.CONNECTING -> "MENGHUBUNGKAN" to p.warn
                Status.DISCONNECTING -> "MEMUTUSKAN" to p.warn
                Status.ERROR -> "GAGAL" to p.err
                Status.IDLE -> "TERPUTUS" to p.sub
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(color, RoundedCornerShape(2.dp)))
                        Text(label, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
                    }
                    Text(
                        when {
                            status == Status.CONNECTED && since > 0 ->
                                formatDuration(now - since) + " · ↓ " + formatRate(rxRate) + " ↑ " + formatRate(txRate)
                            status == Status.ERROR && err != null -> err!!
                            selected == null -> "Belum ada profil"
                            else -> "Siap (${selected?.name})"
                        },
                        color = if (status == Status.ERROR) p.err else p.sub, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp), maxLines = 2
                    )
                }
                Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuroraButton(if (active) "PUTUSKAN" else "KONEK", Modifier.fillMaxWidth(),
                        enabled = active || selected != null) {
                        if (active) onDisconnect() else selected?.let { onConnect(it.id) }
                    }
                    GhostButton("KONEK ULANG", Modifier.fillMaxWidth()) { selected?.let { onReconnect(it.id) } }
                }
            }
        }
    }

    if (showLink) InputDialog("Impor link", "", "Tempel link vmess:// vless:// trojan:// ss:// atau host:port@user:pass",
        onOk = { if (it.isNotBlank()) doImport(it) }) { showLink = false }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalAurora.current
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (selected) p.accent else p.sub, fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 16.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) p.accent else Color.Transparent,
            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
    }
}

@Composable
private fun SmallBtn(text: String, on: Boolean = false, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalAurora.current
    val shape = RoundedCornerShape(12.dp)
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = if (primary) Color(0xFF04131A) else if (on) p.accent else p.text,
        modifier = Modifier.clip(shape)
            .background(if (primary) p.accent else p.card, shape)
            .border(1.dp, if (on) p.accent else p.cardBorder, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
private fun PowerGlyph(c: Color, modifier: Modifier) {
    Canvas(modifier.size(26.dp)) {
        val r = 9.dp.toPx()
        val ctr = Offset(size.width / 2, size.height / 2 + 2.dp.toPx())
        val sw = 2.5.dp.toPx()
        drawArc(c, -60f, 300f, false, Offset(ctr.x - r, ctr.y - r), Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(c, Offset(ctr.x, ctr.y - r - 3.dp.toPx()), Offset(ctr.x, ctr.y - 1.dp.toPx()), sw, StrokeCap.Round)
    }
}
