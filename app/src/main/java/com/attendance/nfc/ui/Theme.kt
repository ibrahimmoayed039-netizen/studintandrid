package com.attendance.nfc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection

val Bg1 = Color(0xFF0B1026)
val Bg2 = Color(0xFF141B3D)
val Pri = Color(0xFF6D7CFF)
val Pri2 = Color(0xFF9A6BFF)
val OkC = Color(0xFF19C37D)
val WarnC = Color(0xFFF5A524)
val BadC = Color(0xFFF0446B)
val InfoC = Color(0xFF4DA3FF)
val Txt = Color(0xFFE9ECFF)
val Mut = Color(0xFF9AA3C7)

@Composable
fun AttendanceTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Pri, secondary = Pri2, background = Bg1, surface = Bg2,
        onPrimary = Color.White, onBackground = Txt, onSurface = Txt,
        error = BadC, surfaceVariant = Color(0x22FFFFFF), onSurfaceVariant = Mut,
        outline = Color(0x55FFFFFF)
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
    }
}
