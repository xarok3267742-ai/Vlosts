package com.vslot.app.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionalPlayerStateStoreTest {
    @Test
    fun `serializer round trip keeps one complete migrated snapshot`() = runTest {
        val expected = checkpoint(generation = 7L, balance = 12_000L).copy(
            rawPendingSpinSettlement = "settlement",
            rawPendingSpinRefundEnvelope = "refund",
            migrationComplete = true
        )
        val output = ByteArrayOutputStream()

        PlayerStateCheckpointSerializer.writeTo(expected, output)
        val restored = PlayerStateCheckpointSerializer.readFrom(
            ByteArrayInputStream(output.toByteArray())
        )

        assertEquals(expected, restored)
    }

    @Test
    fun `legacy migration runs once and marks its snapshot complete`() = runTest {
        val legacy = checkpoint(generation = 6L, balance = 15_000L)
        var calls = 0
        val migration = LegacyPlayerStateMigration {
            calls += 1
            legacy
        }

        assertTrue(migration.shouldMigrate(PlayerStateCheckpointSerializer.defaultValue))
        val migrated = migration.migrate(PlayerStateCheckpointSerializer.defaultValue)

        assertEquals(legacy.copy(migrationComplete = true), migrated)
        assertTrue(!migration.shouldMigrate(migrated))
        assertEquals(1, calls)
    }

    @Test
    fun `checkpoint schema migration upgrades in place without consulting legacy preferences`() = runTest {
        val legacyPrimary = checkpoint(generation = 91L, balance = 48_000L).copy(
            schemaVersion = 2,
            rawPendingSpinSettlement = "settlement",
            rawPendingSpinRefundEnvelope = "refund",
            rawPendingSpinPresentation = "presentation",
            migrationComplete = true
        )
        var legacyPreferenceMigrations = 0
        val legacyMigration = LegacyPlayerStateMigration {
            legacyPreferenceMigrations += 1
            checkpoint(generation = 1L, balance = 1L)
        }

        assertTrue(PlayerStateCheckpointSchemaMigration.shouldMigrate(legacyPrimary))
        val upgraded = PlayerStateCheckpointSchemaMigration.migrate(legacyPrimary)

        assertEquals(
            legacyPrimary.copy(schemaVersion = PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION),
            upgraded
        )
        assertTrue(!legacyMigration.shouldMigrate(upgraded))
        assertEquals(0, legacyPreferenceMigrations)

        val output = ByteArrayOutputStream()
        PlayerStateCheckpointSerializer.writeTo(legacyPrimary, output)
        assertEquals(
            upgraded,
            PlayerStateCheckpointSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        )
    }

    @Test
    fun `state and all spin journals commit as one atomic snapshot`() = runTest {
        val initial = checkpoint(generation = 4L, balance = 10_000L)
        val persistence = FakeDataStore(initial)
        val store = TransactionalPlayerStateStore(persistence)
        val expected = initial.copy(
            generation = 5L,
            playerState = initial.playerState.copy(coinsBalance = 9_900L),
            rawPendingSpinSettlement = "settlement",
            rawPendingSpinRefundEnvelope = "refund",
            rawPendingSpinPresentation = null
        )

        val committed = store.update { expected }
        val durableExpected = expected.copy(migrationComplete = true)

        assertEquals(durableExpected, committed)
        assertEquals(durableExpected, persistence.current)
        assertEquals(durableExpected, store.data.first())
    }

    @Test
    fun `transaction normalizes legacy schema before exposing it to updates`() = runTest {
        val legacyPrimary = checkpoint(generation = 5L, balance = 10_000L).copy(schemaVersion = 1)
        val persistence = FakeDataStore(legacyPrimary)
        val store = TransactionalPlayerStateStore(persistence)

        val committed = store.update { current ->
            assertEquals(PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION, current.schemaVersion)
            current.copy(
                generation = 6L,
                playerState = current.playerState.copy(coinsBalance = 9_900L)
            )
        }

        assertEquals(PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION, committed.schemaVersion)
        assertEquals(committed, persistence.current)
    }

    @Test
    fun `failed atomic write preserves previous complete snapshot and flow state`() = runTest {
        val initial = checkpoint(generation = 8L, balance = 10_000L)
        val persistence = FakeDataStore(initial)
        val store = TransactionalPlayerStateStore(persistence)
        assertEquals(initial, store.data.first())
        persistence.writeFailure = IOException("disk full")
        var observed: IOException? = null

        try {
            store.update {
                it.copy(
                    generation = 9L,
                    playerState = it.playerState.copy(coinsBalance = 9_000L),
                    rawPendingSpinSettlement = "must-not-commit"
                )
            }
        } catch (failure: IOException) {
            observed = failure
        }

        assertSame(persistence.writeFailure, observed)
        assertEquals(initial, persistence.current)
        assertEquals(initial, store.data.first())
    }

    @Test
    fun `restart reads the committed primary snapshot`() = runTest {
        val committed = checkpoint(generation = 12L, balance = 44_000L)
        val persistence = FakeDataStore(committed)
        val restarted = TransactionalPlayerStateStore(persistence)

        assertEquals(committed, restarted.data.first())
        assertEquals(0, persistence.writeCount)
    }

    @Test
    fun `corrupt primary serializer fails closed`() = runTest {
        var observed: CorruptionException? = null

        try {
            PlayerStateCheckpointSerializer.readFrom(
                ByteArrayInputStream("not-a-checkpoint".toByteArray())
            )
        } catch (failure: CorruptionException) {
            observed = failure
        }

        assertTrue(observed?.message.orEmpty().contains("failed checksum"))
    }

    @Test
    fun `generation cannot move backwards`() = runTest {
        val initial = checkpoint(generation = 3L, balance = 10_000L)
        val store = TransactionalPlayerStateStore(FakeDataStore(initial))

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.update { it.copy(generation = 2L) }
            }
        }
    }

    @Test
    fun `debug reset clears player data without rolling revision backward`() {
        val preferences = emptyPreferences().toMutablePreferences().apply {
            this[PlayerRepository.Keys.Revision] = 17L
            this[PlayerRepository.Keys.CoinsBalanceLong] = 99_000L
            this[PlayerRepository.Keys.PendingSpinSettlement] = "pending"
        }

        preferences.clearPlayerStatePreservingRevision()

        assertEquals(17L, preferences[PlayerRepository.Keys.Revision])
        assertNull(preferences[PlayerRepository.Keys.CoinsBalanceLong])
        assertNull(preferences[PlayerRepository.Keys.PendingSpinSettlement])
        assertEquals(1, preferences.asMap().size)
    }

    @Test
    fun `revision advances without saturation`() {
        assertEquals(1L, nextPlayerStateRevision(0L))
        assertEquals(Long.MAX_VALUE, nextPlayerStateRevision(Long.MAX_VALUE - 1L))
        assertThrows(IllegalStateException::class.java) {
            nextPlayerStateRevision(Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            nextPlayerStateRevision(-1L)
        }
    }

    private fun checkpoint(generation: Long, balance: Long): PlayerStateCheckpoint {
        return PlayerStateCheckpoint(
            generation = generation,
            playerState = PlayerState(coinsBalance = balance)
        )
    }

    private class FakeDataStore(
        initial: PlayerStateCheckpoint
    ) : DataStore<PlayerStateCheckpoint> {
        private val snapshots = MutableStateFlow(initial)
        var writeFailure: IOException? = null
        var writeCount: Int = 0
        val current: PlayerStateCheckpoint get() = snapshots.value

        override val data: Flow<PlayerStateCheckpoint> = snapshots

        override suspend fun updateData(
            transform: suspend (t: PlayerStateCheckpoint) -> PlayerStateCheckpoint
        ): PlayerStateCheckpoint {
            writeCount += 1
            writeFailure?.let { throw it }
            return transform(snapshots.value).also { snapshots.value = it }
        }
    }
}
