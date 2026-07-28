package com.vslot.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyAnalyticsRuntimeTest {
    @Test
    fun `disabled analytics never activates provider`() {
        var activations = 0
        val runtime = LazyAnalyticsRuntime {
            activations += 1
            RecordingRuntime()
        }

        assertTrue(runtime.setAnalyticsEnabled(false))
        runtime.track("blocked")

        assertEquals(0, activations)
    }

    @Test
    fun `analytics consent activates provider once and delegates events`() {
        var activations = 0
        val delegate = RecordingRuntime()
        val runtime = LazyAnalyticsRuntime {
            activations += 1
            delegate
        }

        assertTrue(runtime.setAnalyticsEnabled(true))
        runtime.track("spin", mapOf("slot" to "violet"))
        assertTrue(runtime.setAnalyticsEnabled(true))

        assertEquals(1, activations)
        assertEquals(listOf(true, true), delegate.consentChanges)
        assertEquals(listOf("spin"), delegate.events)
    }

    @Test
    fun `push consent activates provider without enabling analytics`() {
        val delegate = RecordingRuntime()
        val runtime = LazyAnalyticsRuntime { delegate }

        assertTrue(runtime.ensureActivatedForPush())

        assertTrue(delegate.consentChanges.isEmpty())
        assertTrue(delegate.events.isEmpty())
    }

    @Test
    fun `failed activation remains retryable and fails closed`() {
        var activations = 0
        val runtime = LazyAnalyticsRuntime {
            activations += 1
            if (activations == 1) null else RecordingRuntime()
        }

        assertFalse(runtime.setAnalyticsEnabled(true))
        assertTrue(runtime.ensureActivatedForPush())
        assertEquals(2, activations)
    }

    private class RecordingRuntime : AnalyticsRuntime {
        val events = mutableListOf<String>()
        val consentChanges = mutableListOf<Boolean>()

        override fun track(eventName: String, params: Map<String, Any?>) {
            events += eventName
        }

        override fun setAnalyticsEnabled(enabled: Boolean): Boolean {
            consentChanges += enabled
            return true
        }
    }
}
