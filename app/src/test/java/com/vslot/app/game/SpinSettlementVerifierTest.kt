package com.vslot.app.game

import com.vslot.app.data.PendingSpinSettlement
import com.vslot.app.data.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpinSettlementVerifierTest {
    private val config = SlotConfig(
        id = "test-slot",
        name = "Test",
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
    private val engine = SlotEngine()
    private val catalog = object : SlotCatalog {
        override fun getSlot(slotId: String): SlotConfig = config
    }
    private val verifier = SpinSettlementVerifier(catalog, engine)

    @Test
    fun `valid settlement is rebuilt exactly from authoritative stops`() {
        val settlement = settlement()
        val expected = engine.evaluateStops(
            config,
            settlement.stopIndexes,
            settlement.lineBet,
            settlement.lines
        )

        val verified = verifier.verify(settlement)

        assertEquals(expected, verified?.visualResult)
        assertEquals(expected.winAmount, verified?.winAmount)
        assertEquals(expected.freeSpinsAwarded, verified?.freeSpinsAwarded)
    }

    @Test
    fun `valid free spin settlement is rebuilt with bonus reel order`() {
        val settlement = settlement(isFreeSpin = true)
        val expected = engine.evaluateStops(
            config,
            settlement.stopIndexes,
            settlement.lineBet,
            settlement.lines,
            isFreeSpin = true
        )

        val verified = verifier.verify(settlement)

        assertEquals(expected, verified?.visualResult)
    }

    @Test
    fun `unknown slot unsupported version and config mismatch fail closed`() {
        val settlement = settlement()

        assertNull(verifier.verify(settlement.copy(slotId = "unknown-slot")))
        assertNull(verifier.verify(settlement.copy(mathVersion = SlotMathIdentity.VERSION + 1)))
        assertNull(verifier.verify(settlement.copy(configFingerprint = "0".repeat(64))))
    }

    @Test
    fun `tampered stops payout bonus and xp fail verification`() {
        val settlement = settlement()

        assertNull(verifier.verify(settlement.copy(stopIndexes = listOf(1, 1, 1, 1, 1))))
        assertNull(verifier.verify(settlement.copy(winAmount = settlement.winAmount + 1)))
        assertNull(
            verifier.verify(
                settlement.copy(freeSpinsAwarded = (settlement.freeSpinsAwarded + 1).coerceAtMost(1_000))
            )
        )
        assertNull(verifier.verify(settlement.copy(levelXpAwarded = settlement.levelXpAwarded + 1)))
    }

    private fun settlement(isFreeSpin: Boolean = false): PendingSpinSettlement {
        val stops = listOf(0, 1, 2, 3, 0)
        val result = engine.evaluateStops(
            config,
            stops,
            bet = 25,
            lines = 10,
            isFreeSpin = isFreeSpin
        )
        return PendingSpinSettlement(
            id = "verified-spin",
            processSessionId = "process",
            slotId = config.id,
            isFreeSpin = isFreeSpin,
            lineBet = result.bet,
            lines = result.lines,
            totalBet = result.totalBet,
            winAmount = result.winAmount,
            freeSpinsAwarded = result.freeSpinsAwarded,
            levelXpAwarded = PlayerState.xpForSpin(
                result.totalBet,
                isFreeSpin = isFreeSpin,
                result.winAmount
            ),
            mathVersion = SlotMathIdentity.VERSION,
            configFingerprint = SlotMathIdentity.fingerprint(config),
            stopIndexes = stops
        )
    }
}
