package com.vslot.app.data

import androidx.datastore.preferences.core.emptyPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerCoinsBalanceMigrationTest {
    @Test
    fun `legacy int balance migrates to distinct long key on write`() {
        val preferences = emptyPreferences().toMutablePreferences().apply {
            this[PlayerRepository.Keys.LegacyCoinsBalance] = Int.MAX_VALUE
        }

        assertEquals(Int.MAX_VALUE.toLong(), preferences.readPersistedCoinsBalance())

        preferences.migrateCoinsBalance()

        assertEquals(
            Int.MAX_VALUE.toLong(),
            preferences[PlayerRepository.Keys.CoinsBalanceLong]
        )
        assertFalse(preferences.contains(PlayerRepository.Keys.LegacyCoinsBalance))
    }

    @Test
    fun `new long balance wins over legacy value and preserves values above int max`() {
        val expected = Int.MAX_VALUE.toLong() + 123_456L
        val preferences = emptyPreferences().toMutablePreferences().apply {
            this[PlayerRepository.Keys.CoinsBalanceLong] = expected
            this[PlayerRepository.Keys.LegacyCoinsBalance] = 7
        }

        preferences.migrateCoinsBalance()

        assertEquals(expected, preferences.readPersistedCoinsBalance())
        assertEquals(expected, preferences[PlayerRepository.Keys.CoinsBalanceLong])
        assertFalse(preferences.contains(PlayerRepository.Keys.LegacyCoinsBalance))
    }
}
