package com.umeos.io

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object G {
    val bg0H = Color(0xFF1D2021)
    val bg0 = Color(0xFF282828)
    val bg1 = Color(0xFF3C3836)
    val bg2 = Color(0xFF504945)
    val bg3 = Color(0xFF665C54)
    val fg = Color(0xFFEBDBB2)
    val fg4 = Color(0xFFA89984)
    val gray = Color(0xFF928374)
    val red = Color(0xFFFB4934)
    val green = Color(0xFFB8BB26)
    val yellow = Color(0xFFFABD2F)
    val blue = Color(0xFF83A598)
    val purple = Color(0xFFD3869B)
    val aqua = Color(0xFF8EC07C)
    val orange = Color(0xFFFE8019)
}

private val scheme = darkColorScheme(
    primary = G.yellow,
    onPrimary = G.bg0,
    secondary = G.aqua,
    onSecondary = G.bg0,
    background = G.bg0,
    onBackground = G.fg,
    surface = G.bg0,
    onSurface = G.fg,
    surfaceVariant = G.bg1,
    onSurfaceVariant = G.fg4,
    outline = G.bg3
)

@Composable
fun GruvboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
