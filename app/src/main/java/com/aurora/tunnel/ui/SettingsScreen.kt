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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.data.ThemeId
import com.aurora.tunnel.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val p = LocalAurora.current
    var showApps by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Pengaturan")

        SectionTitle("Tema")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeId.values().forEach { t ->
                ThemePreview(t, Settings.theme == t, Modifier.weight(1f)) { Settings.applyTheme(t) }
            }
        }

        SectionTitle("Koneksi")
        AuroraCard(Modifier.fillMaxWidth()) {
            ToggleRow("Sambung ulang otomatis", Settings.autoReconnect, "Coba lagi hingga 5 kali bila putus") {
                Settings.applyAutoReconnect(it)
            }
            ToggleRow("Log debug", Settings.debugLog, "Tampilkan detail teknis di tab Log") {
                Settings.applyDebugLog(it); AppLog.debugEnabled = it
            }
        }

        SectionTitle("Split tunnel")
        AuroraCard(Modifier.fillMaxWidth(), onClick = { showApps = true }) {
            Text("Aplikasi yang lewat tunnel", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (Settings.allowedApps.isEmpty()) "Semua aplikasi (default)" else "${Settings.allowedApps.size} aplikasi dipilih",
                color = p.sub, fontSize = 12.sp
            )
        }

        SectionTitle("Tentang")
        AuroraCard(Modifier.fillMaxWidth()) {
            Text("Aurora Tunnel 1.0.0", color = p.text, fontWeight = FontWeight.SemiBold)
            Text("SSH (payload/SNI/proxy), SlowDNS, UDP Custom (udpgw), Xray. OpenVPN menyusul.",
                color = p.sub, fontSize = 12.sp)
        }
        Box(Modifier.height(24.dp))
    }

    if (showApps) AppPicker { showApps = false }
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
        containerColor = if (p.glass) androidx.compose.ui.graphics.Color(0xFF241A4A) else p.card,
        title = { Text("Aplikasi lewat tunnel", color = p.text) },
        text = {
            val list = apps
            if (list == null) Text("Memuat…", color = p.sub)
            else Column {
                Text("Kosongkan semua centang untuk memakai tunnel di seluruh aplikasi.", color = p.sub, fontSize = 12.sp)
                LazyColumn(Modifier.height(360.dp)) {
                    items(list, key = { it.first }) { (pkg, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            chosen = if (pkg in chosen) chosen - pkg else chosen + pkg
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(pkg in chosen, { chosen = if (it) chosen + pkg else chosen - pkg },
                                colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = androidx.compose.ui.graphics.Color(0xFF04131A)))
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
