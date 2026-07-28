package com.vslot.app.ui.splash

import com.vslot.app.data.PlayerState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {
    @Test
    fun `persistent storage failure becomes retryable and retry can load state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var failRead = true
            var reads = 0
            val expected = PlayerState(disclaimerAccepted = true)
            val viewModel = SplashViewModel(
                flow {
                    reads += 1
                    if (failRead) throw IOException("persistent test failure")
                    emit(expected)
                }
            )
            val observed = mutableListOf<SplashLoadState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.loadState.collect(observed::add)
            }

            advanceUntilIdle()
            assertTrue(observed.contains(SplashLoadState.Failed))

            failRead = false
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(2, reads)
            assertEquals(SplashLoadState.Ready(expected), viewModel.loadState.value)
            collector.cancel()
            advanceTimeBy(5_001)
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
