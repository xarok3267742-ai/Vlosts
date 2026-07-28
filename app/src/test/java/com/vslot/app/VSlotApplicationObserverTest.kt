package com.vslot.app

import com.vslot.app.data.retryTransientPersistenceReads
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VSlotApplicationObserverTest {
    @Test
    fun `analytics observer fails closed before backoff and resumes collection`() = runTest {
        var subscriptions = 0
        val events = mutableListOf<String>()

        observeAnalyticsConsentState(
            consentState = flow {
                subscriptions += 1
                emit(true)
                if (subscriptions == 1) throw IOException("player state unavailable")
            },
            setAnalyticsEnabled = { enabled -> events += "analytics:$enabled" },
            onPersistenceFailure = { events += "io" },
            retryDelay = { delayMs -> events += "delay:$delayMs" }
        )

        assertEquals(2, subscriptions)
        assertEquals(
            listOf("analytics:true", "analytics:false", "io", "delay:1000", "analytics:true"),
            events
        )
    }

    @Test
    fun `push observer fails closed before backoff and reconciles recovered consent`() = runTest {
        var subscriptions = 0
        val events = mutableListOf<String>()

        assertEquals(
            PushRegistrationCommand(enabled = false, deleteToken = true),
            PROCESS_PUSH_FAIL_CLOSED_COMMAND
        )
        observePushConsentState(
            consentState = flow {
                subscriptions += 1
                emit(true)
                if (subscriptions == 1) throw IOException("player state unavailable")
            },
            reconcileConsent = { permissionAsked -> events += "reconcile:$permissionAsked" },
            failClosed = { events += "fail-closed" },
            onPersistenceFailure = { events += "io" },
            retryDelay = { delayMs -> events += "delay:$delayMs" }
        )

        assertEquals(2, subscriptions)
        assertEquals(
            listOf("reconcile:true", "fail-closed", "io", "delay:1000", "reconcile:true"),
            events
        )
    }

    @Test
    fun `process observer resubscribes after repository read retries are exhausted`() = runTest {
        var readAttempts = 0
        var failClosedCalls = 0
        val retryDelays = mutableListOf<Long>()

        val recoveredValue = flow {
            readAttempts += 1
            if (readAttempts <= 3) throw IOException("bounded read failure $readAttempts")
            emit(42)
        }
            .retryTransientPersistenceReads(
                fallbackAfterAttempts = 3,
                retryDelay = {}
            )
            .retryProcessObserverPersistence(
                onPersistenceFailure = { failClosedCalls += 1 },
                retryDelay = { retryDelays += it }
            )
            .single()

        assertEquals(42, recoveredValue)
        assertEquals(4, readAttempts)
        assertEquals(1, failClosedCalls)
        assertEquals(listOf(1_000L), retryDelays)
    }

    @Test
    fun `process observer keeps retrying persistent io with capped backoff`() = runTest {
        var subscriptions = 0
        val failures = mutableListOf<IOException>()
        val retryDelays = mutableListOf<Long>()

        val recoveredValue = flow {
            subscriptions += 1
            if (subscriptions <= 8) throw IOException("failure $subscriptions")
            emit(42)
        }
            .retryProcessObserverPersistence(
                onPersistenceFailure = { failures += it },
                retryDelay = { retryDelays += it }
            )
            .single()

        assertEquals(42, recoveredValue)
        assertEquals(9, subscriptions)
        assertEquals(8, failures.size)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            retryDelays
        )
        assertEquals(30_000L, processObserverRetryDelayMs(Long.MAX_VALUE))
    }

    @Test
    fun `process observer does not hide non io failures`() = runTest {
        val failure = IllegalStateException("programming failure")
        val retryDelays = mutableListOf<Long>()
        var observedFailure: Throwable? = null

        try {
            flow<Int> { throw failure }
                .retryProcessObserverPersistence(
                    onPersistenceFailure = { error("unexpected persistence callback") },
                    retryDelay = { retryDelays += it }
                )
                .single()
        } catch (error: Throwable) {
            observedFailure = error
        }

        assertSame(failure, observedFailure)
        assertEquals(emptyList<Long>(), retryDelays)
    }
}
