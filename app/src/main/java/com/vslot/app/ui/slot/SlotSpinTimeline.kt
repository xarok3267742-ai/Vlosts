package com.vslot.app.ui.slot

import com.vslot.app.game.SlotConfig
import com.vslot.app.game.SpinResult

object SlotSpinTimeline {
    const val REEL_COUNT = 5
    const val BASE_SPIN_DURATION_MS = 1_520L
    const val REEL_STOP_STAGGER_MS = 300L
    const val REEL_STOP_BOUNCE_DURATION_MS = 520L
    const val REVEAL_SETTLE_MS = 40L
    const val SCATTER_HOLD_MS = 148L
    const val SCATTER_COLUMN_STEP_MS = 28L
    const val SCATTER_INTENSITY_STEP_MS = 48L
    const val SCATTER_VISIBLE_CHASE_MS = 1_040L
    const val SLAM_STOP_COLUMN_STAGGER_MS = 70L
    const val SLAM_STOP_ALIGNMENT_LEAD_MS = 240L
    const val SLAM_STOP_BOUNCE_DURATION_MS = 260L
    const val SLAM_STOP_SETTLE_MS = 60L
    const val SLAM_STOP_MIN_REVEAL_MS = 1_180L
    const val REDUCED_MOTION_MIN_REVEAL_MS = 160L
    const val REDUCED_MOTION_SETTLE_MS = 160L

    fun revealDurationMs(config: SlotConfig, result: SpinResult): Long {
        val lastStopMs = (0 until config.reels).maxOf { column ->
            stopAtMs(config, result, column)
        }
        return lastStopMs + REEL_STOP_BOUNCE_DURATION_MS + REVEAL_SETTLE_MS
    }

    fun stopAtMs(config: SlotConfig, result: SpinResult?, column: Int): Long {
        require(column in 0 until config.reels) { "Invalid reel column: $column" }
        val nominalStopMs = baseStopAtMs(config, column) +
            scatterAnticipationHoldMs(config, result, column)
        val landedAtMs = latestRequiredScatterLandedAtMs(config, result, column)
            ?: return nominalStopMs
        val firstChasedColumn = scatterTriggerThreshold(config) - 1
        val chaseColumnStaggerMs = (column - firstChasedColumn).coerceAtLeast(0) *
            REEL_STOP_STAGGER_MS
        return maxOf(
            nominalStopMs,
            landedAtMs + SCATTER_VISIBLE_CHASE_MS + chaseColumnStaggerMs
        )
    }

    fun baseStopAtMs(config: SlotConfig, column: Int): Long {
        require(column in 0 until config.reels) { "Invalid reel column: $column" }
        return BASE_SPIN_DURATION_MS + column * REEL_STOP_STAGGER_MS
    }

    fun hasScatterChase(config: SlotConfig, result: SpinResult?, column: Int): Boolean {
        val threshold = scatterTriggerThreshold(config)
        if (threshold != SCATTER_CHASE_TRIGGER_COUNT || column < REQUIRED_SCATTER_CHASE_REELS.size) {
            return false
        }
        val landedScatterColumns = result?.scatterPositions.orEmpty()
            .asSequence()
            .map { it.reel }
            .toSet()
        return REQUIRED_SCATTER_CHASE_REELS.all(landedScatterColumns::contains)
    }

    fun scatterAnticipationHoldMs(config: SlotConfig, result: SpinResult?, column: Int): Long {
        if (!hasScatterChase(config, result, column)) return 0L
        val scatterColumns = result?.scatterPositions.orEmpty().map { it.reel }.toSet()
        val threshold = scatterTriggerThreshold(config)
        val previousScatterColumns = scatterColumns.count { it < column }
        val intensity = previousScatterColumns.coerceIn(1, threshold)
        return SCATTER_HOLD_MS +
            column * SCATTER_COLUMN_STEP_MS +
            (intensity - 1) * SCATTER_INTENSITY_STEP_MS
    }

