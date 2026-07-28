package com.vslot.app.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDataStoreRetryPolicyTest {
    @Test
    fun `transient io failures retry until data can be read`() = runTest {
        var collectionAttempts = 0
        val retryDelays = mutableListOf<Long>()

        val value = flow {
            collectionAttempts += 1
            if (collectionAttempts < 3) throw IOException("temporary read failure")
            emit(42)
        }
            .retryTransientPersistenceReads { retryDelays += it }
            .single()

        assertEquals(3, collectionAttempts)
        assertEquals(listOf(100L, 200L), retryDelays)
        assertEquals(42, value)
    }

    @Test
    fun `non io failures are not hidden or retried`() = runTest {
        val failure = IllegalStateException("programming failure")
        var observedFailure: Throwable? = null
        var collectionAttempts = 0
        val retryDelays = mutableListOf<Long>()

        flow<Int> {
            collectionAttempts += 1
            throw failure
        }
            .retryTransientPersistenceReads { retryDelays += it }
            .catch { observedFailure = it }
            .collect()

        assertEquals(1, collectionAttempts)
        assertEquals(emptyList<Long>(), retryDelays)
        assertSame(failure, observedFailure)
    }

    @Test
    fun `persistent io failure emits startup fallback after bounded attempts`() = runTest {
        var collectionAttempts = 0
        val retryDelays = mutableListOf<Long>()

        val value = flow<Int> {
            collectionAttempts += 1
            throw IOException("persistent read failure")
        }
            .retryTransientPersistenceReads(
                retryDelay = { retryDelays += it },
                fallbackAfterAttempts = 3,
                fallbackValue = { 7 }
            )
            .first()

        assertEquals(3, collectionAttempts)
        assertEquals(listOf(100L, 200L), retryDelays)
        assertEquals(7, value)
    }

    @Test
    fun `persistent io failure without fallback stops after bounded attempts`() = runTest {
        val failure = IOException("persistent read failure")
        var collectionAttempts = 0
        val retryDelays = mutableListOf<Long>()
        var observedFailure: Throwable? = null

        flow<Int> {
            collectionAttempts += 1
            throw failure
        }
            .retryTransientPersistenceReads(
                retryDelay = { retryDelays += it },
                fallbackAfterAttempts = 3
            )
            .catch { observedFailure = it }
            .collect()

        assertEquals(3, collectionAttempts)
        assertEquals(listOf(100L, 200L), retryDelays)
        assertSame(failure, observedFailure)
    }

    @Test
    fun `short persistence transaction finishes after caller cancellation`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted = false

        val job = launch {
            finishTransientPersistenceIo {
                started.complete(Unit)
                release.await()
                persisted = true
            }
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        runCurrent()

        assertTrue(persisted)
    }

    @Test
    fun `retry backoff is capped without overflowing`() {
        assertEquals(100L, persistenceRetryDelayMs(0))
        assertEquals(3_200L, persistenceRetryDelayMs(5))
        assertEquals(5_000L, persistenceRetryDelayMs(6))
        assertEquals(5_000L, persistenceRetryDelayMs(Long.MAX_VALUE))
    }

    @Test
    fun `transient persistence io failure retries with bounded backoff`() = runTest {
        var attempts = 0
        val retryDelays = mutableListOf<Long>()

        val value = retryTransientPersistenceIo(
            maxAttempts = 3,
            retryDelay = { retryDelays += it }
        ) {
            attempts += 1
            if (attempts < 3) throw IOException("temporary write failure")
            42
        }

        assertEquals(3, attempts)
        assertEquals(listOf(100L, 200L), retryDelays)
        assertEquals(42, value)
    }

    @Test
    fun `persistent write failure stops at configured attempt limit`() = runTest {
        val failure = IOException("persistent write failure")
        var attempts = 0
        val retryDelays = mutableListOf<Long>()
        var observedFailure: IOException? = null

        try {
            retryTransientPersistenceIo(
                maxAttempts = 2,
                retryDelay = { retryDelays += it }
            ) {
                attempts += 1
                throw failure
            }
        } catch (error: IOException) {
            observedFailure = error
        }

        assertEquals(2, attempts)
        assertEquals(listOf(100L), retryDelays)
        assertSame(failure, observedFailure)
    }

    @Test
    fun `persistence retry does not hide programming failures`() = runTest {
        val failure = IllegalStateException("programming failure")
        var attempts = 0
        var observedFailure: Throwable? = null

        try {
            retryTransientPersistenceIo(maxAttempts = 3, retryDelay = {}) {
                attempts += 1
                throw failure
            }
        } catch (error: Throwable) {
            observedFailure = error
        }

        assertEquals(1, attempts)
        assertSame(failure, observedFailure)
    }
}
