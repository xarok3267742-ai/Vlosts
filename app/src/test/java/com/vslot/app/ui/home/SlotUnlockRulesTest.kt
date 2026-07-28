package com.vslot.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotUnlockRulesTest {
    @Test
    fun `first two slots are open from level one`() {
        assertEquals(1, SlotUnlockRules.requiredLevel(SlotUnlockRules.VIOLET_FORTUNE))
        assertEquals(1, SlotUnlockRules.requiredLevel(SlotUnlockRules.ROMAN_REELS))
        assertTrue(SlotUnlockRules.isUnlocked(SlotUnlockRules.VIOLET_FORTUNE, playerLevel = 1))
        assertTrue(SlotUnlockRules.isUnlocked(SlotUnlockRules.ROMAN_REELS, playerLevel = 1))
    }

    @Test
    fun `new slots unlock progressively by level`() {
        assertEquals(2, SlotUnlockRules.requiredLevel(SlotUnlockRules.NEON_NIGHTS))
        assertEquals(3, SlotUnlockRules.requiredLevel(SlotUnlockRules.PHARAOH_GOLD))
        assertEquals(4, SlotUnlockRules.requiredLevel(SlotUnlockRules.OCEAN_PEARL))

        assertFalse(SlotUnlockRules.isUnlocked(SlotUnlockRules.NEON_NIGHTS, playerLevel = 1))
        assertTrue(SlotUnlockRules.isUnlocked(SlotUnlockRules.NEON_NIGHTS, playerLevel = 2))
        assertFalse(SlotUnlockRules.isUnlocked(SlotUnlockRules.PHARAOH_GOLD, playerLevel = 2))
        assertTrue(SlotUnlockRules.isUnlocked(SlotUnlockRules.PHARAOH_GOLD, playerLevel = 3))
        assertFalse(SlotUnlockRules.isUnlocked(SlotUnlockRules.OCEAN_PEARL, playerLevel = 3))
        assertTrue(SlotUnlockRules.isUnlocked(SlotUnlockRules.OCEAN_PEARL, playerLevel = 4))
    }

    @Test
    fun `slots unlocked between levels are ordered and exclude starter slots`() {
        assertEquals(emptyList<String>(), SlotUnlockRules.slotsUnlockedBetween(previousLevel = 1, currentLevel = 1))
        assertEquals(listOf(SlotUnlockRules.NEON_NIGHTS), SlotUnlockRules.slotsUnlockedBetween(previousLevel = 1, currentLevel = 2))
        assertEquals(
            listOf(SlotUnlockRules.NEON_NIGHTS, SlotUnlockRules.PHARAOH_GOLD, SlotUnlockRules.OCEAN_PEARL),
            SlotUnlockRules.slotsUnlockedBetween(previousLevel = 1, currentLevel = 4)
        )
        assertEquals(emptyList<String>(), SlotUnlockRules.slotsUnlockedBetween(previousLevel = 4, currentLevel = 3))
    }
}
