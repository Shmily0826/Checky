package com.checky.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.checky.app.R
import com.checky.app.domain.model.RewardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.checky.app.domain.model.Reward

@Composable
fun RewardBadge(reward: Reward, modifier: Modifier = Modifier) {
    if (reward.isEmpty) return
    val color = if (reward.type == com.checky.app.domain.model.RewardType.POINTS)
        Color(0xFF3B6EF6) else Color(0xFF7C3AED)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (reward.type) {
                RewardType.POINTS -> stringResource(R.string.reward_points, reward.amount)
                RewardType.EXPERIENCE -> stringResource(R.string.reward_xp, reward.amount)
                RewardType.MEMBERSHIP_DAY -> if (reward.amount == 1) {
                    stringResource(R.string.reward_membership_day, reward.amount)
                } else {
                    stringResource(R.string.reward_membership_days, reward.amount)
                }
                RewardType.NONE -> ""
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
