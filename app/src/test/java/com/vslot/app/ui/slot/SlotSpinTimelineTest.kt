package com.vslot.app.ui.slot

import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotConfig
import com.vslot.app.game.SlotTheme
import com.vslot.app.game.SpinResult
import com.vslot.app.game.SymbolPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotSpinTimelineTest {
    @Test
    fun `ordinary spin ends after the last reel bounce without a dead tail`() {
        assertEquals(3_280L, SlotSpinTimeline.revealDurationMs(config(), result(emptyList())))
    }

    @Test
    fun `scatter chase starts only after two scatters have visibly landed`() {
        val result = result(listOf(SymbolPosition(0, 0), SymbolPosition(1, 1)))

        assertFalse(SlotSpinTimeline.hasScatterChase(config(), result, column = 1))
        assertFalse(SlotSpinTimeline.hasScatterChase(config(), result, column = 0))
        assertTrue(SlotSpinTimeline.hasScatterChase(config(), result, column = 2))
        assertTrue(SlotSpinTimeline.hasScatterChase(config(), result, column = 4))
        assertEquals(
            SlotSpinTimeline.stopAtMs(config(), result, column = 1) +
                SlotSpinTimeline.REEL_STOP_BOUNCE_DURATION_MS,
            SlotSpinTimeline.scatterAnticipationStartAtMs(
                config(),
                result,
                column = 2,
                anticipationWindowMs = 1_040L
            )
        )
        assertFalse(
            SlotSpinTimeline.isScatterChaseActive(
                config(), result, column = 2, elapsedMs = 2_339L, anticipationWindowMs = 1_040L
            )
        )
        assertTrue(
            SlotSpinTimeline.isScatterChaseActive(
                config(), result, column = 2, elapsedMs = 2_340L, anticipationWindowMs = 1_040L
            )
        )
        assertEquals(4_540L, SlotSpinTimeline.revealDurationMs(config(), result))
    }

    @Test
    fun `scatter on chased reel does not reveal itself through longer pre-stop timing`() {
        val withoutThirdScatter = result(listOf(SymbolPosition(0, 0), SymbolPosition(1, 1)))
        val withThirdScatter = result(
            listOf(SymbolPosition(0, 0), SymbolPosition(1, 1), SymbolPosition(2, 2))
        )

        assertEquals(
            SlotSpinTimeline.stopAtMs(config(), withoutThirdScatter, column = 2),
            SlotSpinTimeline.stopAtMs(config(), withThirdScatter, column = 2)
        )
    }

    @Test
    fun `slam stop keeps a compact sequential stop for every reel`() {
        assertEquals(600L, SlotSpinTimeline.slamStopDurationMs())
        assertEquals(0L, SlotSpinTimeline.slamStopDurationMs(reelCount = 0))
        assertEquals(1_180L, SlotSpinTimeline.slamStopStartAtMs(elapsedMs = 120L))
        assertEquals(1_660L, SlotSpinTimeline.slamStopStartAtMs(elapsedMs = 1_420L))
    }

    @Test
    fun `late slam never extends the normal reveal deadline`() {
        val config = config()
        val result = result(emptyList())
        val revealDeadline = SlotSpinTimeline.revealDurationMs(config, result)

        assertEquals(
            revealDeadline,
            SlotSpinTimeline.slamStopDeadlineMs(
                config = config,
                result = result,
                stopRequestedElapsedMs = revealDeadline - 100L,
                observedElapsedMs = revealDeadline - 100L
            )
        )
    }

    @Test
    fun `late slam preserves normal bounce when alignment lead misses the last stop`() {
        val config = config()
        val result = result(emptyList())

        assertEquals(
            SlotSpinTimeline.revealDurationMs(config, result),
            SlotSpinTimeline.slamStopDeadlineMs(
                config = config,
                result = result,
                stopRequestedElapsedMs = 2_500L,
                observedElapsedMs = 2_500L
            )
        )
    }

    @Test
    fun `slam boundary counts only reels whose normal stop is still ahead`() {
        val config = config()
        val result = result(emptyList())
        val firstStopMs = SlotSpinTimeline.stopAtMs(config, result, column = 0)

        assertEquals(
            SlotSpinTimeline.slamStopStartAtMs(firstStopMs) +
                SlotSpinTimeline.slamStopDurationMs(reelCount = 4),
            SlotSpinTimeline.slamStopDeadlineMs(
                config = config,
                result = result,
                stopRequestedElapsedMs = firstStopMs,
                observedElapsedMs = firstStopMs
            )
        )
    }

    private fun config() = SlotConfig(
        id = "timeline",
        name = "Timeline",
        theme = SlotTheme.Violet,
        reels = 5,
        rows = 3,
        paylines = 10,
        wild = "wild",
        scatter = "scatter",
        symbols = listOf("wild", "scatter", "a"),
        bets = listOf(10),
        payouts = mapOf("wild" to mapOf(3 to 1), "a" to mapOf(3 to 1)),
        scatterBonus = mapOf(3 to 5),
        reelStrips = List(5) { listOf("wild", "scatter", "a") },
        freeSpinReelStrips = List(5) { listOf("wild", "a", "scatter") }
    )

    private fun result(scatterPositions: List<SymbolPosition>) = SpinResult(
        reels = List(5) { listOf("wild", "scatter", "a") },
        bet = 10,
        lines = 10,
        totalBet = 100,
        winAmount = 0,
        resultType = ResultType.Lose,
        winningLines = emptyList(),
        scatterCount = scatterPositions.size,
        scatterPositions = scatterPositions
    )
}
