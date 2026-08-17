package com.vslot.app.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class TransactionalPlayerStateStoreAndroidTest {
    @Test
    fun legacyPrimarySchemasUpgradeInPlaceAndSurviveDurableRewrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (schemaVersion in 1..3) {
            val file = uniquePrimaryFile(context, "schema-$schemaVersion").apply {
                parentFile?.mkdirs()
            }
            val legacy = PlayerStateCheckpoint(
                generation = 41L + schemaVersion,
                playerState = PlayerState(coinsBalance = 23_000L + schemaVersion),
                rawPendingSpinSettlement = "settlement-$schemaVersion",
                rawPendingSpinRefundEnvelope = "refund-$schemaVersion",
                rawPendingSpinPresentation = "presentation-$schemaVersion",
                migrationComplete = true
            )
            file.writeText(legacyEncoding(legacy, schemaVersion), Charsets.UTF_8)
            var legacyPreferenceMigrations = 0
            val store = TransactionalPlayerStateStore.create(file) {
                legacyPreferenceMigrations += 1
                PlayerStateCheckpoint(generation = 1L, playerState = PlayerState(coinsBalance = 1L))
            }

            val upgraded = store.data.first()
            assertEquals(PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION, upgraded.schemaVersion)
            assertEquals(legacy.generation, upgraded.generation)
            assertEquals(legacy.playerState, upgraded.playerState)
            assertEquals(legacy.rawPendingSpinSettlement, upgraded.rawPendingSpinSettlement)
            assertEquals(legacy.rawPendingSpinPresentation, upgraded.rawPendingSpinPresentation)
            assertEquals(
                legacy.rawPendingSpinRefundEnvelope.takeIf { schemaVersion >= 2 },
                upgraded.rawPendingSpinRefundEnvelope
            )
            assertEquals(0, legacyPreferenceMigrations)

            val committed = store.update { current ->
                current.copy(
                    generation = current.generation + 1L,
                    playerState = current.playerState.copy(
                        coinsBalance = current.playerState.coinsBalance + 500L
                    )
                )
            }
            val durable = file.inputStream().use { input ->
                PlayerStateCheckpointSerializer.readFrom(input)
            }
            assertEquals(committed, durable)
            assertEquals(PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION, durable.schemaVersion)
        }
    }

    @Test
    fun legacySnapshotMigratesOnceThenUpdatesAsOnePrimaryTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = uniquePrimaryFile(context, "migration")
        val legacy = PlayerStateCheckpoint(
            generation = 5L,
            playerState = PlayerState(coinsBalance = 12_500L),
            rawPendingSpinSettlement = "legacy-settlement"
        )
        var migrations = 0
        val store = TransactionalPlayerStateStore.create(file) {
            migrations += 1
            legacy
        }

        val migrated = store.data.first()
        val committed = store.update {
            it.copy(
                generation = 6L,
                playerState = it.playerState.copy(coinsBalance = 12_400L),
                rawPendingSpinSettlement = "committed-settlement",
                rawPendingSpinRefundEnvelope = "committed-refund"
            )
        }

        assertTrue(migrated.migrationComplete)
        assertEquals(legacy.copy(migrationComplete = true), migrated)
        assertEquals(1, migrations)
        assertEquals(6L, committed.generation)
        assertEquals(12_400L, committed.playerState.coinsBalance)
        assertEquals("committed-settlement", committed.rawPendingSpinSettlement)
        assertEquals("committed-refund", committed.rawPendingSpinRefundEnvelope)
        assertEquals(committed, store.data.first())
    }

    @Test
    fun corruptedPrimaryFailsClosedWithoutConsultingLegacyState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = uniquePrimaryFile(context, "corrupt").apply {
            parentFile?.mkdirs()
            writeText("not-a-valid-player-snapshot", Charsets.UTF_8)
        }
        var migrationCalled = false
        val store = TransactionalPlayerStateStore.create(file) {
            migrationCalled = true
            PlayerStateCheckpoint(0L, PlayerState())
        }
        var observed: Throwable? = null

        try {
            store.data.first()
        } catch (failure: Throwable) {
            observed = failure
        }

        assertTrue(observed is CorruptionException)
        assertFalse(migrationCalled)
    }

    @Test
    fun concurrentTransactionsSerializeWithoutLostUpdates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = uniquePrimaryFile(context, "concurrent")
        val initialBalance = 10_000L
        val store = TransactionalPlayerStateStore.create(file) {
            PlayerStateCheckpoint(
                generation = 0L,
                playerState = PlayerState(coinsBalance = initialBalance)
            )
        }
        store.data.first()

        coroutineScope {
            List(CONCURRENT_TRANSACTION_COUNT) {
                async {
                    store.update { current ->
                        current.copy(
                            generation = current.generation + 1L,
                            playerState = current.playerState.copy(
                                coinsBalance = current.playerState.coinsBalance + 1L
                            )
                        )
                    }
                }
            }.awaitAll()
        }

        val committed = store.data.first()
        assertEquals(CONCURRENT_TRANSACTION_COUNT.toLong(), committed.generation)
        assertEquals(initialBalance + CONCURRENT_TRANSACTION_COUNT, committed.playerState.coinsBalance)
    }

    @Test
    fun cancelledTransformCannotPartiallyCommitEconomyOrJournal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = uniquePrimaryFile(context, "cancelled")
        val store = TransactionalPlayerStateStore.create(file) {
            PlayerStateCheckpoint(
                generation = 3L,
                playerState = PlayerState(coinsBalance = 8_000L)
            )
        }
        val before = store.data.first()

        try {
            store.update {
                throw CancellationException("fault injection before commit")
            }
        } catch (_: CancellationException) {
            // Expected fault boundary.
        }

        assertEquals(before, store.data.first())
    }

    private fun uniquePrimaryFile(context: Context, label: String): File {
        return File(
            context.cacheDir,
            "transactional-player-$label-${System.nanoTime()}.json"
        )
    }

    private fun legacyEncoding(checkpoint: PlayerStateCheckpoint, schemaVersion: Int): String {
        require(schemaVersion in 1..3)
        val envelope = JSONObject(PlayerStateCheckpointCodec.encode(checkpoint))
        val payload = envelope.getJSONObject("payload")
        payload.getJSONObject("playerState").remove("freeSpinFeatureTotalWins")
        if (schemaVersion < 3) {
            payload.remove("migrationComplete")
        } else {
            payload.put("migrationComplete", true)
        }
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
        const val CONCURRENT_TRANSACTION_COUNT = 32
    }
}
