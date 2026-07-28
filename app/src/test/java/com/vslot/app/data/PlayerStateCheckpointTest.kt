package com.vslot.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateCheckpointTest {
    @Test
    fun `legacy primary checkpoint versions remain authoritative after upgrade`() = runTest {
        val original = PlayerStateCheckpoint(
            generation = 91L,
            playerState = fullPlayerState(),
            rawPendingSpinSettlement = "settlement-v1",
            rawPendingSpinRefundEnvelope = "refund-v2",
            rawPendingSpinPresentation = "presentation-v1"
        )
        for (schemaVersion in 1..2) {
            val restored = requireNotNull(
                PlayerStateCheckpointCodec.decode(legacyEncoding(original, schemaVersion))
            )
            var legacyMigrationCalls = 0
            val migration = LegacyPlayerStateMigration {
                legacyMigrationCalls += 1
                PlayerStateCheckpoint(generation = 1L, playerState = PlayerState(coinsBalance = 1L))
            }

            assertEquals(schemaVersion, restored.schemaVersion)
            assertEquals(original.generation, restored.generation)
            assertEquals(original.playerState, restored.playerState)
            assertEquals(original.rawPendingSpinSettlement, restored.rawPendingSpinSettlement)
            assertEquals(original.rawPendingSpinPresentation, restored.rawPendingSpinPresentation)
            assertEquals(
                original.rawPendingSpinRefundEnvelope.takeIf { schemaVersion >= 2 },
                restored.rawPendingSpinRefundEnvelope
            )
            assertTrue(restored.migrationComplete)
            assertFalse(migration.shouldMigrate(restored))
            assertEquals(0, legacyMigrationCalls)

            val rewritten = ByteArrayOutputStream().also { output ->
                PlayerStateCheckpointSerializer.writeTo(restored, output)
            }
            val upgraded = PlayerStateCheckpointSerializer.readFrom(
                ByteArrayInputStream(rewritten.toByteArray())
            )
            assertEquals(PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION, upgraded.schemaVersion)
            assertEquals(
                restored.copy(schemaVersion = PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION),
                upgraded
            )
        }
    }

    @Test
    fun `full player state round trips through canonical envelope`() {
        val checkpoint = PlayerStateCheckpoint(
            generation = 42L,
            playerState = fullPlayerState()
        )

        val encoded = PlayerStateCheckpointCodec.encode(checkpoint)
        val restored = PlayerStateCheckpointCodec.decode(encoded)

        assertEquals(checkpoint, restored)
        val envelope = JSONObject(encoded)
        assertEquals(
            PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION,
            envelope.getInt("schemaVersion")
        )
        assertTrue(envelope.getString("checksum").matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `checkpoint accepts legacy int json number for long balance`() {
        val checkpoint = PlayerStateCheckpoint(
            generation = 1L,
            playerState = PlayerState(coinsBalance = 123L)
        )
        val encoded = PlayerStateCheckpointCodec.encode(checkpoint)
        val encodedBalance = JSONObject(encoded)
            .getJSONObject("payload")
            .getJSONObject("playerState")
            .get("coinsBalance")

        assertTrue(encodedBalance is Int)
        assertEquals(123L, PlayerStateCheckpointCodec.decode(encoded)?.playerState?.coinsBalance)
    }

    @Test
    fun `canonical encoding is independent of map and set insertion order`() {
        val first = fullPlayerState()
        val second = first.copy(
            freeSpinBonuses = linkedMapOf(
                SLOT_VIOLET to first.freeSpinBonuses.getValue(SLOT_VIOLET),
                SLOT_ROMAN to first.freeSpinBonuses.getValue(SLOT_ROMAN)
            ),
            freeSpinAutoPlaySlots = linkedSetOf(SLOT_VIOLET, SLOT_ROMAN)
        )

        val firstEncoded = PlayerStateCheckpointCodec.encode(
            PlayerStateCheckpoint(generation = 7L, playerState = first)
        )
        val secondEncoded = PlayerStateCheckpointCodec.encode(
            PlayerStateCheckpoint(generation = 7L, playerState = second)
        )

        assertEquals(firstEncoded, secondEncoded)
    }

    @Test
    fun `checksum corruption is rejected`() {
        val encoded = PlayerStateCheckpointCodec.encode(
            PlayerStateCheckpoint(generation = 1L, playerState = PlayerState())
        )
        val envelope = JSONObject(encoded)
        val originalChecksum = envelope.getString("checksum")
        val corruptedChecksum = (if (originalChecksum[0] == '0') '1' else '0') +
            originalChecksum.substring(1)
        val corrupted = envelope.put("checksum", corruptedChecksum).toString()

        assertNotEquals(originalChecksum, corruptedChecksum)
        assertNull(PlayerStateCheckpointCodec.decode(corrupted))
    }

    @Test
    fun `unknown schema version is rejected`() {
        val encoded = PlayerStateCheckpointCodec.encode(
            PlayerStateCheckpoint(generation = 1L, playerState = PlayerState())
        )
        val unknownVersion = JSONObject(encoded)
            .put("schemaVersion", PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION + 1)
            .toString()

        assertNull(PlayerStateCheckpointCodec.decode(unknownVersion))
        assertThrows(IllegalArgumentException::class.java) {
            PlayerStateCheckpointCodec.encode(
                PlayerStateCheckpoint(
                    schemaVersion = PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION + 1,
                    generation = 1L,
                    playerState = PlayerState()
                )
            )
        }
    }

    @Test
    fun `oversized and malformed inputs are rejected`() {
        assertNull(PlayerStateCheckpointCodec.decode("not-json"))
        assertNull(PlayerStateCheckpointCodec.decode("{}"))
        assertNull(
            PlayerStateCheckpointCodec.decode(
                byteArrayOf(0xC3.toByte(), 0x28)
            )
        )
        assertNull(
            PlayerStateCheckpointCodec.decode(
                ByteArray(PlayerStateCheckpointCodec.MAX_FILE_BYTES + 1) { 'x'.code.toByte() }
            )
        )

        val oversizedJournal = "x".repeat(
            PlayerStateCheckpointCodec.MAX_PENDING_JOURNAL_CHARS + 1
        )
        assertThrows(IllegalArgumentException::class.java) {
            PlayerStateCheckpointCodec.encode(
                PlayerStateCheckpoint(
                    generation = 1L,
                    playerState = PlayerState(),
                    rawPendingSpinSettlement = oversizedJournal
                )
            )
        }

        val oversizedBonuses = (0..PlayerStateCheckpointCodec.MAX_STATE_COLLECTION_SIZE)
            .associate { index ->
                val slotId = "slot-$index"
                slotId to FreeSpinBonus(slotId, count = 1, lineBet = 1, lines = 1)
            }
        assertThrows(IllegalArgumentException::class.java) {
            PlayerStateCheckpointCodec.encode(
                PlayerStateCheckpoint(
                    generation = 1L,
                    playerState = PlayerState(freeSpinBonuses = oversizedBonuses)
                )
            )
        }
    }

    @Test
    fun `decoded state is normalized after integrity verification`() {
        val corruptedState = PlayerState(
            coinsBalance = -10,
            lastDailyBonusTimestamp = Long.MAX_VALUE,
            selectedBet = -25,
            selectedLines = Int.MAX_VALUE,
            freeSpinsBalance = -3,
            freeSpinBet = -5,
            freeSpinLines = -10,
            freeSpinSlotId = "legacy-slot",
            freeSpinBonuses = mapOf(
                "ignored-key" to FreeSpinBonus(
                    slotId = "",
                    count = -1,
                    lineBet = -1,
                    lines = -1
                )
            ),
            freeSpinAutoPlaySlots = setOf("", SLOT_ROMAN),
            levelXp = Int.MAX_VALUE,
            lastPlayedSlot = ""
        )
        val checkpoint = PlayerStateCheckpoint(
            generation = 9L,
            playerState = corruptedState
        )

        val restored = PlayerStateCheckpointCodec.decode(
            PlayerStateCheckpointCodec.encode(checkpoint)
        )

        assertEquals(corruptedState.normalized(), restored?.playerState)
        assertEquals(Long.MAX_VALUE, restored?.playerState?.lastDailyBonusTimestamp)
        assertEquals(PlayerState.DEFAULT_BET, restored?.playerState?.selectedBet)
        assertEquals(PlayerState.MAX_LINES, restored?.playerState?.selectedLines)
        assertEquals(PlayerState.maxLevelXp(), restored?.playerState?.levelXp)
    }

    @Test
    fun `pending journals are preserved as opaque nullable strings`() {
        val settlement = "  {\n  \"version\":2,\n  \"note\":\"\\u20ac\"\n}\n"
        val refundEnvelope = "{\"version\":1,\"settlementId\":\"spin-1\"}"
        val presentation = "{\"settlement\":{\"id\":\"spin-1\"},\"claimedBy\":null}"
        val checkpoint = PlayerStateCheckpoint(
            generation = Long.MAX_VALUE,
            playerState = PlayerState(),
            rawPendingSpinSettlement = settlement,
            rawPendingSpinRefundEnvelope = refundEnvelope,
            rawPendingSpinPresentation = presentation
        )

        val encoded = PlayerStateCheckpointCodec.encode(checkpoint)
        val restoredFromString = PlayerStateCheckpointCodec.decode(encoded)
        val restoredFromBytes = PlayerStateCheckpointCodec.decode(
            encoded.toByteArray(StandardCharsets.UTF_8)
        )

        assertEquals(settlement, restoredFromString?.rawPendingSpinSettlement)
        assertEquals(refundEnvelope, restoredFromString?.rawPendingSpinRefundEnvelope)
        assertEquals(presentation, restoredFromString?.rawPendingSpinPresentation)
        assertEquals(restoredFromString, restoredFromBytes)
    }

    @Test
    fun `checkpoint converts back to complete preferences including journals`() {
        val state = fullPlayerState()
        val checkpoint = PlayerStateCheckpoint(
            generation = 11L,
            playerState = state,
            rawPendingSpinSettlement = "pending-settlement",
            rawPendingSpinRefundEnvelope = "pending-refund-envelope",
            rawPendingSpinPresentation = "pending-presentation"
        )

        val preferences = PlayerRepository.preferencesFromCheckpoint(checkpoint)

        assertEquals(state.coinsBalance, preferences[PlayerRepository.Keys.CoinsBalanceLong])
        assertFalse(preferences.contains(PlayerRepository.Keys.LegacyCoinsBalance))
        assertEquals(state.selectedBet, preferences[PlayerRepository.Keys.SelectedBet])
        assertEquals(state.selectedLines, preferences[PlayerRepository.Keys.SelectedLines])
        assertEquals(state.analyticsEnabled, preferences[PlayerRepository.Keys.AnalyticsEnabled])
        assertEquals(state.freeSpinAutoPlaySlots, preferences[PlayerRepository.Keys.FreeSpinAutoPlaySlots])
        assertEquals("pending-settlement", preferences[PlayerRepository.Keys.PendingSpinSettlement])
        assertEquals(
            "pending-refund-envelope",
            preferences[PlayerRepository.Keys.PendingSpinRefundEnvelope]
        )
        assertEquals("pending-presentation", preferences[PlayerRepository.Keys.PendingSpinPresentation])
        val bonuses = JSONArray(preferences[PlayerRepository.Keys.FreeSpinBonuses])
        assertEquals(2, bonuses.length())
        assertEquals(SLOT_ROMAN, bonuses.getJSONObject(0).getString("slotId"))
        assertEquals(SLOT_VIOLET, bonuses.getJSONObject(1).getString("slotId"))
    }

    private fun fullPlayerState(): PlayerState {
        val romanBonus = FreeSpinBonus(
            slotId = SLOT_ROMAN,
            count = 3,
            lineBet = 25,
            lines = 10
        )
        val violetBonus = FreeSpinBonus(
            slotId = SLOT_VIOLET,
            count = 5,
            lineBet = 50,
            lines = 7
        )
        return PlayerState(
            coinsBalance = Int.MAX_VALUE.toLong() + 123_456L,
            lastDailyBonusTimestamp = 80_000L,
            selectedBet = 50,
            selectedLines = 7,
            freeSpinsBalance = 8,
            freeSpinBet = romanBonus.lineBet,
            freeSpinLines = romanBonus.lines,
            freeSpinSlotId = romanBonus.slotId,
            freeSpinBonuses = linkedMapOf(
                SLOT_ROMAN to romanBonus,
                SLOT_VIOLET to violetBonus
            ),
            freeSpinAutoPlaySlots = linkedSetOf(SLOT_ROMAN, SLOT_VIOLET),
            levelXp = 12_345,
            disclaimerAccepted = true,
            pushPermissionAsked = true,
            soundEnabled = false,
            hapticsEnabled = false,
            analyticsEnabled = true,
            lastPlayedSlot = SLOT_ROMAN
        )
    }

    private fun legacyEncoding(checkpoint: PlayerStateCheckpoint, schemaVersion: Int): String {
        require(schemaVersion in 1..2)
        val envelope = JSONObject(PlayerStateCheckpointCodec.encode(checkpoint))
        val payload = envelope.getJSONObject("payload")
        payload.remove("migrationComplete")
        if (schemaVersion < 2) payload.remove("pendingSpinRefundEnvelope")
        val canonicalPayload = PlayerStateCheckpointCodec.canonicalJson(payload)
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("payload", payload)
            .put("checksum", checksum)
            .toString()
    }

    private companion object {
        const val SLOT_ROMAN = "roman_reels"
        const val SLOT_VIOLET = "violet_fortune"
    }
}
