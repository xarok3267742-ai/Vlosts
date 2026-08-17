package com.vslot.app.ui.slot

import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotConfig
import com.vslot.app.game.SlotConfigParser
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
    fun `scatter chase requires scatters on the first two reels`() {
        val misplacedScatters = result(listOf(SymbolPosition(0, 0), SymbolPosition(2, 1)))
        val lateScatters = result(listOf(SymbolPosition(1, 0), SymbolPosition(2, 1)))

        (0 until config().reels).forEach { column ->
            assertFalse(SlotSpinTimeline.hasScatterChase(config(), misplacedScatters, column))
            assertFalse(SlotSpinTimeline.hasScatterChase(config(), lateScatters, column))
        }
    }

    @Test
    fun `losing scatter chase stays below one per bonus trigger`() {
        bundledConfigs().forEach { slot ->
            val scatterVisibleStops = slot.reelStrips.map { strip ->
                strip.indices.count { stop ->
                    (0 until slot.rows).any { row ->
                        strip[(stop + row) % strip.size] == slot.scatter
                    }
                }
            }
            var losingChaseWeight = 0L
            var bonusWeight = 0L
            for (mask in 0 until (1 shl slot.reels)) {
                var weight = 1L
                for (reel in 0 until slot.reels) {
                    val scatterStops = scatterVisibleStops[reel]
                    val selectedStops = if (mask and (1 shl reel) != 0) {
                        scatterStops
                    } else {
                        slot.reelStrips[reel].size - scatterStops
                    }
                    weight *= selectedStops.toLong()
                }
                val scatterCount = Integer.bitCount(mask)
                if (scatterCount >= 3) bonusWeight += weight
                if (mask and 0b11 == 0b11 && scatterCount < 3) losingChaseWeight += weight
            }

            val ratio = losingChaseWeight.toDouble() / bonusWeight.toDouble()
            assertTrue(
                "${slot.id} losing chase/bonus ratio is too high: $ratio",
                ratio <= MAX_LOSING_CHASE_PER_BONUS
            )
        }
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

    private fun bundledConfigs(): List<SlotConfig> {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }
        return SlotConfigParser().parse(json)
    }

    private companion object {
        const val MAX_LOSING_CHASE_PER_BONUS = 1.0
    }
}
