package com.vslot.app.analytics

/** Defers provider initialization until analytics or push has explicit user consent. */
class LazyAnalyticsRuntime(
    private val activate: () -> AnalyticsRuntime?
) : AnalyticsRuntime {
    private val lock = Any()

    @Volatile
    private var delegate: AnalyticsRuntime? = null

    override fun track(eventName: String, params: Map<String, Any?>) {
        delegate?.track(eventName, params)
    }

    override fun setAnalyticsEnabled(enabled: Boolean): Boolean {
        return synchronized(lock) {
            if (!enabled) {
                return@synchronized delegate?.setAnalyticsEnabled(false) ?: true
            }
            getOrActivateLocked()?.setAnalyticsEnabled(true) ?: false
        }
    }

    fun ensureActivatedForPush(): Boolean {
        return synchronized(lock) { getOrActivateLocked() != null }
    }

    private fun getOrActivateLocked(): AnalyticsRuntime? {
        delegate?.let { return it }
        return activate()?.also { delegate = it }
    }
}
