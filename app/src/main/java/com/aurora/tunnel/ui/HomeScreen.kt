package com.aurora.tunnel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.core.Tunnel
import com.aurora.tunnel.core.formatBytes
import com.aurora.tunnel.core.formatRate
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ping
import kotlinx.coroutines.launch

/** Beranda: logo, profil aktif, statistik, daftar profil; tombol Konek ada di panel bawah. */
@Composable
fun HomeScreen(
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: (String) -> Unit,
    goProfiles: () -> Unit
) {
    val p = LocalAurora.current
    val rx by Tunnel.rxBytes.collectAsState()
    val tx by Tunnel.txBytes.collectAsState()
    val rxRate by Tunnel.rxRate.collectAsState()
    val txRate by Tunnel.txRate.collectAsState()
    val profiles by ProfileStore.profiles.collectAsState()
    val selected = profiles.firstOrNull { it.id == Settings.selectedProfileId } ?: profiles.firstOrNull()

    var ping by remember(selected?.id) { mutableStateOf<Long?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            VSpace(8)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CocoteLogo(46.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Cocote Tunnel", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("SSH · SlowDNS · UDP · Xray", color = p.sub, fontSize = 12.sp)
                }
            }
            VSpace(18)

            AuroraCard(Modifier.fillMaxWidth()) {
                Text("Profil", color = p.sub, fontSize = 13.sp)
                VSpace(8)
                Box {
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape)
                            .background(p.text.copy(alpha = 0.06f))
                            .border(1.dp, p.cardBorder, shape)
                            .clickable {
                                if (profiles.isEmpty()) goProfiles() else if (!Tunnel.isActive) expanded = true
                            }
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            selected?.let { it.name + " (" + protoLabel(it) + ")" } ?: "Belum ada profil — ketuk untuk membuat",
                            color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f), maxLines = 1
                        )
                        Chevron(if (Tunnel.isActive) p.sub else p.text)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        profiles.forEach { pr ->
                            DropdownMenuItem(
                                text = { Text(pr.name + " (" + protoLabel(pr) + ")") },
                                onClick = { Settings.select(pr.id); expanded = false }
                            )
                        }
                    }
                }
                if (Tunnel.isActive) {
                    VSpace(6)
                    Text("Putuskan koneksi dulu untuk mengganti server", color = p.accent, fontSize = 12.sp)
                }
            }
            VSpace(12)
            AuroraCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when { pinging -> "Mengukur…"; selected == null -> "Ping: —"; ping == null -> "Ping: —"; ping!! < 0 -> "Ping: timeout"; else -> "Ping: ${ping} ms" },
                            color = if (ping != null && ping!! >= 0) p.ok else p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium
                        )
                        val t = selected?.let { Ping.target(it) }
                        Text(t?.let { "${it.first}:${it.second}" } ?: "—", color = p.sub, fontSize = 12.sp)
                    }
                    GhostButton("Tes ping") {
                        val t = selected?.let { Ping.target(it) }
                        if (t != null && !pinging) scope.launch {
                            pinging = true; ping = Ping.tcp(t.first, t.second); pinging = false
                        }
                    }
                }
            }
            VSpace(14)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Unduh", formatRate(rxRate), formatBytes(rx), p.accent, Modifier.weight(1f))
                StatCard("Unggah", formatRate(txRate), formatBytes(tx), p.accent2, Modifier.weight(1f))
            }
            VSpace(16)
        }
        ConnectPanel(onConnect, onDisconnect)
    }

}

@Composable
private fun Chevron(c: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val sw = 2.5.dp.toPx()
        drawLine(c, Offset(w * 0.2f, h * 0.38f), Offset(w * 0.5f, h * 0.66f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.5f, h * 0.66f), Offset(w * 0.8f, h * 0.38f), sw, StrokeCap.Round)
    }
}

@Composable
private fun StatCard(title: String, rate: String, total: String, c: Color, modifier: Modifier) {
    val p = LocalAurora.current
    AuroraCard(modifier) {
        Text(title.uppercase(), color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        VSpace(4)
        Text(rate, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Total $total", color = p.sub, fontSize = 12.sp)
    }
}
