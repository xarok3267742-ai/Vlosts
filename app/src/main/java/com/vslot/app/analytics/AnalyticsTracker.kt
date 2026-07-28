package com.vslot.app.analytics

interface AnalyticsTracker {
    fun track(eventName: String, params: Map<String, Any?> = emptyMap())
}

interface AnalyticsConsentController {
    fun setAnalyticsEnabled(enabled: Boolean): Boolean

    fun beginUserConsentChange(enabled: Boolean): AnalyticsConsentChange {
        val immediateSucceeded = enabled || setAnalyticsEnabled(false)
        return AnalyticsConsentChange(
            enabled = enabled,
            generation = 0L,
            immediateSucceeded = immediateSucceeded
        )
    }

    fun completeUserConsentChange(
        change: AnalyticsConsentChange,
        persisted: Boolean
    ): AnalyticsConsentCompletion {
        if (!persisted) return AnalyticsConsentCompletion.Failed
        if (!change.enabled) {
            return if (change.immediateSucceeded) {
                AnalyticsConsentCompletion.Applied
            } else {
                AnalyticsConsentCompletion.Failed
            }
        }
        return if (setAnalyticsEnabled(true)) {
            AnalyticsConsentCompletion.Applied
        } else {
            AnalyticsConsentCompletion.Failed
        }
    }

    fun isAnalyticsRevoked(): Boolean = false
}

class AnalyticsConsentChange internal constructor(
    val enabled: Boolean,
    internal val generation: Long,
    val immediateSucceeded: Boolean
)

enum class AnalyticsConsentCompletion {
    Applied,
    Stale,
    Failed
}

interface AnalyticsRuntime : AnalyticsTracker, AnalyticsConsentController

class NoOpAnalyticsTracker : AnalyticsRuntime {
    override fun track(eventName: String, params: Map<String, Any?>) = Unit

    override fun setAnalyticsEnabled(enabled: Boolean): Boolean = true
}
