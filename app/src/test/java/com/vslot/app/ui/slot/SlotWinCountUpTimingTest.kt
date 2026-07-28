package com.vslot.app.ui.slot

import com.vslot.app.game.ResultType
import com.vslot.app.game.SpinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotWinCountUpTimingTest {
    @Test
    fun `count up duration scales with win relative to total bet`() {
        assertEquals(0L, SlotWinCountUpTiming.durationMs(result(winAmount = 0, totalBet = 250)))
        assertEquals(
            SlotWinCountUpTiming.SMALL_WIN_DURATION_MS,
            SlotWinCountUpTiming.durationMs(result(winAmount = 250, totalBet = 250))
        )
        assertEquals(
            SlotWinCountUpTiming.REGULAR_WIN_DURATION_MS,
            SlotWinCountUpTiming.durationMs(result(winAmount = 251, totalBet = 250))
        )
        assertEquals(
            SlotWinCountUpTiming.LARGE_WIN_DURATION_MS,
            SlotWinCountUpTiming.durationMs(result(winAmount = 1_250, totalBet = 250))
        )
        assertEquals(
            SlotWinCountUpTiming.BIG_WIN_DURATION_MS,
            SlotWinCountUpTiming.durationMs(result(winAmount = 2_500, totalBet = 250))
        )
    }

    @Test
    fun `count up always finishes before the base result presentation delay`() {
        listOf(1, 250, 2_499, 2_500, Int.MAX_VALUE).forEach { winAmount ->
            assertTrue(
                SlotWinCountUpTiming.durationMs(result(winAmount = winAmount, totalBet = 250)) <
                    SlotWinFeedbackTiming.RESULT_DIALOG_BASE_DELAY_MS
            )
        }
    }

    @Test
    fun `count up threshold math does not overflow`() {
        assertEquals(
            SlotWinCountUpTiming.SMALL_WIN_DURATION_MS,
            SlotWinCountUpTiming.durationMs(
                result(winAmount = Int.MAX_VALUE, totalBet = Int.MAX_VALUE)
            )
        )
        assertEquals(
            SlotWinCountUpTiming.REGULAR_WIN_DURATION_MS,
            SlotWinCountUpTiming.durationMs(result(winAmount = 100, totalBet = 0))
        )
    }

    private fun result(winAmount: Int, totalBet: Int): SpinResult {
        return SpinResult(
            reels = emptyList(),
            bet = 1,
            totalBet = totalBet,
            winAmount = winAmount,
            resultType = if (winAmount > 0) ResultType.Win else ResultType.Lose,
            winningLines = emptyList(),
            scatterCount = 0,
            scatterPositions = emptyList()
        )
    }
}
