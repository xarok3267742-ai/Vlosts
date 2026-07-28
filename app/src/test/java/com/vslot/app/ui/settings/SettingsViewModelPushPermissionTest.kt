package com.vslot.app.ui.settings

import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.AnalyticsConsentCoordinator
import com.vslot.app.analytics.AnalyticsRuntime
import com.vslot.app.analytics.FakeAnalyticsTracker
import com.vslot.app.analytics.InMemoryAnalyticsRevocationGuard
import com.vslot.app.analytics.NoOpAnalyticsTracker
import com.vslot.app.data.PlayerSettingsStore
import com.vslot.app.data.PlayerState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelPushPermissionTest {
    @Test
    fun `deferring pre prompt does not persist a system permission attempt`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakePlayerSettingsStore()
            val analytics = FakeAnalyticsTracker()
            val viewModel = SettingsViewModel(store, analytics, NoOpAnalyticsTracker())

            viewModel.onPushPermissionDeferred()
            advanceUntilIdle()

            assertEquals(0, store.permissionAskedWrites)
            assertFalse(store.current.pushPermissionAsked)
            assertEquals(AnalyticsEvents.PushPermissionDeferred, analytics.events.single().first)
            assertEquals("settings", analytics.events.single().second["source"])
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `system permission denial is persisted and tracked as denied`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakePlayerSettingsStore()
            val analytics = FakeAnalyticsTracker()
            val viewModel = SettingsViewModel(store, analytics, NoOpAnalyticsTracker())

            viewModel.onPushPermissionResult(granted = false)
            advanceUntilIdle()

            assertEquals(1, store.permissionAskedWrites)
            assertTrue(store.current.pushPermissionAsked)
            assertEquals(AnalyticsEvents.PushPermissionDenied, analytics.events.single().first)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `system permission grant is persisted and tracked as granted`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakePlayerSettingsStore()
            val analytics = FakeAnalyticsTracker()
            val viewModel = SettingsViewModel(store, analytics, NoOpAnalyticsTracker())

            viewModel.onPushPermissionResult(granted = true)
            advanceUntilIdle()

            assertEquals(1, store.permissionAskedWrites)
            assertTrue(store.current.pushPermissionAsked)
            assertEquals(AnalyticsEvents.PushPermissionGranted, analytics.events.single().first)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `analytics opt in persists before enabling the runtime`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val operations = mutableListOf<String>()
            val store = FakePlayerSettingsStore(operations)
            val controller = RecordingAnalyticsConsentController(operations)
            val viewModel = SettingsViewModel(store, FakeAnalyticsTracker(), controller)

            viewModel.setAnalyticsEnabled(true)
            advanceUntilIdle()

            assertEquals(listOf("persist:true", "runtime:true"), operations)
            assertTrue(store.current.analyticsEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `analytics opt out blocks runtime before persisting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val operations = mutableListOf<String>()
            val store = FakePlayerSettingsStore(operations, analyticsEnabled = true)
            val controller = RecordingAnalyticsConsentController(operations)
            val viewModel = SettingsViewModel(store, FakeAnalyticsTracker(), controller)

            viewModel.setAnalyticsEnabled(false)
            assertEquals(listOf("runtime:false"), operations)
            advanceUntilIdle()

            assertEquals(listOf("runtime:false", "persist:false"), operations)
            assertFalse(store.current.analyticsEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `analytics opt in stays runtime disabled when persistence retries are exhausted`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val operations = mutableListOf<String>()
            val store = FakePlayerSettingsStore(
                operations = operations,
                analyticsWriteFailuresRemaining = 2
            )
            val controller = RecordingAnalyticsConsentController(operations)
            val viewModel = SettingsViewModel(store, FakeAnalyticsTracker(), controller)

            viewModel.setAnalyticsEnabled(true)
            advanceUntilIdle()

            assertEquals(listOf("persist_failed:true", "persist_failed:true"), operations)
            assertFalse(store.current.analyticsEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `analytics opt out persists and remains revoked when provider transition fails`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val operations = mutableListOf<String>()
            val store = FakePlayerSettingsStore(operations, analyticsEnabled = true)
            val controller = RecordingAnalyticsConsentController(
                operations = operations,
                transitionSucceeds = false
            )
            val guard = InMemoryAnalyticsRevocationGuard()
            val coordinator = AnalyticsConsentCoordinator(controller, guard)
            val viewModel = SettingsViewModel(store, FakeAnalyticsTracker(), coordinator, guard)

            viewModel.setAnalyticsEnabled(false)
            advanceUntilIdle()

            assertEquals(
                listOf("runtime:false", "persist:false", "runtime:false"),
                operations
            )
            assertFalse(store.current.analyticsEnabled)
            assertTrue(guard.isRevoked())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `analytics opt out remains revoked across view models when persistence fails`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val operations = mutableListOf<String>()
            val store = FakePlayerSettingsStore(
                operations = operations,
                analyticsEnabled = true,
                analyticsWriteFailuresRemaining = 2
            )
            val guard = InMemoryAnalyticsRevocationGuard()
            val controller = AnalyticsConsentCoordinator(
                RecordingAnalyticsConsentController(operations),
                guard
            )
            val firstViewModel = SettingsViewModel(
                store,
                FakeAnalyticsTracker(),
                controller,
                guard
            )

            firstViewModel.setAnalyticsEnabled(false)
            advanceUntilIdle()

            assertTrue(store.current.analyticsEnabled)
            assertTrue(guard.isRevoked())
            assertFalse(firstViewModel.playerState.value.analyticsEnabled)

            val recreatedViewModel = SettingsViewModel(
                store,
                FakeAnalyticsTracker(),
                controller,
                guard
            )
            assertFalse(recreatedViewModel.playerState.value.analyticsEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed analytics opt in rolls persisted consent back to disabled`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val operations = mutableListOf<String>()
            val store = FakePlayerSettingsStore(operations)
            val controller = RecordingAnalyticsConsentController(
                operations = operations,
                transitionSucceeds = false
            )
            val viewModel = SettingsViewModel(store, FakeAnalyticsTracker(), controller)

            viewModel.setAnalyticsEnabled(true)
            advanceUntilIdle()

            assertEquals(
                listOf("persist:true", "runtime:true", "runtime:false", "persist:false"),
                operations
            )
            assertFalse(store.current.analyticsEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `late opt in persistence cannot override a newer opt out`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val operations = mutableListOf<String>()
            val optInWriteStarted = CompletableDeferred<Unit>()
            val releaseOptInWrite = CompletableDeferred<Unit>()
            val store = FakePlayerSettingsStore(
                operations = operations,
                optInWriteStarted = optInWriteStarted,
                releaseOptInWrite = releaseOptInWrite
            )
            val guard = InMemoryAnalyticsRevocationGuard()
            val runtime = RecordingAnalyticsConsentController(operations)
            val coordinator = AnalyticsConsentCoordinator(runtime, guard)
            val viewModel = SettingsViewModel(
                store,
                coordinator,
                coordinator,
                guard
            )

            viewModel.setAnalyticsEnabled(true)
            runCurrent()
            assertTrue(optInWriteStarted.isCompleted)

            viewModel.setAnalyticsEnabled(false)
            runCurrent()
            assertTrue(guard.isRevoked())
            assertFalse(store.current.analyticsEnabled)

            releaseOptInWrite.complete(Unit)
            advanceUntilIdle()

            assertFalse(store.current.analyticsEnabled)
            assertTrue(guard.isRevoked())
            assertTrue("runtime must never be enabled", "runtime:true" !in operations)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakePlayerSettingsStore(
        private val operations: MutableList<String> = mutableListOf(),
        analyticsEnabled: Boolean = false,
        private var analyticsWriteFailuresRemaining: Int = 0,
        private val optInWriteStarted: CompletableDeferred<Unit>? = null,
        private val releaseOptInWrite: CompletableDeferred<Unit>? = null
    ) : PlayerSettingsStore {
        private val state = MutableStateFlow(PlayerState(analyticsEnabled = analyticsEnabled))
        var permissionAskedWrites = 0
            private set
        val current: PlayerState get() = state.value

        override val playerState: Flow<PlayerState> = state

        override suspend fun updateSoundEnabled(enabled: Boolean) {
            state.value = state.value.copy(soundEnabled = enabled)
        }

        override suspend fun updateHapticsEnabled(enabled: Boolean) {
            state.value = state.value.copy(hapticsEnabled = enabled)
        }

        override suspend fun updateAnalyticsEnabled(enabled: Boolean) {
            if (enabled && releaseOptInWrite != null) {
                optInWriteStarted?.complete(Unit)
                releaseOptInWrite.await()
            }
            if (analyticsWriteFailuresRemaining > 0) {
                analyticsWriteFailuresRemaining -= 1
                operations += "persist_failed:$enabled"
                throw IOException("test analytics persistence failure")
            }
            operations += "persist:$enabled"
            state.value = state.value.copy(analyticsEnabled = enabled)
        }

        override suspend fun markPushPermissionAsked() {
            permissionAskedWrites += 1
            state.value = state.value.copy(pushPermissionAsked = true)
        }
    }

    private class RecordingAnalyticsConsentController(
        private val operations: MutableList<String>,
        private val transitionSucceeds: Boolean = true
    ) : AnalyticsRuntime {
        override fun track(eventName: String, params: Map<String, Any?>) = Unit

        override fun setAnalyticsEnabled(enabled: Boolean): Boolean {
            operations += "runtime:$enabled"
            return transitionSucceeds
        }
    }
}
