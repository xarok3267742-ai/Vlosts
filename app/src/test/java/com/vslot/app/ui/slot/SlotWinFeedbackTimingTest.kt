package com.vslot.app.ui.slot

import com.vslot.app.game.ResultType
import com.vslot.app.game.SpinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotWinFeedbackTimingTest {
    @Test
    fun `reduced motion keeps a readable result hold without animated carousel timing`() {
        assertEquals(
            SlotWinFeedbackTiming.REDUCED_MOTION_RESULT_HOLD_MS,
            SlotWinFeedbackTiming.resultPresentationDurationMs(result(), reducedMotion = true)
        )
        assertEquals(
            SlotWinFeedbackTiming.REDUCED_MOTION_RESULT_HOLD_MS,
            SlotWinFeedbackTiming.resultDialogDelayMs(result(), reducedMotion = true)
        )
    }

    @Test
    fun `animated presentation keeps the payline reading window`() {
        assertTrue(
            SlotWinFeedbackTiming.resultPresentationDurationMs(result()) >=
                SlotWinFeedbackTiming.RESULT_DIALOG_BASE_DELAY_MS
        )
    }

    private fun result(): SpinResult = SpinResult(
        reels = List(5) { listOf("a", "b", "c") },
        winningLines = emptyList(),
        winAmount = 750,
        bet = 25,
        lines = 10,
        totalBet = 250,
        resultType = ResultType.Bonus,
        scatterCount = 0,
        scatterPositions = emptyList(),
        freeSpinsAwarded = 5,
        stopIndexes = List(5) { 0 }
    )
}
