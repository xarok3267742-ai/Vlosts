package com.vslot.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow

/** One transactional source for player economy state and every spin journal. */
internal class TransactionalPlayerStateStore internal constructor(
    private val dataStore: DataStore<PlayerStateCheckpoint>
) {
    val data: Flow<PlayerStateCheckpoint> = dataStore.data

    suspend fun update(
        transform: suspend (PlayerStateCheckpoint) -> PlayerStateCheckpoint
    ): PlayerStateCheckpoint {
        return dataStore.updateData { current ->
            val currentSchema = current.withCurrentSchema()
            val updated = transform(currentSchema)
            require(updated.generation >= currentSchema.generation) {
                "Player state generation cannot move backwards."
            }
            updated.withCurrentSchema()
        }
    }

    companion object {
        fun create(
            primaryFile: File,
            migrateLegacyState: suspend () -> PlayerStateCheckpoint
        ): TransactionalPlayerStateStore {
            return TransactionalPlayerStateStore(
                DataStoreFactory.create(
                    serializer = PlayerStateCheckpointSerializer,
                    migrations = listOf(
                        PlayerStateCheckpointSchemaMigration,
                        LegacyPlayerStateMigration(migrateLegacyState)
                    ),
                    produceFile = { primaryFile }
                )
            )
        }
    }
}

internal object PlayerStateCheckpointSerializer : Serializer<PlayerStateCheckpoint> {
    override val defaultValue = PlayerStateCheckpoint(
        generation = 0L,
        playerState = PlayerState(),
        migrationComplete = false
    )

    override suspend fun readFrom(input: InputStream): PlayerStateCheckpoint {
        val bytes = input.readBounded(PlayerStateCheckpointCodec.MAX_FILE_BYTES)
            ?: throw CorruptionException("Transactional player state exceeds its size limit.")
        return PlayerStateCheckpointCodec.decode(bytes)
            ?: throw CorruptionException("Transactional player state failed checksum or schema validation.")
    }

    override suspend fun writeTo(t: PlayerStateCheckpoint, output: OutputStream) {
        val encoded = PlayerStateCheckpointCodec.encode(
            t.withCurrentSchema()
        )
        output.write(encoded.toByteArray(StandardCharsets.UTF_8))
    }
}

internal object PlayerStateCheckpointSchemaMigration : DataMigration<PlayerStateCheckpoint> {
    override suspend fun shouldMigrate(currentData: PlayerStateCheckpoint): Boolean {
        return currentData.schemaVersion < PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION
    }

    override suspend fun migrate(currentData: PlayerStateCheckpoint): PlayerStateCheckpoint {
        return currentData.withCurrentSchema()
    }

    override suspend fun cleanUp() = Unit
}

internal class LegacyPlayerStateMigration(
    private val migrateLegacyState: suspend () -> PlayerStateCheckpoint
) : DataMigration<PlayerStateCheckpoint> {
    override suspend fun shouldMigrate(currentData: PlayerStateCheckpoint): Boolean {
        return !currentData.migrationComplete &&
            currentData.schemaVersion == PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION
    }

    override suspend fun migrate(currentData: PlayerStateCheckpoint): PlayerStateCheckpoint {
        return migrateLegacyState().withCurrentSchema()
    }

    override suspend fun cleanUp() = Unit
}

private fun PlayerStateCheckpoint.withCurrentSchema(): PlayerStateCheckpoint {
    return copy(
        migrationComplete = true,
        schemaVersion = PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION
    )
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(available().coerceAtLeast(0), maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        totalBytes += read
        if (totalBytes > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
