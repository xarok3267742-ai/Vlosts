package com.vslot.app.ui.slot

import com.vslot.app.game.ResultType
import com.vslot.app.game.SpinResult

object SlotResultPresentationPolicy {
    const val BIG_WIN_TOTAL_BET_MULTIPLIER = 10

    fun shouldShowResultDialog(result: SpinResult): Boolean {
        return result.resultType == ResultType.Bonus || isBigWin(result)
    }

    fun isBigWin(result: SpinResult): Boolean {
        if (result.resultType != ResultType.Win || result.totalBet <= 0) return false
        return result.winAmount.toLong() >=
            result.totalBet.toLong() * BIG_WIN_TOTAL_BET_MULTIPLIER
    }

    fun isPartialReturn(result: SpinResult): Boolean {
        return result.resultType == ResultType.Win &&
            result.winAmount > 0 &&
            result.totalBet > 0 &&
            result.winAmount < result.totalBet
    }
}
