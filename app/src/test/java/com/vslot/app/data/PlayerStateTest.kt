package com.vslot.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    @Test
    fun `feedback preferences default on and preserve explicit opt out`() {
        val defaults = PlayerState()
        val optedOut = defaults.copy(soundEnabled = false, hapticsEnabled = false).normalized()

        assertTrue(defaults.soundEnabled)
        assertTrue(defaults.hapticsEnabled)
        assertFalse(optedOut.soundEnabled)
        assertFalse(optedOut.hapticsEnabled)
    }

    @Test
    fun `daily bonus is available after interval`() {
        val now = 2_000_000L
        val state = PlayerState(lastDailyBonusTimestamp = now - PlayerState.DAILY_BONUS_INTERVAL_MS)

        assertTrue(state.isDailyBonusAvailable(now))
    }

    @Test
    fun `daily bonus is blocked during cooldown`() {
        val now = 2_000_000L
        val state = PlayerState(lastDailyBonusTimestamp = now - 1_000L)

        assertFalse(state.isDailyBonusAvailable(now))
    }

    @Test
    fun `future daily bonus timestamp remains an anti rollback cooldown floor`() {
        val now = 2_000_000L
        val state = PlayerState(lastDailyBonusTimestamp = Long.MAX_VALUE)

        assertFalse(state.isDailyBonusAvailable(now))
        assertEquals(
            Long.MAX_VALUE,
            PlayerState.dailyBonusRemainingMs(state.lastDailyBonusTimestamp, now)
        )
    }

    @Test
    fun `rolling clock back after a later claim cannot reopen daily bonus`() {
        val originalTime = 2_000_000L
        val advancedClaimTime = originalTime + PlayerState.DAILY_BONUS_INTERVAL_MS

        assertEquals(
            PlayerState.DAILY_BONUS_INTERVAL_MS * 2L,
            PlayerState.dailyBonusRemainingMs(advancedClaimTime, originalTime)
        )
        assertFalse(PlayerState(lastDailyBonusTimestamp = advancedClaimTime)
            .isDailyBonusAvailable(originalTime))
    }

    @Test
    fun `daily bonus remaining avoids timestamp overflow`() {
        assertEquals(
            PlayerState.DAILY_BONUS_INTERVAL_MS + 1L,
            PlayerState.dailyBonusRemainingMs(Long.MAX_VALUE, now = Long.MAX_VALUE - 1L)
        )
        assertEquals(
            0L,
            PlayerState.dailyBonusRemainingMs(
                Long.MAX_VALUE - PlayerState.DAILY_BONUS_INTERVAL_MS,
                now = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `level is derived from persisted xp`() {
        val levelTwoXp = PlayerState.xpRequiredForLevel(2)
        val state = PlayerState(levelXp = levelTwoXp + 25)

        assertEquals(2, state.playerLevel)
        assertEquals(25, state.xpInCurrentLevel)
        assertEquals(
            PlayerState.xpRequiredForLevel(3) - PlayerState.xpRequiredForLevel(2),
            state.xpForCurrentLevel
        )
    }

    @Test
    fun `negative persisted xp displays as zero progress at level one`() {
        val state = PlayerState(levelXp = -500)

        assertEquals(1, state.playerLevel)
        assertEquals(0, state.xpInCurrentLevel)
        assertTrue(state.xpForCurrentLevel > 0)
    }

    @Test
    fun `max level keeps positive xp span and caps progress`() {
        val state = PlayerState(levelXp = PlayerState.maxLevelXp() + 10_000)

        assertEquals(PlayerState.MAX_PLAYER_LEVEL, state.playerLevel)
        assertTrue(state.xpForCurrentLevel > 0)
        assertEquals(state.xpForCurrentLevel, state.xpInCurrentLevel)
        assertEquals(PlayerState.MAX_PLAYER_LEVEL, PlayerState.levelForXp(Int.MAX_VALUE))
    }

    @Test
    fun `free spins award less profile xp than paid spins`() {
        val paidXp = PlayerState.xpForSpin(totalBet = 250, isFreeSpin = false, winAmount = 400)
        val freeSpinXp = PlayerState.xpForSpin(totalBet = 250, isFreeSpin = true, winAmount = 400)

        assertTrue(freeSpinXp > 0)
        assertTrue(paidXp > freeSpinXp)
    }

    @Test
    fun `legacy slot scoped free spin stake does not leak to another slot`() {
        val state = PlayerState(
            freeSpinsBalance = 3,
            freeSpinBet = 25,
            freeSpinLines = 10,
            freeSpinSlotId = "roman_reels"
        )

        assertEquals(3, state.freeSpinsForSlot("roman_reels"))
        assertEquals(25, state.freeSpinBetForSlot("roman_reels"))
        assertEquals(10, state.freeSpinLinesForSlot("roman_reels"))
        assertEquals(0, state.freeSpinsForSlot("violet_fortune"))
        assertEquals(0, state.freeSpinBetForSlot("violet_fortune"))
        assertEquals(0, state.freeSpinLinesForSlot("violet_fortune"))
    }

    @Test
    fun `per slot free spin mirror stake does not leak to slots without bonuses`() {
        val state = PlayerState(
            freeSpinsBalance = 3,
            freeSpinBet = 25,
            freeSpinLines = 10,
            freeSpinSlotId = "roman_reels",
            freeSpinBonuses = mapOf(
                "roman_reels" to FreeSpinBonus(
                    slotId = "roman_reels",
                    count = 3,
                    lineBet = 25,
                    lines = 10
                )
            )
        )

        assertEquals(0, state.freeSpinsForSlot("violet_fortune"))
        assertEquals(0, state.freeSpinBetForSlot("violet_fortune"))
        assertEquals(0, state.freeSpinLinesForSlot("violet_fortune"))
    }

    @Test
    fun `awarding slot bonus preserves legacy free spins from another slot`() {
        val bonuses = mergeAwardedFreeSpinBonus(
            currentBonuses = emptyMap(),
            legacySlotId = "other_slot",
            legacyCount = 3,
            legacyLineBet = 10,
            legacyLines = 5,
            awardSlotId = "current_slot",
            awardCount = 5,
            awardLineBet = 25,
            awardLines = 10
        )

        assertEquals(2, bonuses.size)
        assertEquals(3, bonuses["other_slot"]?.count)
        assertEquals(10, bonuses["other_slot"]?.lineBet)
        assertEquals(5, bonuses["other_slot"]?.lines)
        assertEquals(5, bonuses["current_slot"]?.count)
        assertEquals(25, bonuses["current_slot"]?.lineBet)
        assertEquals(10, bonuses["current_slot"]?.lines)
    }

    @Test
    fun `awarding slot bonus migrates blank legacy free spins into awarded slot`() {
        val bonuses = mergeAwardedFreeSpinBonus(
            currentBonuses = emptyMap(),
            legacySlotId = "",
            legacyCount = 3,
            legacyLineBet = 10,
            legacyLines = 5,
            awardSlotId = "current_slot",
            awardCount = 5,
            awardLineBet = 25,
            awardLines = 10
        )

        assertEquals(1, bonuses.size)
        assertEquals(8, bonuses["current_slot"]?.count)
        assertEquals(25, bonuses["current_slot"]?.lineBet)
        assertEquals(10, bonuses["current_slot"]?.lines)
    }

    @Test
    fun `awarding slot bonus caps overflowing free spin count`() {
        val bonuses = mergeAwardedFreeSpinBonus(
            currentBonuses = mapOf(
                "current_slot" to FreeSpinBonus(
                    slotId = "current_slot",
                    count = Int.MAX_VALUE,
                    lineBet = 10,
                    lines = 5
                )
            ),
            legacySlotId = "",
            legacyCount = 0,
            legacyLineBet = 0,
            legacyLines = 0,
            awardSlotId = "current_slot",
            awardCount = 5,
            awardLineBet = 25,
            awardLines = 10
        )

        assertEquals(Int.MAX_VALUE, bonuses["current_slot"]?.count)
        assertEquals(25, bonuses["current_slot"]?.lineBet)
        assertEquals(10, bonuses["current_slot"]?.lines)
    }

    @Test
    fun `normalizes corrupted persisted player state snapshot`() {
        val state = PlayerState(
            coinsBalance = -1,
            lastDailyBonusTimestamp = Long.MAX_VALUE,
            selectedBet = -25,
            selectedLines = -10,
            freeSpinsBalance = -5,
            freeSpinBet = -25,
            freeSpinLines = -10,
            freeSpinSlotId = "legacy_slot",
            freeSpinBonuses = mapOf(
                "bad" to FreeSpinBonus(
                    slotId = "",
                    count = -1,
                    lineBet = -25,
                    lines = -10
                )
            ),
            freeSpinAutoPlaySlots = setOf("", "valid_slot"),
            levelXp = -200,
            lastPlayedSlot = ""
        ).normalized()

        assertEquals(0L, state.coinsBalance)
        assertEquals(Long.MAX_VALUE, state.lastDailyBonusTimestamp)
        assertEquals(PlayerState.DEFAULT_BET, state.selectedBet)
        assertEquals(PlayerState.MIN_LINES, state.selectedLines)
        assertEquals(0, state.freeSpinsBalance)
        assertEquals(0, state.freeSpinBet)
        assertEquals(0, state.freeSpinLines)
        assertEquals("", state.freeSpinSlotId)
        assertTrue(state.freeSpinBonuses.isEmpty())
        assertEquals(setOf("valid_slot"), state.freeSpinAutoPlaySlots)
        assertEquals(0, state.levelXp)
        assertEquals(PlayerState.DEFAULT_SLOT_ID, state.lastPlayedSlot)
    }

    @Test
    fun `normalizes blank last played slot to default slot`() {
        val state = PlayerState(lastPlayedSlot = "").normalized()

        assertEquals(PlayerState.DEFAULT_SLOT_ID, state.lastPlayedSlot)
        assertEquals(PlayerState.DEFAULT_SLOT_ID, PlayerState.normalizedLastPlayedSlot(""))
    }

    @Test
    fun `normalizes persisted selected and free spin lines above supported maximum`() {
        val state = PlayerState(
            selectedLines = Int.MAX_VALUE,
            freeSpinsBalance = 2,
            freeSpinBet = 25,
            freeSpinLines = Int.MAX_VALUE,
            freeSpinSlotId = "legacy_slot"
        ).normalized()

        assertEquals(PlayerState.MAX_LINES, state.selectedLines)
        assertEquals(PlayerState.MAX_LINES, state.freeSpinLines)
    }

    @Test
    fun `normalizes per slot free spin lines above supported maximum`() {
        val state = PlayerState(
            freeSpinBonuses = mapOf(
                "roman_reels" to FreeSpinBonus(
                    slotId = "roman_reels",
                    count = 3,
                    lineBet = 25,
                    lines = Int.MAX_VALUE
                )
            )
        ).normalized()

        assertEquals(PlayerState.MAX_LINES, state.freeSpinLines)
        assertEquals(PlayerState.MAX_LINES, state.freeSpinLinesForSlot("roman_reels"))
    }

    @Test
    fun `normalizes free spin mirror from valid per slot bonuses`() {
        val state = PlayerState(
            freeSpinsBalance = 99,
            freeSpinBet = 250,
            freeSpinLines = 10,
            freeSpinSlotId = "legacy_slot",
            freeSpinBonuses = mapOf(
                "roman_reels" to FreeSpinBonus(
                    slotId = "roman_reels",
                    count = 3,
                    lineBet = 25,
                    lines = 10
                ),
                "violet_fortune" to FreeSpinBonus(
                    slotId = "violet_fortune",
                    count = 2,
                    lineBet = 10,
                    lines = 5
                )
            )
        ).normalized()

        assertEquals(5, state.freeSpinsBalance)
        assertEquals(25, state.freeSpinBet)
        assertEquals(10, state.freeSpinLines)
        assertEquals("roman_reels", state.freeSpinSlotId)
        assertEquals(2, state.freeSpinBonuses.size)
    }

    @Test
    fun `normalizes overflowing per slot free spin totals without wrapping negative`() {
        val state = PlayerState(
            freeSpinBonuses = mapOf(
                "roman_reels" to FreeSpinBonus(
                    slotId = "roman_reels",
                    count = Int.MAX_VALUE,
                    lineBet = 25,
                    lines = 10
                ),
                "violet_fortune" to FreeSpinBonus(
                    slotId = "violet_fortune",
                    count = 1,
                    lineBet = 10,
                    lines = 5
                )
            )
        ).normalized()

        assertEquals(Int.MAX_VALUE, state.freeSpinsBalance)
        assertEquals(Int.MAX_VALUE, state.freeSpinsForSlot("roman_reels"))
        assertEquals(1, state.freeSpinsForSlot("violet_fortune"))
    }
}
