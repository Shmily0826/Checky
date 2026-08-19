package com.checky.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.checky.app.data.preferences.ThemeMode

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3B6EF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF6C5CE7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E1FF),
    onSecondaryContainer = Color(0xFF220968),
    tertiary = Color(0xFF0EA47A),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFD14343),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF14161F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFEEF1F8),
    onSurfaceVariant = Color(0xFF44495A),
    outline = Color(0xFFD3D8E4),
    outlineVariant = Color(0xFFE4E8F2)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AA6FF),
    onPrimary = Color(0xFF0A1A4D),
    primaryContainer = Color(0xFF1E3A8F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFB7B2FF),
    onSecondary = Color(0xFF2A1A66),
    secondaryContainer = Color(0xFF3B2E80),
    onSecondaryContainer = Color(0xFFE7E1FF),
    tertiary = Color(0xFF4FD1A5),
    onTertiary = Color(0xFF003528),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF4A0008),
    errorContainer = Color(0xFF740016),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1016),
    onBackground = Color(0xFFE7E9F2),
    surface = Color(0xFF161922),
    onSurface = Color(0xFFE7E9F2),
    surfaceVariant = Color(0xFF212634),
    onSurfaceVariant = Color(0xFFB7BDD0),
    outline = Color(0xFF3A4154),
    outlineVariant = Color(0xFF2A3040)
)

@Composable
fun CheckyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColorScheme else LightColorScheme,
        content = content
    )
}
