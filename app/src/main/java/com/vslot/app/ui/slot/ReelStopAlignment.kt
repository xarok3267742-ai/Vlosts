package com.vslot.app.ui.slot

internal object ReelStopAlignment {
    fun targetOffset(
        currentOffset: Int,
        targetStopIndex: Int,
        column: Int,
        stripSize: Int,
        columnOffset: Int
    ): Int {
        if (stripSize <= 0) return currentOffset
        val normalizedTargetOffset = targetStopIndex - 1 - column * columnOffset
        val backwardDistance = wrappedIndex(currentOffset - normalizedTargetOffset, stripSize)
            .takeIf { it > 0 }
            ?: stripSize
        return currentOffset - backwardDistance
    }

    fun targetOffsetWithMinimumTravel(
        currentOffset: Int,
        targetStopIndex: Int,
        column: Int,
        stripSize: Int,
        columnOffset: Int,
        minimumTravel: Int,
        maximumTravel: Int = Int.MAX_VALUE
    ): Int {
        if (stripSize <= 0) return currentOffset
        var targetOffset = targetOffset(
            currentOffset = currentOffset,
            targetStopIndex = targetStopIndex,
            column = column,
            stripSize = stripSize,
            columnOffset = columnOffset
        )
        while (
            currentOffset - targetOffset < minimumTravel.coerceAtLeast(1) &&
            currentOffset - (targetOffset - stripSize) <= maximumTravel.coerceAtLeast(1)
        ) {
            targetOffset -= stripSize
        }
        return targetOffset
    }

    fun targetOffsetWithinCapacity(
        currentOffset: Int,
        targetOffset: Int,
        stripSize: Int,
        maximumTravel: Int
    ): Int {
        if (stripSize <= 0) return targetOffset
        var reachableTarget = targetOffset
        while (
            currentOffset - reachableTarget > maximumTravel.coerceAtLeast(0) &&
            reachableTarget + stripSize <= currentOffset
        ) {
            reachableTarget += stripSize
        }
        return reachableTarget
    }

    fun step(
        currentOffset: Int,
        targetOffset: Int,
        framesRemaining: Int,
        maximumStep: Int = Int.MAX_VALUE
    ): Int {
        val remainingDistance = (currentOffset - targetOffset).coerceAtLeast(0)
        if (remainingDistance == 0) return 0
        val frames = framesRemaining.coerceAtLeast(1)
        val evenStep = (remainingDistance + frames - 1) / frames
        val leaveOnePerFutureFrame = (remainingDistance - frames + 1).coerceAtLeast(1)
        return minOf(evenStep, leaveOnePerFutureFrame, maximumStep.coerceAtLeast(1))
    }

    private fun wrappedIndex(index: Int, size: Int): Int = ((index % size) + size) % size
}
