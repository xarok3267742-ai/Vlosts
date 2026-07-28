package com.vslot.app.analytics

import io.appmetrica.analytics.AppMetrica

class AppMetricaAnalyticsTracker internal constructor(
    private val reporter: AnalyticsReporter = AppMetricaReporter,
    dataSendingEnabled: Boolean = true
) : AnalyticsRuntime {
    private val consentLock = Any()

    @Volatile
    private var dataSendingEnabled = dataSendingEnabled
    private var providerDataSendingEnabled = dataSendingEnabled

    override fun track(eventName: String, params: Map<String, Any?>) {
        val cleanEventName = eventName.analyticsName(MAX_EVENT_NAME_LENGTH) ?: return
        val cleanParams = params.toAnalyticsParams()
        synchronized(consentLock) {
            if (!dataSendingEnabled) return
            try {
                if (cleanParams.isEmpty()) {
                    reporter.reportEvent(cleanEventName)
                } else {
                    reporter.reportEvent(cleanEventName, cleanParams)
                }
            } catch (_: RuntimeException) {
                // Analytics failures must never interrupt gameplay or navigation.
            }
        }
    }

    override fun setAnalyticsEnabled(enabled: Boolean): Boolean {
        return synchronized(consentLock) {
            if (providerDataSendingEnabled == enabled) {
                dataSendingEnabled = enabled
                return@synchronized true
            }
            if (!enabled) dataSendingEnabled = false
            try {
                reporter.setDataSendingEnabled(enabled)
                providerDataSendingEnabled = enabled
                dataSendingEnabled = enabled
                true
            } catch (_: RuntimeException) {
                // Keep custom events blocked and leave provider state retryable after a failed transition.
                false
            }
        }
    }

    private fun Map<String, Any?>.toAnalyticsParams(): Map<String, Any> {
        return entries
            .mapNotNull { (key, value) ->
                val cleanKey = key.analyticsName(MAX_PARAM_NAME_LENGTH) ?: return@mapNotNull null
                if (cleanKey in COARSE_NUMERIC_PARAM_NAMES && value is Number) {
                    return@mapNotNull "${cleanKey}_range" to value.analyticsMagnitudeRange()
                }
                val cleanValue = value?.analyticsValue() ?: return@mapNotNull null
                cleanKey to cleanValue
            }
            .toMap()
    }

    private fun Number.analyticsMagnitudeRange(): String {
        val value = toDouble()
        return when {
            !value.isFinite() -> "non_finite"
            value < 0.0 -> "negative"
            value == 0.0 -> "0"
            value < 10.0 -> "1_9"
            value < 50.0 -> "10_49"
            value < 100.0 -> "50_99"
            value < 250.0 -> "100_249"
            value < 500.0 -> "250_499"
            value < 1_000.0 -> "500_999"
            value < 2_500.0 -> "1000_2499"
            value < 5_000.0 -> "2500_4999"
            value < 10_000.0 -> "5000_9999"
            value < 50_000.0 -> "10000_49999"
            else -> "50000_plus"
        }
    }

    private fun Any.analyticsValue(): Any? {
        return when (this) {
            is Boolean -> this
            is Byte -> this.toInt()
            is Short -> this.toInt()
            is Int -> this
            is Long -> this
            is Float -> this
            is Double -> this
            is Number -> this.toDouble()
            is CharSequence -> toString().analyticsStringValue()
            else -> null
        }
    }

    private fun String.analyticsName(maxLength: Int): String? {
        return trim()
            .take(maxLength)
            .map { character ->
                if (character.isLetterOrDigit() || character == '_') character else '_'
            }
            .joinToString("")
            .trim('_')
            .takeIf { it.isNotBlank() }
    }

    private fun String.analyticsStringValue(): String? {
        val normalized = trim()
        if (normalized.hasUnsafeAnalyticsMarker()) return null
        return normalized
            .take(MAX_STRING_VALUE_LENGTH)
            .map { character ->
                if (character.isLetterOrDigit() || character == '_' || character == '-' || character == '.') {
                    character
                } else {
                    '_'
                }
            }
            .joinToString("")
            .trim('_', '-', '.')
            .takeIf { it.isNotBlank() }
    }

    private fun String.hasUnsafeAnalyticsMarker(): Boolean {
        return contains("://") || any { it == '@' || it == '?' || it == '=' || it == '&' || it == '#' }
    }

    private companion object {
        const val MAX_EVENT_NAME_LENGTH = 80
        const val MAX_PARAM_NAME_LENGTH = 80
        const val MAX_STRING_VALUE_LENGTH = 120
        val COARSE_NUMERIC_PARAM_NAMES = setOf(
            "amount",
            "balance",
            "balance_after",
            "balance_before",
            "bet",
            "coins_balance",
            "free_spins",
            "free_spins_after",
            "free_spins_awarded",
            "free_spins_before",
            "level",
            "level_xp_awarded",
            "line_bet",
            "lines",
            "total_bet",
            "win_amount"
        )
    }
}

internal interface AnalyticsReporter {
    fun reportEvent(eventName: String)
    fun reportEvent(eventName: String, params: Map<String, Any>)
    fun setDataSendingEnabled(enabled: Boolean) = Unit
}

private object AppMetricaReporter : AnalyticsReporter {
    override fun reportEvent(eventName: String) {
        AppMetrica.reportEvent(eventName)
    }

    override fun reportEvent(eventName: String, params: Map<String, Any>) {
        AppMetrica.reportEvent(eventName, params)
    }

    override fun setDataSendingEnabled(enabled: Boolean) {
        AppMetrica.setDataSendingEnabled(enabled)
    }
}
