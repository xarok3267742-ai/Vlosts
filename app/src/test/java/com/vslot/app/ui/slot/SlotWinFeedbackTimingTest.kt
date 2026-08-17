package com.vslot.app.ui.slot

import com.vslot.app.game.ResultType
import com.vslot.app.game.SpinResult
import com.vslot.app.game.SymbolPosition
import com.vslot.app.game.WinningLine
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

    @Test
    fun `partial return keeps every winning line visible before next autospin`() {
        val partialReturn = result().copy(
            resultType = ResultType.Win,
            totalBet = 1_000,
            winAmount = 900,
            freeSpinsAwarded = 0,
            winningLines = List(3) { index ->
                WinningLine(
                    paylineIndex = index,
                    symbol = "a",
                    count = 3,
                    amount = 300,
                    positions = List(3) { reel -> SymbolPosition(reel = reel, row = 1) }
                )
            }
        )

        assertEquals(
            SlotWinFeedbackTiming.resultPresentationDurationMs(partialReturn),
            SlotWinFeedbackTiming.inlineAutoSpinDelayMs(
                result = partialReturn,
                noPayoutDelayMs = 650L
            )
        )
        assertTrue(
            SlotWinFeedbackTiming.inlineAutoSpinDelayMs(
                result = partialReturn,
                noPayoutDelayMs = 650L
            ) > 650L
        )
    }

    @Test
    fun `loss keeps the short autospin cadence`() {
        assertEquals(
            650L,
            SlotWinFeedbackTiming.inlineAutoSpinDelayMs(
                result = result().copy(
                    resultType = ResultType.Lose,
                    winAmount = 0,
                    freeSpinsAwarded = 0
                ),
                noPayoutDelayMs = 650L
            )
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
