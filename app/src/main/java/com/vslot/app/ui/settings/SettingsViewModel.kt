package com.vslot.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.AnalyticsConsentCompletion
import com.vslot.app.analytics.AnalyticsConsentController
import com.vslot.app.analytics.AnalyticsRevocationGuard
import com.vslot.app.analytics.AnalyticsTracker
import com.vslot.app.analytics.InMemoryAnalyticsRevocationGuard
import com.vslot.app.data.PlayerSettingsStore
import com.vslot.app.data.PlayerState
import com.vslot.app.data.finishTransientPersistenceIo
import java.io.IOException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

class SettingsViewModel(
    private val playerRepository: PlayerSettingsStore,
    private val analyticsTracker: AnalyticsTracker,
    private val analyticsConsentController: AnalyticsConsentController,
    private val analyticsRevocationGuard: AnalyticsRevocationGuard =
        InMemoryAnalyticsRevocationGuard()
) : ViewModel() {
    private val analyticsRevoked = MutableStateFlow(isAnalyticsRevoked())
    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    @Volatile
    private var latestAnalyticsIntent: Boolean? = null

    val playerState: StateFlow<PlayerState> = combine(
        playerRepository.playerState,
        analyticsRevoked
    ) { state, revoked ->
        state.copy(analyticsEnabled = state.analyticsEnabled && !revoked)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerState()
    )

    fun onVisible() {
        analyticsTracker.track(AnalyticsEvents.SettingsOpen, mapOf("source" to "home"))
    }

    fun onPushPermissionShown(androidVersion: Int) {
        analyticsTracker.track(AnalyticsEvents.PushPermissionShown, mapOf("android_version" to androidVersion))
    }

    fun onPushPermissionDeferred() {
        analyticsTracker.track(
            AnalyticsEvents.PushPermissionDeferred,
            mapOf("source" to "settings")
        )
    }

    fun onPushPermissionResult(
        granted: Boolean,
        onPersisted: () -> Unit = {},
        onFailure: () -> Unit = {}
    ) {
        persistSetting(
            update = { playerRepository.markPushPermissionAsked() },
            onPersisted = {
                analyticsTracker.track(
                    if (granted) {
                        AnalyticsEvents.PushPermissionGranted
                    } else {
                        AnalyticsEvents.PushPermissionDenied
                    },
                    mapOf("source" to "settings")
                )
                onPersisted()
            },
            onFailure = onFailure
        )
    }

    fun setSoundEnabled(enabled: Boolean) {
        persistSetting(update = { playerRepository.updateSoundEnabled(enabled) })
    }

    fun setHapticsEnabled(enabled: Boolean) {
        persistSetting(update = { playerRepository.updateHapticsEnabled(enabled) })
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        latestAnalyticsIntent = enabled
        val consentChange = analyticsConsentController.beginUserConsentChange(enabled)
        if (!enabled) {
            analyticsRevoked.value = true
        }
        persistSetting(
            update = { playerRepository.updateAnalyticsEnabled(enabled) },
            onPersisted = {
                if (enabled) {
                    when (
                        analyticsConsentController.completeUserConsentChange(
                            consentChange,
                            persisted = true
                        )
                    ) {
                        AnalyticsConsentCompletion.Applied -> analyticsRevoked.value = false
                        AnalyticsConsentCompletion.Stale -> {
                            analyticsRevoked.value = isAnalyticsRevoked()
                            if (latestAnalyticsIntent == false) {
                                persistSetting(
                                    update = { playerRepository.updateAnalyticsEnabled(false) }
                                )
                            }
                        }
                        AnalyticsConsentCompletion.Failed -> failAnalyticsOptInClosed()
                    }
                } else {
                    analyticsConsentController.completeUserConsentChange(
                        consentChange,
                        persisted = true
                    )
                }
            },
            onFailure = {
                analyticsConsentController.completeUserConsentChange(
                    consentChange,
                    persisted = false
                )
                analyticsRevoked.value = isAnalyticsRevoked()
            }
        )
    }

    private fun failAnalyticsOptInClosed() {
        analyticsConsentController.beginUserConsentChange(false)
        analyticsRevoked.value = true
        persistSetting(update = { playerRepository.updateAnalyticsEnabled(false) })
    }

    private fun isAnalyticsRevoked(): Boolean {
        return analyticsRevocationGuard.isRevoked() ||
            analyticsConsentController.isAnalyticsRevoked()
    }

    private fun persistSetting(
        update: suspend () -> Unit,
        onPersisted: () -> Unit = {},
        onFailure: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                finishTransientPersistenceIo(operation = update)
                onPersisted()
            } catch (_: IOException) {
                // The collected persisted state remains authoritative after a failed update.
                eventChannel.trySend(SettingsEvent.SaveFailed)
                onFailure()
            }
        }
    }

    class Factory(
        private val playerRepository: PlayerSettingsStore,
        private val analyticsTracker: AnalyticsTracker,
        private val analyticsConsentController: AnalyticsConsentController,
        private val analyticsRevocationGuard: AnalyticsRevocationGuard
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                playerRepository,
                analyticsTracker,
                analyticsConsentController,
                analyticsRevocationGuard
            ) as T
        }
    }
}

sealed interface SettingsEvent {
    data object SaveFailed : SettingsEvent
}
