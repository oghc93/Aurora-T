package com.aurora.tunnel.ui

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

            AuroraCard(Modifier.fillMaxWidth(), onClick = goProfiles) {
                Text("PROFIL AKTIF", color = p.sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                VSpace(6)
                if (selected == null) {
                    Text("Belum ada profil — buat dulu di tab Profil", color = p.text, fontSize = 16.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selected.name, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            val t = Ping.target(selected)
                            Text(t?.let { "${it.first}:${it.second}" } ?: "—", color = p.sub, fontSize = 12.sp)
                        }
                        Pill(selected.mode.label, p.accent2)
                    }
                    VSpace(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when { pinging -> "Mengukur…"; ping == null -> "Ping: —"; ping!! < 0 -> "Ping: timeout"; else -> "Ping: ${ping} ms" },
                            color = if (ping != null && ping!! >= 0) p.ok else p.sub, fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        GhostButton("Tes ping") {
                            val t = Ping.target(selected)
                            if (t != null && !pinging) scope.launch {
                                pinging = true; ping = Ping.tcp(t.first, t.second); pinging = false
                            }
                        }
                    }
                }
            }
            VSpace(14)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Unduh", formatRate(rxRate), formatBytes(rx), p.accent, Modifier.weight(1f))
                StatCard("Unggah", formatRate(txRate), formatBytes(tx), p.accent2, Modifier.weight(1f))
            }
            if (Settings.showProfilesHome.v && profiles.isNotEmpty()) {
                SectionTitle("Profil", Modifier.fillMaxWidth())
                profiles.forEach { pr ->
                    AuroraCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), selected = pr.id == selected?.id,
                        onClick = { if (!Tunnel.isActive) Settings.select(pr.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pr.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(transportLabel(pr), color = p.sub, fontSize = 12.sp)
                            }
                            Pill(protoLabel(pr), p.accent2)
                            if (pr.lb && Settings.loadBalance.v) Pill("LB", p.accent, Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            VSpace(16)
        }
        ConnectPanel(onConnect, onDisconnect, onReconnect)
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
