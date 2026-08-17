package com.vslot.app.ui.slot

import com.vslot.app.game.NetOutcome
import com.vslot.app.game.SpinResult
import com.vslot.app.game.netOutcome

object SlotResultPresentationPolicy {
    const val BIG_WIN_TOTAL_BET_MULTIPLIER = 10

    fun shouldShowResultDialog(result: SpinResult): Boolean {
        return result.netOutcome == NetOutcome.Bonus || isBigWin(result)
    }

    fun isBigWin(result: SpinResult): Boolean {
        if (result.netOutcome != NetOutcome.NetWin || result.totalBet <= 0) return false
        return result.winAmount.toLong() >=
            result.totalBet.toLong() * BIG_WIN_TOTAL_BET_MULTIPLIER
    }

    fun isPartialReturn(result: SpinResult): Boolean {
        return result.netOutcome == NetOutcome.PartialReturn
    }

    fun isBreakEven(result: SpinResult): Boolean = result.netOutcome == NetOutcome.BreakEven

    fun hasFullWinFeedback(result: SpinResult): Boolean {
        return result.netOutcome == NetOutcome.NetWin || result.netOutcome == NetOutcome.Bonus
    }
}
