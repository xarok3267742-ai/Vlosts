package com.vslot.app.data

import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotMathIdentity
import com.vslot.app.game.SpinResult
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSpinSettlementTest {
    @Test
    fun `v3 journal round trips canonical immutable payload`() {
        val settlement = settlement(visualResult = visualResult())

        val serialized = settlement.serialize()
        val restored = deserializePendingSpinSettlement(serialized)

        assertEquals(PENDING_SPIN_JOURNAL_VERSION, JSONObject(serialized).getInt("version"))
        assertEquals(settlement.copy(visualResult = null), restored)
        assertNull(restored?.visualResult)
        assertTrue(JSONObject(serialized).getString("checksum").matches(Regex("[0-9a-f]{64}")))
        assertTrue(
            restored!!.matchesReservation(
                SLOT_ID,
                isFreeSpin = false,
                lineBet = 25,
                lines = 10,
                totalBet = 250
            )
        )
    }

    @Test
    fun `canonical payload and checksum have stable golden values`() {
        val first = settlement()
        val second = settlement(stopIndexes = first.stopIndexes.toList())

        assertArrayEquals(first.canonicalJournalPayload(), second.canonicalJournalPayload())
        assertEquals(GOLDEN_JOURNAL_CHECKSUM, first.journalChecksum())
        assertEquals(first.serialize(), second.serialize())
    }

    @Test
    fun `json key order does not change verified payload`() {
        val serialized = settlement().serialize()
        val original = JSONObject(serialized)
        val reordered = JSONObject().apply {
            original.keys().asSequence().toList().sortedDescending().forEach { key ->
                put(key, original.get(key))
            }
        }

        assertEquals(
            deserializePendingSpinSettlement(serialized),
            deserializePendingSpinSettlement(reordered.toString())
        )
    }

    @Test
    fun `tampering any protected authority is rejected`() {
        val serialized = settlement().serialize()
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("slotId", OTHER_SLOT_ID) },
            { it.put("mathVersion", SlotMathIdentity.VERSION + 1) },
            { it.put("configFingerprint", "b".repeat(64)) },
            { it.put("lineBet", 10) },
            { it.getJSONArray("stopIndexes").put(0, 3) },
            { it.put("winAmount", 401) },
            { it.put("freeSpinsAwarded", 0) },
            { it.put("levelXpAwarded", 15) },
            { it.put("checksum", "0".repeat(64)) }
        )

        mutations.forEach { mutate ->
            val tampered = JSONObject(serialized).also(mutate).toString()
            assertNull(tampered, deserializePendingSpinSettlement(tampered))
        }
    }

    @Test
    fun `legacy unknown malformed and extra-field journals are invalid`() {
        val valid = JSONObject(settlement().serialize())

        assertNull(deserializePendingSpinSettlement(""))
        assertNull(deserializePendingSpinSettlement("not-json"))
        assertNull(deserializePendingSpinSettlement(valid.put("version", 1).toString()))
        assertNull(deserializePendingSpinSettlement(valid.put("version", 2).toString()))
        assertNull(deserializePendingSpinSettlement(valid.put("version", 99).toString()))
        assertTrue(
            decodePendingSpinSettlement(valid.put("version", 99).toString()) is
                PendingSpinJournalDecode.UnsupportedFormat
        )
        assertNull(
            deserializePendingSpinSettlement(
                JSONObject(settlement().serialize()).put("unexpected", true).toString()
            )
        )
        assertTrue(
            decodePendingSpinSettlement("not-json") is PendingSpinJournalDecode.Corrupt
        )
        assertTrue(
            decodePendingSpinSettlement(
                JSONObject(settlement().serialize()).put("unexpected", true).toString()
            ) is PendingSpinJournalDecode.Corrupt
        )
        assertTrue(runCatching { settlement().copy(id = "\uD800").serialize() }.isFailure)
    }

    @Test
    fun `journal decoding is independent of future line XP and award policies`() {
        val futureSettlement = settlement().copy(
            mathVersion = SlotMathIdentity.VERSION + 1,
            lines = PlayerState.MAX_LINES + 1,
            totalBet = 25 * (PlayerState.MAX_LINES + 1),
            freeSpinsAwarded = 1_001,
            levelXpAwarded = PlayerState.maxLevelXp() + 1
        )

        val decoded = decodePendingSpinSettlement(futureSettlement.serialize())

        assertTrue(decoded is PendingSpinJournalDecode.Decoded)
        assertEquals(futureSettlement, (decoded as PendingSpinJournalDecode.Decoded).settlement)
    }

    @Test
    fun `stable refund envelope survives an incompatible settlement journal version`() {
        val settlement = settlement(isFreeSpin = true)
        val serializedEnvelope = settlement.toRefundEnvelope().serialize()
        val incompatibleJournal = JSONObject(settlement.serialize())
            .put("version", 99)
            .toString()

        assertNull(deserializePendingSpinSettlement(incompatibleJournal))
        assertEquals(
            settlement.toRefundEnvelope(),
            deserializePendingSpinRefundEnvelope(serializedEnvelope)
        )
        assertNull(
            deserializePendingSpinRefundEnvelope(
                JSONObject(serializedEnvelope).put("totalBet", 251).toString()
            )
        )
    }

    @Test
    fun `unsupported free spin stake is reconciled deterministically without losing total value`() {
        val migrated = reconciledFreeSpinStake(
            lockedLineBet = 25,
            lockedLines = 10,
            supportedBets = listOf(10, 50, 100),
            maxLines = 5
        )
        val repeated = reconciledFreeSpinStake(
            lockedLineBet = migrated.lineBet,
            lockedLines = migrated.lines,
            supportedBets = listOf(100, 10, 50, 50),
            maxLines = 5
        )
        val playerFavorableTie = reconciledFreeSpinStake(
            lockedLineBet = 15,
            lockedLines = 1,
            supportedBets = listOf(10, 20),
            maxLines = 1
        )

        assertEquals(FreeSpinStake(lineBet = 50, lines = 5), migrated)
        assertEquals(250L, migrated.totalBet)
        assertEquals(migrated, repeated)
        assertEquals(FreeSpinStake(lineBet = 20, lines = 1), playerFavorableTie)
    }

    @Test
    fun `presentation stores protected settlement and requires verification result`() {
        val verified = settlement(visualResult = visualResult())
        val presentation = deserializePendingSpinPresentation(
            verified.serializePresentation(claimedByProcessSessionId = "process-claim")
        )

        assertEquals(verified.copy(visualResult = null), presentation?.settlement)
        assertEquals("process-claim", presentation?.claimedByProcessSessionId)
        assertTrue(runCatching { verified.copy(visualResult = null).serializePresentation(null) }.isFailure)
    }

    @Test
    fun `recovered settlement crosses int max without losing win credit`() {
        val initial = PlayerState(
            coinsBalance = Int.MAX_VALUE.toLong() - 10L,
            freeSpinsBalance = 3,
            freeSpinBet = 10,
            freeSpinLines = 5,
            freeSpinSlotId = OTHER_SLOT_ID,
            levelXp = PlayerState.maxLevelXp() - 2,
            freeSpinAutoPlaySlots = setOf(SLOT_ID)
        )

        val recovered = initial.applyPendingSpinSettlement(settlement())

        assertEquals(Int.MAX_VALUE.toLong() + 390L, recovered.coinsBalance)
        assertEquals(PlayerState.maxLevelXp(), recovered.levelXp)
        assertEquals(3, recovered.freeSpinsForSlot(OTHER_SLOT_ID))
        assertEquals(5, recovered.freeSpinsForSlot(SLOT_ID))
        assertEquals(25, recovered.freeSpinBetForSlot(SLOT_ID))
        assertEquals(10, recovered.freeSpinLinesForSlot(SLOT_ID))
        assertEquals(8, recovered.freeSpinsBalance)
        assertTrue(recovered.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
    }

    @Test
    fun `final recovered feature spin clears persisted autoplay marker`() {
        val initial = PlayerState(
            freeSpinAutoPlaySlots = setOf(SLOT_ID),
            freeSpinFeatureTotalWins = mapOf(SLOT_ID to 725)
        )

        val recovered = initial.applyPendingSpinSettlement(
            settlement(isFreeSpin = true, freeSpinsAwarded = 0)
        )

        assertFalse(recovered.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
        assertEquals(1_125, recovered.freeSpinFeatureTotalWinForSlot(SLOT_ID))
    }

    @Test
    fun `new paid feature resets prior completed feature total`() {
        val initial = PlayerState(freeSpinFeatureTotalWins = mapOf(SLOT_ID to 9_999))

        val recovered = initial.applyPendingSpinSettlement(
            settlement(isFreeSpin = false, freeSpinsAwarded = 5)
        )

        assertEquals(0, recovered.freeSpinFeatureTotalWinForSlot(SLOT_ID))
    }

    private fun settlement(
        winAmount: Int = 400,
        isFreeSpin: Boolean = false,
        freeSpinsAwarded: Int = 5,
        stopIndexes: List<Int> = listOf(4, 8, 15, 16, 23),
        visualResult: SpinResult? = null
    ): PendingSpinSettlement {
        return PendingSpinSettlement(
            id = "spin-123",
            processSessionId = "process-123",
            slotId = SLOT_ID,
            isFreeSpin = isFreeSpin,
            lineBet = 25,
            lines = 10,
            totalBet = 250,
            winAmount = winAmount,
            freeSpinsAwarded = freeSpinsAwarded,
            levelXpAwarded = 14,
            mathVersion = SlotMathIdentity.VERSION,
            configFingerprint = "a".repeat(64),
            stopIndexes = stopIndexes,
            visualResult = visualResult
        )
    }

    private fun visualResult(): SpinResult {
        return SpinResult(
            reels = listOf(
                listOf("violet", "wild", "scatter"),
                listOf("violet", "scatter", "crown"),
                listOf("violet", "gem", "scatter"),
                listOf("ring", "gem", "crown"),
                listOf("ring", "wild", "crown")
            ),
            bet = 25,
            lines = 10,
            totalBet = 250,
            winAmount = 400,
            resultType = ResultType.Bonus,
            winningLines = emptyList(),
            scatterCount = 3,
            scatterPositions = emptyList(),
            freeSpinsAwarded = 5,
            stopIndexes = listOf(4, 8, 15, 16, 23)
        )
    }

    private companion object {
        const val SLOT_ID = "violet_fortune"
        const val OTHER_SLOT_ID = "roman_reels"
        const val GOLDEN_JOURNAL_CHECKSUM =
            "0cb49bebc40a4b80e75ab6db1912a29a59777b55a9571eabc3503e1a28a1c440"
    }
}
