package com.vslot.app.analytics

class FakeAnalyticsTracker : AnalyticsTracker {
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun track(eventName: String, params: Map<String, Any?>) {
        events += eventName to params
    }
}
