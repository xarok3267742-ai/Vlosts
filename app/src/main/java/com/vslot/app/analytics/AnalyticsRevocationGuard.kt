package com.vslot.app.analytics

import android.annotation.SuppressLint
import android.content.Context

interface AnalyticsRevocationGuard {
    fun isRevoked(): Boolean
    fun markRevoked(): Boolean
    fun clearRevoked(): Boolean
}

class InMemoryAnalyticsRevocationGuard(
    initialValue: Boolean = false
) : AnalyticsRevocationGuard {
    @Volatile
    private var revoked = initialValue

    override fun isRevoked(): Boolean = revoked

    override fun markRevoked(): Boolean {
        revoked = true
        return true
    }

    override fun clearRevoked(): Boolean {
        revoked = false
        return true
    }
}

@SuppressLint("UseKtx")
class SharedPreferencesAnalyticsRevocationGuard(context: Context) : AnalyticsRevocationGuard {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Volatile
    private var processRevoked = preferences.getBoolean(KEY_REVOKED, false)

    override fun isRevoked(): Boolean = processRevoked || preferences.getBoolean(KEY_REVOKED, false)

    override fun markRevoked(): Boolean {
        processRevoked = true
        return preferences.edit().putBoolean(KEY_REVOKED, true).commit()
    }

    override fun clearRevoked(): Boolean {
        val persisted = preferences.edit().remove(KEY_REVOKED).commit()
        if (persisted) processRevoked = false
        return persisted
    }

    private companion object {
        const val PREFERENCES_NAME = "v_slot_analytics_consent_guard"
        const val KEY_REVOKED = "analytics_revoked"
    }
}
