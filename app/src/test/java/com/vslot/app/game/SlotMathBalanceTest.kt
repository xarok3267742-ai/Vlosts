package com.vslot.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class SlotMathBalanceTest {
    @Test
    fun `published math balance report mirrors current exact metrics`() {
        val report = Path.of("../qa/slot_math_balance.md").toFile().readText()

        listOf(
            "| Neon Nights | Low | 86.2452% | 94.9975% |",
            "| Ocean Pearl | Medium-low | 86.2418% | 94.9984% |",
            "| Violet Fortune | Medium | 86.1379% | 94.9323% |",
            "| Roman Reels | Medium-high | 86.1818% | 94.9922% |",
            "| Pharaoh Gold | High | 86.2228% | 94.9999% |"
        ).forEach { expectedRow ->
            assertTrue("Math report is stale: $expectedRow", report.contains(expectedRow))
        }
    }

    @Test
    fun `test slot config mirrors production asset`() {
        assertEquals(
            Path.of("src/main/assets/slots_config.json").toFile().readText(),
            Path.of("src/test/resources/slots_config.json").toFile().readText()
        )
    }

    @Test
    fun `configured reel strips stay near target ninety five percent all in rtp`() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }
        val slots = SlotConfigParser().parse(json)
        val engine = SlotEngine()

        slots.forEach { slot ->
            val metricsByLineCount = sampledMetricsByLineCount(slot, engine)
            var previousPayoutHitRate = 0.0

            (1..slot.paylines).forEach { lineCount ->
                val metrics = metricsByLineCount.getValue(lineCount)

                assertTrue(
                    "${slot.id} $lineCount-line all-in RTP is too far from 95%: ${metrics.allInRtp}",
                    abs(metrics.allInRtp - TARGET_RTP) <= RTP_TOLERANCE
                )
                assertTrue(
                    "${slot.id} $lineCount-line direct RTP should stay below all-in RTP because free spins have expected value",
                    metrics.paidDirectRtp < metrics.allInRtp
                )
                assertTrue(
                    "${slot.id} $lineCount-line free-spin direct RTP must exceed paid direct RTP",
                    metrics.freeSpinDirectRtp > metrics.paidDirectRtp
                )
                assertTrue("${slot.id} $lineCount-line exact paid bonus rate is too low: ${metrics.paidBonusRate}", metrics.paidBonusRate >= 0.005)
                assertTrue("${slot.id} $lineCount-line exact paid bonus rate is too high: ${metrics.paidBonusRate}", metrics.paidBonusRate <= 0.03)
                assertTrue(
                    "${slot.id} payout hit rate should not fall when active lines increase",
                    metrics.payoutHitRate >= previousPayoutHitRate
                )
                previousPayoutHitRate = metrics.payoutHitRate
                assertEquals(
                    "${slot.id} paid payout classes must partition payout hits",
                    metrics.payoutHitRate,
                    metrics.partialReturnRate + metrics.breakEvenRate + metrics.netWinRate,
                    RATE_EPSILON
                )
            }

            val maxLineMetrics = metricsByLineCount.getValue(slot.paylines)
            assertTrue("${slot.id} exact payout hit rate is too low: ${maxLineMetrics.payoutHitRate}", maxLineMetrics.payoutHitRate >= 0.30)
            assertTrue("${slot.id} exact payout hit rate is too high: ${maxLineMetrics.payoutHitRate}", maxLineMetrics.payoutHitRate <= 0.46)
            assertTrue("${slot.id} exact net win rate is too low: ${maxLineMetrics.netWinRate}", maxLineMetrics.netWinRate >= 0.20)
            assertTrue("${slot.id} exact net win rate is too high: ${maxLineMetrics.netWinRate}", maxLineMetrics.netWinRate <= 0.36)
            println(
                "${slot.id}: allInRtp=${maxLineMetrics.allInRtp}, " +
                    "paidDirectRtp=${maxLineMetrics.paidDirectRtp}, " +
                    "freeSpinDirectRtp=${maxLineMetrics.freeSpinDirectRtp}, " +
                    "payoutHitRate=${maxLineMetrics.payoutHitRate}, " +
                    "partialReturnRate=${maxLineMetrics.partialReturnRate}, " +
                    "breakEvenRate=${maxLineMetrics.breakEvenRate}, " +
                    "netWinRate=${maxLineMetrics.netWinRate}, " +
                    "paidBonusRate=${maxLineMetrics.paidBonusRate}, " +
                    "freeSpinRetriggerRate=${maxLineMetrics.freeSpinBonusRate}"
            )
            assertEquals(
                "${slot.id} maximum single-spin payout changed",
                MAXIMUM_WIN_AT_MINIMUM_LINE_BET.getValue(slot.id),
                maxLineMetrics.maximumWin
            )
        }
    }

    @Test
    fun `configured slots have deliberately distinct volatility profiles`() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }
        val slots = SlotConfigParser().parse(json)
        val metrics = slots.associate { slot -> slot.id to exactSingleLineMetrics(slot) }

        val orderedProfiles = listOf(
            "neon_nights" to 18.5..20.5,
            "ocean_pearl" to 20.5..22.5,
            "violet_fortune" to 22.5..24.5,
            "roman_reels" to 25.5..28.5,
            "pharaoh_gold" to 36.0..40.0
        )
        orderedProfiles.forEach { (slotId, expectedVariance) ->
            val slotMetrics = metrics.getValue(slotId)
            assertTrue(
                "$slotId line RTP drifted outside the balanced range: ${slotMetrics.meanPayout}",
                slotMetrics.meanPayout in MIN_BALANCED_LINE_RTP..MAX_BALANCED_LINE_RTP
            )
            assertTrue(
                "$slotId volatility drifted outside its designed profile: ${slotMetrics.payoutVariance}",
                slotMetrics.payoutVariance in expectedVariance
            )
        }
        orderedProfiles.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "${higher.first} must remain more volatile than ${lower.first}",
                metrics.getValue(higher.first).payoutVariance >
                    metrics.getValue(lower.first).payoutVariance
            )
        }
    }

    @Test
    fun `configured slots keep full round volatility profiles across free spins`() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }
        val slots = SlotConfigParser().parse(json)
        val metrics = slots.associate { slot ->
            slot.id to sampledFullRoundMetrics(slot, FULL_ROUND_SAMPLE_SEED)
        }
        println(
            metrics.entries.joinToString(separator = "\n") { (slotId, value) ->
                "$slotId: fullRoundRtp=${value.meanPayout}, " +
                    "fullRoundStdDev=${value.payoutStandardDeviation}, " +
                    "freeSpinsPlayed=${value.freeSpinsPlayed}, " +
                    "freeSpinRetriggers=${value.freeSpinRetriggers}"
            }
        )

        val orderedProfiles = listOf(
            "neon_nights" to 2.02..2.10,
            "ocean_pearl" to 2.07..2.15,
            "violet_fortune" to 2.10..2.19,
            "roman_reels" to 2.18..2.28,
            "pharaoh_gold" to 2.50..2.62
        )
        orderedProfiles.forEach { (slotId, expectedStandardDeviation) ->
            val slotMetrics = metrics.getValue(slotId)
            assertTrue(
                "$slotId sampled full-round RTP drifted too far from 95%: ${slotMetrics.meanPayout}",
                abs(slotMetrics.meanPayout - TARGET_RTP) <= FULL_ROUND_RTP_TOLERANCE
            )
            assertTrue("$slotId must exercise awarded free spins", slotMetrics.freeSpinsPlayed > 0)
            assertTrue("$slotId must exercise free-spin retriggers", slotMetrics.freeSpinRetriggers > 0)
            assertTrue(
                "$slotId full-round volatility drifted outside its designed profile: " +
                    slotMetrics.payoutStandardDeviation,
                slotMetrics.payoutStandardDeviation in expectedStandardDeviation
            )
        }
        orderedProfiles.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "${higher.first} (${metrics.getValue(higher.first).payoutStandardDeviation}) must remain " +
                    "more volatile than ${lower.first} " +
                    "(${metrics.getValue(lower.first).payoutStandardDeviation}) across full rounds",
                metrics.getValue(higher.first).payoutStandardDeviation >
                    metrics.getValue(lower.first).payoutStandardDeviation
            )
        }
    }

    private fun sampledFullRoundMetrics(slot: SlotConfig, seed: Long): FullRoundMetrics {
        assertEquals("${slot.id} must expose all ten paylines", ACTIVE_PAYLINES, slot.paylines)
        val engine = SlotEngine(SeededSlotRng(seed))
        val bet = slot.bets.first()
        val totalBet = bet * ACTIVE_PAYLINES
        var freeSpinsPlayed = 0
        var freeSpinRetriggers = 0
        var meanPayout = 0.0
        var payoutSquaredDeviation = 0.0

        repeat(FULL_ROUND_SAMPLES) { sampleIndex ->
            var pendingSpins = 1
            var isPaidSpin = true
            var roundWin = 0L
            var roundSpins = 0

            while (pendingSpins > 0) {
                roundSpins += 1
                if (roundSpins > MAX_SPINS_PER_ROUND) {
                    throw AssertionError("${slot.id} free-spin chain did not terminate")
                }
                pendingSpins -= 1
                val result = engine.spin(
                    slot,
                    bet = bet,
                    lines = ACTIVE_PAYLINES,
                    isFreeSpin = !isPaidSpin
                )
                assertEquals("Every spin must use all configured lines", totalBet, result.totalBet)
                roundWin += result.winAmount.toLong()

                if (!isPaidSpin) {
                    freeSpinsPlayed += 1
                    if (result.freeSpinsAwarded > 0) freeSpinRetriggers += 1
                }
                isPaidSpin = false
                pendingSpins += result.freeSpinsAwarded
            }

            val payout = roundWin.toDouble() / totalBet.toDouble()
            val sampleCount = sampleIndex + 1
            val delta = payout - meanPayout
            meanPayout += delta / sampleCount.toDouble()
            payoutSquaredDeviation += delta * (payout - meanPayout)
        }

        return FullRoundMetrics(
            meanPayout = meanPayout,
            payoutStandardDeviation = sqrt(payoutSquaredDeviation / FULL_ROUND_SAMPLES.toDouble()),
            freeSpinsPlayed = freeSpinsPlayed,
            freeSpinRetriggers = freeSpinRetriggers
        )
    }

    private fun exactSingleLineMetrics(slot: SlotConfig): SingleLineMetrics {
        val symbolCountsByReel = slot.reelStrips.map { strip ->
            slot.symbols.associateWith { symbol -> strip.count { it == symbol } }
        }
        val selectedSymbols = MutableList(slot.reels) { slot.symbols.first() }
        var totalStopWeight = 0L
        var weightedPayout = 0L
        var weightedSquaredPayout = 0L

        fun visit(reelIndex: Int, stopWeight: Long) {
            if (reelIndex == slot.reels) {
                val payout = singleLinePayout(slot, selectedSymbols)
                totalStopWeight += stopWeight
                weightedPayout += stopWeight * payout.toLong()
                weightedSquaredPayout += stopWeight * payout.toLong() * payout.toLong()
                return
            }
            symbolCountsByReel[reelIndex].forEach { (symbol, count) ->
                if (count <= 0) return@forEach
                selectedSymbols[reelIndex] = symbol
                visit(reelIndex + 1, stopWeight * count.toLong())
            }
        }

        visit(reelIndex = 0, stopWeight = 1L)
        val expectedStopWeight = slot.reelStrips.fold(1L) { total, strip -> total * strip.size }
        assertEquals("Single-line distribution must cover every stop combination", expectedStopWeight, totalStopWeight)
        val meanPayout = weightedPayout.toDouble() / totalStopWeight.toDouble()
        val meanSquaredPayout = weightedSquaredPayout.toDouble() / totalStopWeight.toDouble()
        return SingleLineMetrics(
            meanPayout = meanPayout,
            payoutVariance = meanSquaredPayout - meanPayout * meanPayout
        )
    }

    private fun singleLinePayout(slot: SlotConfig, symbols: List<String>): Int {
        return slot.payouts.keys
            .filter { it != slot.scatter }
            .mapNotNull { target ->
                val count = matchingSymbolCount(slot, symbols, target)
                if (count < 3) return@mapNotNull null
                slot.payouts[target]?.get(count)
            }
            .maxOrNull()
            ?: 0
    }

    private fun sampledMetricsByLineCount(slot: SlotConfig, engine: SlotEngine): Map<Int, SlotMetrics> {
        val bet = slot.bets.first()
        var spins = 0
        val totalBets = LongArray(slot.paylines + 1)
        val paidTotalWins = LongArray(slot.paylines + 1)
        val freeSpinTotalWins = LongArray(slot.paylines + 1)
        val payoutHits = IntArray(slot.paylines + 1)
        val partialReturns = IntArray(slot.paylines + 1)
        val breakEvens = IntArray(slot.paylines + 1)
        val netWins = IntArray(slot.paylines + 1)
        val paidBonuses = IntArray(slot.paylines + 1)
        val freeSpinBonuses = IntArray(slot.paylines + 1)
        val maximumWins = IntArray(slot.paylines + 1)

        forEachStopCombination(slot.reelStrips) { stops ->
            val reels = slot.reelStrips.mapIndexed { reelIndex, strip ->
                List(slot.rows) { row -> strip[(stops[reelIndex] + row) % strip.size] }
            }
            val freeSpinReels = slot.reelStrips.indices.map { reelIndex ->
                List(slot.rows) { row ->
                    slot.reelSymbolAt(reelIndex, stops[reelIndex] + row, isFreeSpin = true)
                }
            }
            val maxLineResult = engine.evaluate(slot, reels, bet = bet, lines = slot.paylines, stopIndexes = stops.toList())
            val freeSpinMaxLineResult = engine.evaluate(
                slot,
                freeSpinReels,
                bet = bet,
                lines = slot.paylines,
                stopIndexes = stops.toList(),
                isFreeSpin = true
            )
            val lineWins = SlotEngine.PAYLINE_ROWS
                .take(slot.paylines)
                .map { rows -> lineWinAmount(slot, reels, rows, bet) }
            val freeSpinLineWins = SlotEngine.PAYLINE_ROWS
                .take(slot.paylines)
                .map { rows -> lineWinAmount(slot, freeSpinReels, rows, bet) }
            val scatterMultiplier = slot.scatterBonus[maxLineResult.scatterCount] ?: 0
            val freeSpinScatterMultiplier = slot.scatterBonus[freeSpinMaxLineResult.scatterCount] ?: 0
            var cumulativeLineWin = 0
            var cumulativeFreeSpinLineWin = 0
            spins += 1

            (1..slot.paylines).forEach { lineCount ->
                cumulativeLineWin += lineWins[lineCount - 1]
                cumulativeFreeSpinLineWin += freeSpinLineWins[lineCount - 1]
                val totalBet = bet * lineCount
                val totalWin = cumulativeLineWin + scatterMultiplier * totalBet
                val freeSpinTotalWin = cumulativeFreeSpinLineWin + freeSpinScatterMultiplier * totalBet
                if (lineCount == slot.paylines && totalWin != maxLineResult.winAmount) {
                    throw AssertionError("Exact RTP calculator diverged from SlotEngine for ${slot.id}.")
                }
                if (lineCount == slot.paylines && freeSpinTotalWin != freeSpinMaxLineResult.winAmount) {
                    throw AssertionError("Exact free-spin maximum calculator diverged from SlotEngine for ${slot.id}.")
                }
                totalBets[lineCount] += totalBet.toLong()
                paidTotalWins[lineCount] += totalWin.toLong()
                freeSpinTotalWins[lineCount] += freeSpinTotalWin.toLong()
                maximumWins[lineCount] = maxOf(maximumWins[lineCount], totalWin, freeSpinTotalWin)
                if (totalWin > 0) payoutHits[lineCount] += 1
                when {
                    totalWin in 1 until totalBet -> partialReturns[lineCount] += 1
                    totalWin == totalBet -> breakEvens[lineCount] += 1
                    totalWin > totalBet -> netWins[lineCount] += 1
                }
                if (maxLineResult.resultType == ResultType.Bonus) paidBonuses[lineCount] += 1
                if (freeSpinMaxLineResult.resultType == ResultType.Bonus) {
                    freeSpinBonuses[lineCount] += 1
                }
            }
        }

        return (1..slot.paylines).associateWith { lineCount ->
            val paidDirectRtp = paidTotalWins[lineCount].toDouble() / totalBets[lineCount].toDouble()
            val freeSpinDirectRtp = freeSpinTotalWins[lineCount].toDouble() / totalBets[lineCount].toDouble()
            val paidBonusRate = paidBonuses[lineCount].toDouble() / spins.toDouble()
            val freeSpinBonusRate = freeSpinBonuses[lineCount].toDouble() / spins.toDouble()
            val freeSpinsRetriggerRate = metricsFreeSpinAwardRate(freeSpinBonusRate)
            val expectedFreeSpinsPerPaidSpin = SlotEngine.FREE_SPINS_BONUS_AWARD * paidBonusRate /
                (1.0 - freeSpinsRetriggerRate)

            SlotMetrics(
                paidDirectRtp = paidDirectRtp,
                freeSpinDirectRtp = freeSpinDirectRtp,
                allInRtp = paidDirectRtp + expectedFreeSpinsPerPaidSpin * freeSpinDirectRtp,
                payoutHitRate = payoutHits[lineCount].toDouble() / spins.toDouble(),
                partialReturnRate = partialReturns[lineCount].toDouble() / spins.toDouble(),
                breakEvenRate = breakEvens[lineCount].toDouble() / spins.toDouble(),
                netWinRate = netWins[lineCount].toDouble() / spins.toDouble(),
                paidBonusRate = paidBonusRate,
                freeSpinBonusRate = freeSpinBonusRate,
                maximumWin = maximumWins[lineCount]
            )
        }
    }

    private fun forEachStopCombination(reelStrips: List<List<String>>, block: (IntArray) -> Unit) {
        val stops = IntArray(reelStrips.size)

        fun visit(reelIndex: Int) {
            if (reelIndex == reelStrips.size) {
                block(stops.copyOf())
                return
            }
            reelStrips[reelIndex].indices.forEach { stopIndex ->
                stops[reelIndex] = stopIndex
                visit(reelIndex + 1)
            }
        }

        visit(reelIndex = 0)
    }

    private fun lineWinAmount(
        slot: SlotConfig,
        reels: List<List<String>>,
        rows: List<Int>,
        bet: Int
    ): Int {
        val symbols = rows.mapIndexed { reelIndex, rowIndex ->
            reels[reelIndex][rowIndex]
        }
        return slot.payouts.keys
            .filter { it != slot.scatter }
            .mapNotNull { target ->
                val count = matchingSymbolCount(slot, symbols, target)
                if (count < 3) return@mapNotNull null
                val multiplier = slot.payouts[target]?.get(count) ?: return@mapNotNull null
                multiplier * bet
            }
            .maxOrNull()
            ?: 0
    }

    private fun matchingSymbolCount(slot: SlotConfig, symbols: List<String>, target: String): Int {
        var count = 0
        for (symbol in symbols) {
            if (symbol == slot.scatter) break
            if (symbol == target || symbol == slot.wild) {
                count += 1
            } else {
                break
            }
        }
        return count
    }

    private fun metricsFreeSpinAwardRate(bonusRate: Double): Double {
        val freeSpinAwardRate = SlotEngine.FREE_SPINS_BONUS_AWARD * bonusRate
        assertTrue("Free spins must not mathematically retrigger forever.", freeSpinAwardRate < 1.0)
        return freeSpinAwardRate
    }

    private data class SlotMetrics(
        val paidDirectRtp: Double,
        val freeSpinDirectRtp: Double,
        val allInRtp: Double,
        val payoutHitRate: Double,
        val partialReturnRate: Double,
        val breakEvenRate: Double,
        val netWinRate: Double,
        val paidBonusRate: Double,
        val freeSpinBonusRate: Double,
        val maximumWin: Int
    )

    private data class SingleLineMetrics(
        val meanPayout: Double,
        val payoutVariance: Double
    )

    private data class FullRoundMetrics(
        val meanPayout: Double,
        val payoutStandardDeviation: Double,
        val freeSpinsPlayed: Int,
        val freeSpinRetriggers: Int
    )

    private class SeededSlotRng(seed: Long) : SlotRng {
        private val random = Random(seed)

        override fun nextInt(bound: Int): Int = random.nextInt(bound)
    }

    private companion object {
        const val ACTIVE_PAYLINES = 10
        const val FULL_ROUND_SAMPLES = 100_000
        const val FULL_ROUND_SAMPLE_SEED = 0x5EED_10L
        const val FULL_ROUND_RTP_TOLERANCE = 0.03
        const val MAX_SPINS_PER_ROUND = 1_000
        const val TARGET_RTP = 0.95
        const val RTP_TOLERANCE = 0.0025
        const val RATE_EPSILON = 1e-12
        const val MIN_BALANCED_LINE_RTP = 0.773
        const val MAX_BALANCED_LINE_RTP = 0.777
        val MAXIMUM_WIN_AT_MINIMUM_LINE_BET = mapOf(
            "violet_fortune" to 3_920,
            "roman_reels" to 5_170,
            "neon_nights" to 3_600,
            "pharaoh_gold" to 11_060,
            "ocean_pearl" to 6_970
        )
    }
}
