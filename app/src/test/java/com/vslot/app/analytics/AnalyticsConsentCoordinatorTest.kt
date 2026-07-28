package com.vslot.app.analytics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsConsentCoordinatorTest {
    @Test
    fun `explicit opt in clears revocation only after persistence completes`() {
        val runtime = RecordingRuntime()
        val guard = InMemoryAnalyticsRevocationGuard(initialValue = true)
        val coordinator = AnalyticsConsentCoordinator(runtime, guard)

        val optIn = coordinator.beginUserConsentChange(true)
        assertFalse(runtime.enabled)
        assertTrue(guard.isRevoked())

        assertEquals(
            AnalyticsConsentCompletion.Applied,
            coordinator.completeUserConsentChange(optIn, persisted = true)
        )
        coordinator.track(AnalyticsEvents.AppOpen)

        assertFalse(guard.isRevoked())
        assertTrue(runtime.enabled)
        assertEquals(listOf(AnalyticsEvents.AppOpen), runtime.events)
    }

    @Test
    fun `passive enable captured before opt out cannot clear completed revocation`() {
        val runtime = RecordingRuntime()
        val guard = InMemoryAnalyticsRevocationGuard()
        val coordinator = AnalyticsConsentCoordinator(runtime, guard)

        assertTrue(coordinator.setAnalyticsEnabled(true))
        val stalePersistedValue = true
        val revocation = coordinator.beginUserConsentChange(false)

        assertTrue(revocation.immediateSucceeded)
        assertFalse(coordinator.setAnalyticsEnabled(stalePersistedValue))
        coordinator.track(AnalyticsEvents.AppOpen)

        assertTrue(coordinator.isAnalyticsRevoked())
        assertFalse(runtime.enabled)
        assertTrue(runtime.events.isEmpty())
    }

    @Test
    fun `newer opt out invalidates an in flight user opt in`() {
        val runtime = RecordingRuntime()
        val guard = InMemoryAnalyticsRevocationGuard()
        val coordinator = AnalyticsConsentCoordinator(runtime, guard)

        val optIn = coordinator.beginUserConsentChange(true)
        coordinator.beginUserConsentChange(false)

        assertEquals(
            AnalyticsConsentCompletion.Stale,
            coordinator.completeUserConsentChange(optIn, persisted = true)
        )
        assertFalse(runtime.enabled)
        assertTrue(guard.isRevoked())
    }

    @Test
    fun `failed revocation persistence remains process fail closed`() {
        val runtime = RecordingRuntime()
        val guard = FailingMarkRevocationGuard()
        val coordinator = AnalyticsConsentCoordinator(runtime, guard)
        assertTrue(coordinator.setAnalyticsEnabled(true))

        val revocation = coordinator.beginUserConsentChange(false)
        coordinator.track(AnalyticsEvents.AppOpen)

        assertFalse(revocation.immediateSucceeded)
        assertEquals(1, guard.markAttempts)
        assertTrue(coordinator.isAnalyticsRevoked())
        assertFalse(coordinator.setAnalyticsEnabled(true))
        assertEquals(2, guard.markAttempts)
        assertFalse(runtime.enabled)
        assertTrue(runtime.events.isEmpty())
    }

    @Test
    fun `concurrent revocation is serialized after an in flight enable`() {
        val enableStarted = CountDownLatch(1)
        val releaseEnable = CountDownLatch(1)
        val runtime = RecordingRuntime(enableStarted, releaseEnable)
        val coordinator = AnalyticsConsentCoordinator(
            runtime,
            InMemoryAnalyticsRevocationGuard()
        )
        val enableResult = AtomicReference<Boolean>()
        val revokeResult = AtomicReference<AnalyticsConsentChange>()

        val enabling = thread {
            enableResult.set(coordinator.setAnalyticsEnabled(true))
        }
        assertTrue(enableStarted.await(2, TimeUnit.SECONDS))
        val revoking = thread {
            revokeResult.set(coordinator.beginUserConsentChange(false))
        }
        releaseEnable.countDown()

        enabling.join(2_000)
        revoking.join(2_000)
        coordinator.track(AnalyticsEvents.AppOpen)

        assertFalse(enabling.isAlive)
        assertFalse(revoking.isAlive)
        assertTrue(enableResult.get())
        assertTrue(revokeResult.get().immediateSucceeded)
        assertEquals(listOf(true, false), runtime.transitions)
        assertFalse(runtime.enabled)
        assertTrue(runtime.events.isEmpty())
    }

    private class RecordingRuntime(
        private val enableStarted: CountDownLatch? = null,
        private val releaseEnable: CountDownLatch? = null
    ) : AnalyticsRuntime {
        val transitions = mutableListOf<Boolean>()
        val events = mutableListOf<String>()

        @Volatile
        var enabled = false
            private set

        override fun track(eventName: String, params: Map<String, Any?>) {
            if (enabled) synchronized(events) { events += eventName }
        }

        override fun setAnalyticsEnabled(enabled: Boolean): Boolean {
            if (enabled) {
                enableStarted?.countDown()
                assertTrue(releaseEnable?.await(2, TimeUnit.SECONDS) ?: true)
            }
            synchronized(transitions) { transitions += enabled }
            this.enabled = enabled
            return true
        }
    }

    private class FailingMarkRevocationGuard : AnalyticsRevocationGuard {
        var markAttempts = 0
            private set

        override fun isRevoked(): Boolean = false

        override fun markRevoked(): Boolean {
            markAttempts += 1
            return false
        }

        override fun clearRevoked(): Boolean = true
    }
}
