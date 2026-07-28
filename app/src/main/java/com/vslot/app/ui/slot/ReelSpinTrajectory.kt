package com.vslot.app.ui.slot

internal object ReelSpinTrajectory {
    const val SETTLED_CELL_OFFSET = 1f
    const val ALIGNMENT_WINDOW_MS = 2_400L
    const val NORMAL_ALIGNMENT_MAX_STEP = 1
    const val MAX_ALIGNMENT_STEP = 4
    const val ALIGNMENT_FRAME_MS = 80L
    const val SLAM_ALIGNMENT_FRAME_MS = 40L

    private const val STOP_OVERSHOOT_CELL_OFFSET = 0.78f
    private const val STOP_REBOUND_CELL_OFFSET = 1.08f
    private const val STOP_SETTLE_CELL_OFFSET = 0.98f

    fun animationStartCellOffset(step: Int): Float {
        return SETTLED_CELL_OFFSET + step.coerceAtLeast(0)
    }

    fun shouldAnimate(step: Int): Boolean = step > 0

    fun shouldBeginAlignment(remainingMs: Long): Boolean {
        return remainingMs <= ALIGNMENT_WINDOW_MS
    }

    fun framesUntilStop(remainingMs: Long, frameDurationMs: Long): Int {
        val duration = frameDurationMs.coerceAtLeast(1L)
        return ((remainingMs.coerceAtLeast(0L) + duration - 1L) / duration)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun frameDurationUntilStop(frameDurationMs: Long, remainingMs: Long): Long {
        if (remainingMs <= 0L) return frameDurationMs.coerceAtLeast(1L)
        return minOf(frameDurationMs.coerceAtLeast(1L), remainingMs)
    }

    fun alignmentFramesUntilStop(
        remainingMs: Long,
        remainingDistance: Int,
        preferredFrameDurationMs: Long,
        minimumFrameDurationMs: Long,
        maximumStep: Int
    ): Int {
        val distance = remainingDistance.coerceAtLeast(1)
        val preferredFrames = minOf(
            framesUntilStop(remainingMs, preferredFrameDurationMs),
            distance
        )
        val requiredFrames = (distance + maximumStep.coerceAtLeast(1) - 1) /
            maximumStep.coerceAtLeast(1)
        val availableFrames = minOf(
            framesUntilStop(remainingMs, minimumFrameDurationMs),
            distance
        )
        return maxOf(preferredFrames, requiredFrames).coerceAtMost(availableFrames)
    }

    fun alignmentFrameDurationMs(
        remainingMs: Long,
        framesRemaining: Int,
        fallbackDurationMs: Long
    ): Long {
        if (remainingMs <= 0L) return fallbackDurationMs.coerceAtLeast(1L)
        val frames = framesRemaining.coerceAtLeast(1)
        return ((remainingMs + frames - 1L) / frames).coerceAtLeast(1L)
    }

    fun requiredStripSymbolCount(visibleRows: Int): Int {
        return SETTLED_CELL_OFFSET.toInt() + MAX_ALIGNMENT_STEP + visibleRows
    }

    fun stopBounceCellOffsets(currentCellOffset: Float): FloatArray {
        return floatArrayOf(
            currentCellOffset,
            STOP_OVERSHOOT_CELL_OFFSET,
            STOP_REBOUND_CELL_OFFSET,
            STOP_SETTLE_CELL_OFFSET,
            SETTLED_CELL_OFFSET
        )
    }
}
