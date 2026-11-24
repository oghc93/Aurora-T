package com.aurora.tunnel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.core.Status
import com.aurora.tunnel.core.Tunnel
import com.aurora.tunnel.core.formatDuration
import com.aurora.tunnel.core.formatRate
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ping
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Panel Konek di bawah layar Beranda: status dan tombol Konek / Putuskan. */
@Composable
fun ConnectPanel(onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    val p = LocalAurora.current
    val status by Tunnel.status.collectAsState()
    val err by Tunnel.error.collectAsState()
    val since by Tunnel.connectedAt.collectAsState()
    val rxRate by Tunnel.rxRate.collectAsState()
    val txRate by Tunnel.txRate.collectAsState()
    val lbNodes by Tunnel.lbNodes.collectAsState()
    val profiles by ProfileStore.profiles.collectAsState()
    val selected = profiles.firstOrNull { it.id == Settings.selectedProfileId } ?: profiles.firstOrNull()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val active = Tunnel.isActive

    val barColor = if (p.blobs.isEmpty()) p.card else p.card.copy(alpha = if (p.glass) 0.16f else 0.62f)

    Column(Modifier.fillMaxWidth().background(barColor)) {
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
                            (if (lbNodes > 0) "Load Balance • $lbNodes node\n" else "") +
                                formatDuration(now - since) + "  ↓ " + formatRate(rxRate) + "  ↑ " + formatRate(txRate)
                        status == Status.ERROR && err != null -> err!!
                        selected == null -> "Belum ada profil"
                        Settings.loadBalance.v && profiles.count { it.lb } >= 1 -> "Siap • Load Balance (${selected?.name} + LB)"
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
            }
        }
    }
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
