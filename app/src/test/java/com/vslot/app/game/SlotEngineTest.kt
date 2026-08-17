package com.vslot.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotEngineTest {
    private val config = SlotConfig(
        id = "test",
        name = "Test Slot",
        theme = SlotTheme.Violet,
        reels = 5,
        rows = 3,
        paylines = 10,
        wild = "wild",
        scatter = "scatter",
        symbols = listOf("wild", "scatter", "a", "b"),
        bets = listOf(10, 25),
        payouts = mapOf(
            "wild" to mapOf(3 to 5, 4 to 10, 5 to 20),
            "a" to mapOf(3 to 2, 4 to 4, 5 to 8),
            "b" to mapOf(3 to 1, 4 to 2, 5 to 4)
        ),
        scatterBonus = mapOf(3 to 5, 4 to 10, 5 to 25),
        reelStrips = List(5) { listOf("wild", "scatter", "a", "b") },
        freeSpinReelStrips = List(5) { listOf("wild", "b", "a", "scatter") }
    )

    @Test
    fun `wild symbols substitute line targets`() {
        val reels = listOf(
            listOf("b", "wild", "b"),
            listOf("b", "a", "b"),
            listOf("b", "wild", "b"),
            listOf("b", "a", "b"),
            listOf("b", "b", "b")
        )

        val result = SlotEngine().evaluate(config, reels, bet = 10)
        val aLine = result.winningLines.first { it.symbol == "a" && it.count == 4 }

        assertTrue(result.winAmount >= 80)
        assertEquals(40, aLine.amount)
        assertEquals(
            listOf(
                SymbolPosition(reel = 0, row = 1),
                SymbolPosition(reel = 1, row = 1),
                SymbolPosition(reel = 2, row = 1),
                SymbolPosition(reel = 3, row = 1)
            ),
            aLine.positions
        )
    }

    @Test
    fun `leading wilds choose highest paying line interpretation`() {
        val reels = listOf(
            listOf("a", "wild", "a"),
            listOf("a", "wild", "a"),
            listOf("a", "wild", "a"),
            listOf("a", "wild", "a"),
            listOf("a", "b", "a")
        )

        val result = SlotEngine().evaluate(config, reels, bet = 10, lines = 1)

        assertEquals(1, result.winningLines.size)
        assertEquals("wild", result.winningLines.first().symbol)
        assertEquals(4, result.winningLines.first().count)
        assertEquals(100, result.winAmount)
        assertEquals(
            listOf(
                SymbolPosition(reel = 0, row = 1),
                SymbolPosition(reel = 1, row = 1),
                SymbolPosition(reel = 2, row = 1),
                SymbolPosition(reel = 3, row = 1)
            ),
            result.winningLines.first().positions
        )
    }

    @Test
    fun `scatter symbols do not pay as normal lines`() {
        val reels = listOf(
            listOf("b", "scatter", "b"),
            listOf("b", "wild", "b"),
            listOf("b", "wild", "b"),
            listOf("b", "wild", "b"),
            listOf("b", "wild", "b")
        )
        val configWithScatterPayout = config.copy(
            payouts = config.payouts + ("scatter" to mapOf(3 to 99, 4 to 199, 5 to 299))
        )

        val result = SlotEngine().evaluate(configWithScatterPayout, reels, bet = 10, lines = 1)

        assertEquals(ResultType.Lose, result.resultType)
        assertEquals(0, result.winAmount)
        assertTrue(result.winningLines.none { it.symbol == "scatter" })
    }

    @Test
    fun `wild symbols do not substitute scatter targets`() {
        val reels = listOf(
            listOf("b", "wild", "b"),
            listOf("b", "scatter", "b"),
            listOf("b", "wild", "b"),
            listOf("b", "wild", "b"),
            listOf("b", "wild", "b")
        )
        val configWithScatterPayout = config.copy(
            payouts = config.payouts + ("scatter" to mapOf(3 to 99, 4 to 199, 5 to 299))
        )

        val result = SlotEngine().evaluate(configWithScatterPayout, reels, bet = 10, lines = 1)

        assertEquals(ResultType.Lose, result.resultType)
        assertEquals(0, result.winAmount)
        assertTrue(result.winningLines.none { it.symbol == "scatter" })
    }

    @Test
    fun `scatter symbols add bonus win`() {
        val reels = listOf(
            listOf("scatter", "b", "b"),
            listOf("b", "scatter", "b"),
            listOf("b", "b", "scatter"),
            listOf("b", "a", "b"),
            listOf("b", "a", "b")
        )

        val result = SlotEngine().evaluate(config, reels, bet = 25)

        assertEquals(ResultType.Bonus, result.resultType)
        assertEquals(3, result.scatterCount)
        assertEquals(SlotEngine.FREE_SPINS_BONUS_AWARD, result.freeSpinsAwarded)
        assertEquals(
            listOf(
                SymbolPosition(reel = 0, row = 0),
                SymbolPosition(reel = 1, row = 1),
                SymbolPosition(reel = 2, row = 2)
            ),
            result.scatterPositions
        )
        assertTrue(result.winAmount >= 125)
    }

    @Test
    fun `active lines limit evaluated paylines and increase total bet`() {
        val reels = listOf(
            listOf("a", "b", "b"),
            listOf("a", "a", "b"),
            listOf("a", "b", "b"),
            listOf("a", "a", "b"),
            listOf("a", "b", "b")
        )

        val oneLine = SlotEngine().evaluate(config, reels, bet = 10, lines = 1)
        val twoLines = SlotEngine().evaluate(config, reels, bet = 10, lines = 2)

        assertEquals(1, oneLine.lines)
        assertEquals(10, oneLine.totalBet)
        assertEquals(0, oneLine.winningLines.size)
        assertEquals(2, twoLines.lines)
        assertEquals(20, twoLines.totalBet)
        assertEquals(1, twoLines.winningLines.size)
        assertEquals(80, twoLines.winAmount)
    }

    @Test
    fun `spin uses provided rng for deterministic reel stops`() {
        val rng = object : SlotRng {
            private var index = 0
            override fun nextInt(bound: Int): Int {
                val value = index % bound
                index += 1
                return value
            }
        }

        val result = SlotEngine(rng).spin(config, bet = 10)

        assertEquals(5, result.reels.size)
        assertEquals(3, result.reels.first().size)
        assertEquals("wild", result.reels[0][0])
        assertEquals("scatter", result.reels[0][1])
        assertEquals("a", result.reels[0][2])
    }

    @Test
    fun `spin rejects missing reel strips before consuming rng`() {
        var rngCalls = 0
        val rng = object : SlotRng {
            override fun nextInt(bound: Int): Int {
                rngCalls += 1
                return 0
            }
        }

        assertFailureContains("must define 5 paid reel strips") {
            SlotEngine(rng).spin(config.copy(reelStrips = emptyList()), bet = 10)
        }
        assertEquals(0, rngCalls)
    }

    @Test
    fun `spin uses reel strip stop positions when strips are configured`() {
        val reelStrip = listOf("a", "b", "scatter", "wild", "a", "b")
        val stripConfig = config.copy(
            reelStrips = List(config.reels) { reelStrip }
        )
        val stops = intArrayOf(0, 1, 2, 3, 4)
        val rng = object : SlotRng {
            private var index = 0
            override fun nextInt(bound: Int): Int {
                val value = stops[index] % bound
                index += 1
                return value
            }
        }

        val result = SlotEngine(rng).spin(stripConfig, bet = 10, lines = 1)

        assertEquals(listOf("a", "b", "scatter"), result.reels[0])
        assertEquals(listOf("b", "scatter", "wild"), result.reels[1])
        assertEquals(listOf("scatter", "wild", "a"), result.reels[2])
        assertEquals(listOf("wild", "a", "b"), result.reels[3])
        assertEquals(listOf("a", "b", "a"), result.reels[4])
        assertEquals(stops.toList(), result.stopIndexes)
    }

    @Test
    fun `spin and authoritative stop evaluation produce identical results`() {
        val stops = listOf(0, 1, 2, 3, 0)
        val rng = object : SlotRng {
            private var index = 0

            override fun nextInt(bound: Int): Int = stops[index++].mod(bound)
        }

        val spun = SlotEngine(rng).spin(config, bet = 25, lines = 7)
        val recovered = SlotEngine().evaluateStops(config, stops, bet = 25, lines = 7)

        assertEquals(spun, recovered)
    }

    @Test
    fun `free spins use their configured physical reel order`() {
        val freeSpinConfig = config.copy(
            freeSpinReelStrips = List(config.reels) { listOf("wild", "b", "a", "scatter") }
        )
        val stops = List(freeSpinConfig.reels) { 0 }

        val paid = SlotEngine().evaluateStops(freeSpinConfig, stops, bet = 10, lines = 1)
        val free = SlotEngine().evaluateStops(
            freeSpinConfig,
            stops,
            bet = 10,
            lines = 1,
            isFreeSpin = true
        )

        assertEquals(listOf("wild", "scatter", "a"), paid.reels.first())
        assertEquals(listOf("wild", "b", "a"), free.reels.first())
        assertFalse(paid.isFreeSpin)
        assertTrue(free.isFreeSpin)
        assertTrue(paid.reels != free.reels)
    }

    @Test
    fun `free spin windows are adjacent positions on the configured free strip`() {
        val stops = listOf(3, 0, 1, 2, 3)

        val free = SlotEngine().evaluateStops(
            config,
            stops,
            bet = 10,
            lines = 1,
            isFreeSpin = true
        )

        assertEquals(listOf("scatter", "wild", "b"), free.reels[0])
        assertEquals(listOf("wild", "b", "a"), free.reels[1])
        assertEquals(listOf("b", "a", "scatter"), free.reels[2])
    }

    @Test
    fun `evaluate rejects reel windows that do not match authoritative stops`() {
        val stops = listOf(0, 1, 2, 3, 0)
        val authoritative = SlotEngine().evaluateStops(config, stops, bet = 10, lines = 1)
        val impossibleReels = authoritative.reels.mapIndexed { reelIndex, reel ->
            if (reelIndex == 0) reel.toMutableList().also { it[0] = "b" } else reel
        }

        assertFailureContains("must match the supplied stop indexes") {
            SlotEngine().evaluate(
                config,
                impossibleReels,
                bet = 10,
                lines = 1,
                stopIndexes = stops
            )
        }
    }

    @Test
    fun `evaluate rejects unsupported bet and malformed reel windows`() {
        val engine = SlotEngine()
        val reels = listOf(
            listOf("a", "b", "b"),
            listOf("a", "a", "b"),
            listOf("a", "b", "b"),
            listOf("a", "a", "b"),
            listOf("a", "b", "b")
        )

        assertFailureContains("Unsupported bet") {
            engine.evaluate(config, reels, bet = 15)
        }
        assertFailureContains("must contain 5 reels") {
            engine.evaluate(config, reels.dropLast(1), bet = 10)
        }
        assertFailureContains("must contain 3 visible symbols") {
            engine.evaluate(config, reels.toMutableList().also { it[2] = listOf("a", "b") }, bet = 10)
        }
        assertFailureContains("unknown symbol") {
            engine.evaluate(config, reels.toMutableList().also { it[3] = listOf("a", "mystery", "b") }, bet = 10)
        }
        assertFailureContains("one index per reel") {
            engine.evaluate(config, reels, bet = 10, stopIndexes = listOf(0, 1, 2))
        }
        assertFailureContains("non-negative") {
            engine.evaluate(config, reels, bet = 10, stopIndexes = listOf(0, 1, 2, 3, -1))
        }
        assertFailureContains("exceeds reel strip length") {
            engine.evaluate(
                config.copy(reelStrips = List(config.reels) { listOf("wild", "scatter", "a", "b") }),
                reels,
                bet = 10,
                stopIndexes = listOf(0, 1, 2, 3, 4)
            )
        }
    }

    @Test
    fun `evaluate rejects overflowing total bet instead of wrapping negative`() {
        val unsafeConfig = config.copy(bets = listOf(Int.MAX_VALUE))
        val reels = List(unsafeConfig.reels) { listOf("a", "b", "b") }

        assertFailureContains("Total bet exceeds") {
            SlotEngine().evaluate(unsafeConfig, reels, bet = Int.MAX_VALUE, lines = 10)
        }
    }

    @Test
    fun `evaluate rejects overflowing line payout instead of wrapping negative`() {
        val unsafeConfig = config.copy(
            bets = listOf(2),
            payouts = config.payouts +
                ("a" to mapOf(3 to Int.MAX_VALUE, 4 to Int.MAX_VALUE, 5 to Int.MAX_VALUE))
        )
        val reels = listOf(
            listOf("b", "a", "b"),
            listOf("b", "a", "b"),
            listOf("b", "a", "b"),
            listOf("b", "b", "b"),
            listOf("b", "b", "b")
        )

        assertFailureContains("Line payout exceeds") {
            SlotEngine().evaluate(unsafeConfig, reels, bet = 2, lines = 1)
        }
    }

    private fun assertFailureContains(messagePart: String, block: () -> Unit) {
        val result = runCatching(block)

        assertTrue(result.isFailure)
        assertTrue(
            "Expected failure containing '$messagePart' but was '${result.exceptionOrNull()?.message}'",
            result.exceptionOrNull()?.message.orEmpty().contains(messagePart)
        )
    }
}
