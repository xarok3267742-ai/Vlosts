package com.vslot.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsEventsTest {
    @Test
    fun `fake tracker captures event names and payloads`() {
        val tracker = FakeAnalyticsTracker()

        tracker.track(AnalyticsEvents.SpinResult, mapOf("slot_id" to "violet_fortune", "win_amount" to 100))

        assertEquals(AnalyticsEvents.SpinResult, tracker.events.single().first)
        assertEquals("violet_fortune", tracker.events.single().second["slot_id"])
        assertEquals(100, tracker.events.single().second["win_amount"])
    }
}
