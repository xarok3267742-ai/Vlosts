package com.vslot.app.ui.slot

import com.vslot.app.game.ResultType
import com.vslot.app.game.SpinResult
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

    private fun result(resultType: ResultType, totalBet: Int, winAmount: Int): SpinResult {
        return SpinResult(
            reels = emptyList(),
            bet = 1,
            totalBet = totalBet,
            winAmount = winAmount,
            resultType = resultType,
            winningLines = emptyList(),
            scatterCount = 0,
            scatterPositions = emptyList()
        )
    }
}
