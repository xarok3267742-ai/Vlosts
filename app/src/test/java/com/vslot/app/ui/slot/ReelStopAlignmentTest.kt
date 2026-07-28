package com.vslot.app.ui.slot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelStopAlignmentTest {
    @Test
    fun `target offset aligns visible rows with stop index without reversing`() {
        val currentOffset = -37
        val target = ReelStopAlignment.targetOffset(
            currentOffset = currentOffset,
            targetStopIndex = 8,
            column = 3,
            stripSize = 19,
            columnOffset = 3
        )

        assertTrue(target < currentOffset)
        assertEquals(8, wrappedIndex(target + 3 * 3 + 1, 19))
    }

    @Test
    fun `alignment distributes travel without freezing before the final frame`() {
        var current = -12
        val target = -23
        var framesRemaining = 8

        repeat(framesRemaining) {
            val step = ReelStopAlignment.step(current, target, framesRemaining)
            assertTrue(step > 0)
            current -= step
            framesRemaining -= 1
        }

        assertEquals(target, current)
        assertEquals(0, ReelStopAlignment.step(current, target, framesRemaining = 1))
    }

    @Test
    fun `late alignment reaches every reel stop on its final moving frame`() {
        repeat(5) { column ->
            repeat(24) { stopIndex ->
                val currentOffset = -41 - column
                var framesRemaining = 9
                val targetOffset = ReelStopAlignment.targetOffsetWithMinimumTravel(
                    currentOffset = currentOffset,
                    targetStopIndex = stopIndex,
                    column = column,
                    stripSize = 24,
                    columnOffset = 3,
                    minimumTravel = framesRemaining,
                    maximumTravel = framesRemaining * ReelSpinTrajectory.MAX_ALIGNMENT_STEP
                )
                var current = currentOffset
                repeat(framesRemaining) {
                    val step = ReelStopAlignment.step(current, targetOffset, framesRemaining)
                    assertTrue(step in 1..ReelSpinTrajectory.MAX_ALIGNMENT_STEP)
                    current -= step
                    framesRemaining -= 1
                }

                assertEquals(targetOffset, current)
                assertEquals(stopIndex, wrappedIndex(current + column * 3 + 1, 24))
            }
        }
    }

    @Test
    fun `target alignment remains disabled during acceleration and cruise`() {
        assertTrue(!ReelSpinTrajectory.shouldBeginAlignment(2_401L))
        assertTrue(ReelSpinTrajectory.shouldBeginAlignment(2_400L))
        assertTrue(ReelSpinTrajectory.shouldBeginAlignment(0L))
    }

    @Test
    fun `normal alignment advances one symbol per rendered frame`() {
        assertEquals(1, ReelSpinTrajectory.NORMAL_ALIGNMENT_MAX_STEP)
        assertEquals(
            1,
            ReelStopAlignment.step(
                currentOffset = -12,
                targetOffset = -20,
                framesRemaining = 8,
                maximumStep = ReelSpinTrajectory.NORMAL_ALIGNMENT_MAX_STEP
            )
        )
    }

    @Test
    fun `one-symbol strip transform preserves visible symbol geometry`() {
        val previousOffset = -17
        repeat(ReelSpinTrajectory.MAX_ALIGNMENT_STEP) { zeroBasedStep ->
            val step = zeroBasedStep + 1
            val nextOffset = previousOffset - step

            repeat(3) { visibleRow ->
                val previousSymbolIndex = previousOffset + 1 + visibleRow
                val nextStartSymbolIndex = nextOffset +
                    ReelSpinTrajectory.animationStartCellOffset(step).toInt() + visibleRow
                assertEquals(previousSymbolIndex, nextStartSymbolIndex)
            }
        }
        assertEquals(2f, ReelSpinTrajectory.animationStartCellOffset(1), 0f)
        assertEquals(1f, ReelSpinTrajectory.SETTLED_CELL_OFFSET, 0f)
    }

    @Test
    fun `final frame duration ends exactly at stop deadline`() {
        assertEquals(3, ReelSpinTrajectory.framesUntilStop(220L, 80L))
        assertEquals(3, ReelSpinTrajectory.alignmentFramesUntilStop(220L, 10, 80L, 40L, 4))
        assertEquals(2, ReelSpinTrajectory.alignmentFramesUntilStop(220L, 2, 80L, 40L, 4))
        assertEquals(110L, ReelSpinTrajectory.alignmentFrameDurationMs(220L, 2, 80L))
        assertEquals(60L, ReelSpinTrajectory.frameDurationUntilStop(136L, 60L))
        assertEquals(136L, ReelSpinTrajectory.frameDurationUntilStop(136L, 220L))
        assertEquals(8, ReelSpinTrajectory.requiredStripSymbolCount(visibleRows = 3))
    }

    @Test
    fun `dropped alignment frame never requests more symbols than the strip can display`() {
        val currentOffset = -40
        val originalTarget = -72
        val retargeted = ReelStopAlignment.targetOffsetWithinCapacity(
            currentOffset = currentOffset,
            targetOffset = originalTarget,
            stripSize = 24,
            maximumTravel = 28
        )
        val remainingDistance = currentOffset - retargeted
        val framesRemaining = ReelSpinTrajectory.alignmentFramesUntilStop(
            remainingMs = 560L,
            remainingDistance = remainingDistance,
            preferredFrameDurationMs = 80L,
            minimumFrameDurationMs = 40L,
            maximumStep = ReelSpinTrajectory.MAX_ALIGNMENT_STEP
        )
        val step = ReelStopAlignment.step(
            currentOffset,
            retargeted,
            framesRemaining,
            ReelSpinTrajectory.MAX_ALIGNMENT_STEP
        )

        assertEquals(-48, retargeted)
        assertTrue(step in 1..ReelSpinTrajectory.MAX_ALIGNMENT_STEP)
    }

    @Test
    fun `slam retarget fits any strip stop into six populated frames`() {
        repeat(5) { column ->
            repeat(24) { stopIndex ->
                val currentOffset = -67 - column
                val scheduledFrames = 6
                val targetOffset = ReelStopAlignment.targetOffsetWithMinimumTravel(
                    currentOffset = currentOffset,
                    targetStopIndex = stopIndex,
                    column = column,
                    stripSize = 24,
                    columnOffset = 3,
                    minimumTravel = 1,
                    maximumTravel = scheduledFrames * ReelSpinTrajectory.MAX_ALIGNMENT_STEP
                )
                var current = currentOffset
                var framesRemaining = minOf(scheduledFrames, current - targetOffset)

                repeat(framesRemaining) {
                    val step = ReelStopAlignment.step(current, targetOffset, framesRemaining)
                    assertTrue(step in 1..ReelSpinTrajectory.MAX_ALIGNMENT_STEP)
                    current -= step
                    framesRemaining -= 1
                }

                assertEquals(targetOffset, current)
                assertEquals(stopIndex, wrappedIndex(current + column * 3 + 1, 24))
            }
        }
    }

    @Test
    fun `stop bounce continues from the settled strip without a position jump`() {
        val offsets = ReelSpinTrajectory.stopBounceCellOffsets(
            ReelSpinTrajectory.SETTLED_CELL_OFFSET
        )

        assertEquals(ReelSpinTrajectory.SETTLED_CELL_OFFSET, offsets.first(), 0f)
        assertTrue(offsets[1] < offsets.first())
        assertTrue(offsets[2] > offsets[1])
        assertEquals(ReelSpinTrajectory.SETTLED_CELL_OFFSET, offsets.last(), 0f)
    }

    private fun wrappedIndex(index: Int, size: Int): Int = ((index % size) + size) % size
}
