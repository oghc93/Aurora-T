package com.aurora.tunnel.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.Settings
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
    var query by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    val shown = all.filter {
        it.level.ordinal >= minLevel.ordinal &&
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
        Field("Cari di log", query, Modifier.padding(top = 4.dp)) { query = it }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GhostButton(if (auto) "Auto-scroll: ON" else "Auto-scroll: OFF") { auto = !auto }
            GhostButton(if (copied) "Tersalin" else "Salin") {
                clip.setText(AnnotatedString(AppLog.export(shown))); copied = true
            }
            GhostButton("Bagikan") {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Log Cocote Tunnel")
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
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { LogRow(it) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Satu baris log bergaya "[23:37:24] pesan"; warna mengikuti level. */
@Composable
private fun LogRow(e: LogEntry) {
    val p = LocalAurora.current
    val c: Color = when (e.level) {
        Level.ERROR -> p.err
        Level.WARN -> p.warn
        Level.SUCCESS -> p.ok
        Level.INFO -> p.text
        Level.DEBUG -> p.sub
    }
    val time = "[${AppLog.time(e)}] "
    if (e.html) {
        Column(Modifier.fillMaxWidth()) {
            Text(time.trim(), color = p.accent2, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            if (Settings.htmlBanner.v) HtmlText(e.msg, p.text)
            else Text(stripHtml(e.msg), color = c, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    } else {
        val rep = if (e.repeat > 1) "  (x${e.repeat})" else ""
        val styled = androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = p.accent2))
            append(time)
            pop()
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c))
            append(AppLog.text(e) + rep)
            pop()
        }
        Text(styled, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    }
}
