package com.vslot.app.analytics

/** Serializes analytics consent, provider state, and event delivery for this process. */
class AnalyticsConsentCoordinator(
    private val runtime: AnalyticsRuntime,
    private val revocationGuard: AnalyticsRevocationGuard
) : AnalyticsRuntime {
    private val consentLock = Any()

    private var generation = 0L
    private var desiredUserState: Boolean? = null
    private var processRevoked = revocationGuard.isRevoked()
    private var revocationPersistencePending = false
    private var runtimeAllowed = false

    override fun track(eventName: String, params: Map<String, Any?>) {
        synchronized(consentLock) {
            if (!runtimeAllowed || refreshRevocationLocked()) return
            runtime.track(eventName, params)
        }
    }

    /** Reconciles persisted state. A passive enable is never allowed to clear a revocation. */
    override fun setAnalyticsEnabled(enabled: Boolean): Boolean {
        return synchronized(consentLock) {
            if (!enabled) {
                retryRevocationPersistenceLocked()
                disableRuntimeLocked()
            } else if (refreshRevocationLocked()) {
                retryRevocationPersistenceLocked()
                disableRuntimeLocked()
                false
            } else {
                enableRuntimeLocked()
            }
        }
    }

    override fun beginUserConsentChange(enabled: Boolean): AnalyticsConsentChange {
        return synchronized(consentLock) {
            generation += 1L
            desiredUserState = enabled
            val immediateSucceeded = if (enabled) {
                true
            } else {
                processRevoked = true
                val guardPersisted = persistRevocationLocked()
                val runtimeDisabled = disableRuntimeLocked()
                guardPersisted && runtimeDisabled
            }
            AnalyticsConsentChange(
                enabled = enabled,
                generation = generation,
                immediateSucceeded = immediateSucceeded
            )
        }
    }

    override fun completeUserConsentChange(
        change: AnalyticsConsentChange,
        persisted: Boolean
    ): AnalyticsConsentCompletion {
        return synchronized(consentLock) {
            if (change.generation != generation || desiredUserState != change.enabled) {
                return@synchronized AnalyticsConsentCompletion.Stale
            }
            if (!persisted) {
                desiredUserState = false
                generation += 1L
                retryRevocationPersistenceLocked()
                disableRuntimeLocked()
                return@synchronized AnalyticsConsentCompletion.Failed
            }
            if (!change.enabled) {
                if (change.immediateSucceeded) {
                    return@synchronized AnalyticsConsentCompletion.Applied
                }
                val guardPersisted = persistRevocationLocked()
                val runtimeDisabled = disableRuntimeLocked()
                return@synchronized if (guardPersisted && runtimeDisabled) {
                    AnalyticsConsentCompletion.Applied
                } else {
                    AnalyticsConsentCompletion.Failed
                }
            }
            if (!revocationGuard.clearRevoked()) {
                failClosedLocked()
                return@synchronized AnalyticsConsentCompletion.Failed
            }

            processRevoked = false
            if (enableRuntimeLocked()) {
                AnalyticsConsentCompletion.Applied
            } else {
                failClosedLocked()
                AnalyticsConsentCompletion.Failed
            }
        }
    }

    override fun isAnalyticsRevoked(): Boolean {
        return synchronized(consentLock) { refreshRevocationLocked() }
    }

    private fun refreshRevocationLocked(): Boolean {
        if (revocationGuard.isRevoked()) processRevoked = true
        return processRevoked
    }

    private fun persistRevocationLocked(): Boolean {
        val persisted = revocationGuard.markRevoked()
        revocationPersistencePending = !persisted
        return persisted
    }

    private fun retryRevocationPersistenceLocked() {
        if (processRevoked && revocationPersistencePending) persistRevocationLocked()
    }

    private fun enableRuntimeLocked(): Boolean {
        val enabled = runtime.setAnalyticsEnabled(true)
        runtimeAllowed = enabled
        return enabled
    }

    private fun disableRuntimeLocked(): Boolean {
        runtimeAllowed = false
        return runtime.setAnalyticsEnabled(false)
    }

    private fun failClosedLocked() {
        processRevoked = true
        desiredUserState = false
        generation += 1L
        persistRevocationLocked()
        disableRuntimeLocked()
    }
}
