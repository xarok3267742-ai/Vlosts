package com.vslot.app.ui

import com.vslot.app.data.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBonusCountdownFormatterTest {

    @Test
    fun `formats full daily cooldown as hours minutes seconds`() {
        val display = DailyBonusCountdownFormatter.format(
            lastDailyBonusTimestamp = CLAIM_TIME_MS,
            now = CLAIM_TIME_MS
        )

        assertEquals("24:00:00", display.digits)
        assertEquals(24, display.hours)
        assertEquals(0, display.minutes)
        assertEquals(0, display.seconds)
        assertFalse(display.isReady)
    }

    @Test
    fun `rounds up partial second without hiding remaining time`() {
        val display = DailyBonusCountdownFormatter.format(
            lastDailyBonusTimestamp = CLAIM_TIME_MS,
            now = CLAIM_TIME_MS + PlayerState.DAILY_BONUS_INTERVAL_MS - 1L
        )

        assertEquals("00:00:01", display.digits)
        assertFalse(display.isReady)
    }

    @Test
    fun `marks ready when cooldown has elapsed`() {
        val display = DailyBonusCountdownFormatter.format(
            lastDailyBonusTimestamp = CLAIM_TIME_MS,
            now = CLAIM_TIME_MS + PlayerState.DAILY_BONUS_INTERVAL_MS
        )

        assertEquals("00:00:00", display.digits)
        assertTrue(display.isReady)
    }

    @Test
    fun `caps extreme future timestamp display without making bonus ready`() {
        val display = DailyBonusCountdownFormatter.format(
            lastDailyBonusTimestamp = Long.MAX_VALUE,
            now = CLAIM_TIME_MS
        )

        assertEquals("99:59:59", display.digits)
        assertEquals(99, display.hours)
        assertFalse(display.isReady)
    }

    @Test
    fun `shows rollback wait in addition to the next daily cooldown`() {
        val advancedClaim = CLAIM_TIME_MS + PlayerState.DAILY_BONUS_INTERVAL_MS
        val display = DailyBonusCountdownFormatter.format(
            lastDailyBonusTimestamp = advancedClaim,
            now = CLAIM_TIME_MS
        )

        assertEquals("48:00:00", display.digits)
        assertFalse(display.isReady)
    }

    @Test
    fun `formats overflow edge timestamps without wrapping ready`() {
        val display = DailyBonusCountdownFormatter.format(
            lastDailyBonusTimestamp = Long.MAX_VALUE - PlayerState.DAILY_BONUS_INTERVAL_MS,
            now = Long.MAX_VALUE
        )

        assertEquals("00:00:00", display.digits)
        assertTrue(display.isReady)
    }

    @Test
    fun `accessibility uses minute buckets until the final minute`() {
        val minuteLevel = DailyBonusCountdownFormatter.accessibility(
            hours = 1,
            minutes = 12,
            seconds = 43
        )
        val secondLevel = DailyBonusCountdownFormatter.accessibility(
            hours = 0,
            minutes = 0,
            seconds = 43
        )

        assertEquals(72, minuteLevel.bucket)
        assertFalse(minuteLevel.usesSeconds)
        assertEquals(-43, secondLevel.bucket)
        assertTrue(secondLevel.usesSeconds)
    }

    private companion object {
        const val CLAIM_TIME_MS = 1_700_000_000_000L
    }
}
