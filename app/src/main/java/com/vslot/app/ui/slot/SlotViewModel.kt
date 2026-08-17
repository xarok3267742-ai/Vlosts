package com.vslot.app.ui.slot

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.vslot.app.ProcessSession
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.AnalyticsTracker
import com.vslot.app.data.PendingSpinSettlement
import com.vslot.app.data.PendingSpinRecoveryStatus
import com.vslot.app.data.PlayerState
import com.vslot.app.data.PlayerStore
import com.vslot.app.data.SpinReservation
import com.vslot.app.data.SpinReservationAttempt
import com.vslot.app.data.SpinSettlementReceipt
import com.vslot.app.data.retryTransientPersistenceIo
import com.vslot.app.game.NetOutcome
import com.vslot.app.game.SlotCatalog
import com.vslot.app.game.SlotConfig
import com.vslot.app.game.SlotEngine
import com.vslot.app.game.SlotMathIdentity
import com.vslot.app.game.SpinResult
import com.vslot.app.game.checkedSlotMultiply
import com.vslot.app.game.netOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

data class SlotUiState(
    val config: SlotConfig,
    val playerState: PlayerState = PlayerState(),
    val isSpinStartReserved: Boolean = false,
    val isSpinning: Boolean = false,
    val isSlamStopping: Boolean = false,
    val isReducedMotionStop: Boolean = false,
    val isCurrentSpinFreeSpin: Boolean = false,
    val spinStartedAtMonotonicMs: Long? = null,
    val spinStopRequestedAtMonotonicMs: Long? = null,
    val spinPresentationId: String? = null,
    val lastResult: SpinResult? = null,
    val lastResultPresentationId: String? = null,
    val pendingResult: SpinResult? = null,
    val pendingPresentationId: String? = null,
    val isSettlementRecoveryPending: Boolean = false,
    val isResultPending: Boolean = false,
    val isAutoSpinEnabled: Boolean = false,
    val autoSpinsRemaining: Int? = null,
    val isFreeSpinAutoPlay: Boolean = false,
    val pendingFreeSpinsTotalWin: Int? = null,
    val autoSpinStopReason: AutoSpinStopReason? = null,
    val isSettlementRecoveryBlockedByMath: Boolean = false
)

sealed class SlotEvent {
    data class LowCoins(
        val bonusAvailable: Boolean,
        val canReduceStake: Boolean
    ) : SlotEvent()
    data class ResultReady(
        val result: SpinResult,
        val freeSpinsAwarded: Int,
        val presentationId: String,
        val freeSpinsTotalWin: Int? = null
    ) : SlotEvent()

    data class PendingPresentation(val slotId: String) : SlotEvent()
    data class AutoSpinStopped(val reason: AutoSpinStopReason) : SlotEvent()
}

enum class AutoSpinStopReason {
    LossLimit,
    BigWin,
    Bonus
}

