package com.aurora.tunnel.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.core.Status
import com.aurora.tunnel.core.Tunnel
import com.aurora.tunnel.core.formatBytes
import com.aurora.tunnel.core.formatDuration
import com.aurora.tunnel.core.formatRate
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ping
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onConnect: (String) -> Unit, onDisconnect: () -> Unit, goProfiles: () -> Unit) {
    val p = LocalAurora.current
    val status by Tunnel.status.collectAsState()
    val err by Tunnel.error.collectAsState()
    val since by Tunnel.connectedAt.collectAsState()
    val rx by Tunnel.rxBytes.collectAsState()
    val tx by Tunnel.txBytes.collectAsState()
    val rxRate by Tunnel.rxRate.collectAsState()
    val txRate by Tunnel.txRate.collectAsState()
    val profiles by ProfileStore.profiles.collectAsState()
    val selected = profiles.firstOrNull { it.id == Settings.selectedProfileId } ?: profiles.firstOrNull()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    var ping by remember(selected?.id) { mutableStateOf<Long?>(null) }
    var pinging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val (label, color) = when (status) {
        Status.CONNECTED -> "Terhubung" to p.ok
        Status.CONNECTING -> "Menghubungkan…" to p.warn
        Status.DISCONNECTING -> "Memutuskan…" to p.warn
        Status.ERROR -> "Gagal" to p.err
        Status.IDLE -> "Terputus" to p.sub
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.fillMaxWidth()) { ScreenTitle("Aurora Tunnel", "Tunnel SSH · SlowDNS · Xray") }
        VSpace(18)
        PowerButton(status) {
            when {
                Tunnel.isActive -> onDisconnect()
                selected != null -> onConnect(selected.id)
                else -> goProfiles()
            }
        }
        VSpace(16)
        Text(label, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            if (status == Status.CONNECTED && since > 0) formatDuration(now - since)
            else if (selected == null) "Belum ada profil — buat dulu di tab Profil" else "Ketuk tombol untuk menyambung",
            color = p.sub, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        if (status == Status.ERROR && err != null) {
            VSpace(8)
            Text(err!!, color = p.err, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        VSpace(22)

        AuroraCard(Modifier.fillMaxWidth(), onClick = goProfiles) {
            Text("PROFIL AKTIF", color = p.sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            VSpace(6)
            if (selected == null) {
                Text("Belum ada profil", color = p.text, fontSize = 16.sp)
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
        VSpace(24)
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

@Composable
private fun PowerButton(status: Status, onClick: () -> Unit) {
    val p = LocalAurora.current
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse"
    )
    val on = status == Status.CONNECTED
    val busy = status == Status.CONNECTING || status == Status.DISCONNECTING
    val ring = when {
        on -> p.ok; busy -> p.warn; status == Status.ERROR -> p.err; else -> p.accent
    }
    Box(
        Modifier.size(190.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(190.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            // Cahaya luar
            val glow = if (busy) 0.25f + 0.25f * pulse else if (on) 0.35f else 0.18f
            drawCircle(Brush.radialGradient(listOf(ring.copy(glow), Color.Transparent), c, size.width / 2), size.width / 2, c)
            // Cincin
            drawCircle(
                Brush.linearGradient(listOf(ring, p.accent2)), radius = size.width * 0.36f, center = c,
                style = Stroke(width = 6.dp.toPx())
            )
            // Isi
            drawCircle(
                if (on) Brush.radialGradient(listOf(ring.copy(0.35f), ring.copy(0.08f)), c, size.width * 0.36f)
                else Brush.radialGradient(listOf(Color.White.copy(0.08f), Color.White.copy(0.02f)), c, size.width * 0.36f),
                radius = size.width * 0.34f, center = c
            )
            // Ikon daya
            val r = size.width * 0.13f
            val sw = 6.dp.toPx()
            drawArc(
                color = ring, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r + 3.dp.toPx()), size = Size(r * 2, r * 2),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
            drawLine(ring, Offset(c.x, c.y - r - 6.dp.toPx()), Offset(c.x, c.y - 2.dp.toPx()), sw, StrokeCap.Round)
        }
    }
}
