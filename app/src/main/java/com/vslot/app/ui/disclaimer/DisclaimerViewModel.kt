package com.vslot.app.ui.disclaimer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.AnalyticsTracker
import com.vslot.app.data.DisclaimerStore
import com.vslot.app.data.finishTransientPersistenceIo
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DisclaimerViewModel(
    private val playerRepository: DisclaimerStore,
    private val analyticsTracker: AnalyticsTracker
) : ViewModel() {
    private val _acceptanceState = MutableStateFlow(DisclaimerAcceptanceState.Idle)
    val acceptanceState: StateFlow<DisclaimerAcceptanceState> = _acceptanceState.asStateFlow()

    fun accept() {
        if (_acceptanceState.value in setOf(
                DisclaimerAcceptanceState.Saving,
                DisclaimerAcceptanceState.Saved
            )
        ) {
            return
        }
        _acceptanceState.value = DisclaimerAcceptanceState.Saving
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                finishTransientPersistenceIo { playerRepository.acceptDisclaimer() }
            } catch (_: IOException) {
                _acceptanceState.value = DisclaimerAcceptanceState.Failed
                return@launch
            }
            analyticsTracker.track(AnalyticsEvents.DisclaimerAccept)
            _acceptanceState.value = DisclaimerAcceptanceState.Saved
        }
    }

    class Factory(
        private val playerRepository: DisclaimerStore,
        private val analyticsTracker: AnalyticsTracker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DisclaimerViewModel(playerRepository, analyticsTracker) as T
        }
    }
}

enum class DisclaimerAcceptanceState {
    Idle,
    Saving,
    Saved,
    Failed
}
