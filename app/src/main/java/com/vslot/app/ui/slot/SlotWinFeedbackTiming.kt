package com.vslot.app.ui.slot

import com.vslot.app.game.SpinResult

object SlotWinFeedbackTiming {
    const val RESULT_DIALOG_BASE_DELAY_MS = 1_200L
    const val RESULT_DIALOG_SETTLE_MS = 360L
    const val PAYLINE_CAROUSEL_STEP_MS = 320L
    const val PAYLINE_CAROUSEL_REPEAT_LIMIT = 3
    const val PAYLINE_CAROUSEL_REPEAT_ROUNDS = 2
    const val REDUCED_MOTION_RESULT_HOLD_MS = 650L

    fun resultPresentationDurationMs(result: SpinResult, reducedMotion: Boolean = false): Long {
        if (reducedMotion) return REDUCED_MOTION_RESULT_HOLD_MS
        val uniquePaylineCount = result.winningLines
            .distinctBy { it.paylineIndex }
            .size
        if (uniquePaylineCount <= 1) return RESULT_DIALOG_BASE_DELAY_MS

        val carouselSteps = if (uniquePaylineCount <= PAYLINE_CAROUSEL_REPEAT_LIMIT) {
            uniquePaylineCount * PAYLINE_CAROUSEL_REPEAT_ROUNDS
        } else {
            uniquePaylineCount
        }
        val carouselDuration = carouselSteps * PAYLINE_CAROUSEL_STEP_MS + RESULT_DIALOG_SETTLE_MS
        return maxOf(RESULT_DIALOG_BASE_DELAY_MS, carouselDuration)
    }

    fun resultDialogDelayMs(result: SpinResult, reducedMotion: Boolean = false): Long =
        resultPresentationDurationMs(result, reducedMotion)

    fun inlineAutoSpinDelayMs(
        result: SpinResult,
        reducedMotion: Boolean = false,
        noPayoutDelayMs: Long
    ): Long {
        return if (result.winAmount > 0) {
            resultPresentationDurationMs(result, reducedMotion)
        } else {
            noPayoutDelayMs.coerceAtLeast(0L)
        }
    }
}
