package com.aurora.tunnel.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.log.AppLog
import com.aurora.tunnel.log.Level
import com.aurora.tunnel.log.LogEntry

@Composable
fun LogScreen() {
    val p = LocalAurora.current
    val all by AppLog.entries.collectAsState()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current

    var minLevel by remember { mutableStateOf(Level.DEBUG) }
    var tag by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    val tags = all.map { it.tag }.distinct()
    val shown = all.filter {
        it.level.ordinal >= minLevel.ordinal &&
            (tag == null || it.tag == tag) &&
            (query.isBlank() || it.msg.contains(query, true) || it.tag.contains(query, true))
    }
    val errors = all.count { it.level == Level.ERROR }
    val warns = all.count { it.level == Level.WARN }

    val state = rememberLazyListState()
    LaunchedEffect(shown.lastOrNull()?.id, shown.lastOrNull()?.repeat, auto) {
        if (auto && shown.isNotEmpty()) state.scrollToItem(shown.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle("Log", "${all.size} baris · $errors error · $warns peringatan")

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Semua", minLevel == Level.DEBUG) { minLevel = Level.DEBUG }
            FilterPill("Info", minLevel == Level.INFO) { minLevel = Level.INFO }
            FilterPill("Peringatan", minLevel == Level.WARN) { minLevel = Level.WARN }
            FilterPill("Error", minLevel == Level.ERROR) { minLevel = Level.ERROR }
        }
        if (tags.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("Semua modul", tag == null) { tag = null }
                tags.forEach { t -> FilterPill(t, tag == t) { tag = if (tag == t) null else t } }
            }
        }
        Field("Cari di log", query, Modifier.padding(top = 6.dp)) { query = it }

        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (auto) "Auto-scroll: ON" else "Auto-scroll: OFF") { auto = !auto }
            GhostButton(if (copied) "Tersalin" else "Salin") {
                clip.setText(AnnotatedString(AppLog.export(shown))); copied = true
            }
            GhostButton("Bagikan") {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Log Aurora Tunnel")
                    .putExtra(Intent.EXTRA_TEXT, AppLog.export(shown))
                ctx.startActivity(Intent.createChooser(i, "Bagikan log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            GhostButton("Hapus", color = p.err) { AppLog.clear(); copied = false }
        }

        AuroraCard(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Belum ada log", color = p.sub, fontSize = 13.sp)
                }
            } else {
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.id }) { LogRow(it) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val p = LocalAurora.current
    val c: Color = when (e.level) {
        Level.ERROR -> p.err
        Level.WARN -> p.warn
        Level.INFO -> p.accent
        Level.DEBUG -> p.sub
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c))
        Column(Modifier.padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppLog.time(e), color = p.sub, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(e.tag, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (e.repeat > 1) Pill("×${e.repeat}", c)
            }
            Text(
                e.msg, color = if (e.level == Level.ERROR) p.err else p.text,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp
            )
        }
    }
}