class SlotViewModel private constructor(
    slotId: String,
    private val playerRepository: PlayerStore,
    slotRepository: SlotCatalog,
    private val slotEngine: SlotEngine,
    private val analyticsTracker: AnalyticsTracker,
    private val monotonicTimeMs: () -> Long,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    @SuppressLint("VisibleForTests")
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    constructor(
        slotId: String,
        playerRepository: PlayerStore,
        slotRepository: SlotCatalog,
        slotEngine: SlotEngine,
        analyticsTracker: AnalyticsTracker,
        monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L }
    ) : this(
        slotId,
        playerRepository,
        slotRepository,
        slotEngine,
        analyticsTracker,
        monotonicTimeMs,
        SavedStateHandle()
    )

    private val config = slotRepository.getSlot(slotId)
    private val settlementOwnershipLock = Any()
    private val presentationConsumerId = "${ProcessSession.id}:${UUID.randomUUID()}"
    @Volatile
    private var cleared = false
    private val activeSpin = MutableStateFlow<ActiveSpinPresentation?>(null)
    private val lastResult = MutableStateFlow<SpinResult?>(null)
    private val lastResultPresentationId = MutableStateFlow<String?>(null)
    private val isResultPending = MutableStateFlow(false)
    private val pendingFreeSpinsTotalWin = MutableStateFlow<Int?>(null)
    private val autoSpinStopReason = MutableStateFlow<AutoSpinStopReason?>(null)
    private val autoPlayState = MutableStateFlow<AutoPlayState>(AutoPlayState.Off)
    private var autoSpinStartJob: Job? = null
    private var autoSpinJob: Job? = null
    private var featureResumeJob: Job? = null
    private var autoPlayGeneration = 0L
    private var explicitAutoPlayStopRevision = 0L
    private val isSpinStartReserved = MutableStateFlow(true)
    private val pendingPresentationId = MutableStateFlow<String?>(null)
    private val isSettlementRecoveryPending = MutableStateFlow(false)
    private val isSettlementRecoveryBlockedByMath = MutableStateFlow(false)
    private var pendingSettlementRecovery: CommittedSpin? = null
    private var settlementRecoveryJob: Job? = null
    private var settlementRecoveryRetryAttempt = 0
    private var presentationRecoveryJob: Job? = null
    private var presentationRecoveryRetryAttempt = 0
    private var presentationAcknowledgementInFlight: String? = null
    private var retryablePresentationAcknowledgementId: String? = null
    private var presentationAcknowledgementRetryJob: Job? = null
    private var presentationAcknowledgementRetryAttempt = 0
    private var deferredResultDialogPresentedId: String? = null
    private var nextAutoSpinDelayAfterPresentation: Pair<String, Long>? = null
    private var spinSlamStopSignal: CompletableDeferred<Unit>? = null
    private val ownedSettlementIds = mutableSetOf<String>()
    private val stakeUpdateMutex = Mutex()
    private val eventChannel = Channel<SlotEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val resultPresentationState = combine(
        isResultPending,
        pendingFreeSpinsTotalWin,
        autoSpinStopReason
    ) { resultPending, freeSpinsTotalWin, stopReason ->
        ResultPresentationState(resultPending, freeSpinsTotalWin, stopReason)
    }

    private val settlementRecoveryState = combine(
        isSettlementRecoveryPending,
        isSettlementRecoveryBlockedByMath
    ) { isPending, isBlockedByMath ->
        SettlementRecoveryUiState(isPending, isBlockedByMath)
    }

    private val baseUiState = combine(
        playerRepository.playerState,
        activeSpin,
        lastResult,
        lastResultPresentationId,
        resultPresentationState
    ) { playerState, activePresentation, result, resultPresentationId, presentationState ->
        SlotUiState(
            config = config,
            playerState = playerState,
            isSpinning = activePresentation != null,
            isSlamStopping = activePresentation?.stopMode == SpinStopMode.Slam,
            isReducedMotionStop = activePresentation?.stopMode == SpinStopMode.ReducedMotion,
            isCurrentSpinFreeSpin = activePresentation?.isFreeSpin == true,
            spinStartedAtMonotonicMs = activePresentation?.startedAtMonotonicMs,
            spinStopRequestedAtMonotonicMs = activePresentation?.stopRequestedAtMonotonicMs,
            spinPresentationId = activePresentation?.id,
            lastResult = result,
            lastResultPresentationId = resultPresentationId,
            pendingResult = activePresentation?.result,
            isResultPending = presentationState.isResultPending,
            pendingFreeSpinsTotalWin = presentationState.freeSpinsTotalWin,
            autoSpinStopReason = presentationState.autoSpinStopReason
        )
    }

    val uiState: StateFlow<SlotUiState> = combine(
        baseUiState,
        autoPlayState,
        isSpinStartReserved,
        pendingPresentationId,
        settlementRecoveryState
    ) { state, currentAutoPlay, spinStartReserved, presentationId, recoveryState ->
        state.copy(
            isAutoSpinEnabled = currentAutoPlay !is AutoPlayState.Off,
            autoSpinsRemaining = currentAutoPlay.paidBatchOrNull()?.let { batch ->
                batch.remainingToStart + if (state.isSpinning) 1 else 0
            },
            isFreeSpinAutoPlay = currentAutoPlay is AutoPlayState.FreeSpins,
            isSpinStartReserved = spinStartReserved,
            pendingPresentationId = presentationId,
            isSettlementRecoveryPending = recoveryState.isPending,
            isSettlementRecoveryBlockedByMath = recoveryState.isBlockedByMath
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SlotUiState(config, isSpinStartReserved = true)
    )

    init {
        ProcessSession.registerPresentationConsumer(presentationConsumerId)
        analyticsTracker.track(AnalyticsEvents.SlotScreenView, mapOf("slot_id" to config.id))
        launchIdempotentPersistence {
            playerRepository.updateLastPlayedSlot(config.id)
        }
        retryPendingPresentationRecovery()
    }

    fun retryPendingPresentationRecovery() {
        if (
            presentationRecoveryJob?.isActive == true ||
            pendingPresentationId.value != null ||
            activeSpin.value != null
        ) return
        launchPresentationRecovery(delayMs = 0L)
    }

    private fun launchPresentationRecovery(delayMs: Long) {
        if (presentationRecoveryJob?.isActive == true) return
        isSpinStartReserved.value = true
        presentationRecoveryJob = viewModelScope.launch {
            var retryRequired = false
            try {
                if (delayMs > 0L) delay(delayMs)
                restorePendingSpinPresentation()
                presentationRecoveryRetryAttempt = 0
            } catch (_: IOException) {
                retryRequired = true
            } finally {
                presentationRecoveryJob = null
                if (retryRequired && !cleared && pendingPresentationId.value == null) {
                    val retryDelayMs = (PRESENTATION_RECOVERY_RETRY_DELAY_MS shl
                        presentationRecoveryRetryAttempt.coerceAtMost(
                            PRESENTATION_RECOVERY_MAX_BACKOFF_SHIFT
                        )).coerceAtMost(PRESENTATION_RECOVERY_MAX_RETRY_DELAY_MS)
                    presentationRecoveryRetryAttempt += 1
                    launchPresentationRecovery(retryDelayMs)
                } else {
                    isSpinStartReserved.value = false
                    if (
                        pendingPresentationId.value == null &&
                        isAutoPlayActive() &&
                        activeSpin.value == null &&
                        !isResultPending.value
                    ) {
                        scheduleNextAutoSpin(AUTO_SPIN_RESUME_DELAY_MS)
                    }
                }
            }
        }
    }

    private suspend fun restorePendingSpinPresentation() {
        val settlement = retryTransientPersistenceIo {
            playerRepository.claimSpinPresentation(config.id, presentationConsumerId)
        }
        val result = settlement?.visualResult?.takeIf(::isRecoveredResultCompatible)
        if (settlement != null && result == null) {
            retryTransientPersistenceIo {
                playerRepository.acknowledgeSpinPresentation(settlement.id)
            }
            return
        }
        if (settlement == null || result == null) {
            val recoveryStatus = retryTransientPersistenceIo {
                playerRepository.pendingSpinRecoveryStatus()
            }
            if (recoveryStatus != PendingSpinRecoveryStatus.None) {
                pauseAutoSpin()
                isSettlementRecoveryBlockedByMath.value =
                    recoveryStatus == PendingSpinRecoveryStatus.UnsupportedMath
                isSettlementRecoveryPending.value = true
                return
            }
            retryTransientPersistenceIo {
                playerRepository.pendingSpinPresentationSlotId()
            }
                ?.takeIf { it != config.id }
                ?.let { eventChannel.send(SlotEvent.PendingPresentation(it)) }
            return
        }

        val restoredPlayerState = playerRepository.playerState.first()
        val restoredFreeSpinsTotalWin = if (
            settlement.isFreeSpin &&
            !restoredPlayerState.hasFreeSpinsForSlot(config.id)
        ) {
            restoredPlayerState.freeSpinFeatureTotalWinForSlot(config.id)
                ?: currentFreeSpinsTotalWin().takeIf { isFreeSpinsSessionTracked() }
        } else {
            null
        }
        val shouldShowResultDialog = restoredFreeSpinsTotalWin != null ||
            SlotResultPresentationPolicy.shouldShowResultDialog(result)
        if (!shouldShowResultDialog) {
            nextAutoSpinDelayAfterPresentation = settlement.id to
                inlineResultAutoSpinDelayMs(result)
        }
        lastResult.value = result
        lastResultPresentationId.value = settlement.id
        pendingPresentationId.value = settlement.id
        pendingFreeSpinsTotalWin.value = restoredFreeSpinsTotalWin
        if (shouldShowResultDialog) {
            isResultPending.value = true
            reconcileDeferredResultDialogPresentation()
            eventChannel.send(
                SlotEvent.ResultReady(
                    result,
                    settlement.freeSpinsAwarded,
                    settlement.id,
                    restoredFreeSpinsTotalWin
                )
            )
        }
    }

    private fun isRecoveredResultCompatible(result: SpinResult): Boolean {
        return result.bet in config.bets &&
            result.lines in PlayerState.MIN_LINES..config.paylines &&
            result.totalBet == result.bet * result.lines &&
            result.reels.size == config.reels &&
            result.reels.all { reel ->
                reel.size == config.rows && reel.all { symbol -> symbol in config.symbols }
            } &&
            result.winningLines.all { line -> line.paylineIndex in 0 until result.lines } &&
            result.scatterPositions.all { position ->
                position.reel in 0 until config.reels && position.row in 0 until config.rows
            }
    }

    fun selectPreviousBet() {
        updateBetByOffset(-1)
    }

    fun selectNextBet() {
        updateBetByOffset(1)
    }

    fun selectPreviousLines() {
        updateLinesByOffset(-1)
    }

    fun selectNextLines() {
        updateLinesByOffset(1)
    }

    fun selectMaxLines() {
        if (!canChangeStake()) return
        viewModelScope.launch {
            runSerializedStakeUpdate {
                playerRepository.updateSelectedLines(config.paylines)
            }
        }
    }

    fun reduceStakeToAffordable() {
        if (!canChangeStake()) return
        viewModelScope.launch {
            runSerializedStakeUpdate { state ->
                val target = AffordableStakePolicy.select(
                    balance = state.coinsBalance,
                    selectedLines = state.selectedLines,
                    supportedBets = config.bets,
                    maxLines = config.paylines
                ) ?: return@runSerializedStakeUpdate
                if (target.lineBet != state.selectedBet) {
                    playerRepository.updateSelectedBet(target.lineBet)
                }
                if (target.lines != state.selectedLines) {
                    playerRepository.updateSelectedLines(target.lines)
                }
            }
        }
    }

    fun spin() {
        dismissAutoSpinStopNotice()
        spin(autoTriggered = false)
    }

    fun dismissAutoSpinStopNotice() {
        autoSpinStopReason.value = null
    }

    fun requestSlamStop() {
        val currentSpin = activeSpin.value ?: return
        if (isAutoPlayActive() || currentSpin.stopMode != SpinStopMode.None) return
        activeSpin.value = currentSpin.copy(
            stopMode = SpinStopMode.Slam,
            stopRequestedAtMonotonicMs = monotonicTimeMs()
        )
        spinSlamStopSignal?.complete(Unit)
    }

    fun requestReducedMotionStop() {
        val currentSpin = activeSpin.value ?: return
        if (currentSpin.stopMode == SpinStopMode.ReducedMotion) return
        activeSpin.value = currentSpin.copy(
            stopMode = SpinStopMode.ReducedMotion,
            stopRequestedAtMonotonicMs = monotonicTimeMs()
        )
        spinSlamStopSignal?.complete(Unit)
    }

    fun toggleAutoSpin() {
        if (isAutoPlayActive()) {
            stopAutoSpin()
            return
        }
        startAutoSpin(DEFAULT_AUTO_SPIN_COUNT)
    }

    fun startAutoSpin(count: Int) {
        if (count !in SUPPORTED_AUTO_SPIN_COUNTS) return
        dismissAutoSpinStopNotice()
        if (
            isSpinStartReserved.value ||
            activeSpin.value != null ||
            isResultPending.value ||
            isSettlementRecoveryPending.value ||
            pendingPresentationId.value != null
        ) return
        val generation = ++autoPlayGeneration
        autoSpinStartJob?.cancel()
        isSpinStartReserved.value = true
        var handedOffToSpin = false
        val startJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val state = playerRepository.playerState.first()
                val startsFreeSpinFeature = state.hasFreeSpinsForSlot(config.id)
                if (generation != autoPlayGeneration) return@launch
                autoPlayState.value = if (startsFreeSpinFeature) {
                    AutoPlayState.FreeSpins(suspendedPaidBatch = null)
                } else {
                    createPaidAutoSpinBatch(count, state)
                }
                if (generation != autoPlayGeneration) return@launch
                isSpinStartReserved.value = false
                handedOffToSpin = true
                spin(autoTriggered = true, expectedAutoPlayGeneration = generation)
            } finally {
                if (!handedOffToSpin && generation == autoPlayGeneration) {
                    isSpinStartReserved.value = false
                }
            }
        }
        autoSpinStartJob = startJob
        startJob.invokeOnCompletion {
            if (autoSpinStartJob === startJob) autoSpinStartJob = null
        }
        startJob.start()
    }

    fun stopAutoSpin() {
        explicitAutoPlayStopRevision += 1
        pauseAutoSpin()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                persistIdempotentUpdate {
                    playerRepository.updateFreeSpinAutoPlay(config.id, enabled = false)
                }
            }
        }
    }

    fun pauseAutoSpin() {
        autoPlayGeneration += 1
        val pendingStartJob = autoSpinStartJob
        autoSpinStartJob = null
        pendingStartJob?.cancel()
        if (pendingStartJob != null && activeSpin.value == null) {
            isSpinStartReserved.value = false
        }
        autoPlayState.value = AutoPlayState.Off
        autoSpinJob?.cancel()
        autoSpinJob = null
        featureResumeJob?.cancel()
        featureResumeJob = null
    }

    fun resumeFreeSpinsFeatureIfNeeded() {
        featureResumeJob?.cancel()
        featureResumeJob = viewModelScope.launch {
            val state = playerRepository.playerState.first { playerState ->
                !playerState.shouldAutoPlayFreeSpinsForSlot(config.id) ||
                    playerState.hasFreeSpinsForSlot(config.id)
            }
            if (!state.shouldAutoPlayFreeSpinsForSlot(config.id)) return@launch

            if (!isFreeSpinsSessionTracked()) {
                savedStateHandle[FREE_SPINS_TOTAL_WIN_KEY] =
                    state.freeSpinFeatureTotalWinForSlot(config.id) ?: 0
                savedStateHandle[FREE_SPINS_SESSION_TRACKED_KEY] = true
            }
            autoPlayState.value = AutoPlayState.FreeSpins(suspendedPaidBatch = null)
            if (
                !isSpinStartReserved.value &&
                activeSpin.value == null &&
                !isResultPending.value &&
                !isSettlementRecoveryPending.value &&
                pendingPresentationId.value == null
            ) {
                scheduleNextAutoSpin(AUTO_SPIN_RESUME_DELAY_MS)
            }
        }
    }

    private fun spin(
        autoTriggered: Boolean,
        reservationRetriesRemaining: Int = RESERVATION_STATE_RETRY_ATTEMPTS,
        expectedAutoPlayGeneration: Long? = null
    ) {
        if (
            isSpinStartReserved.value ||
            activeSpin.value != null ||
            isResultPending.value ||
            isSettlementRecoveryPending.value ||
            pendingPresentationId.value != null
        ) return
        if (autoTriggered && !canContinueAutoSpin(expectedAutoPlayGeneration)) return

        isSpinStartReserved.value = true
        viewModelScope.launch {
            var committedSpin: CommittedSpin? = null
            var stakeReserved = false
            var spinSettled = false
            var keepDialogPending = false
            var wagerModeRetryAutoTriggered: Boolean? = null
            var reservationInvalidated = false
            try {
                val recoveryStatus = retryTransientPersistenceIo {
                    playerRepository.pendingSpinRecoveryStatus()
                }
                if (recoveryStatus != PendingSpinRecoveryStatus.None) {
                    pauseAutoSpin()
                    isSettlementRecoveryBlockedByMath.value =
                        recoveryStatus == PendingSpinRecoveryStatus.UnsupportedMath
                    isSettlementRecoveryPending.value = true
                    return@launch
                }
                val state = playerRepository.reconcileFreeSpinStake(
                    slotId = config.id,
                    supportedBets = config.bets,
                    maxLines = config.paylines
                )
                val freeSpinsBefore = state.freeSpinsForSlot(config.id)
                val isFreeSpin = freeSpinsBefore > 0
                val bet = state.effectiveBet(isFreeSpin)
                val lines = state.effectiveLines(isFreeSpin)
                val totalBet = checkedSlotMultiply(bet, lines, "Total bet")

                isResultPending.value = false
                val spin = withContext(NonCancellable) {
                    if (
                        autoTriggered &&
                        !canContinueAutoSpin(expectedAutoPlayGeneration)
                    ) {
                        reservationInvalidated = true
                        return@withContext null
                    }
                    val reservationAttempt = playerRepository.reserveSpinAttempt(
                        slotId = config.id,
                        isFreeSpin = isFreeSpin,
                        lineBet = bet,
                        lines = lines,
                        totalBet = totalBet,
                        selectedBetSnapshot = state.selectedBet,
                        selectedLinesSnapshot = state.selectedLines,
                        autoPlayFreeSpins = autoTriggered && isAutoPlayActive() && isFreeSpin
                    ) {
                        committedSpin?.let { existing ->
                            SpinReservation(existing.settlement, existing)
                        } ?: run {
                            val result = slotEngine.spin(config, bet, lines, isFreeSpin)
                            val levelXpAwarded = PlayerState.xpForSpin(
                                totalBet = result.totalBet,
                                isFreeSpin = isFreeSpin,
                                winAmount = result.winAmount
                            )
                            val settlement = PendingSpinSettlement(
                                id = UUID.randomUUID().toString(),
                                processSessionId = ProcessSession.id,
                                slotId = config.id,
                                isFreeSpin = isFreeSpin,
                                lineBet = result.bet,
                                lines = result.lines,
                                totalBet = result.totalBet,
                                winAmount = result.winAmount,
                                freeSpinsAwarded = result.freeSpinsAwarded,
                                levelXpAwarded = levelXpAwarded,
                                mathVersion = SlotMathIdentity.VERSION,
                                configFingerprint = SlotMathIdentity.fingerprint(config),
                                stopIndexes = result.stopIndexes,
                                visualResult = result
                            )
                            val committed = CommittedSpin(
                                result = result,
                                isFreeSpin = isFreeSpin,
                                autoTriggered = autoTriggered,
                                autoPlayGenerationAtReservation = autoPlayGeneration,
                                explicitStopRevisionAtReservation = explicitAutoPlayStopRevision,
                                settlement = settlement
                            )
                            registerSettlementOwnership(settlement.id)
                            committedSpin = committed
                            SpinReservation(settlement, committed)
                        }
                    }
                    val reservation = when (reservationAttempt) {
                        is SpinReservationAttempt.Reserved -> reservationAttempt.reservation
                        is SpinReservationAttempt.BlockedByPendingSpin -> {
                            pauseAutoSpin()
                            isSettlementRecoveryBlockedByMath.value =
                                reservationAttempt.recoveryStatus ==
                                    PendingSpinRecoveryStatus.UnsupportedMath
                            isSettlementRecoveryPending.value = true
                            null
                        }
                        SpinReservationAttempt.Rejected -> null
                    }
                    stakeReserved = reservation != null
                    reservation?.value.also { committedSpin = it }
                }
                if (reservationInvalidated) return@launch
                if (spin == null) {
                    val pendingPresentationSlotId = retryTransientPersistenceIo {
                        playerRepository.pendingSpinPresentationSlotId()
                    }
                    if (pendingPresentationSlotId != null) {
                        pauseAutoSpin()
                        if (pendingPresentationSlotId == config.id) {
                            retryPendingPresentationRecovery()
                        } else {
                            eventChannel.send(SlotEvent.PendingPresentation(pendingPresentationSlotId))
                        }
                        return@launch
                    }
                    val recoveryStatus = retryTransientPersistenceIo {
                        playerRepository.pendingSpinRecoveryStatus()
                    }
                    if (recoveryStatus != PendingSpinRecoveryStatus.None) {
                        pauseAutoSpin()
                        isSettlementRecoveryBlockedByMath.value =
                            recoveryStatus == PendingSpinRecoveryStatus.UnsupportedMath
                        isSettlementRecoveryPending.value = true
                        return@launch
                    }
                    val latestState = playerRepository.playerState.first()
                    val latestIsFreeSpin = latestState.hasFreeSpinsForSlot(config.id)
                    val latestBet = latestState.effectiveBet(latestIsFreeSpin)
                    val latestLines = latestState.effectiveLines(latestIsFreeSpin)
                    val wagerModeChanged = latestIsFreeSpin != isFreeSpin
                    val stakeChanged = latestBet != bet || latestLines != lines
                    if (wagerModeChanged || stakeChanged) {
                        val canRetryCurrentIntent = !autoTriggered || isAutoPlayActive()
                        val avoidsUnexpectedPaidWager = latestIsFreeSpin || !wagerModeChanged
                        if (
                            avoidsUnexpectedPaidWager &&
                            reservationRetriesRemaining > 0 &&
                            canRetryCurrentIntent
                        ) {
                            val resumeFeature = latestIsFreeSpin && (
                                autoTriggered || latestState.shouldAutoPlayFreeSpinsForSlot(config.id)
                            )
                            if (resumeFeature) {
                                autoPlayState.value = AutoPlayState.FreeSpins(
                                    suspendedPaidBatch = autoPlayState.value as? AutoPlayState.PaidBatch
                                )
                            }
                            wagerModeRetryAutoTriggered = autoTriggered || resumeFeature
                        } else {
                            pauseAutoSpin()
                        }
                        return@launch
                    }

                    pauseAutoSpin()
                    if (!isFreeSpin) {
                        analyticsTracker.track(
                            AnalyticsEvents.CoinsLow,
                            mapOf(
                                "slot_id" to config.id,
                                "bet" to totalBet,
                                "line_bet" to bet,
                                "lines" to lines,
                                "total_bet" to totalBet,
                                "balance" to latestState.coinsBalance,
                                "auto_spin" to autoTriggered,
                                "free_spins" to latestState.freeSpinsForSlot(config.id)
                            )
                        )
                        eventChannel.send(
                            SlotEvent.LowCoins(
                                bonusAvailable = latestState.isDailyBonusAvailable(),
                                canReduceStake = AffordableStakePolicy.select(
                                    balance = latestState.coinsBalance,
                                    selectedLines = latestState.selectedLines,
                                    supportedBets = config.bets,
                                    maxLines = config.paylines
                                ) != null
                            )
                        )
                    }
                    return@launch
                }

                if (autoTriggered && !isFreeSpin) {
                    val paidBatch = autoPlayState.value as? AutoPlayState.PaidBatch
                    if (paidBatch != null && paidBatch.remainingToStart > 0) {
                        autoPlayState.value = paidBatch.copy(
                            remainingToStart = paidBatch.remainingToStart - 1
                        )
                    }
                }

                val result = spin.result
                analyticsTracker.track(
                    AnalyticsEvents.SpinStart,
                    mapOf(
                        "slot_id" to config.id,
                        "bet" to totalBet,
                        "line_bet" to bet,
                        "lines" to lines,
                        "total_bet" to totalBet,
                        "balance_before" to state.coinsBalance,
                        "auto_spin" to autoTriggered,
                        "free_spin" to isFreeSpin,
                        "free_spins_before" to freeSpinsBefore
                    )
                )
                val slamStopSignal = if (autoTriggered) null else CompletableDeferred<Unit>().also {
                    spinSlamStopSignal = it
                }
                activeSpin.value = ActiveSpinPresentation(
                    id = spin.settlement.id,
                    result = result,
                    startedAtMonotonicMs = monotonicTimeMs(),
                    isFreeSpin = isFreeSpin
                )
                isSpinStartReserved.value = false
                awaitSpinReveal(result, slamStopSignal)
                val settlement = withContext(NonCancellable) {
                    settleCommittedSpinWithRetry(spin).also {
                        spinSettled = true
                    }
                }
                keepDialogPending = publishSettledSpin(spin, settlement)
            } catch (cancellation: CancellationException) {
                if (stakeReserved && !spinSettled) {
                    committedSpin?.let { spin ->
                        withContext(NonCancellable) {
                            try {
                                settleCommittedSpinWithRetry(spin)
                                spinSettled = true
                            } catch (_: IOException) {
                                // The persisted journal remains available for process recovery.
                                releaseSettlementOwnership(spin.settlement.id)
                            }
                        }
                    }
                }
                throw cancellation
            } catch (_: IOException) {
                pauseAutoSpin()
                if (stakeReserved && !spinSettled) {
                    committedSpin?.let { spin ->
                        var recoveredSettlement: SpinSettlement? = null
                        withContext(NonCancellable) {
                            try {
                                recoveredSettlement = settleCommittedSpinWithRetry(spin)
                                spinSettled = true
                            } catch (_: IOException) {
                                queueSettlementRecovery(spin)
                            }
                        }
                        recoveredSettlement?.let { settlement ->
                            keepDialogPending = publishSettledSpin(spin, settlement)
                        }
                    }
                }
            } finally {
                if (!stakeReserved) {
                    committedSpin?.settlement?.id?.let(::releaseSettlementOwnership)
                }
                if (isSpinStartReserved.value && !isSettlementRecoveryPending.value) {
                    isSpinStartReserved.value = false
                }
                if (!keepDialogPending && !isSettlementRecoveryPending.value) {
                    activeSpin.value = null
                    isResultPending.value = false
                }
                wagerModeRetryAutoTriggered?.let { retryAutoTriggered ->
                    spin(
                        autoTriggered = retryAutoTriggered,
                        reservationRetriesRemaining = reservationRetriesRemaining - 1,
                        expectedAutoPlayGeneration = if (retryAutoTriggered) {
                            expectedAutoPlayGeneration ?: autoPlayGeneration
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    private suspend fun settleCommittedSpinWithRetry(spin: CommittedSpin): SpinSettlement {
        var lastFailure: IOException? = null
        repeat(SPIN_SETTLEMENT_RECOVERY_ATTEMPTS) { attempt ->
            try {
                return settleCommittedSpin(spin)
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt < SPIN_SETTLEMENT_RECOVERY_ATTEMPTS - 1) {
                    delay(SPIN_SETTLEMENT_RECOVERY_DELAY_MS)
                }
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun queueSettlementRecovery(spin: CommittedSpin) {
        pendingSettlementRecovery = spin
        settlementRecoveryRetryAttempt = 0
        lastResult.value = spin.result
        lastResultPresentationId.value = null
        activeSpin.value = null
        isResultPending.value = false
        isSettlementRecoveryPending.value = true
        launchSettlementRecovery(spin, FOREGROUND_SETTLEMENT_RECOVERY_DELAY_MS)
    }

    fun retryPendingSettlementRecovery() {
        val spin = pendingSettlementRecovery ?: return
        launchSettlementRecovery(spin, delayMs = 0L)
    }

    private fun launchSettlementRecovery(spin: CommittedSpin, delayMs: Long) {
        if (settlementRecoveryJob?.isActive == true) return
        isSpinStartReserved.value = true
        settlementRecoveryJob = viewModelScope.launch {
            var retryRequired = false
            try {
                if (delayMs > 0L) delay(delayMs)
                if (pendingSettlementRecovery?.settlement?.id != spin.settlement.id) return@launch
                val settlement = withContext(NonCancellable) {
                    settleCommittedSpinWithRetry(spin)
                }
                if (pendingSettlementRecovery?.settlement?.id != spin.settlement.id) return@launch
                pendingSettlementRecovery = null
                settlementRecoveryRetryAttempt = 0
                isSettlementRecoveryPending.value = false
                publishSettledSpin(spin, settlement)
            } catch (_: IOException) {
                retryRequired = true
            } finally {
                if (!isSettlementRecoveryPending.value) {
                    isSpinStartReserved.value = false
                }
                settlementRecoveryJob = null
                if (
                    retryRequired &&
                    pendingSettlementRecovery?.settlement?.id == spin.settlement.id
                ) {
                    val retryDelayMs = (FOREGROUND_SETTLEMENT_RECOVERY_DELAY_MS shl
                        settlementRecoveryRetryAttempt.coerceAtMost(
                            SETTLEMENT_RECOVERY_MAX_BACKOFF_SHIFT
                        )).coerceAtMost(FOREGROUND_SETTLEMENT_RECOVERY_MAX_DELAY_MS)
                    settlementRecoveryRetryAttempt += 1
                    launchSettlementRecovery(spin, retryDelayMs)
                }
            }
        }
    }

    private suspend fun publishSettledSpin(
        spin: CommittedSpin,
        settlement: SpinSettlement
    ): Boolean {
        if (!settlement.outcomeSettled) {
            lastResult.value = null
            lastResultPresentationId.value = null
            activeSpin.value = null
            isResultPending.value = false
            if (spin.autoTriggered && !spin.isFreeSpin) {
                val paidBatch = autoPlayState.value as? AutoPlayState.PaidBatch
                if (paidBatch != null) {
                    autoPlayState.value = paidBatch.copy(
                        remainingToStart = (paidBatch.remainingToStart + 1)
                            .coerceAtMost(paidBatch.total)
                    )
                }
            }
            if (spin.autoTriggered && canResumeAutoPlayAfter(spin)) {
                scheduleNextAutoSpin(AUTO_SPIN_RESUME_DELAY_MS)
            }
            return false
        }

        val result = spin.result
        val presentedResult = result
        val autoSpinStopReason = applyAutoSpinSafeguards(
            spin = spin,
            settlement = settlement
        )
        val reducedMotion = activeSpin.value?.stopMode == SpinStopMode.ReducedMotion
        val completedFreeSpinsTotalWin = recordFreeSpinWin(spin, settlement)
        val resultPresentationDurationMs = SlotWinFeedbackTiming.resultPresentationDurationMs(
            presentedResult,
            reducedMotion
        )
        val shouldShowResultDialog = completedFreeSpinsTotalWin != null ||
            SlotResultPresentationPolicy.shouldShowResultDialog(presentedResult)
        if (!shouldShowResultDialog) {
            val nextSpinDelayMs = inlineResultAutoSpinDelayMs(
                presentedResult,
                reducedMotion
            )
            nextAutoSpinDelayAfterPresentation = spin.settlement.id to nextSpinDelayMs
        }
        lastResult.value = presentedResult
        lastResultPresentationId.value = spin.settlement.id
        pendingPresentationId.value = spin.settlement.id
        pendingFreeSpinsTotalWin.value = completedFreeSpinsTotalWin
        if (shouldShowResultDialog) {
            reconcileDeferredResultDialogPresentation()
        }
        if (
            spin.autoTriggered &&
            spin.isFreeSpin &&
            canResumeAutoPlayAfter(spin) &&
            settlement.freeSpinsAfter > 0 &&
            settlement.updatedState.shouldAutoPlayFreeSpinsForSlot(config.id)
        ) {
            autoPlayState.value = autoPlayState.value.asFreeSpinsState()
        }
        if (spin.autoTriggered && spin.isFreeSpin && settlement.freeSpinsAfter <= 0) {
            finishFreeSpinsFeature()
        }
        analyticsTracker.track(
            AnalyticsEvents.SpinResult,
            mapOf(
                "slot_id" to config.id,
                "bet" to result.totalBet,
                "line_bet" to result.bet,
                "lines" to result.lines,
                "total_bet" to result.totalBet,
                "win_amount" to result.winAmount,
                "settlement_applied" to settlement.applied,
                "balance_after" to settlement.updatedState.coinsBalance,
                "result_type" to result.resultType.name.lowercase(),
                "net_outcome" to result.netOutcome.name.lowercase(),
                "auto_spin" to spin.autoTriggered,
                "free_spin" to spin.isFreeSpin,
                "free_spins_after" to settlement.freeSpinsAfter,
                "free_spins_awarded" to settlement.freeSpinsAwarded,
                "level" to settlement.updatedState.playerLevel,
                "level_xp_awarded" to settlement.levelXpAwarded
            )
        )
        activeSpin.value = null
        if (
            spin.autoTriggered &&
            !spin.isFreeSpin &&
            autoPlayState.value is AutoPlayState.PaidBatch &&
            paidAutoSpinBatchExhausted()
        ) {
            pauseAutoSpin()
        }
        if (!shouldShowResultDialog) {
            if (autoSpinStopReason != null) {
                this@SlotViewModel.autoSpinStopReason.value = autoSpinStopReason
                eventChannel.send(SlotEvent.AutoSpinStopped(autoSpinStopReason))
            }
            isResultPending.value = false
            return false
        }
        if (resultPresentationDurationMs > 0L) delay(resultPresentationDurationMs)
        isResultPending.value = true
        eventChannel.send(
            SlotEvent.ResultReady(
                presentedResult,
                settlement.freeSpinsAwarded,
                spin.settlement.id,
                completedFreeSpinsTotalWin
            )
        )
        if (autoSpinStopReason != null) {
            this@SlotViewModel.autoSpinStopReason.value = autoSpinStopReason
            eventChannel.send(SlotEvent.AutoSpinStopped(autoSpinStopReason))
        }
        return true
    }

    private suspend fun settleCommittedSpin(spin: CommittedSpin): SpinSettlement {
        val result = spin.result
        val receipt: SpinSettlementReceipt = playerRepository.settleSpin(
            spin.settlement,
            presentationConsumerId
        )
        if (
            spin.autoTriggered &&
            spin.explicitStopRevisionAtReservation != explicitAutoPlayStopRevision
        ) {
            retryTransientPersistenceIo {
                playerRepository.updateFreeSpinAutoPlay(config.id, enabled = false)
            }
        }
        releaseSettlementOwnership(spin.settlement.id)
        val updatedState = if (
            spin.autoTriggered &&
            spin.explicitStopRevisionAtReservation != explicitAutoPlayStopRevision
        ) {
            playerRepository.playerState.first()
        } else {
            receipt.updatedState
        }
        val freeSpinsAwarded = spin.settlement.freeSpinsAwarded
        if (
            receipt.outcomeSettled &&
            freeSpinsAwarded > 0 &&
            !spin.isFreeSpin &&
            canResumeAutoPlayAfter(spin)
        ) {
            startFreeSpinsFeature()
        }
        return SpinSettlement(
            updatedState = updatedState,
            applied = receipt.applied,
            outcomeSettled = receipt.outcomeSettled,
            freeSpinsAfter = updatedState.freeSpinsForSlot(config.id),
            freeSpinsAwarded = freeSpinsAwarded,
            levelXpAwarded = spin.settlement.levelXpAwarded
        )
    }

    private fun startFreeSpinsFeature() {
        autoSpinJob?.cancel()
        autoSpinJob = null
        savedStateHandle[FREE_SPINS_TOTAL_WIN_KEY] = 0
        savedStateHandle[FREE_SPINS_SESSION_TRACKED_KEY] = true
        autoPlayState.value = autoPlayState.value.asFreeSpinsState()
    }

    private fun finishFreeSpinsFeature() {
        val suspendedPaidBatch = (autoPlayState.value as? AutoPlayState.FreeSpins)
            ?.suspendedPaidBatch
            ?.takeIf { batch -> batch.remainingToStart > 0 }
        if (suspendedPaidBatch == null) {
            pauseAutoSpin()
        } else {
            autoPlayState.value = suspendedPaidBatch
        }
    }

    fun onResultDialogDismissed(presentationId: String = "") {
        val presentedPresentationId = deferredResultDialogPresentedId
        if (presentationId.isNotBlank()) {
            val currentPresentationId = pendingPresentationId.value
            if (currentPresentationId != null && currentPresentationId != presentationId) return
            if (presentedPresentationId != null && presentedPresentationId != presentationId) return
        }
        val dismissedPresentationId = presentationId.takeIf(String::isNotBlank)
            ?: presentedPresentationId
        val completedFreeSpinsSummary = pendingFreeSpinsTotalWin.value != null
        deferredResultDialogPresentedId = null
        isResultPending.value = false
        if (completedFreeSpinsSummary) {
            pendingFreeSpinsTotalWin.value = null
            savedStateHandle.remove<Int>(FREE_SPINS_TOTAL_WIN_KEY)
            savedStateHandle.remove<Boolean>(FREE_SPINS_SESSION_TRACKED_KEY)
        }
        if (
            dismissedPresentationId != null &&
            (
                completedFreeSpinsSummary ||
                    lastResult.value?.let(SlotResultPresentationPolicy::shouldShowResultDialog) == true
                )
        ) {
            acknowledgeSpinPresentation(dismissedPresentationId)
            if (
                pendingPresentationId.value == dismissedPresentationId ||
                retryablePresentationAcknowledgementId == dismissedPresentationId ||
                presentationAcknowledgementInFlight == dismissedPresentationId
            ) {
                return
            }
        }
        scheduleNextAutoSpin()
    }

    private fun recordFreeSpinWin(spin: CommittedSpin, settlement: SpinSettlement): Int? {
        if (!spin.isFreeSpin || !settlement.outcomeSettled) return null
        val updatedTotal = settlement.updatedState.freeSpinFeatureTotalWinForSlot(config.id)
            ?: currentFreeSpinsTotalWin().toLong()
                .plus(spin.result.winAmount.coerceAtLeast(0).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        savedStateHandle[FREE_SPINS_TOTAL_WIN_KEY] = updatedTotal
        savedStateHandle[FREE_SPINS_SESSION_TRACKED_KEY] = true
        return updatedTotal.takeIf { settlement.freeSpinsAfter <= 0 }
    }

    private fun currentFreeSpinsTotalWin(): Int {
        return savedStateHandle.get<Int>(FREE_SPINS_TOTAL_WIN_KEY)?.coerceAtLeast(0) ?: 0
    }

    private fun isFreeSpinsSessionTracked(): Boolean {
        return savedStateHandle.get<Boolean>(FREE_SPINS_SESSION_TRACKED_KEY) == true
    }

    fun onSpinPresentationRendered(id: String) {
        if (
            pendingFreeSpinsTotalWin.value != null ||
            lastResult.value?.let(SlotResultPresentationPolicy::shouldShowResultDialog) == true
        ) return
        acknowledgeSpinPresentation(id)
    }

    fun onResultDialogPresented(id: String) {
        if (id.isBlank()) return
        val currentPresentationId = pendingPresentationId.value
        if (currentPresentationId != null && currentPresentationId != id) return
        deferredResultDialogPresentedId = id
        autoSpinJob?.cancel()
        autoSpinJob = null
        isResultPending.value = true
        if (currentPresentationId == null) return
        if (
            pendingFreeSpinsTotalWin.value == null &&
            lastResult.value?.let(SlotResultPresentationPolicy::shouldShowResultDialog) != true
        ) return
        acknowledgeSpinPresentation(id)
    }

    private fun reconcileDeferredResultDialogPresentation() {
        val presentationId = deferredResultDialogPresentedId ?: return
        if (pendingPresentationId.value != presentationId) return
        onResultDialogPresented(presentationId)
    }

    private fun acknowledgeSpinPresentation(
        id: String
    ) {
        if (id.isBlank()) return
        if (
            pendingPresentationId.value != id &&
            retryablePresentationAcknowledgementId != id
        ) return
        if (presentationAcknowledgementInFlight == id) return
        presentationAcknowledgementRetryJob?.cancel()
        presentationAcknowledgementRetryJob = null
        if (retryablePresentationAcknowledgementId != id) {
            presentationAcknowledgementRetryAttempt = 0
        }
        retryablePresentationAcknowledgementId = id
        presentationAcknowledgementInFlight = id
        viewModelScope.launch {
            var acknowledged = false
            try {
                retryTransientPersistenceIo {
                    playerRepository.acknowledgeSpinPresentation(id)
                }
                acknowledged = true
            } catch (_: IOException) {
                schedulePresentationAcknowledgementRetry(id)
            } finally {
                if (presentationAcknowledgementInFlight == id) {
                    presentationAcknowledgementInFlight = null
                }
                if (acknowledged) {
                    retryablePresentationAcknowledgementId = null
                    presentationAcknowledgementRetryAttempt = 0
                    presentationAcknowledgementRetryJob?.cancel()
                    presentationAcknowledgementRetryJob = null
                    val nextSpinDelayMs = nextAutoSpinDelayAfterPresentation
                        ?.takeIf { (presentationId, _) -> presentationId == id }
                        ?.second
                        ?: AUTO_SPIN_RESUME_DELAY_MS
                    releasePendingPresentationLock(id)
                    if (isAutoPlayActive() && activeSpin.value == null && !isResultPending.value) {
                        scheduleNextAutoSpin(nextSpinDelayMs)
                    }
                }
            }
        }
    }

    private fun schedulePresentationAcknowledgementRetry(id: String) {
        if (retryablePresentationAcknowledgementId != id) return
        if (presentationAcknowledgementRetryJob?.isActive == true) return
        val retryDelayMs = (PRESENTATION_ACKNOWLEDGEMENT_RETRY_DELAY_MS shl
            presentationAcknowledgementRetryAttempt.coerceAtMost(
                PRESENTATION_ACKNOWLEDGEMENT_MAX_BACKOFF_SHIFT
            )).coerceAtMost(PRESENTATION_ACKNOWLEDGEMENT_MAX_RETRY_DELAY_MS)
        presentationAcknowledgementRetryAttempt += 1
        presentationAcknowledgementRetryJob = viewModelScope.launch {
            delay(retryDelayMs)
            if (retryablePresentationAcknowledgementId != id) return@launch
            presentationAcknowledgementRetryJob = null
            acknowledgeSpinPresentation(id)
        }
    }

    private fun releasePendingPresentationLock(id: String) {
        if (nextAutoSpinDelayAfterPresentation?.first == id) {
            nextAutoSpinDelayAfterPresentation = null
        }
        if (pendingPresentationId.value == id) {
            pendingPresentationId.value = null
        }
    }

    private suspend fun awaitSpinReveal(
        result: SpinResult,
        slamStopSignal: CompletableDeferred<Unit>?
    ) {
        val revealDurationMs = SlotSpinTimeline.revealDurationMs(config, result)
        val revealSignal = slamStopSignal ?: CompletableDeferred<Unit>().also { spinSlamStopSignal = it }
        val spinStartedAtMs = activeSpin.value?.startedAtMonotonicMs ?: monotonicTimeMs()
        try {
            val stopRequested = withTimeoutOrNull(revealDurationMs) {
                revealSignal.await()
                true
            } ?: false
            if (!stopRequested) return

            val activePresentation = activeSpin.value ?: return
            val stopMode = activePresentation.stopMode
            if (stopMode == SpinStopMode.None) return
            val minimumRevealMs = when (stopMode) {
                SpinStopMode.ReducedMotion -> SlotSpinTimeline.REDUCED_MOTION_MIN_REVEAL_MS
                SpinStopMode.Slam -> SlotSpinTimeline.SLAM_STOP_MIN_REVEAL_MS
                SpinStopMode.None -> return
            }
            val elapsedMs = (monotonicTimeMs() - spinStartedAtMs).coerceAtLeast(0L)
            val stopRequestedElapsedMs = activePresentation.stopRequestedAtMonotonicMs
                ?.let { requestedAt -> (requestedAt - spinStartedAtMs).coerceAtLeast(0L) }
                ?: elapsedMs
            val settleDeadlineMs = when (stopMode) {
                SpinStopMode.ReducedMotion -> minOf(
                    revealDurationMs,
                    maxOf(minimumRevealMs, stopRequestedElapsedMs, elapsedMs) +
                        SlotSpinTimeline.REDUCED_MOTION_SETTLE_MS
                )
                SpinStopMode.Slam -> SlotSpinTimeline.slamStopDeadlineMs(
                    config = config,
                    result = result,
                    stopRequestedElapsedMs = stopRequestedElapsedMs,
                    observedElapsedMs = elapsedMs
                )
                SpinStopMode.None -> return
            }
            val remainingMs = settleDeadlineMs - elapsedMs
            if (remainingMs > 0L) delay(remainingMs)
        } finally {
            if (spinSlamStopSignal === revealSignal) {
                spinSlamStopSignal = null
            }
        }
    }

    private fun scheduleNextAutoSpin(delayMs: Long = AUTO_SPIN_NEXT_DELAY_MS) {
        if (
            !canScheduleAutoSpin() ||
            isSettlementRecoveryPending.value ||
            pendingPresentationId.value != null
        ) return
        val expectedAutoPlayGeneration = autoPlayGeneration
        autoSpinJob?.cancel()
        autoSpinJob = viewModelScope.launch {
            delay(delayMs)
            if (
                expectedAutoPlayGeneration == autoPlayGeneration &&
                canScheduleAutoSpin() &&
                activeSpin.value == null &&
                !isResultPending.value &&
                !isSettlementRecoveryPending.value &&
                pendingPresentationId.value == null
            ) {
                spin(
                    autoTriggered = true,
                    expectedAutoPlayGeneration = expectedAutoPlayGeneration
                )
            }
        }
    }

    fun onPaytableOpen() {
        analyticsTracker.track(AnalyticsEvents.PaytableOpen, mapOf("slot_id" to config.id))
    }

    private fun updateBetByOffset(offset: Int) {
        if (!canChangeStake()) return
        viewModelScope.launch {
            runSerializedStakeUpdate { state ->
                val currentBet = state.selectedBet.coerceToSupportedBet()
                val currentIndex = config.bets.indexOf(currentBet).coerceAtLeast(0)
                val nextIndex = (currentIndex + offset).coerceIn(0, config.bets.lastIndex)
                playerRepository.updateSelectedBet(config.bets[nextIndex])
            }
        }
    }

    private fun updateLinesByOffset(offset: Int) {
        if (!canChangeStake()) return
        viewModelScope.launch {
            runSerializedStakeUpdate { state ->
                val currentLines = state.selectedLines.coerceToSupportedLines()
                val nextLines = (currentLines + offset).coerceIn(PlayerState.MIN_LINES, config.paylines)
                playerRepository.updateSelectedLines(nextLines)
            }
        }
    }

    private suspend fun runSerializedStakeUpdate(update: suspend (PlayerState) -> Unit) {
        stakeUpdateMutex.lock()
        try {
            if (!canChangeStake()) return
            val state = playerRepository.playerState.first()
            if (state.hasFreeSpinsForSlot(config.id)) return
            persistIdempotentUpdate { update(state) }
        } finally {
            stakeUpdateMutex.unlock()
        }
    }

    private fun launchIdempotentPersistence(update: suspend () -> Unit) {
        viewModelScope.launch {
            persistIdempotentUpdate(update)
        }
    }

    private suspend fun persistIdempotentUpdate(update: suspend () -> Unit) {
        try {
            retryTransientPersistenceIo(operation = update)
        } catch (_: IOException) {
            // The current persisted value remains valid and gameplay stays available.
        }
    }

    private fun isAutoPlayActive(): Boolean = autoPlayState.value !is AutoPlayState.Off

    private fun canContinueAutoSpin(expectedGeneration: Long?): Boolean {
        return expectedGeneration == autoPlayGeneration && isAutoPlayActive()
    }

    private fun canResumeAutoPlayAfter(spin: CommittedSpin): Boolean {
        return !spin.autoTriggered ||
            (
                spin.autoPlayGenerationAtReservation == autoPlayGeneration &&
                    spin.explicitStopRevisionAtReservation == explicitAutoPlayStopRevision
                )
    }

    private fun inlineResultAutoSpinDelayMs(
        result: SpinResult,
        reducedMotion: Boolean = false
    ): Long {
        return SlotWinFeedbackTiming.inlineAutoSpinDelayMs(
            result = result,
            reducedMotion = reducedMotion,
            noPayoutDelayMs = AUTO_SPIN_NEXT_DELAY_MS
        )
    }

    private fun createPaidAutoSpinBatch(count: Int, state: PlayerState): AutoPlayState.PaidBatch {
        val lineBet = state.selectedBet.coerceToSupportedBet()
        val lines = state.selectedLines.coerceToSupportedLines()
        val totalBet = checkedSlotMultiply(lineBet, lines, "Autospin total bet")
        return AutoPlayState.PaidBatch(
            total = count,
            remainingToStart = count,
            startingBalance = state.coinsBalance,
            lossLimitCoins = totalBet.toLong() * AUTO_SPIN_LOSS_LIMIT_BETS
        )
    }

    private fun applyAutoSpinSafeguards(
        spin: CommittedSpin,
        settlement: SpinSettlement
    ): AutoSpinStopReason? {
        if (!spin.autoTriggered || spin.isFreeSpin) return null
        val paidBatch = autoPlayState.value.paidBatchOrNull() ?: return null
        val reason = when {
            spin.result.netOutcome == NetOutcome.Bonus -> AutoSpinStopReason.Bonus
            SlotResultPresentationPolicy.isBigWin(spin.result) -> AutoSpinStopReason.BigWin
            paidBatch.lossFrom(settlement.updatedState.coinsBalance) >= paidBatch.lossLimitCoins ->
                AutoSpinStopReason.LossLimit
            else -> return null
        }
        if (reason == AutoSpinStopReason.Bonus && settlement.freeSpinsAfter > 0) {
            autoPlayState.value = AutoPlayState.FreeSpins(suspendedPaidBatch = null)
        } else {
            pauseAutoSpin()
        }
        return reason
    }

    private fun paidAutoSpinBatchExhausted(): Boolean {
        return (autoPlayState.value as? AutoPlayState.PaidBatch)
            ?.remainingToStart == 0
    }

    private fun canScheduleAutoSpin(): Boolean {
        return when (val current = autoPlayState.value) {
            AutoPlayState.Off -> false
            is AutoPlayState.FreeSpins -> true
            is AutoPlayState.PaidBatch -> current.remainingToStart > 0
        }
    }

    private fun canChangeStake(): Boolean {
        return !isSpinStartReserved.value &&
            activeSpin.value == null &&
            !isResultPending.value &&
            !isAutoPlayActive() &&
            !isSettlementRecoveryPending.value &&
            pendingPresentationId.value == null
    }

    private fun Int.coerceToSupportedBet(): Int {
        return if (this in config.bets) this else config.bets.first()
    }

    private fun Int.coerceToSupportedLines(): Int {
        return coerceIn(PlayerState.MIN_LINES, config.paylines)
    }

    private fun PlayerState.effectiveBet(isFreeSpin: Boolean): Int {
        val rawBet = if (isFreeSpin) {
            freeSpinBetForSlot(config.id).takeIf { it in config.bets } ?: selectedBet
        } else {
            selectedBet
        }
        return rawBet.coerceToSupportedBet()
    }

    private fun PlayerState.effectiveLines(isFreeSpin: Boolean): Int {
        val rawLines = if (isFreeSpin) {
            freeSpinLinesForSlot(config.id).takeIf { it in PlayerState.MIN_LINES..config.paylines } ?: selectedLines
        } else {
            selectedLines
        }
        return rawLines.coerceToSupportedLines()
    }

    private companion object {
        const val AUTO_SPIN_NEXT_DELAY_MS = 650L
        const val AUTO_SPIN_RESUME_DELAY_MS = 350L
        const val SPIN_SETTLEMENT_RECOVERY_ATTEMPTS = 2
        const val SPIN_SETTLEMENT_RECOVERY_DELAY_MS = 120L
        const val FOREGROUND_SETTLEMENT_RECOVERY_DELAY_MS = 500L
        const val FOREGROUND_SETTLEMENT_RECOVERY_MAX_DELAY_MS = 30_000L
        const val SETTLEMENT_RECOVERY_MAX_BACKOFF_SHIFT = 6
        const val PRESENTATION_ACKNOWLEDGEMENT_RETRY_DELAY_MS = 350L
        const val PRESENTATION_ACKNOWLEDGEMENT_MAX_RETRY_DELAY_MS = 30_000L
        const val PRESENTATION_ACKNOWLEDGEMENT_MAX_BACKOFF_SHIFT = 7
        const val PRESENTATION_RECOVERY_RETRY_DELAY_MS = 350L
        const val PRESENTATION_RECOVERY_MAX_RETRY_DELAY_MS = 30_000L
        const val PRESENTATION_RECOVERY_MAX_BACKOFF_SHIFT = 7
        const val RESERVATION_STATE_RETRY_ATTEMPTS = 1
        const val DEFAULT_AUTO_SPIN_COUNT = 10
        const val AUTO_SPIN_LOSS_LIMIT_BETS = 10L
        const val FREE_SPINS_TOTAL_WIN_KEY = "free_spins_total_win"
        const val FREE_SPINS_SESSION_TRACKED_KEY = "free_spins_session_tracked"
        val SUPPORTED_AUTO_SPIN_COUNTS = setOf(10, 25, 50)
    }

    private data class CommittedSpin(
        val result: SpinResult,
        val isFreeSpin: Boolean,
        val autoTriggered: Boolean,
        val autoPlayGenerationAtReservation: Long,
        val explicitStopRevisionAtReservation: Long,
        val settlement: PendingSpinSettlement
    )

    private data class ActiveSpinPresentation(
        val id: String,
        val result: SpinResult,
        val startedAtMonotonicMs: Long,
        val isFreeSpin: Boolean,
        val stopMode: SpinStopMode = SpinStopMode.None,
        val stopRequestedAtMonotonicMs: Long? = null
    )

    private enum class SpinStopMode {
        None,
        Slam,
        ReducedMotion
    }

    private sealed interface AutoPlayState {
        data object Off : AutoPlayState
        data class PaidBatch(
            val total: Int,
            val remainingToStart: Int,
            val startingBalance: Long,
            val lossLimitCoins: Long
        ) : AutoPlayState {
            fun lossFrom(currentBalance: Long): Long {
                return (startingBalance - currentBalance).coerceAtLeast(0L)
            }
        }

        data class FreeSpins(
            val suspendedPaidBatch: PaidBatch?
        ) : AutoPlayState
    }

    private fun AutoPlayState.paidBatchOrNull(): AutoPlayState.PaidBatch? = when (this) {
        AutoPlayState.Off -> null
        is AutoPlayState.PaidBatch -> this
        is AutoPlayState.FreeSpins -> suspendedPaidBatch
    }

    private fun AutoPlayState.asFreeSpinsState(): AutoPlayState.FreeSpins = when (this) {
        AutoPlayState.Off -> AutoPlayState.FreeSpins(suspendedPaidBatch = null)
        is AutoPlayState.PaidBatch -> AutoPlayState.FreeSpins(suspendedPaidBatch = this)
        is AutoPlayState.FreeSpins -> this
    }

    private data class SpinSettlement(
        val updatedState: PlayerState,
        val applied: Boolean,
        val outcomeSettled: Boolean,
        val freeSpinsAfter: Int,
        val freeSpinsAwarded: Int,
        val levelXpAwarded: Int
    )

    private data class ResultPresentationState(
        val isResultPending: Boolean,
        val freeSpinsTotalWin: Int?,
        val autoSpinStopReason: AutoSpinStopReason?
    )

    private data class SettlementRecoveryUiState(
        val isPending: Boolean,
        val isBlockedByMath: Boolean
    )

    override fun onCleared() {
        pauseAutoSpin()
        ProcessSession.releasePresentationConsumer(presentationConsumerId)
        val settlementIds = synchronized(settlementOwnershipLock) {
            cleared = true
            ownedSettlementIds.toList().also { ownedSettlementIds.clear() }
        }
        settlementIds.forEach(ProcessSession::releaseSpinSettlement)
    }

    private fun registerSettlementOwnership(settlementId: String) {
        synchronized(settlementOwnershipLock) {
            if (cleared) return
            ProcessSession.registerSpinSettlement(settlementId)
            ownedSettlementIds += settlementId
        }
    }

    private fun releaseSettlementOwnership(settlementId: String) {
        synchronized(settlementOwnershipLock) {
            ProcessSession.releaseSpinSettlement(settlementId)
            ownedSettlementIds -= settlementId
        }
    }

    class Factory(
        private val slotId: String,
        private val playerRepository: PlayerStore,
        private val slotRepository: SlotCatalog,
        private val slotEngine: SlotEngine,
        private val analyticsTracker: AnalyticsTracker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return SlotViewModel(
                slotId,
                playerRepository,
                slotRepository,
                slotEngine,
                analyticsTracker,
                monotonicTimeMs = { System.nanoTime() / 1_000_000L },
                savedStateHandle = extras.createSavedStateHandle()
            ) as T
        }
    }

}
