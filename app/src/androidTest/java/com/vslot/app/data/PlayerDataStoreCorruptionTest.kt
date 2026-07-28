package com.vslot.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerDataStoreCorruptionTest {
    @Test
    fun corruptedPreferencesFileRestoresVerifiedCheckpointAndRemainsWritable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "corrupted-player-${System.nanoTime()}.preferences_pb")
        val checkpointStore = PlayerStateCheckpointStore(
            File(context.cacheDir, "corrupted-player-checkpoint-${System.nanoTime()}.json")
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x01, 0x02, 0x03))
        checkpointStore.write(
            PlayerStateCheckpoint(
                generation = 7L,
                playerState = PlayerState(
                    coinsBalance = 8_765,
                    selectedBet = 25,
                    selectedLines = 7,
                    disclaimerAccepted = true
                )
            )
        )

        try {
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = playerDataStoreCorruptionHandler {
                    checkpointStore.read()?.let(PlayerRepository::preferencesFromCheckpoint)
                },
                scope = scope,
                produceFile = { file }
            )

            val restored = dataStore.data.first()
            assertEquals(8_765L, restored[longPreferencesKey("coinsBalanceLong")])
            assertEquals(null, restored[intPreferencesKey("coinsBalance")])
            assertEquals(25, restored[intPreferencesKey("selectedBet")])
            assertEquals(7, restored[intPreferencesKey("selectedLines")])

            val balanceKey = intPreferencesKey("recoveryBalance")
            dataStore.edit { it[balanceKey] = 12_345 }
            assertEquals(12_345, dataStore.data.first()[balanceKey])
        } finally {
            scope.cancel()
            file.delete()
            checkpointStore.clear()
        }
    }

    @Test
    fun corruptedPreferencesWithoutValidCheckpointResetsAndRemainsWritable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "unrecoverable-player-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x01, 0x02, 0x03))

        try {
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = playerDataStoreCorruptionHandler(),
                scope = scope,
                produceFile = { file }
            )

            val balanceKey = intPreferencesKey("recoveryBalance")
            assertEquals(null, dataStore.data.first()[balanceKey])
            dataStore.edit { it[balanceKey] = 321 }
            assertEquals(321, dataStore.data.first()[balanceKey])
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
