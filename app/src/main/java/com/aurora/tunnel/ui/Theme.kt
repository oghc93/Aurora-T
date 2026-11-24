package com.aurora.tunnel.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.ThemeId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class AuroraPalette(
    val id: ThemeId,
    val bgTop: Color,
    val bgBottom: Color,
    val blobs: List<Color>,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val sub: Color,
    val accent: Color,
    val accent2: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val glass: Boolean
)

val NightPalette = AuroraPalette(
    id = ThemeId.NIGHT,
    bgTop = Color(0xFF050816), bgBottom = Color(0xFF0B1030),
    blobs = listOf(Color(0x4D00E5B0), Color(0x557C4DFF), Color(0x33FF4DA6)),
    card = Color(0xB3111833), cardBorder = Color(0x99212C5A),
    text = Color(0xFFEAF0FF), sub = Color(0xFF8FA0C8),
    accent = Color(0xFF2EE6C5), accent2 = Color(0xFF8B7BFF),
    ok = Color(0xFF39E58C), warn = Color(0xFFFFC14D), err = Color(0xFFFF5C7A),
    glass = false
)

val GlassPalette = AuroraPalette(
    id = ThemeId.GLASS,
    bgTop = Color(0xFF0E2B52), bgBottom = Color(0xFF3A1D6E),
    blobs = listOf(Color(0x9900F0C8), Color(0x99A06BFF), Color(0x88FF6BC1)),
    card = Color(0x1FFFFFFF), cardBorder = Color(0x55FFFFFF),
    text = Color(0xFFFFFFFF), sub = Color(0xFFD0DBFF),
    accent = Color(0xFF7CFFE1), accent2 = Color(0xFFC3A8FF),
    ok = Color(0xFF6BFFB0), warn = Color(0xFFFFD66B), err = Color(0xFFFF8CA3),
    glass = true
)

/** Hitam pekat (hemat baterai di layar AMOLED), tanpa efek aurora. */
val DarkPalette = AuroraPalette(
    id = ThemeId.DARK,
    bgTop = Color(0xFF000000), bgBottom = Color(0xFF000000),
    blobs = emptyList(),
    card = Color(0xFF0E0E10), cardBorder = Color(0xFF222228),
    text = Color(0xFFF2F2F2), sub = Color(0xFF8A8A93),
    accent = Color(0xFF2EE6C5), accent2 = Color(0xFF8B7BFF),
    ok = Color(0xFF39E58C), warn = Color(0xFFFFC14D), err = Color(0xFFFF5C7A),
    glass = false
)

/** Tampilan standar: abu-gelap datar dengan aksen lavender. */
val StandardPalette = AuroraPalette(
    id = ThemeId.STANDARD,
    bgTop = Color(0xFF121318), bgBottom = Color(0xFF121318),
    blobs = emptyList(),
    card = Color(0xFF1E1F26), cardBorder = Color(0xFF2C2D36),
    text = Color(0xFFE3E2E9), sub = Color(0xFF9A9CAA),
    accent = Color(0xFFBFC2FF), accent2 = Color(0xFF8B90E8),
    ok = Color(0xFF66D68C), warn = Color(0xFFFFB74D), err = Color(0xFFFF6B6B),
    glass = false
)

val LocalAurora = staticCompositionLocalOf { NightPalette }

fun paletteFor(id: ThemeId) = when (id) {
    ThemeId.GLASS -> GlassPalette
    ThemeId.DARK -> DarkPalette
    ThemeId.STANDARD -> StandardPalette
    else -> NightPalette
}

@Composable
fun AuroraTheme(themeId: ThemeId, content: @Composable () -> Unit) {
    val p = paletteFor(themeId)
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = Color(0xFF04131A),
        secondary = p.accent2, background = p.bgBottom, surface = p.card,
        onBackground = p.text, onSurface = p.text, onSurfaceVariant = p.sub,
        outline = p.cardBorder
    )
    CompositionLocalProvider(LocalAurora provides p) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize()) {
                AuroraBackground(p)
                content()
            }
        }
    }
}

@Composable
fun AuroraBackground(p: AuroraPalette) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
        val a = phase * 2f * PI.toFloat()
        p.blobs.forEachIndexed { i, c ->
            val cx = size.width * (0.5f + 0.4f * sin(a + i * 2.1f))
            val cy = size.height * (0.18f + 0.26f * i) + size.height * 0.05f * cos(a * 1.3f + i)
            val r = size.width * (0.95f - 0.1f * i)
            drawCircle(
                brush = Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy)
            )
        }
    }
}

@Composable
fun AuroraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(16.dp),
    shapeRadius: androidx.compose.ui.unit.Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalAurora.current
    val shape = RoundedCornerShape(shapeRadius)
    val fill = if (p.glass)
        Modifier.background(Brush.linearGradient(listOf(Color.White.copy(0.20f), Color.White.copy(0.06f))), shape)
    else Modifier.background(p.card, shape)
    val border: Brush = when {
        selected -> Brush.linearGradient(listOf(p.accent, p.accent2))
        p.glass -> Brush.linearGradient(listOf(Color.White.copy(0.6f), Color.White.copy(0.08f)))
        else -> SolidColor(p.cardBorder)
    }
    Column(
        modifier.clip(shape).then(fill)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val p = LocalAurora.current
    Text(text.uppercase(), color = p.sub, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp, modifier = modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
fun AuroraButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalAurora.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape)
            .background(Brush.horizontalGradient(
                if (enabled) listOf(p.accent, p.accent2) else listOf(p.sub.copy(0.3f), p.sub.copy(0.3f))), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color(0xFF04131A), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color? = null, onClick: () -> Unit) {
    val p = LocalAurora.current
    val c = color ?: p.accent
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape).border(1.dp, c.copy(alpha = 0.6f), shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = c, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
}

@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalAurora.current
    val shape = RoundedCornerShape(50)
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (selected) Color(0xFF04131A) else p.text,
        modifier = Modifier.clip(shape)
            .background(if (selected) p.accent else p.text.copy(alpha = 0.08f), shape)
            .border(1.dp, if (selected) p.accent else p.text.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    val p = LocalAurora.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.text, fontSize = 14.sp)
            if (hint != null) Text(hint, color = p.sub, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04131A), checkedTrackColor = p.accent,
                uncheckedThumbColor = p.sub, uncheckedTrackColor = p.text.copy(0.1f),
                uncheckedBorderColor = p.text.copy(0.25f)
            )
        )
    }
}

@Composable
fun Field(
    label: String, value: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false,
    singleLine: Boolean = true, minLines: Int = 1, enabled: Boolean = true, onChange: (String) -> Unit
) {
    val p = LocalAurora.current
    OutlinedTextField(
        enabled = enabled,
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 12.sp) },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = singleLine, minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent, unfocusedBorderColor = p.text.copy(0.25f),
            focusedLabelColor = p.accent, unfocusedLabelColor = p.sub,
            focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent
        )
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    val p = LocalAurora.current
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = p.sub, fontSize = 13.sp)
    }
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
