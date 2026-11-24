package com.aurora.tunnel.ui

import android.text.Html
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Logo Cocote Tunnel: huruf "C" dari cincin aurora dengan titik terowongan di tengah. */
@Composable
fun CocoteLogo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2, s / 2)
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF0B1030), Color(0xFF2A1B5E)), Offset(0f, 0f), Offset(s, s)),
            cornerRadius = CornerRadius(s * 0.24f)
        )
        val cols = listOf(Color(0xFF2EE6C5), Color(0xFF8B7BFF), Color(0xFFFF4DA6), Color(0xFF2EE6C5))
        drawArc(
            Brush.sweepGradient(cols, c), startAngle = 40f, sweepAngle = 280f, useCenter = false,
            topLeft = Offset(s * 0.2f, s * 0.2f), size = Size(s * 0.6f, s * 0.6f),
            style = Stroke(s * 0.085f, cap = StrokeCap.Round)
        )
        drawArc(
            Brush.sweepGradient(cols.map { it.copy(alpha = 0.6f) }, c), startAngle = 40f, sweepAngle = 280f, useCenter = false,
            topLeft = Offset(s * 0.34f, s * 0.34f), size = Size(s * 0.32f, s * 0.32f),
            style = Stroke(s * 0.05f, cap = StrokeCap.Round)
        )
        drawCircle(Color(0xFF2EE6C5), s * 0.045f, c)
    }
}

/** Satu baris pengaturan: judul + keterangan, opsional checkbox di kanan. */
@Composable
fun SettingItem(
    title: String, subtitle: String? = null,
    checked: Boolean? = null, onCheck: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val p = LocalAurora.current
    val click: (() -> Unit)? = when {
        checked != null && onCheck != null -> ({ onCheck(!checked) })
        else -> onClick
    }
    Column {
        Row(
            Modifier.fillMaxWidth()
                .then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, color = p.sub, fontSize = 12.sp, lineHeight = 16.sp)
            }
            if (checked != null) {
                Checkbox(
                    checked = checked, onCheckedChange = onCheck,
                    colors = CheckboxDefaults.colors(
                        checkedColor = p.accent, checkmarkColor = Color(0xFF04131A),
                        uncheckedColor = p.sub
                    )
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.text.copy(alpha = 0.07f)))
    }
}

@Composable
internal fun dialogColor(): Color {
    val p = LocalAurora.current
    return if (p.glass) Color(0xFF241A4A) else Color(0xFF111833)
}

@Composable
fun ChoiceDialog(
    title: String, options: List<String>, selected: Int,
    onPick: (Int) -> Unit, onDismiss: () -> Unit
) {
    val p = LocalAurora.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = dialogColor(),
        title = { Text(title, color = p.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(i); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = i == selected, onClick = { onPick(i); onDismiss() },
                            colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.sub)
                        )
                        Text(o, color = p.text, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Batal", color = p.sub) } }
    )
}

@Composable
fun InputDialog(
    title: String, initial: String, hint: String? = null, numeric: Boolean = false,
    onOk: (String) -> Unit, onDismiss: () -> Unit
) {
    val p = LocalAurora.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = dialogColor(),
        title = { Text(title, color = p.text) },
        text = {
            Column {
                if (hint != null) Text(hint, color = p.sub, fontSize = 12.sp)
                Field("", text, keyboard = if (numeric) KeyboardType.Number else KeyboardType.Text) { text = it }
            }
        },
        confirmButton = { TextButton({ onOk(text.trim()); onDismiss() }) { Text("OK", color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Batal", color = p.sub) } }
    )
}

/** Teks HTML (banner server) lewat TextView agar <font>, <center>, dsb tampil benar. */
@Composable
fun HtmlText(html: String, color: Color, modifier: Modifier = Modifier) {
    val src = if (html.contains("<br", true)) html else html.replace("\r\n", "\n").replace("\n", "<br>")
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx -> TextView(ctx).apply { setTextColor(color.toArgb()); textSize = 13f } },
        update = { tv -> tv.text = Html.fromHtml(src, Html.FROM_HTML_MODE_LEGACY) }
    )
}

fun stripHtml(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
