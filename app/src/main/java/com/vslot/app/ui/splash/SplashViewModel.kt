package com.vslot.app.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vslot.app.data.PlayerRepository
import com.vslot.app.data.PlayerState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModel(
    playerState: Flow<PlayerState>
) : ViewModel() {
    constructor(playerRepository: PlayerRepository) : this(playerRepository.playerState)

    private val reloadRequest = MutableStateFlow(0L)

    val loadState: StateFlow<SplashLoadState> = reloadRequest
        .flatMapLatest {
            flow {
                emit(SplashLoadState.Loading)
                emit(SplashLoadState.Ready(playerState.first()))
            }.catch { cause ->
                if (cause is IOException) {
                    emit(SplashLoadState.Failed)
                } else {
                    throw cause
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SplashLoadState.Loading
        )

    fun retry() {
        reloadRequest.value += 1L
    }

    class Factory(
        private val playerRepository: PlayerRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SplashViewModel(playerRepository) as T
        }
    }
}

sealed interface SplashLoadState {
    data object Loading : SplashLoadState
    data class Ready(val playerState: PlayerState) : SplashLoadState
    data object Failed : SplashLoadState
}
