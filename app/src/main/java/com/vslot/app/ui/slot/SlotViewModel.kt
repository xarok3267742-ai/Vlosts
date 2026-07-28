package com.vslot.app.ui.slot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vslot.app.ProcessSession
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.AnalyticsTracker
import com.vslot.app.data.PendingSpinSettlement
import com.vslot.app.data.PlayerState
import com.vslot.app.data.PlayerStore
import com.vslot.app.data.SpinReservation
import com.vslot.app.data.SpinSettlementReceipt
import com.vslot.app.data.retryTransientPersistenceIo
import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotCatalog
import com.vslot.app.game.SlotConfig
import com.vslot.app.game.SlotEngine
import com.vslot.app.game.SlotMathIdentity
import com.vslot.app.game.SpinResult
import com.vslot.app.game.checkedSlotMultiply
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
    val isFreeSpinAutoPlay: Boolean = false
)

sealed class SlotEvent {
    data class LowCoins(val bonusAvailable: Boolean) : SlotEvent()
    data class ResultReady(
        val result: SpinResult,
        val freeSpinsAwarded: Int,
        val presentationId: String
    ) : SlotEvent()

    data class PendingPresentation(val slotId: String) : SlotEvent()
}

class SlotViewModel(
    slotId: String,
    private val playerRepository: PlayerStore,
    slotRepository: SlotCatalog,
    private val slotEngine: SlotEngine,
    private val analyticsTracker: AnalyticsTracker,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : ViewModel() {
    private val config = slotRepository.getSlot(slotId)
    private val settlementOwnershipLock = Any()
    private val presentationConsumerId = "${ProcessSession.id}:${UUID.randomUUID()}"
    @Volatile
    private var cleared = false
    private val activeSpin = MutableStateFlow<ActiveSpinPresentation?>(null)
    private val lastResult = MutableStateFlow<SpinResult?>(null)
    private val lastResultPresentationId = MutableStateFlow<String?>(null)
    private val isResultPending = MutableStateFlow(false)
    private val autoPlayState = MutableStateFlow<AutoPlayState>(AutoPlayState.Off)
    private var autoSpinStartJob: Job? = null
    private var autoSpinJob: Job? = null
    private var featureResumeJob: Job? = null
    private var autoPlayGeneration = 0L
    private var explicitAutoPlayStopRevision = 0L
    private val isSpinStartReserved = MutableStateFlow(true)
    private val pendingPresentationId = MutableStateFlow<String?>(null)
    private val isSettlementRecoveryPending = MutableStateFlow(false)
    private var pendingSettlementRecovery: CommittedSpin? = null
    private var settlementRecoveryJob: Job? = null
    private var settlementRecoveryRetryAttempt = 0
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

    private val baseUiState = combine(
        playerRepository.playerState,
        activeSpin,
        lastResult,
        lastResultPresentationId,
        isResultPending
    ) { playerState, activePresentation, result, resultPresentationId, resultPending ->
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
            isResultPending = resultPending
        )
    }

    val uiState: StateFlow<SlotUiState> = combine(
        baseUiState,
        autoPlayState,
        isSpinStartReserved,
        pendingPresentationId,
        isSettlementRecoveryPending
    ) { state, currentAutoPlay, spinStartReserved, presentationId, settlementRecoveryPending ->
        state.copy(
            isAutoSpinEnabled = currentAutoPlay !is AutoPlayState.Off,
            autoSpinsRemaining = currentAutoPlay.paidBatchOrNull()?.let { batch ->
                batch.remainingToStart + if (state.isSpinning) 1 else 0
            },
            isFreeSpinAutoPlay = currentAutoPlay is AutoPlayState.FreeSpins,
            isSpinStartReserved = spinStartReserved,
            pendingPresentationId = presentationId,
            isSettlementRecoveryPending = settlementRecoveryPending
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
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            restorePendingSpinPresentation()
        }
    }

    private suspend fun restorePendingSpinPresentation() {
        try {
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
                playerRepository.pendingSpinPresentationSlotId()
                    ?.takeIf { it != config.id }
                    ?.let { eventChannel.send(SlotEvent.PendingPresentation(it)) }
                return
            }

            if (!SlotResultPresentationPolicy.shouldShowResultDialog(result)) {
                nextAutoSpinDelayAfterPresentation = settlement.id to
                    inlineResultAutoSpinDelayMs(result)
            }
            lastResult.value = result
            lastResultPresentationId.value = settlement.id
            pendingPresentationId.value = settlement.id
            if (SlotResultPresentationPolicy.shouldShowResultDialog(result)) {
                isResultPending.value = true
                reconcileDeferredResultDialogPresentation()
                eventChannel.send(
                    SlotEvent.ResultReady(result, settlement.freeSpinsAwarded, settlement.id)
                )
            }
        } catch (_: IOException) {
            // The durable presentation remains claimable after a later lifecycle restart.
        } finally {
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

    fun spin() {
        spin(autoTriggered = false)
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
                val startsFreeSpinFeature = playerRepository.playerState.first()
                    .hasFreeSpinsForSlot(config.id)
                if (generation != autoPlayGeneration) return@launch
                autoPlayState.value = if (startsFreeSpinFeature) {
                    AutoPlayState.FreeSpins(
                        suspendedPaidBatch = AutoPlayState.PaidBatch(total = count, remainingToStart = count)
                    )
                } else {
                    AutoPlayState.PaidBatch(total = count, remainingToStart = count)
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
        val preserveAwardedFeature = activeSpin.value?.let { spin ->
            !spin.isFreeSpin && spin.result.freeSpinsAwarded > 0
        } == true
        explicitAutoPlayStopRevision += 1
        pauseAutoSpin()
        if (preserveAwardedFeature) return
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
                    val reservation = playerRepository.reserveSpin(
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
                            restorePendingSpinPresentation()
                        } else {
                            eventChannel.send(SlotEvent.PendingPresentation(pendingPresentationSlotId))
                        }
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
                        eventChannel.send(SlotEvent.LowCoins(latestState.isDailyBonusAvailable()))
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
        val reducedMotion = activeSpin.value?.stopMode == SpinStopMode.ReducedMotion
        val resultPresentationDurationMs = SlotWinFeedbackTiming.resultPresentationDurationMs(
            presentedResult,
            reducedMotion
        )
        val shouldShowResultDialog = SlotResultPresentationPolicy.shouldShowResultDialog(presentedResult)
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
            isResultPending.value = false
            return false
        }
        if (resultPresentationDurationMs > 0L) delay(resultPresentationDurationMs)
        isResultPending.value = true
        eventChannel.send(
            SlotEvent.ResultReady(
                presentedResult,
                settlement.freeSpinsAwarded,
                spin.settlement.id
            )
        )
        return true
    }

    private suspend fun settleCommittedSpin(spin: CommittedSpin): SpinSettlement {
        val result = spin.result
        val receipt: SpinSettlementReceipt = playerRepository.settleSpin(
            spin.settlement,
            presentationConsumerId
        )
        val awardedFeature = !spin.isFreeSpin && spin.settlement.freeSpinsAwarded > 0
        if (
            spin.autoTriggered &&
            spin.explicitStopRevisionAtReservation != explicitAutoPlayStopRevision &&
            !awardedFeature
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
            (canResumeAutoPlayAfter(spin) || awardedFeature)
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
        deferredResultDialogPresentedId = null
        isResultPending.value = false
        if (
            dismissedPresentationId != null &&
            lastResult.value?.let(SlotResultPresentationPolicy::shouldShowResultDialog) == true
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

    fun onSpinPresentationRendered(id: String) {
        if (lastResult.value?.let(SlotResultPresentationPolicy::shouldShowResultDialog) == true) return
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
        if (lastResult.value?.let(SlotResultPresentationPolicy::shouldShowResultDialog) != true) return
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
        return if (result.resultType == ResultType.Win) {
            SlotWinFeedbackTiming.resultPresentationDurationMs(result, reducedMotion)
        } else {
            AUTO_SPIN_NEXT_DELAY_MS
        }
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
        const val RESERVATION_STATE_RETRY_ATTEMPTS = 1
        const val DEFAULT_AUTO_SPIN_COUNT = 10
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
            val remainingToStart: Int
        ) : AutoPlayState

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
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SlotViewModel(slotId, playerRepository, slotRepository, slotEngine, analyticsTracker) as T
        }
    }
}
