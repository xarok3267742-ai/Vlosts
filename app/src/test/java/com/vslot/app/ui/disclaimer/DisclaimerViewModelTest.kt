package com.vslot.app.ui.disclaimer

import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.FakeAnalyticsTracker
import com.vslot.app.data.DisclaimerStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisclaimerViewModelTest {
    @Test
    fun `transient persistence failure retries before completing acceptance`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakeDisclaimerStore(failuresRemaining = 1)
            val analytics = FakeAnalyticsTracker()
            val viewModel = DisclaimerViewModel(store, analytics)

            viewModel.accept()
            advanceUntilIdle()

            assertEquals(2, store.attempts)
            assertTrue(store.accepted)
            assertEquals(DisclaimerAcceptanceState.Saved, viewModel.acceptanceState.value)
            assertEquals(AnalyticsEvents.DisclaimerAccept, analytics.events.single().first)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `persistent persistence failure completes as retryable without analytics`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakeDisclaimerStore(failuresRemaining = Int.MAX_VALUE)
            val analytics = FakeAnalyticsTracker()
            val viewModel = DisclaimerViewModel(store, analytics)

            viewModel.accept()
            advanceUntilIdle()

            assertEquals(2, store.attempts)
            assertFalse(store.accepted)
            assertEquals(DisclaimerAcceptanceState.Failed, viewModel.acceptanceState.value)
            assertTrue(analytics.events.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeDisclaimerStore(
        private var failuresRemaining: Int
    ) : DisclaimerStore {
        var attempts = 0
            private set
        var accepted = false
            private set

        override suspend fun acceptDisclaimer() {
            attempts += 1
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IOException("test disclaimer write failure")
            }
            accepted = true
        }
    }
}