    fun scatterAnticipationStartAtMs(
        config: SlotConfig,
        result: SpinResult?,
        column: Int,
        anticipationWindowMs: Long
    ): Long? {
        if (!hasScatterChase(config, result, column)) return null
        val latestRequiredScatterLandedMs = latestRequiredScatterLandedAtMs(
            config,
            result,
            column
        ) ?: return null
        return maxOf(
            stopAtMs(config, result, column) - anticipationWindowMs.coerceAtLeast(0L),
            latestRequiredScatterLandedMs
        )
    }

    fun isScatterChaseActive(
        config: SlotConfig,
        result: SpinResult?,
        column: Int,
        elapsedMs: Long,
        anticipationWindowMs: Long
    ): Boolean {
        val startsAtMs = scatterAnticipationStartAtMs(
            config,
            result,
            column,
            anticipationWindowMs
        ) ?: return false
        return elapsedMs >= startsAtMs
    }

    fun slamStopDurationMs(reelCount: Int = REEL_COUNT): Long {
        require(reelCount >= 0) { "Remaining reel count cannot be negative." }
        if (reelCount == 0) return 0L
        return (reelCount - 1).coerceAtLeast(0) * SLAM_STOP_COLUMN_STAGGER_MS +
            SLAM_STOP_BOUNCE_DURATION_MS +
            SLAM_STOP_SETTLE_MS
    }

    fun slamStopDeadlineMs(
        config: SlotConfig,
        result: SpinResult,
        stopRequestedElapsedMs: Long,
        observedElapsedMs: Long
    ): Long {
        val elapsedMs = maxOf(stopRequestedElapsedMs, observedElapsedMs).coerceAtLeast(0L)
        val remainingReels = (0 until config.reels).count { column ->
            stopAtMs(config, result, column) > elapsedMs
        }
        val normalRevealDeadlineMs = revealDurationMs(config, result)
        if (remainingReels == 0) return normalRevealDeadlineMs
        val slamStartAtMs = slamStopStartAtMs(elapsedMs)
        var remainingIndex = 0
        val requestedDeadlineMs = (0 until config.reels)
            .mapNotNull { column ->
                val normalStopAtMs = stopAtMs(config, result, column)
                if (normalStopAtMs <= elapsedMs) return@mapNotNull null
                val acceleratedStopAtMs = slamStartAtMs +
                    remainingIndex * SLAM_STOP_COLUMN_STAGGER_MS
                remainingIndex += 1
                if (acceleratedStopAtMs < normalStopAtMs) {
                    acceleratedStopAtMs + SLAM_STOP_BOUNCE_DURATION_MS + SLAM_STOP_SETTLE_MS
                } else {
                    normalStopAtMs + REEL_STOP_BOUNCE_DURATION_MS + REVEAL_SETTLE_MS
                }
            }
            .maxOrNull()
            ?: normalRevealDeadlineMs
        return minOf(normalRevealDeadlineMs, requestedDeadlineMs)
    }

    fun slamStopStartAtMs(elapsedMs: Long): Long {
        return maxOf(elapsedMs + SLAM_STOP_ALIGNMENT_LEAD_MS, SLAM_STOP_MIN_REVEAL_MS)
    }

    private fun scatterTriggerThreshold(config: SlotConfig): Int {
        return config.scatterBonus
            .filterValues { it > 0 }
            .keys
            .minOrNull()
            ?.coerceIn(1, config.reels + 1)
            ?: Int.MAX_VALUE
    }

    private const val SCATTER_CHASE_TRIGGER_COUNT = 3
    private val REQUIRED_SCATTER_CHASE_REELS = setOf(0, 1)

    private fun latestRequiredScatterLandedAtMs(
        config: SlotConfig,
        result: SpinResult?,
        column: Int
    ): Long? {
        val threshold = scatterTriggerThreshold(config)
        val previousScatterColumns = result?.scatterPositions.orEmpty()
            .asSequence()
            .map { it.reel }
            .filter { it < column }
            .distinct()
            .sorted()
            .take(threshold - 1)
            .toList()
        if (previousScatterColumns.size < threshold - 1) return null
        return previousScatterColumns.maxOf { previousColumn ->
            stopAtMs(config, result, previousColumn) + REEL_STOP_BOUNCE_DURATION_MS
        }
    }
}
