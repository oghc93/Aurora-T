package com.aurora.tunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.Importer
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ping
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(onEdit: (Profile?) -> Unit) {
    val p = LocalAurora.current
    val profiles by ProfileStore.profiles.collectAsState()
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val pings = remember { mutableStateMapOf<String, Long>() }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val selectedId = Settings.selectedProfileId ?: profiles.firstOrNull()?.id

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle("Profil", "${profiles.size} profil tersimpan")
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuroraButton("+ Baru") { onEdit(null) }
            GhostButton("Impor clipboard") {
                val text = clip.getText()?.text.orEmpty()
                val prof = Importer.fromText(text)
                if (prof == null) importMsg = "Clipboard tidak berisi link vmess/vless/trojan/ss atau host:port@user:pass"
                else { ProfileStore.upsert(prof); importMsg = "Diimpor: ${prof.name}" }
            }
            GhostButton("Ping semua") {
                profiles.forEach { pr ->
                    val t = Ping.target(pr)
                    if (t != null) scope.launch { pings[pr.id] = Ping.tcp(t.first, t.second) }
                }
            }
        }
        importMsg?.let { Text(it, color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }

        if (profiles.isEmpty()) {
            AuroraCard(Modifier.fillMaxWidth()) {
                Text("Belum ada profil. Ketuk “+ Baru” atau salin link Xray lalu pilih “Impor clipboard”.",
                    color = p.sub, fontSize = 13.sp)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(profiles, key = { it.id }) { pr ->
                AuroraCard(Modifier.fillMaxWidth(), selected = pr.id == selectedId, onClick = { Settings.select(pr.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pr.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            val t = Ping.target(pr)
                            Text(t?.let { "${it.first}:${it.second}" } ?: "—", color = p.sub, fontSize = 12.sp)
                        }
                        Pill(pr.mode.label, p.accent2)
                    }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ms = pings[pr.id]
                        Text(
                            when { ms == null -> "Ping —"; ms < 0 -> "Timeout"; else -> "$ms ms" },
                            color = when { ms == null -> p.sub; ms < 0 -> p.err; ms < 300 -> p.ok; else -> p.warn },
                            fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        GhostButton("Edit") { onEdit(pr) }
                        GhostButton("Hapus", color = p.err) {
                            ProfileStore.delete(pr.id)
                            if (Settings.selectedProfileId == pr.id) Settings.select(null)
                        }
                    }
                }
            }
        }
    }
}
