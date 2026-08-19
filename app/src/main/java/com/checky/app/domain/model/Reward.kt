package com.checky.app.domain.model

enum class RewardType {
    POINTS,
    EXPERIENCE,
    MEMBERSHIP_DAY,
    NONE
}

data class Reward(
    val type: RewardType,
    val amount: Int
) {
    val label: String
        get() = when (type) {
            RewardType.POINTS -> "+$amount pts"
            RewardType.EXPERIENCE -> "+$amount XP"
            RewardType.MEMBERSHIP_DAY -> if (amount == 1) "+1 membership day" else "+$amount membership days"
            RewardType.NONE -> ""
        }

    val isEmpty: Boolean
        get() = type == RewardType.NONE || amount <= 0

    companion object {
        fun empty() = Reward(RewardType.NONE, 0)
    }
}
