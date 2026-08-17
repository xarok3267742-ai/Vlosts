package com.vslot.app.ui.slot

import com.vslot.app.game.NetOutcome
import com.vslot.app.game.ResultType
import com.vslot.app.game.SpinResult
import com.vslot.app.game.netOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotResultPresentationPolicyTest {
    @Test
    fun `ordinary wins stay on reels without result dialog`() {
        assertFalse(
            SlotResultPresentationPolicy.shouldShowResultDialog(
                result(ResultType.Win, totalBet = 250, winAmount = 2_499)
            )
        )
    }

    @Test
    fun `ten x wins and bonuses show result dialog`() {
        assertTrue(
            SlotResultPresentationPolicy.shouldShowResultDialog(
                result(ResultType.Win, totalBet = 250, winAmount = 2_500)
            )
        )
        assertTrue(
            SlotResultPresentationPolicy.shouldShowResultDialog(
                result(ResultType.Bonus, totalBet = 250, winAmount = 0)
            )
        )
    }

    @Test
    fun `losses and invalid zero stakes never count as big wins`() {
        assertFalse(
            SlotResultPresentationPolicy.shouldShowResultDialog(
                result(ResultType.Lose, totalBet = 250, winAmount = Int.MAX_VALUE)
            )
        )
        assertFalse(
            SlotResultPresentationPolicy.shouldShowResultDialog(
                result(ResultType.Win, totalBet = 0, winAmount = Int.MAX_VALUE)
            )
        )
    }

    @Test
    fun `big win threshold comparison does not overflow`() {
        assertFalse(
            SlotResultPresentationPolicy.shouldShowResultDialog(
                result(ResultType.Win, totalBet = Int.MAX_VALUE, winAmount = Int.MAX_VALUE)
            )
        )
    }

    @Test
    fun `positive payout below total bet is a partial return`() {
        assertTrue(
            SlotResultPresentationPolicy.isPartialReturn(
                result(ResultType.Win, totalBet = 250, winAmount = 249)
            )
        )
        assertFalse(
            SlotResultPresentationPolicy.isPartialReturn(
                result(ResultType.Win, totalBet = 250, winAmount = 250)
            )
        )
        assertFalse(
            SlotResultPresentationPolicy.isPartialReturn(
                result(ResultType.Bonus, totalBet = 250, winAmount = 100)
            )
        )
    }

    @Test
    fun `derived net outcome distinguishes every paid result class`() {
        assertEquals(NetOutcome.Loss, result(ResultType.Lose, 250, 0).netOutcome)
        assertEquals(NetOutcome.PartialReturn, result(ResultType.Win, 250, 249).netOutcome)
        assertEquals(NetOutcome.BreakEven, result(ResultType.Win, 250, 250).netOutcome)
        assertEquals(NetOutcome.NetWin, result(ResultType.Win, 250, 251).netOutcome)
        assertEquals(NetOutcome.Bonus, result(ResultType.Bonus, 250, 0).netOutcome)
    }

    @Test
    fun `partial return and break even never receive full win feedback`() {
        val partialReturn = result(ResultType.Win, totalBet = 250, winAmount = 249)
        val breakEven = result(ResultType.Win, totalBet = 250, winAmount = 250)

        assertFalse(SlotResultPresentationPolicy.hasFullWinFeedback(partialReturn))
        assertFalse(SlotResultPresentationPolicy.hasFullWinFeedback(breakEven))
        assertFalse(SlotResultPresentationPolicy.shouldShowResultDialog(partialReturn))
        assertFalse(SlotResultPresentationPolicy.shouldShowResultDialog(breakEven))
        assertTrue(SlotResultPresentationPolicy.isPartialReturn(partialReturn))
        assertTrue(SlotResultPresentationPolicy.isBreakEven(breakEven))
    }

    @Test
    fun `positive free spin payout is a net win because no stake is debited`() {
        val freeSpinPayout = result(
            ResultType.Win,
            totalBet = 250,
            winAmount = 1,
            isFreeSpin = true
        )

        assertEquals(NetOutcome.NetWin, freeSpinPayout.netOutcome)
        assertTrue(SlotResultPresentationPolicy.hasFullWinFeedback(freeSpinPayout))
    }

    private fun result(
        resultType: ResultType,
        totalBet: Int,
        winAmount: Int,
        isFreeSpin: Boolean = false
    ): SpinResult {
        return SpinResult(
            reels = emptyList(),
            bet = 1,
            totalBet = totalBet,
            winAmount = winAmount,
            resultType = resultType,
            winningLines = emptyList(),
            scatterCount = 0,
            scatterPositions = emptyList(),
            isFreeSpin = isFreeSpin
        )
    }
}
