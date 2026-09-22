package com.simtether.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Brand palette — primary anchored to the launcher icon blue.
 * Secondary/error fall back to Material defaults where we don't have
 * an opinion yet.
 */
private val Blue = Color(0xFF1565C0)

val SimTetherLightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF535F70),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    surface = Color(0xFFF8F9FF),
    surfaceVariant = Color(0xFFE0E2EC),
)

val SimTetherDarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF004787),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFBBC7DB),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF43474E),
)

/**
 * In-call UI palette. The call screen is always dark and follows
 * platform call conventions (green answer / red end) — deliberately
 * NOT colorScheme roles: M3 dark `error` is pastel, wrong for a
 * reject button. Grouped so the palette lives in one place.
 */
object CallColors {
    val Surface = Color(0xFF101418)
    val SurfaceVariant = Color(0xFF2E3338)
    val Secondary = Color(0xFF9AA0A6)
    val Tertiary = Color(0xFF5F6368)
    val Answer = Color(0xFF34A853)
    val AnswerAccent = Color(0xFF81C995)
    val Decline = Color(0xFFEA4335)
    val Active = Color(0xFF8AB4F8)
    val OnSurface = Color(0xFFE8EAED)
    val OnAction = Color.White
}

@Composable
fun SimTetherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SimTetherDarkColors else SimTetherLightColors,
        content = content,
    )
}
