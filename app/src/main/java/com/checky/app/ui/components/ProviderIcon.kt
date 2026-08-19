package com.checky.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.checky.app.domain.model.ProviderMeta

private val ICON_EMOJI = mapOf(
    "gamepad" to "🎮",
    "cloud" to "☁️",
    "school" to "🎓",
    "fire" to "🔥",
    "music" to "🎵",
    "cart" to "🛒",
    "star" to "⭐"
)

/**
 * Rounded accent tile that renders an emoji for the provider. No network, no
 * drawables — the mapping lives in code. Falls back to the first letter of the
 * display name when the icon key is unknown.
 */
@Composable
fun ProviderIcon(
    meta: ProviderMeta,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val accent = Color(meta.accentColor)
    val emoji = ICON_EMOJI[meta.iconKey] ?: meta.displayName.take(1).uppercase()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.titleMedium,
            fontSize = (size.value * 0.5).coerceAtMost(26.0).sp,
            color = accent
        )
    }
}
