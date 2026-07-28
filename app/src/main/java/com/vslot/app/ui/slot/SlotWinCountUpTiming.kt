package com.vslot.app.ui.slot

import com.vslot.app.game.SpinResult

object SlotWinCountUpTiming {
    const val SMALL_WIN_DURATION_MS = 420L
    const val REGULAR_WIN_DURATION_MS = 680L
    const val LARGE_WIN_DURATION_MS = 900L
    const val BIG_WIN_DURATION_MS = 1_100L

    fun durationMs(result: SpinResult): Long {
        if (result.winAmount <= 0) return 0L
        if (result.totalBet <= 0) return REGULAR_WIN_DURATION_MS

        val winAmount = result.winAmount.toLong()
        val totalBet = result.totalBet.toLong()
        return when {
            winAmount <= totalBet -> SMALL_WIN_DURATION_MS
            winAmount < totalBet * LARGE_WIN_MULTIPLIER -> REGULAR_WIN_DURATION_MS
            winAmount < totalBet * SlotResultPresentationPolicy.BIG_WIN_TOTAL_BET_MULTIPLIER ->
                LARGE_WIN_DURATION_MS
            else -> BIG_WIN_DURATION_MS
        }
    }

    private const val LARGE_WIN_MULTIPLIER = 5L
}
