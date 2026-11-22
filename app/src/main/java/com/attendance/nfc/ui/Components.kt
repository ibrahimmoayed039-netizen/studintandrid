package com.attendance.nfc.ui

import android.app.TimePickerDialog as AndroidTimePicker
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.toMin
import kotlinx.coroutines.delay
import java.time.LocalDateTime

@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Bg1, Bg2)))
            .drawBehind {
                val r = size.width * 0.8f
                drawCircle(
                    Brush.radialGradient(listOf(Pri.copy(alpha = .35f), Color.Transparent), center = Offset(size.width * .9f, 0f), radius = r),
                    radius = r, center = Offset(size.width * .9f, 0f)
                )
                drawCircle(
                    Brush.radialGradient(listOf(Pri2.copy(alpha = .28f), Color.Transparent), center = Offset(0f, size.height), radius = r),
                    radius = r, center = Offset(0f, size.height)
                )
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides Txt) { content() }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color(0xFF161D42))
            .background(Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x0AFFFFFF))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x55FFFFFF), Color(0x10FFFFFF))), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/** زر بارز ثلاثي الأبعاد: تدرّج + ظل، وينضغط عند اللمس. */
@Composable
fun Btn3D(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Pri,
    enabled: Boolean = true,
    small: Boolean = false
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(if (small) 10.dp else 14.dp)
    val base = if (enabled) color else Color(0xFF3A4066)
    val light = lerp(base, Color.White, 0.25f)
    val dark = lerp(base, Color.Black, 0.35f)
    Box(
        modifier
            .graphicsLayer { translationY = if (pressed) 4.dp.toPx() else 0f }
            .shadow(if (pressed) 1.dp else 8.dp, shape, ambientColor = dark, spotColor = dark)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(light, base)))
            .border(1.dp, Color.White.copy(alpha = .3f), shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 18.dp, vertical = if (small) 7.dp else 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = Color.White, fontWeight = FontWeight.SemiBold,
            fontSize = if (small) 13.sp else 15.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(width = 6.dp, height = 20.dp).clip(RoundedCornerShape(4.dp)).background(Brush.verticalGradient(listOf(Pri, Pri2))))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Txt)
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(30.dp)).background(color.copy(alpha = .28f))
            .border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(30.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun StatBox(value: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.shadow(8.dp, shape).clip(shape).background(Color(0xFF161D42))
            .background(Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x0AFFFFFF))))
            .border(1.dp, Color(0x33FFFFFF), shape).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$value", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, fontSize = 12.sp, color = Mut)
    }
}

@Composable
fun StudentAvatar(path: String, size: Dp, fallback: String = "👤", ringColor: Color = Color.White) {
    val bmp = remember(path) { if (path.isBlank()) null else try { BitmapFactory.decodeFile(path)?.asImageBitmap() } catch (_: Exception) { null } }
    Box(
        Modifier.size(size).shadow(8.dp, CircleShape).clip(CircleShape).background(Color(0x33FFFFFF))
            .border(3.dp, ringColor.copy(alpha = .85f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(fallback, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
fun AppField(
    value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
    visual: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(14.dp), visualTransformation = visual
    )
}

@Composable
fun GradeChips(grades: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (grades.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(grades) { g ->
            FilterChip(selected = selected == g, onClick = { onSelect(if (selected == g) "" else g) }, label = { Text(g) })
        }
    }
}

@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, clearable: Boolean = false) {
    val ctx = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                val m = if (value.isBlank()) 8 * 60 else toMin(value)
                AndroidTimePicker(ctx, AndroidTimePicker.OnTimeSetListener { _, h, mi -> onChange("%02d:%02d".format(h, mi)) }, m / 60, m % 60, true).show()
            },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
        ) { Text("$label: ${value.ifBlank { "--:--" }}") }
        if (clearable && value.isNotBlank()) TextButton(onClick = { onChange("") }) { Text("✕") }
    }
}

@Composable
fun ConfirmDialog(text: String, okText: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        icon = { Text("⚠️", fontSize = 32.sp) },
        text = { Text(text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk() }) { Text(okText, color = BadC, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun rememberTicker(): State<LocalDateTime> =
    produceState(initialValue = LocalDateTime.now()) {
        while (true) { delay(15_000); value = LocalDateTime.now() }
    }


/** زر قراءة البطاقة: أيقونة نابضة أثناء الانتظار، ويتحول للأخضر بعد قراءة رقم. */
@Composable
fun CardReadButton(waiting: Boolean, hasUid: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = when { waiting -> WarnC; hasUid -> OkC; else -> Pri }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val phase by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase"
    )
    val shape = RoundedCornerShape(20.dp)
    val title = when { waiting -> "بانتظار البطاقة..."; hasUid -> "تمت القراءة ✓ (اضغط لإعادة القراءة)"; else -> "قراءة البطاقة" }
    val sub = when {
        waiting -> "مرّر البطاقة على القارئ الخارجي أو قرّبها من الهاتف · اضغط للإلغاء"
        else -> "قارئ RFID الخارجي أو NFC الهاتف"
    }
    Row(
        modifier.fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = color, spotColor = color)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(lerp(color, Color.White, 0.15f), lerp(color, Pri2, 0.45f))))
            .border(1.dp, Color.White.copy(alpha = .35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            if (waiting) {
                Box(
                    Modifier.size(64.dp)
                        .graphicsLayer { scaleX = 0.6f + 0.4f * phase; scaleY = 0.6f + 0.4f * phase; alpha = 1f - phase }
                        .border(2.dp, Color.White, CircleShape)
                )
            }
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f)),
                contentAlignment = Alignment.Center
            ) { Text(if (waiting) "📡" else if (hasUid) "✅" else "💳", fontSize = 24.sp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(sub, fontSize = 12.sp, color = Color(0xFFF3F5FF))
        }
    }
}
