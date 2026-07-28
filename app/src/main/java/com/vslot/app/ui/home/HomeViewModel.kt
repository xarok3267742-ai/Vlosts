package com.vslot.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.AnalyticsTracker
import com.vslot.app.data.PlayerRepository
import com.vslot.app.data.retryTransientPersistenceIo
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(
    private val playerRepository: PlayerRepository,
    private val analyticsTracker: AnalyticsTracker
) : ViewModel() {
    val playerState = playerRepository.playerState

    fun onHomeVisible() {
        viewModelScope.launch {
            val coinsBalance = playerRepository.playerState.first().coinsBalance
            analyticsTracker.track(AnalyticsEvents.HomeView, mapOf("coins_balance" to coinsBalance))
        }
    }

    fun onSlotSelected(slotId: String, slotName: String) {
        viewModelScope.launch {
            try {
                retryTransientPersistenceIo {
                    playerRepository.updateLastPlayedSlot(slotId)
                }
            } catch (_: IOException) {
                // Slot navigation remains usable even if this convenience value cannot be saved.
            }
        }
        analyticsTracker.track(
            AnalyticsEvents.SlotSelect,
            mapOf("slot_id" to slotId, "slot_name" to slotName)
        )
    }

    fun onDailyBonusOpen(available: Boolean) {
        analyticsTracker.track(AnalyticsEvents.DailyBonusOpen, mapOf("available" to available))
    }

    class Factory(
        private val playerRepository: PlayerRepository,
        private val analyticsTracker: AnalyticsTracker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(playerRepository, analyticsTracker) as T
        }
    }
}
