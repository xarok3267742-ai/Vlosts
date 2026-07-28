package com.vslot.app.ui.home

object SlotUnlockRules {
    private val requiredLevels = linkedMapOf(
        VIOLET_FORTUNE to 1,
        ROMAN_REELS to 1,
        NEON_NIGHTS to 2,
        PHARAOH_GOLD to 3,
        OCEAN_PEARL to 4
    )

    fun requiredLevel(slotId: String): Int = requiredLevels[slotId] ?: 1

    fun isUnlocked(slotId: String, playerLevel: Int): Boolean {
        return playerLevel >= requiredLevel(slotId)
    }

    fun slotsUnlockedBetween(previousLevel: Int, currentLevel: Int): List<String> {
        if (currentLevel <= previousLevel) return emptyList()
        return requiredLevels
            .filterValues { requiredLevel -> requiredLevel in (previousLevel + 1)..currentLevel }
            .filterValues { requiredLevel -> requiredLevel > 1 }
            .keys
            .toList()
    }

    const val VIOLET_FORTUNE = "violet_fortune"
    const val ROMAN_REELS = "roman_reels"
    const val NEON_NIGHTS = "neon_nights"
    const val PHARAOH_GOLD = "pharaoh_gold"
    const val OCEAN_PEARL = "ocean_pearl"
}
