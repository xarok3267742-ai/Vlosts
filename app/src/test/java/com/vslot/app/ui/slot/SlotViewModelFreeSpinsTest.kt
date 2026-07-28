package com.vslot.app.ui.slot

import com.vslot.app.ProcessSession
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.analytics.AnalyticsTracker
import com.vslot.app.analytics.FakeAnalyticsTracker
import com.vslot.app.data.FreeSpinBonus
import com.vslot.app.data.PendingSpinSettlement
import com.vslot.app.data.PlayerState
import com.vslot.app.data.PlayerStore
import com.vslot.app.data.SpinReservation
import com.vslot.app.data.SpinSettlementReceipt
import com.vslot.app.data.matchesReservation
import com.vslot.app.data.mergeAwardedFreeSpinBonus
import com.vslot.app.data.withFreeSpinBonusesSnapshot
import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotCatalog
import com.vslot.app.game.SlotConfig
import com.vslot.app.game.SlotEngine
import com.vslot.app.game.SlotMathIdentity
import com.vslot.app.game.SlotRng
import com.vslot.app.game.SlotTheme
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class SlotViewModelFreeSpinsTest {
    @Test
    fun `missing or unknown restored slot id falls back without crashing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = SlotViewModel(
                slotId = "missing-slot",
                playerRepository = FakePlayerStore(PlayerState(disclaimerAccepted = true)),
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )

            advanceUntilIdle()

            assertEquals(CONFIG.id, viewModel.uiState.value.config.id)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `free spin can run below coin bet, skips debit, credits win and retriggers free spins`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 100,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 1,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(500L, playerStore.current.coinsBalance)
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsBalance)
            assertEquals(
                listOf("last_slot:test", "consume_free_spin", "credit:400", "award_free_spins:5", "award_xp:10"),
                playerStore.operations
            )
            assertEquals(10, playerStore.current.levelXp)
            assertTrue(analyticsTracker.events.none { it.first == AnalyticsEvents.CoinsLow })

            val spinStartParams = analyticsTracker.events.last { it.first == AnalyticsEvents.SpinStart }.second
            val spinResultParams = analyticsTracker.events.last { it.first == AnalyticsEvents.SpinResult }.second
            assertEquals(true, spinStartParams["free_spin"])
            assertEquals(1, spinStartParams["free_spins_before"])
            assertEquals(true, spinResultParams["free_spin"])
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, spinResultParams["free_spins_after"])
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, spinResultParams["free_spins_awarded"])
            assertEquals(10, spinResultParams["level_xp_awarded"])
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `persisted upgrade stake is reconciled before rng and remains idempotent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val upgradeConfig = CONFIG.copy(
                bets = listOf(25),
                payouts = emptyMap(),
                scatterBonus = emptyMap()
            )
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 100,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 2,
                    freeSpinBet = 20,
                    freeSpinLines = 10,
                    freeSpinSlotId = CONFIG.id,
                    disclaimerAccepted = true
                )
            )
            val rng = CountingRng()
            val viewModel = SlotViewModel(
                slotId = upgradeConfig.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(upgradeConfig),
                slotEngine = SlotEngine(rng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(5, rng.calls)
            assertEquals(1, playerStore.current.freeSpinsForSlot(CONFIG.id))
            assertEquals(25, playerStore.current.freeSpinBetForSlot(CONFIG.id))
            assertEquals(8, playerStore.current.freeSpinLinesForSlot(CONFIG.id))
            assertEquals(
                1,
                playerStore.operations.count { it == "reconcile_free_spin:20:10:25:8" }
            )
            assertTrue(
                playerStore.operations.indexOf("reconcile_free_spin:20:10:25:8") <
                    playerStore.operations.indexOf("consume_free_spin")
            )

            renderCurrentSpinPresentation(viewModel, playerStore)
            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10, rng.calls)
            assertEquals(0, playerStore.current.freeSpinsForSlot(CONFIG.id))
            assertEquals(
                1,
                playerStore.operations.count { it.startsWith("reconcile_free_spin:") }
            )
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `failed upgrade reconciliation never reaches rng`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 100,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 1,
                    freeSpinBet = 20,
                    freeSpinLines = 10,
                    freeSpinSlotId = CONFIG.id,
                    disclaimerAccepted = true
                ),
                reconciliationIoFailuresRemaining = 1
            )
            val rng = CountingRng()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(rng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(0, rng.calls)
            assertEquals(1, playerStore.current.freeSpinsForSlot(CONFIG.id))
            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })

            viewModel.spin()
            advanceUntilIdle()
            assertEquals(5, rng.calls)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `paid bonus spin debits total bet and awards free spins`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 0,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10_150L, playerStore.current.coinsBalance)
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsBalance)
            assertEquals(25, playerStore.current.freeSpinBet)
            assertEquals(10, playerStore.current.freeSpinLines)
            assertEquals(CONFIG.id, playerStore.current.freeSpinSlotId)
            assertEquals(listOf("last_slot:test", "debit:250", "credit:400", "award_free_spins:5", "award_xp:14"), playerStore.operations)
            assertEquals(14, playerStore.current.levelXp)

            val spinResultParams = analyticsTracker.events.last { it.first == AnalyticsEvents.SpinResult }.second
            assertEquals(false, spinResultParams["free_spin"])
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, spinResultParams["free_spins_after"])
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, spinResultParams["free_spins_awarded"])
            assertEquals(14, spinResultParams["level_xp_awarded"])
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `rapid repeated spin taps start only one spin`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            viewModel.spin()
            advanceUntilIdle()

            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertEquals(1, playerStore.operations.count { it == "credit:400" })
            assertEquals(1, playerStore.operations.count { it == "award_free_spins:5" })
            assertEquals(1, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinStart })
            assertEquals(1, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinResult })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `rapid repeated bet taps preserve every accepted step`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            val config = CONFIG.copy(bets = listOf(10, 25, 50, 100))
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                selectedBetWriteGate = firstWriteStarted to releaseFirstWrite
            )
            val viewModel = SlotViewModel(
                slotId = config.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(config),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.selectNextBet()
            runCurrent()
            firstWriteStarted.await()
            viewModel.selectNextBet()
            runCurrent()
            releaseFirstWrite.complete(Unit)
            advanceUntilIdle()

            assertEquals(100, playerStore.current.selectedBet)
            assertEquals(listOf("bet:50", "bet:100"), playerStore.operations.filter { it.startsWith("bet:") })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `ordinary win stays on reels and does not block the next manual spin`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)

            val completedState = viewModel.uiState.first { it.lastResult != null && !it.isSpinning }
            assertEquals(ResultType.Win, completedState.lastResult?.resultType)
            assertFalse(completedState.isResultPending)
            assertFalse(SlotResultPresentationPolicy.shouldShowResultDialog(completedState.lastResult!!))
            assertEquals(1, playerStore.operations.count { it == "debit:250" })

            viewModel.spin()
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)
            assertEquals(2, playerStore.operations.count { it == "debit:250" })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `recovered spin presentation replays exact result without reroll or another wager`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val expectedResult = SlotEngine(OneRng).spin(CONFIG, bet = 25, lines = 10)
            val recoveredSettlement = PendingSpinSettlement(
                id = "recovered-presentation",
                processSessionId = "previous-process",
                slotId = CONFIG.id,
                isFreeSpin = false,
                lineBet = expectedResult.bet,
                lines = expectedResult.lines,
                totalBet = expectedResult.totalBet,
                winAmount = expectedResult.winAmount,
                freeSpinsAwarded = expectedResult.freeSpinsAwarded,
                levelXpAwarded = 1,
                mathVersion = SlotMathIdentity.VERSION,
                configFingerprint = SlotMathIdentity.fingerprint(CONFIG),
                stopIndexes = expectedResult.stopIndexes,
                visualResult = expectedResult
            )
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_150,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                recoveredPresentation = recoveredSettlement
            )
            val rng = CountingRng()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(rng),
                analyticsTracker = FakeAnalyticsTracker()
            )

            advanceUntilIdle()

            val restoredState = viewModel.uiState.first {
                !it.isSpinStartReserved && it.pendingPresentationId == recoveredSettlement.id
            }
            assertEquals(expectedResult, restoredState.lastResult)
            assertEquals(0, rng.calls)
            assertFalse(playerStore.operations.any { it.startsWith("debit:") })
            assertFalse(playerStore.operations.any { it.startsWith("consume_free_spin") })
            assertFalse(playerStore.operations.any { it.startsWith("credit:") })

            if (SlotResultPresentationPolicy.shouldShowResultDialog(expectedResult)) {
                viewModel.onResultDialogPresented(recoveredSettlement.id)
            } else {
                viewModel.onSpinPresentationRendered(recoveredSettlement.id)
            }
            advanceUntilIdle()

            assertTrue(recoveredSettlement.id in playerStore.acknowledgedPresentationIds)
            assertEquals(null, viewModel.uiState.first { it.pendingPresentationId == null }.pendingPresentationId)
            assertEquals(1, playerStore.operations.count { it == "claim_presentation:${recoveredSettlement.id}" })
            assertEquals(1, playerStore.operations.count { it == "ack_presentation:${recoveredSettlement.id}" })
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `modal presentation is acknowledged only after its result dialog is shown`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            val presentationId = checkNotNull(playerStore.latestSettledPresentationId)
            val pendingState = viewModel.uiState.first { it.pendingPresentationId == presentationId }
            assertTrue(
                SlotResultPresentationPolicy.shouldShowResultDialog(
                    checkNotNull(pendingState.lastResult)
                )
            )

            viewModel.onSpinPresentationRendered(presentationId)
            runCurrent()

            assertFalse(presentationId in playerStore.acknowledgedPresentationIds)
            assertEquals(presentationId, viewModel.uiState.first().pendingPresentationId)

            viewModel.onResultDialogPresented(presentationId)
            runCurrent()

            assertTrue(presentationId in playerStore.acknowledgedPresentationIds)
            assertEquals(null, viewModel.uiState.first { it.pendingPresentationId == null }.pendingPresentationId)
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `dismissed dialog stays fail closed until durable acknowledgement resumes autoplay`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var playerStore: FakePlayerStore? = null
        var viewModel: SlotViewModel? = null
        try {
            val dialogConfig = CONFIG.copy(
                payouts = CONFIG.payouts + ("a" to mapOf(3 to 100, 4 to 100, 5 to 100))
            )
            val activePlayerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 2,
                    freeSpinBet = 25,
                    freeSpinLines = 10,
                    freeSpinSlotId = CONFIG.id,
                    freeSpinAutoPlaySlots = setOf(CONFIG.id),
                    disclaimerAccepted = true
                ),
                acknowledgementIoFailuresRemaining = 100
            )
            playerStore = activePlayerStore
            val activeViewModel = SlotViewModel(
                slotId = dialogConfig.id,
                playerRepository = activePlayerStore,
                slotRepository = FakeSlotCatalog(dialogConfig),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            viewModel = activeViewModel
            advanceUntilIdle()

            activeViewModel.resumeFreeSpinsFeatureIfNeeded()
            advanceUntilIdle()

            val presentationId = checkNotNull(activePlayerStore.latestSettledPresentationId)
            activeViewModel.onResultDialogPresented(presentationId)
            runCurrent()
            activeViewModel.onResultDialogDismissed(presentationId)
            runCurrent()

            assertEquals(presentationId, activePlayerStore.latestSettledPresentationId)
            activeViewModel.selectPreviousLines()
            runCurrent()
            assertEquals(10, activePlayerStore.current.selectedLines)

            advanceTimeBy(2_000L)
            runCurrent()

            assertFalse(presentationId in activePlayerStore.acknowledgedPresentationIds)
            assertTrue(
                activePlayerStore.operations.count {
                    it == "ack_presentation_io_failed:$presentationId"
                } > 1
            )
            assertEquals(presentationId, activePlayerStore.latestSettledPresentationId)
            val acknowledgementBlockedState = activeViewModel.uiState.first { state ->
                state.pendingPresentationId == presentationId
            }
            assertTrue(acknowledgementBlockedState.isAutoSpinEnabled)
            assertEquals(1, activePlayerStore.operations.count { it == "consume_free_spin" })

            activePlayerStore.allowPresentationAcknowledgements()
            advanceUntilIdle()

            assertTrue(presentationId in activePlayerStore.acknowledgedPresentationIds)
            assertEquals(2, activePlayerStore.operations.count { it == "consume_free_spin" })
            assertFalse(activePlayerStore.operations.any { it == "claim_presentation:$presentationId" })
            val finalPresentationId = checkNotNull(activePlayerStore.latestSettledPresentationId)
            activeViewModel.onResultDialogPresented(finalPresentationId)
            runCurrent()
            activeViewModel.onResultDialogDismissed(finalPresentationId)
            activeViewModel.pauseAutoSpin()
            advanceUntilIdle()
        } finally {
            viewModel?.pauseAutoSpin()
            playerStore?.allowPresentationAcknowledgements()
            playerStore?.latestSettledPresentationId
                ?.takeIf { pendingId ->
                    pendingId !in playerStore?.acknowledgedPresentationIds.orEmpty()
                }
                ?.let { pendingId ->
                    viewModel?.onResultDialogPresented(pendingId)
                    runCurrent()
                    viewModel?.onResultDialogDismissed(pendingId)
                }
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `inline presentation acknowledgement keeps retrying beyond the former retry limit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                acknowledgementIoFailuresRemaining = 6
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()
            val presentationId = checkNotNull(playerStore.latestSettledPresentationId)
            assertFalse(
                SlotResultPresentationPolicy.shouldShowResultDialog(
                    checkNotNull(playerStore.latestSettledPresentationResult)
                )
            )

            viewModel.onSpinPresentationRendered(presentationId)
            advanceUntilIdle()

            assertEquals(
                6,
                playerStore.operations.count { it == "ack_presentation_io_failed:$presentationId" }
            )
            assertTrue(presentationId in playerStore.acknowledgedPresentationIds)
            assertEquals(null, viewModel.uiState.first().pendingPresentationId)
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `restored dialog callback received before claim is reconciled after durable result loads`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val result = SlotEngine(ZeroRng).spin(CONFIG, bet = 25, lines = 10)
            val settlement = PendingSpinSettlement(
                id = "early-restored-dialog",
                processSessionId = "previous-process",
                slotId = CONFIG.id,
                isFreeSpin = false,
                lineBet = result.bet,
                lines = result.lines,
                totalBet = result.totalBet,
                winAmount = result.winAmount,
                freeSpinsAwarded = result.freeSpinsAwarded,
                levelXpAwarded = 14,
                mathVersion = SlotMathIdentity.VERSION,
                configFingerprint = SlotMathIdentity.fingerprint(CONFIG),
                stopIndexes = result.stopIndexes,
                visualResult = result
            )
            val claimStarted = CompletableDeferred<Unit>()
            val releaseClaim = CompletableDeferred<Unit>()
            val playerStore = FakePlayerStore(
                initialState = PlayerState(disclaimerAccepted = true),
                recoveredPresentation = settlement,
                presentationClaimGate = claimStarted to releaseClaim
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            claimStarted.await()

            viewModel.onResultDialogPresented(settlement.id)
            releaseClaim.complete(Unit)
            advanceUntilIdle()

            assertTrue(settlement.id in playerStore.acknowledgedPresentationIds)
            val restoredState = viewModel.uiState.first {
                it.lastResult == result && it.pendingPresentationId == null
            }
            assertTrue(restoredState.isResultPending)

            viewModel.onResultDialogDismissed(settlement.id)
            assertFalse(viewModel.uiState.first { !it.isResultPending }.isResultPending)
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `balance above int max preserves full deterministic win and original presentation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val expectedResult = SlotEngine(OneRng).spin(CONFIG, bet = 25, lines = 10)
            assertTrue(expectedResult.winAmount > expectedResult.totalBet)
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = Int.MAX_VALUE.toLong(),
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)

            val expectedBalance = Int.MAX_VALUE.toLong() -
                expectedResult.totalBet.toLong() +
                expectedResult.winAmount.toLong()
            val presentedResult = viewModel.uiState.first { it.lastResult != null }.lastResult!!
            assertEquals(expectedBalance, playerStore.current.coinsBalance)
            assertEquals(expectedResult, presentedResult)
            assertEquals(
                expectedResult.winningLines.map { it.amount },
                presentedResult.winningLines.map { it.amount }
            )
            assertEquals(expectedResult.resultType, presentedResult.resultType)
            val resultAnalytics = analyticsTracker.events.last { it.first == AnalyticsEvents.SpinResult }.second
            assertEquals(expectedResult.winAmount, resultAnalytics["win_amount"])
            assertEquals(expectedBalance, resultAnalytics["balance_after"])
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `autospin tap during reserved manual spin is ignored`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            viewModel.toggleAutoSpin()
            advanceUntilIdle()

            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertFalse(viewModel.uiState.value.isAutoSpinEnabled)
            assertFalse(viewModel.uiState.value.isResultPending)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `pausing while autospin start is loading prevents autoplay and wager reservation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val stateReadStarted = CompletableDeferred<Unit>()
            val releaseStateRead = CompletableDeferred<Unit>()
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                playerStateCollectionGate = stateReadStarted to releaseStateRead
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.startAutoSpin(10)
            stateReadStarted.await()
            viewModel.pauseAutoSpin()
            releaseStateRead.complete(Unit)
            advanceUntilIdle()

            assertFalse(playerStore.operations.any { it.startsWith("debit:") })
            assertFalse(playerStore.operations.any { it == "consume_free_spin" })
            assertFalse(viewModel.uiState.value.isAutoSpinEnabled)

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertFalse(viewModel.uiState.value.isAutoSpinEnabled)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `stake changes queued during reserved spin are ignored`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 5,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val config = CONFIG.copy(bets = listOf(10, 25, 50))
            val viewModel = SlotViewModel(
                slotId = config.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(config),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            viewModel.selectNextBet()
            viewModel.selectMaxLines()
            advanceUntilIdle()

            assertEquals(25, playerStore.current.selectedBet)
            assertEquals(5, playerStore.current.selectedLines)
            assertFalse(playerStore.operations.any { it.startsWith("bet:") })
            assertFalse(playerStore.operations.any { it.startsWith("lines:") })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `transient selected bet persistence failure retries without breaking controls`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 5,
                    disclaimerAccepted = true
                ),
                selectedBetIoFailuresRemaining = 1
            )
            val config = CONFIG.copy(bets = listOf(10, 25, 50))
            val viewModel = SlotViewModel(
                slotId = config.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(config),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.selectNextBet()
            advanceUntilIdle()

            assertEquals(50, playerStore.current.selectedBet)
            assertEquals(1, playerStore.operations.count { it == "bet_io_failed:50" })
            assertEquals(1, playerStore.operations.count { it == "bet:50" })
            assertFalse(viewModel.uiState.value.isSpinning)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `free spin uses stored bonus stake instead of selected stake`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 0,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 1,
                    freeSpinBet = 10,
                    freeSpinLines = 5,
                    freeSpinSlotId = CONFIG.id,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val config = CONFIG.copy(bets = listOf(10, 25))
            val viewModel = SlotViewModel(
                slotId = config.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(config),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(0, playerStore.operations.count { it == "debit:250" })
            assertEquals(0, playerStore.operations.count { it == "debit:50" })
            val spinStartParams = analyticsTracker.events.last { it.first == AnalyticsEvents.SpinStart }.second
            val spinResultParams = analyticsTracker.events.last { it.first == AnalyticsEvents.SpinResult }.second
            assertEquals(true, spinStartParams["free_spin"])
            assertEquals(10, spinStartParams["line_bet"])
            assertEquals(5, spinStartParams["lines"])
            assertEquals(50, spinStartParams["total_bet"])
            assertEquals(10, spinResultParams["line_bet"])
            assertEquals(5, spinResultParams["lines"])
            assertEquals(50, spinResultParams["total_bet"])
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `free spins from another slot do not pay current slot spins`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 0,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 3,
                    freeSpinBet = 10,
                    freeSpinLines = 5,
                    freeSpinSlotId = "other_slot",
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val lowCoinsEvent = async { SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            ).let { viewModel ->
                advanceUntilIdle()
                viewModel.spin()
                viewModel.events.first()
            } }

            advanceUntilIdle()

            assertTrue(lowCoinsEvent.await() is SlotEvent.LowCoins)
            assertEquals(3, playerStore.current.freeSpinsBalance)
            assertEquals("other_slot", playerStore.current.freeSpinSlotId)
            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })
            assertEquals(0, playerStore.operations.count { it.startsWith("debit:") })
            val coinsLowParams = analyticsTracker.events.last { it.first == AnalyticsEvents.CoinsLow }.second
            assertEquals(false, coinsLowParams["auto_spin"])
            assertEquals(0, coinsLowParams["free_spins"])
            assertEquals(250, coinsLowParams["total_bet"])
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `bonus award in current slot does not overwrite another slot bonus`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 3,
                    freeSpinBet = 10,
                    freeSpinLines = 5,
                    freeSpinSlotId = "other_slot",
                    freeSpinBonuses = mapOf(
                        "other_slot" to FreeSpinBonus(
                            slotId = "other_slot",
                            count = 3,
                            lineBet = 10,
                            lines = 5
                        )
                    ),
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(8, playerStore.current.freeSpinsBalance)
            assertEquals(3, playerStore.current.freeSpinsForSlot("other_slot"))
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsForSlot(CONFIG.id))
            assertEquals(25, playerStore.current.freeSpinBetForSlot(CONFIG.id))
            assertEquals(10, playerStore.current.freeSpinLinesForSlot(CONFIG.id))
            assertEquals(10, playerStore.current.freeSpinBetForSlot("other_slot"))
            assertEquals(5, playerStore.current.freeSpinLinesForSlot("other_slot"))
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `bonus award in current slot preserves legacy bonus from another slot`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 3,
                    freeSpinBet = 10,
                    freeSpinLines = 5,
                    freeSpinSlotId = "other_slot",
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(8, playerStore.current.freeSpinsBalance)
            assertEquals(setOf("other_slot", CONFIG.id), playerStore.current.freeSpinBonuses.keys)
            assertEquals(3, playerStore.current.freeSpinsForSlot("other_slot"))
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsForSlot(CONFIG.id))
            assertEquals(10, playerStore.current.freeSpinBetForSlot("other_slot"))
            assertEquals(5, playerStore.current.freeSpinLinesForSlot("other_slot"))
            assertEquals(25, playerStore.current.freeSpinBetForSlot(CONFIG.id))
            assertEquals(10, playerStore.current.freeSpinLinesForSlot(CONFIG.id))
            assertEquals(1, playerStore.operations.count { it == "debit:250" })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `final free spin clears stored bonus stake when it does not retrigger`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 1,
                    freeSpinBet = 10,
                    freeSpinLines = 5,
                    freeSpinSlotId = CONFIG.id,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val config = CONFIG.copy(bets = listOf(10, 25))
            val viewModel = SlotViewModel(
                slotId = config.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(config),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(0, playerStore.current.freeSpinsBalance)
            assertEquals(0, playerStore.current.freeSpinBet)
            assertEquals(0, playerStore.current.freeSpinLines)
            assertEquals("", playerStore.current.freeSpinSlotId)
            assertEquals(1, playerStore.operations.count { it == "consume_free_spin" })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `low coins blocks paid spin when no free spins are available`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 100,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 0,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            val lowCoinsEvent = async { viewModel.events.first() }
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertTrue(lowCoinsEvent.await() is SlotEvent.LowCoins)
            assertEquals(100L, playerStore.current.coinsBalance)
            assertEquals(0, playerStore.current.freeSpinsBalance)
            assertEquals(listOf("last_slot:test", "debit_failed:250"), playerStore.operations)
            val coinsLowParams = analyticsTracker.events.last { it.first == AnalyticsEvents.CoinsLow }.second
            assertEquals(250, coinsLowParams["total_bet"])
            assertEquals(100L, coinsLowParams["balance"])
            assertEquals(0, coinsLowParams["free_spins"])
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `pending bonus recovered during paid reservation retries as free spin feature`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 0,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                recoverBonusBeforeReservation = true
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(1, playerStore.operations.count { it == "recover_pending_bonus" })
            assertEquals(1, playerStore.operations.count { it == "consume_free_spin" })
            assertEquals(0, playerStore.operations.count { it.startsWith("debit:") })
            assertEquals(725L, playerStore.current.coinsBalance)
            assertEquals(0, playerStore.current.freeSpinsForSlot(CONFIG.id))
            assertFalse(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertTrue(analyticsTracker.events.none { it.first == AnalyticsEvents.CoinsLow })
            val spinStart = analyticsTracker.events.single { it.first == AnalyticsEvents.SpinStart }.second
            assertEquals(true, spinStart["free_spin"])
            assertEquals(true, spinStart["auto_spin"])
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `paid spin retries once with the latest persisted stake before debit and rng`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                persistStakeBeforeReservation = 10 to 10
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val config = CONFIG.copy(bets = listOf(10, 25))
            val viewModel = SlotViewModel(
                slotId = config.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(config),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(0, playerStore.operations.count { it == "debit:250" })
            assertEquals(1, playerStore.operations.count { it == "debit:100" })
            assertEquals(10_030L, playerStore.current.coinsBalance)
            val spinStart = analyticsTracker.events.single { it.first == AnalyticsEvents.SpinStart }.second
            assertEquals(10, spinStart["line_bet"])
            assertEquals(10, spinStart["lines"])
            assertEquals(100, spinStart["total_bet"])
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `multi line wins finish payline carousel before autospin continues`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val slotEngine = SlotEngine(OneRng)
            val expectedResult = slotEngine.spin(CONFIG, bet = 25, lines = 10)
            val expectedFeedbackDuration = SlotWinFeedbackTiming.resultPresentationDurationMs(expectedResult)
            assertTrue(expectedResult.winningLines.distinctBy { it.paylineIndex }.size > 1)
            assertTrue(expectedFeedbackDuration > SlotWinFeedbackTiming.RESULT_DIALOG_BASE_DELAY_MS)
            assertFalse(SlotResultPresentationPolicy.shouldShowResultDialog(expectedResult))

            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = slotEngine,
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.toggleAutoSpin()
            runCurrent()
            advanceTimeBy(SlotSpinTimeline.revealDurationMs(CONFIG, expectedResult))
            runCurrent()
            renderCurrentSpinPresentation(viewModel, playerStore)
            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertFalse(viewModel.uiState.value.isResultPending)

            advanceTimeBy(expectedFeedbackDuration - 1L)
            runCurrent()
            assertEquals(1, playerStore.operations.count { it == "debit:250" })

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, playerStore.operations.count { it == "debit:250" })

            viewModel.stopAutoSpin()
            advanceUntilIdle()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `manual slam stop keeps sequential reel landing time after minimum reveal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker,
                monotonicTimeMs = { testScheduler.currentTime }
            )
            advanceUntilIdle()

            viewModel.spin()
            runCurrent()
            assertTrue(playerStore.operations.contains("debit:250"))

            advanceTimeBy(600L)
            viewModel.requestSlamStop()
            advanceTimeBy(SLAM_STOP_MIN_REVEAL_MS - 600L)
            runCurrent()
            assertFalse(playerStore.operations.any { it.startsWith("credit:") })

            advanceTimeBy(SlotSpinTimeline.slamStopDurationMs(CONFIG.reels) - 1L)
            runCurrent()
            assertFalse(playerStore.operations.any { it.startsWith("credit:") })

            advanceTimeBy(1L)
            runCurrent()
            assertTrue(playerStore.operations.contains("credit:400"))
            assertTrue(playerStore.operations.contains("award_free_spins:5"))
        } finally {
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `late manual slam stop cannot delay settlement past normal reveal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = FakeAnalyticsTracker(),
                monotonicTimeMs = { testScheduler.currentTime }
            )
            advanceUntilIdle()

            val expectedResult = SlotEngine(ZeroRng).spin(CONFIG, bet = 25, lines = 10)
            val normalRevealMs = SlotSpinTimeline.revealDurationMs(CONFIG, expectedResult)
            viewModel.spin()
            runCurrent()
            advanceTimeBy(normalRevealMs - 100L)
            viewModel.requestSlamStop()

            advanceTimeBy(99L)
            runCurrent()
            assertFalse(playerStore.operations.any { it.startsWith("credit:") })

            advanceTimeBy(1L)
            runCurrent()
            assertTrue(playerStore.operations.contains("credit:400"))
        } finally {
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `reduced motion keeps a readable result hold after the compact reel reveal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = FakeAnalyticsTracker(),
                monotonicTimeMs = { testScheduler.currentTime }
            )
            advanceUntilIdle()
            val resultReady = async { viewModel.events.first { it is SlotEvent.ResultReady } }

            viewModel.spin()
            runCurrent()
            viewModel.requestReducedMotionStop()

            val reducedMotionDuration = SlotSpinTimeline.REDUCED_MOTION_MIN_REVEAL_MS +
                SlotSpinTimeline.REDUCED_MOTION_SETTLE_MS
            advanceTimeBy(reducedMotionDuration - 1L)
            runCurrent()
            assertFalse(resultReady.isCompleted)

            advanceTimeBy(1L)
            runCurrent()
            assertFalse(resultReady.isCompleted)
            assertFalse(viewModel.uiState.first().isSpinning)

            advanceTimeBy(SlotWinFeedbackTiming.REDUCED_MOTION_RESULT_HOLD_MS - 1L)
            runCurrent()
            assertFalse(resultReady.isCompleted)

            advanceTimeBy(1L)
            runCurrent()
            assertTrue(resultReady.isCompleted)
            assertTrue(viewModel.uiState.first { it.isResultPending }.isResultPending)
        } finally {
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `ordinary spin settles on the shared reel timeline without a dead tail`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val expectedResult = SlotEngine(OneRng).spin(CONFIG, bet = 25, lines = 10)
            val revealDurationMs = SlotSpinTimeline.revealDurationMs(CONFIG, expectedResult)
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            val spinningState = async { viewModel.uiState.first { it.isSpinning } }
            viewModel.spin()
            runCurrent()
            spinningState.await()
            advanceTimeBy(revealDurationMs - 1L)
            runCurrent()
            assertTrue(viewModel.uiState.value.isSpinning)

            advanceTimeBy(1L)
            runCurrent()
            assertFalse(viewModel.uiState.value.isSpinning)
            assertEquals(expectedResult.reels, viewModel.uiState.value.lastResult?.reels)
        } finally {
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `committed spin settles if cancellation happens after confirmed stake reservation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 0,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = CancellingAnalyticsTracker(cancelOnEvent = AnalyticsEvents.SpinStart)
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10_150L, playerStore.current.coinsBalance)
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsBalance)
            assertTrue(playerStore.operations.contains("debit:250"))
            assertTrue(playerStore.operations.contains("credit:400"))
            assertTrue(playerStore.operations.contains("award_free_spins:5"))
            assertTrue(playerStore.operations.contains("award_xp:14"))
            assertEquals(1, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinStart })
            assertFalse(viewModel.uiState.value.isSpinning)
            assertFalse(viewModel.uiState.value.isResultPending)
            assertEquals(null, viewModel.uiState.value.pendingResult)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `cancelled stake reservation does not settle an unpaid spin`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 0,
                    disclaimerAccepted = true
                ),
                cancelBeforeDebit = true
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val rng = CountingRng()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(rng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10_000L, playerStore.current.coinsBalance)
            assertEquals(listOf("last_slot:test", "debit_cancelled:250"), playerStore.operations)
            assertEquals(0, rng.calls)
            assertFalse(playerStore.operations.any { it.startsWith("credit:") })
            assertFalse(playerStore.operations.any { it.startsWith("award_free_spins:") })
            assertEquals(0, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinStart })
            assertEquals(0, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinResult })
            assertFalse(viewModel.uiState.value.isSpinning)
            assertFalse(viewModel.uiState.value.isResultPending)
            assertEquals(null, viewModel.uiState.value.pendingResult)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `paid spin aborts if stake reservation fails after initial balance check`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 0,
                    disclaimerAccepted = true
                ),
                failDebit = true
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val rng = CountingRng()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(rng),
                analyticsTracker = analyticsTracker
            )
            val lowCoinsEvent = async { viewModel.events.first() }
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertTrue(lowCoinsEvent.await() is SlotEvent.LowCoins)
            assertEquals(10_000L, playerStore.current.coinsBalance)
            assertEquals(listOf("last_slot:test", "debit_failed:250"), playerStore.operations)
            assertEquals(0, rng.calls)
            assertEquals(0, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinStart })
            assertEquals(0, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinResult })
            assertTrue(analyticsTracker.events.any { it.first == AnalyticsEvents.CoinsLow })
            assertFalse(viewModel.uiState.value.isSpinning)
            assertFalse(viewModel.uiState.value.isResultPending)
            assertEquals(null, viewModel.uiState.value.pendingResult)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `transient reservation io failure releases spin guard and allows a clean retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                reservationIoFailuresRemaining = 1
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10_000L, playerStore.current.coinsBalance)
            assertEquals(1, playerStore.operations.count { it == "reserve_io_failed" })
            assertFalse(playerStore.operations.any { it.startsWith("debit:") })
            assertFalse(viewModel.uiState.value.isSpinning)
            assertFalse(viewModel.uiState.value.isResultPending)

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10_150L, playerStore.current.coinsBalance)
            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertEquals(1, playerStore.operations.count { it == "credit:400" })
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsBalance)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `transient settlement io failure retries committed outcome exactly once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                settlementIoFailuresRemaining = 1
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10_150L, playerStore.current.coinsBalance)
            assertEquals(1, playerStore.operations.count { it == "settle_io_failed" })
            assertEquals(1, playerStore.operations.count { it == "credit:400" })
            assertEquals(1, playerStore.operations.count { it == "award_free_spins:5" })
            assertEquals(1, playerStore.operations.count { it == "award_xp:14" })
            assertEquals(1, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinResult })
            assertFalse(viewModel.uiState.value.isSpinning)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `voided direct settlement refunds wager without presenting rng outcome`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                voidNextSettlement = true
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val rng = CountingRng()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(rng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(5, rng.calls)
            assertEquals(10_000L, playerStore.current.coinsBalance)
            assertEquals(null, playerStore.latestSettledPresentationId)
            assertEquals(null, viewModel.uiState.value.lastResult)
            assertEquals(null, viewModel.uiState.value.pendingPresentationId)
            assertFalse(viewModel.uiState.value.isSpinning)
            assertEquals(0, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinResult })
            assertEquals(1, playerStore.operations.count { it.startsWith("void_settlement:") })

            viewModel.spin()
            advanceUntilIdle()

            assertEquals(10, rng.calls)
            assertTrue(playerStore.latestSettledPresentationId != null)
            assertEquals(1, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinResult })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `exhausted settlement retries recover exact result without lifecycle bounce`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                settlementIoFailuresRemaining = 6
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            val presentationId = checkNotNull(playerStore.latestSettledPresentationId)
            val recoveredState = viewModel.uiState.first {
                !it.isSettlementRecoveryPending && it.pendingPresentationId == presentationId
            }
            assertFalse(recoveredState.isSpinning)
            assertEquals(6, playerStore.operations.count { it == "settle_io_failed" })
            assertEquals(1, playerStore.operations.count { it == "credit:400" })
            assertEquals(1, analyticsTracker.events.count { it.first == AnalyticsEvents.SpinResult })

            viewModel.onResultDialogPresented(presentationId)
            advanceUntilIdle()
            assertTrue(presentationId in playerStore.acknowledgedPresentationIds)
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `autospin continues after an inline ordinary win without dialog dismissal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var viewModel: SlotViewModel? = null
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val activeViewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            viewModel = activeViewModel
            val expectedResult = SlotEngine(OneRng).spin(CONFIG, bet = 25, lines = 10)
            val feedbackDuration = SlotWinFeedbackTiming.resultPresentationDurationMs(expectedResult)
            advanceUntilIdle()

            activeViewModel.toggleAutoSpin()
            runCurrent()
            assertTrue(activeViewModel.uiState.first { it.isAutoSpinEnabled }.isAutoSpinEnabled)
            advanceTimeBy(SlotSpinTimeline.revealDurationMs(CONFIG, expectedResult))
            runCurrent()
            renderCurrentSpinPresentation(activeViewModel, playerStore)
            advanceTimeBy(feedbackDuration)
            runCurrent()

            assertEquals(2, playerStore.operations.count { it == "debit:250" })
            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })
            assertFalse(activeViewModel.uiState.first { it.isAutoSpinEnabled }.isResultPending)
            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })
            assertEquals(true, analyticsTracker.events.last { it.first == AnalyticsEvents.SpinStart }.second["auto_spin"])
            assertEquals(false, analyticsTracker.events.last { it.first == AnalyticsEvents.SpinStart }.second["free_spin"])
        } finally {
            viewModel?.stopAutoSpin()
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `recovered inline free spin win keeps feedback visible before autoplay resumes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val expectedResult = SlotEngine(OneRng).spin(CONFIG, bet = 25, lines = 10)
            assertEquals(ResultType.Win, expectedResult.resultType)
            assertFalse(SlotResultPresentationPolicy.shouldShowResultDialog(expectedResult))
            val recoveredSettlement = PendingSpinSettlement(
                id = "recovered-inline-free-spin",
                processSessionId = "previous-process",
                slotId = CONFIG.id,
                isFreeSpin = true,
                lineBet = expectedResult.bet,
                lines = expectedResult.lines,
                totalBet = expectedResult.totalBet,
                winAmount = expectedResult.winAmount,
                freeSpinsAwarded = expectedResult.freeSpinsAwarded,
                levelXpAwarded = 1,
                mathVersion = SlotMathIdentity.VERSION,
                configFingerprint = SlotMathIdentity.fingerprint(CONFIG),
                stopIndexes = expectedResult.stopIndexes,
                visualResult = expectedResult
            )
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 1,
                    freeSpinBet = 25,
                    freeSpinLines = 10,
                    freeSpinSlotId = CONFIG.id,
                    freeSpinAutoPlaySlots = setOf(CONFIG.id),
                    disclaimerAccepted = true
                ),
                recoveredPresentation = recoveredSettlement
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()
            viewModel.resumeFreeSpinsFeatureIfNeeded()
            runCurrent()

            viewModel.onSpinPresentationRendered(recoveredSettlement.id)
            runCurrent()
            advanceTimeBy(350L)
            runCurrent()

            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })

            val feedbackDuration = SlotWinFeedbackTiming.resultPresentationDurationMs(expectedResult)
            advanceTimeBy(feedbackDuration - 350L)
            runCurrent()

            assertEquals(1, playerStore.operations.count { it == "consume_free_spin" })
            viewModel.stopAutoSpin()
            advanceUntilIdle()
            playerStore.latestSettledPresentationId?.let { presentationId ->
                renderCurrentSpinPresentation(viewModel, playerStore)
                assertTrue(presentationId in playerStore.acknowledgedPresentationIds)
            }
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `paid autospin batches reserve exactly the selected count and never one more`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 1_000_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()
            var expectedDebits = 0

            listOf(10, 25, 50).forEach { selectedCount ->
                viewModel.startAutoSpin(selectedCount)
                repeat(selectedCount) {
                    advanceUntilIdle()
                    expectedDebits += 1
                    assertEquals(
                        expectedDebits,
                        playerStore.operations.count { it == "debit:250" }
                    )
                    renderCurrentSpinPresentation(viewModel, playerStore)
                }
                advanceUntilIdle()
                advanceTimeBy(AUTO_SPIN_NEXT_DELAY_MS * 2)
                runCurrent()

                assertEquals(expectedDebits, playerStore.operations.count { it == "debit:250" })
                assertFalse(
                    viewModel.uiState.first { !it.isSpinning && !it.isAutoSpinEnabled }
                        .isAutoSpinEnabled
                )
            }
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `stopping paid autospin during a spin finishes only the reserved spin`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.startAutoSpin(10)
            runCurrent()
            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertTrue(viewModel.uiState.first { it.isSpinning }.isAutoSpinEnabled)

            viewModel.stopAutoSpin()
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)
            advanceTimeBy(AUTO_SPIN_NEXT_DELAY_MS * 2)
            runCurrent()

            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertFalse(
                viewModel.uiState.first { !it.isSpinning && !it.isAutoSpinEnabled }
                    .isAutoSpinEnabled
            )
            advanceTimeBy(5_001L)
            runCurrent()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `paid autospin pauses for a big win dialog then resumes the selected batch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bigWinConfig = CONFIG.copy(
                payouts = CONFIG.payouts + ("a" to mapOf(3 to 100, 4 to 100, 5 to 100))
            )
            val expectedResult = SlotEngine(OneRng).spin(bigWinConfig, bet = 25, lines = 10)
            assertTrue(SlotResultPresentationPolicy.shouldShowResultDialog(expectedResult))
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = bigWinConfig.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(bigWinConfig),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.startAutoSpin(10)
            advanceUntilIdle()

            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertTrue(viewModel.uiState.first { it.isAutoSpinEnabled }.isAutoSpinEnabled)
            renderCurrentSpinPresentation(viewModel, playerStore)
            viewModel.onResultDialogDismissed()
            advanceUntilIdleRenderingSpinPresentations(
                viewModel,
                playerStore,
                maximumPresentations = 9
            )

            assertEquals(10, playerStore.operations.count { it == "debit:250" })
            advanceTimeBy(AUTO_SPIN_NEXT_DELAY_MS * 2)
            runCurrent()
            assertEquals(10, playerStore.operations.count { it == "debit:250" })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `unsupported paid autospin count is ignored`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.startAutoSpin(11)
            advanceUntilIdle()

            assertEquals(0, playerStore.operations.count { it == "debit:250" })
            assertFalse(viewModel.uiState.value.isAutoSpinEnabled)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `paid bonus pauses free spins then resumes the remaining paid autospins`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(FeatureEntryThenOrdinaryRng()),
                analyticsTracker = analyticsTracker
            )
            val resultEvent = async { viewModel.events.first() }
            advanceUntilIdle()

            viewModel.toggleAutoSpin()
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)

            val event = resultEvent.await()
            assertTrue(event is SlotEvent.ResultReady)
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, (event as SlotEvent.ResultReady).freeSpinsAwarded)
            assertTrue(viewModel.uiState.first { it.isAutoSpinEnabled }.isAutoSpinEnabled)
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsBalance)
            assertTrue(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })

            viewModel.onResultDialogDismissed()
            advanceUntilIdleRenderingSpinPresentations(
                viewModel,
                playerStore,
                maximumPresentations = PlayerState.FREE_SPINS_BONUS_AWARD + 9
            )

            assertEquals(0, playerStore.current.freeSpinsBalance)
            assertEquals(10, playerStore.operations.count { it == "debit:250" })
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.operations.count { it == "consume_free_spin" })
            assertFalse(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertFalse(
                viewModel.uiState.first { !it.isAutoSpinEnabled && !it.isSpinning && !it.isResultPending }
                    .isAutoSpinEnabled
            )
        } finally {
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `manual paid bonus also enters automatic free spin feature`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(FeatureEntryThenOrdinaryRng()),
                analyticsTracker = FakeAnalyticsTracker()
            )
            val resultEvent = async { viewModel.events.first() }
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)

            assertTrue(resultEvent.await() is SlotEvent.ResultReady)
            assertTrue(viewModel.uiState.first { it.isAutoSpinEnabled }.isAutoSpinEnabled)
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsBalance)

            viewModel.onResultDialogDismissed()
            advanceUntilIdleRenderingSpinPresentations(viewModel, playerStore)

            assertEquals(0, playerStore.current.freeSpinsBalance)
            assertEquals(1, playerStore.operations.count { it == "debit:250" })
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.operations.count { it == "consume_free_spin" })
            assertFalse(
                viewModel.uiState.first { !it.isAutoSpinEnabled && !it.isSpinning && !it.isResultPending }
                    .isAutoSpinEnabled
            )
        } finally {
            advanceUntilIdle()
            resetMainDispatcher()
        }
    }

    @Test
    fun `stake controls are locked while free spins are available`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 5,
                    freeSpinsBalance = 2,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val config = CONFIG.copy(bets = listOf(10, 25, 50))
            val viewModel = SlotViewModel(
                slotId = config.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(config),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.selectPreviousBet()
            viewModel.selectNextBet()
            viewModel.selectPreviousLines()
            viewModel.selectNextLines()
            viewModel.selectMaxLines()
            advanceUntilIdle()

            assertEquals(25, playerStore.current.selectedBet)
            assertEquals(5, playerStore.current.selectedLines)
            assertEquals(listOf("last_slot:test"), playerStore.operations)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `autospin started during final free spin stops before paid spins`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 1,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = analyticsTracker
            )
            advanceUntilIdle()

            viewModel.toggleAutoSpin()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAutoSpinEnabled)
            assertFalse(viewModel.uiState.value.isResultPending)
            assertEquals(0, playerStore.current.freeSpinsBalance)
            assertEquals(1, playerStore.operations.count { it == "consume_free_spin" })
            assertEquals(0, playerStore.operations.count { it == "debit:250" })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `autospin during free spins continues after bonus retrigger`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 1,
                    disclaimerAccepted = true
                )
            )
            val analyticsTracker = FakeAnalyticsTracker()
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = analyticsTracker
            )
            val firstResultEvent = async { viewModel.events.first() }
            advanceUntilIdle()

            viewModel.toggleAutoSpin()
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)

            assertTrue(firstResultEvent.await() is SlotEvent.ResultReady)
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsBalance)
            assertEquals(1, playerStore.operations.count { it == "consume_free_spin" })
            assertEquals(1, playerStore.operations.count { it == "award_free_spins:5" })
            assertEquals(0, playerStore.operations.count { it == "debit:250" })
            assertTrue(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))

            val secondResultEvent = async { viewModel.events.first() }
            viewModel.onResultDialogDismissed()
            advanceTimeBy(AUTO_SPIN_NEXT_DELAY_MS)
            advanceUntilIdle()
            renderCurrentSpinPresentation(viewModel, playerStore)

            assertTrue(secondResultEvent.await() is SlotEvent.ResultReady)
            assertEquals(2, playerStore.operations.count { it == "consume_free_spin" })
            assertEquals(2, playerStore.operations.count { it == "award_free_spins:5" })
            assertEquals(0, playerStore.operations.count { it == "debit:250" })
            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD * 2 - 1, playerStore.current.freeSpinsBalance)

            viewModel.stopAutoSpin()
            advanceUntilIdle()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `persisted free spin feature resumes automatically and stops before a paid spin`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 2,
                    freeSpinBet = 25,
                    freeSpinLines = 10,
                    freeSpinSlotId = CONFIG.id,
                    freeSpinAutoPlaySlots = setOf(CONFIG.id),
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.resumeFreeSpinsFeatureIfNeeded()
            advanceUntilIdleRenderingSpinPresentations(viewModel, playerStore)

            assertEquals(2, playerStore.operations.count { it == "consume_free_spin" })
            assertEquals(0, playerStore.operations.count { it == "debit:250" })
            assertFalse(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertFalse(viewModel.uiState.value.isAutoSpinEnabled)
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `lifecycle pause preserves persisted feature while explicit stop clears it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val playerStore = FakePlayerStore(
                PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 2,
                    freeSpinBet = 25,
                    freeSpinLines = 10,
                    freeSpinSlotId = CONFIG.id,
                    freeSpinAutoPlaySlots = setOf(CONFIG.id),
                    disclaimerAccepted = true
                )
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.resumeFreeSpinsFeatureIfNeeded()
            runCurrent()
            viewModel.pauseAutoSpin()
            advanceUntilIdle()

            assertTrue(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })

            viewModel.resumeFreeSpinsFeatureIfNeeded()
            runCurrent()
            viewModel.stopAutoSpin()
            advanceUntilIdle()

            assertFalse(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertEquals(0, playerStore.operations.count { it == "consume_free_spin" })
            assertTrue(playerStore.operations.contains("free_spin_auto:test:false"))
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `explicit stop cannot be undone by an in flight free spin settlement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val settlementStarted = CompletableDeferred<Unit>()
            val releaseSettlement = CompletableDeferred<Unit>()
            val expectedResult = SlotEngine(OneRng).spin(CONFIG, bet = 25, lines = 10)
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    freeSpinsBalance = 2,
                    freeSpinBet = 25,
                    freeSpinLines = 10,
                    freeSpinSlotId = CONFIG.id,
                    freeSpinAutoPlaySlots = setOf(CONFIG.id),
                    disclaimerAccepted = true
                ),
                settlementWriteGate = settlementStarted to releaseSettlement
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()
            viewModel.resumeFreeSpinsFeatureIfNeeded()
            advanceTimeBy(350L)
            runCurrent()
            advanceTimeBy(SlotSpinTimeline.revealDurationMs(CONFIG, expectedResult))
            runCurrent()
            settlementStarted.await()

            viewModel.stopAutoSpin()
            runCurrent()
            assertFalse(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))

            releaseSettlement.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAutoSpinEnabled)
            assertFalse(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertEquals(1, playerStore.operations.count { it == "consume_free_spin" })
            renderCurrentSpinPresentation(viewModel, playerStore)
            advanceTimeBy(5_001L)
            runCurrent()
            assertEquals(1, playerStore.operations.count { it == "consume_free_spin" })
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `explicit stop ends paid batch but preserves awarded free spins feature`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val settlementStarted = CompletableDeferred<Unit>()
            val releaseSettlement = CompletableDeferred<Unit>()
            val expectedResult = SlotEngine(ZeroRng).spin(CONFIG, bet = 25, lines = 10)
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                settlementWriteGate = settlementStarted to releaseSettlement
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(ZeroRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.toggleAutoSpin()
            runCurrent()
            advanceTimeBy(SlotSpinTimeline.revealDurationMs(CONFIG, expectedResult))
            runCurrent()
            settlementStarted.await()
            assertTrue(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))

            viewModel.stopAutoSpin()
            runCurrent()
            assertTrue(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))

            val featureState = async {
                viewModel.uiState.first { state -> state.isFreeSpinAutoPlay }
            }
            runCurrent()
            releaseSettlement.complete(Unit)
            advanceUntilIdle()

            assertEquals(PlayerState.FREE_SPINS_BONUS_AWARD, playerStore.current.freeSpinsForSlot(CONFIG.id))
            assertTrue(playerStore.current.shouldAutoPlayFreeSpinsForSlot(CONFIG.id))
            assertTrue(featureState.await().isAutoSpinEnabled)
            viewModel.pauseAutoSpin()
            advanceUntilIdle()
        } finally {
            resetMainDispatcher()
        }
    }

    @Test
    fun `settlement ownership is registered before reserve returns`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var settlementId: String? = null
        val reservationCreated = CompletableDeferred<Unit>()
        val releaseReservation = CompletableDeferred<Unit>()
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                reservationReturnGate = reservationCreated to releaseReservation
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.spin()
            runCurrent()
            reservationCreated.await()
            settlementId = playerStore.latestReservedSettlementId
            assertTrue(ProcessSession.isSpinSettlementActive(checkNotNull(settlementId)))

            releaseReservation.complete(Unit)
            advanceUntilIdle()
            assertFalse(ProcessSession.isSpinSettlementActive(checkNotNull(settlementId)))
        } finally {
            releaseReservation.complete(Unit)
            settlementId?.let(ProcessSession::releaseSpinSettlement)
            resetMainDispatcher()
        }
    }

    @Test
    fun `settlement ownership is released when reservation commit fails`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var settlementId: String? = null
        try {
            val playerStore = FakePlayerStore(
                initialState = PlayerState(
                    coinsBalance = 10_000,
                    selectedBet = 25,
                    selectedLines = 10,
                    disclaimerAccepted = true
                ),
                reservationCommitIoFailuresRemaining = 1
            )
            val viewModel = SlotViewModel(
                slotId = CONFIG.id,
                playerRepository = playerStore,
                slotRepository = FakeSlotCatalog(CONFIG),
                slotEngine = SlotEngine(OneRng),
                analyticsTracker = FakeAnalyticsTracker()
            )
            advanceUntilIdle()

            viewModel.spin()
            advanceUntilIdle()

            settlementId = playerStore.latestReservedSettlementId
            assertFalse(ProcessSession.isSpinSettlementActive(checkNotNull(settlementId)))
            assertEquals(1, playerStore.operations.count { it == "reserve_commit_io_failed" })
            assertFalse(viewModel.uiState.value.isSpinning)
        } finally {
            settlementId?.let(ProcessSession::releaseSpinSettlement)
            resetMainDispatcher()
        }
    }

    private fun TestScope.renderCurrentSpinPresentation(
        viewModel: SlotViewModel,
        playerStore: FakePlayerStore,
        dismissResultDialog: Boolean = false
    ) {
        val presentationId = checkNotNull(playerStore.latestSettledPresentationId)
        check(presentationId !in playerStore.acknowledgedPresentationIds)
        if (
            playerStore.latestSettledPresentationResult
                ?.let(SlotResultPresentationPolicy::shouldShowResultDialog) == true
        ) {
            viewModel.onResultDialogPresented(presentationId)
        } else {
            viewModel.onSpinPresentationRendered(presentationId)
        }
        runCurrent()
        check(presentationId in playerStore.acknowledgedPresentationIds)
        if (dismissResultDialog) {
            viewModel.onResultDialogDismissed(presentationId)
            runCurrent()
        }
    }

    private fun TestScope.advanceUntilIdleRenderingSpinPresentations(
        viewModel: SlotViewModel,
        playerStore: FakePlayerStore,
        maximumPresentations: Int = PlayerState.FREE_SPINS_BONUS_AWARD + 1
    ) {
        repeat(maximumPresentations) {
            advanceUntilIdle()
            val presentationId = playerStore.latestSettledPresentationId ?: return
            if (presentationId in playerStore.acknowledgedPresentationIds) return
            renderCurrentSpinPresentation(viewModel, playerStore, dismissResultDialog = true)
        }
        val finalPresentationId = playerStore.latestSettledPresentationId
        check(finalPresentationId == null || finalPresentationId in playerStore.acknowledgedPresentationIds) {
            "Autospin did not become idle after $maximumPresentations rendered results."
        }
    }

    private fun TestScope.resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class FakePlayerStore(
        initialState: PlayerState,
        private val cancelBeforeDebit: Boolean = false,
        private val failDebit: Boolean = false,
        private var reservationIoFailuresRemaining: Int = 0,
        private var reservationCommitIoFailuresRemaining: Int = 0,
        private var settlementIoFailuresRemaining: Int = 0,
        private var acknowledgementIoFailuresRemaining: Int = 0,
        private var reconciliationIoFailuresRemaining: Int = 0,
        private var voidNextSettlement: Boolean = false,
        private var selectedBetIoFailuresRemaining: Int = 0,
        private var recoverBonusBeforeReservation: Boolean = false,
        private var persistStakeBeforeReservation: Pair<Int, Int>? = null,
        private var recoveredPresentation: PendingSpinSettlement? = null,
        private val presentationClaimGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
        private val playerStateCollectionGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
        private val selectedBetWriteGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
        private val reservationReturnGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
        private val settlementWriteGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    ) : PlayerStore {
        private val state = MutableStateFlow(initialState)
        val operations = mutableListOf<String>()
        val acknowledgedPresentationIds = mutableSetOf<String>()
        private var latestSettledPresentation: PendingSpinSettlement? = null
        private var latestReservedSettlement: PendingSpinSettlement? = null
        val latestReservedSettlementId: String?
            get() = latestReservedSettlement?.id
        val latestSettledPresentationId: String?
            get() = latestSettledPresentation?.id
        val latestSettledPresentationResult
            get() = latestSettledPresentation?.visualResult
        private var selectedBetWriteWasBlocked = false
        private var settlementWriteWasBlocked = false
        val current: PlayerState get() = state.value

        override val playerState: Flow<PlayerState> = playerStateCollectionGate?.let { (started, release) ->
            flow {
                started.complete(Unit)
                release.await()
                emitAll(state)
            }
        } ?: state

        fun allowPresentationAcknowledgements() {
            acknowledgementIoFailuresRemaining = 0
        }

        override suspend fun updateSelectedBet(bet: Int) {
            if (selectedBetIoFailuresRemaining > 0) {
                selectedBetIoFailuresRemaining -= 1
                operations += "bet_io_failed:$bet"
                throw IOException("test selected bet persistence failure")
            }
            if (!selectedBetWriteWasBlocked) {
                selectedBetWriteGate?.let { (started, release) ->
                    selectedBetWriteWasBlocked = true
                    started.complete(Unit)
                    release.await()
                }
            }
            state.value = state.value.copy(selectedBet = bet)
            operations += "bet:$bet"
        }

        override suspend fun updateSelectedLines(lines: Int) {
            state.value = state.value.copy(selectedLines = lines)
            operations += "lines:$lines"
        }

        override suspend fun updateLastPlayedSlot(slotId: String) {
            state.value = state.value.copy(lastPlayedSlot = slotId)
            operations += "last_slot:$slotId"
        }

        override suspend fun debitSpinBet(totalBet: Int): Boolean {
            if (cancelBeforeDebit) {
                operations += "debit_cancelled:$totalBet"
                throw CancellationException("test cancellation before debit")
            }
            if (failDebit || state.value.coinsBalance < totalBet) {
                operations += "debit_failed:$totalBet"
                return false
            }
            state.value = state.value.copy(
                coinsBalance = (state.value.coinsBalance - totalBet.toLong()).coerceAtLeast(0L)
            )
            operations += "debit:$totalBet"
            return true
        }

        override suspend fun creditSpinWin(winAmount: Int) {
            if (winAmount <= 0) return
            val updatedBalance = saturatedLongCredit(state.value.coinsBalance, winAmount)
            state.value = state.value.copy(coinsBalance = updatedBalance)
            operations += "credit:$winAmount"
        }

        override suspend fun consumeFreeSpin(slotId: String): Boolean {
            val currentState = state.value
            val bonuses = currentState.freeSpinBonuses.toMutableMap()
            val currentBonus = bonuses[slotId]
            if (currentBonus != null) {
                val remainingFreeSpins = (currentBonus.count - 1).coerceAtLeast(0)
                if (remainingFreeSpins > 0) {
                    bonuses[slotId] = currentBonus.copy(count = remainingFreeSpins)
                } else {
                    bonuses.remove(slotId)
                }
                state.value = currentState.withFreeSpinBonusesSnapshot(bonuses)
                operations += "consume_free_spin"
                return true
            }

            if (!currentState.hasFreeSpinsForSlot(slotId)) return false
            val remainingFreeSpins = (currentState.freeSpinsBalance - 1).coerceAtLeast(0)
            state.value = currentState.copy(
                freeSpinsBalance = remainingFreeSpins,
                freeSpinBet = if (remainingFreeSpins > 0) currentState.freeSpinBet else 0,
                freeSpinLines = if (remainingFreeSpins > 0) currentState.freeSpinLines else 0,
                freeSpinSlotId = if (remainingFreeSpins > 0) currentState.freeSpinSlotId else ""
            )
            operations += "consume_free_spin"
            return true
        }

        override suspend fun awardFreeSpins(count: Int, lineBet: Int, lines: Int, slotId: String) {
            if (count <= 0) return
            if (lineBet <= 0 || lines <= 0) return
            val currentState = state.value
            val bonuses = mergeAwardedFreeSpinBonus(
                currentBonuses = currentState.freeSpinBonuses,
                legacySlotId = currentState.freeSpinSlotId,
                legacyCount = currentState.freeSpinsBalance,
                legacyLineBet = currentState.freeSpinBet,
                legacyLines = currentState.freeSpinLines,
                awardSlotId = slotId,
                awardCount = count,
                awardLineBet = lineBet,
                awardLines = lines
            )
            state.value = currentState.withFreeSpinBonusesSnapshot(bonuses)
            operations += "award_free_spins:$count"
        }

        override suspend fun reconcileFreeSpinStake(
            slotId: String,
            supportedBets: List<Int>,
            maxLines: Int
        ): PlayerState {
            if (reconciliationIoFailuresRemaining > 0) {
                reconciliationIoFailuresRemaining -= 1
                operations += "reconcile_free_spin_io_failed"
                throw IOException("test free-spin reconciliation failure")
            }

            val currentState = state.value
            if (!currentState.hasFreeSpinsForSlot(slotId)) return currentState
            val persistedLockedBet = currentState.freeSpinBetForSlot(slotId)
            val persistedLockedLines = currentState.freeSpinLinesForSlot(slotId)
            val lockedBet = persistedLockedBet
                .takeIf { it > 0 }
                ?: currentState.selectedBet
            val lockedLines = persistedLockedLines
                .takeIf { it > 0 }
                ?: currentState.selectedLines
            val reconciled = com.vslot.app.data.reconciledFreeSpinStake(
                lockedLineBet = lockedBet,
                lockedLines = lockedLines,
                supportedBets = supportedBets,
                maxLines = maxLines
            )
            val existing = currentState.freeSpinBonuses[slotId]
            if (
                existing != null &&
                existing.lineBet == reconciled.lineBet &&
                existing.lines == reconciled.lines
            ) {
                return currentState
            }
            if (
                existing == null &&
                persistedLockedBet == reconciled.lineBet &&
                persistedLockedLines == reconciled.lines &&
                currentState.freeSpinSlotId == slotId
            ) {
                return currentState
            }

            val migratedBonus = FreeSpinBonus(
                slotId = slotId,
                count = currentState.freeSpinsForSlot(slotId),
                lineBet = reconciled.lineBet,
                lines = reconciled.lines
            )
            state.value = currentState.withFreeSpinBonusesSnapshot(
                currentState.freeSpinBonuses + (slotId to migratedBonus)
            )
            if (persistedLockedBet > 0 && persistedLockedLines > 0) {
                operations += "reconcile_free_spin:$lockedBet:$lockedLines:${reconciled.lineBet}:${reconciled.lines}"
            }
            return state.value
        }

        override suspend fun updateFreeSpinAutoPlay(slotId: String, enabled: Boolean) {
            val slots = state.value.freeSpinAutoPlaySlots.toMutableSet()
            if (enabled) {
                slots.add(slotId)
            } else {
                slots.remove(slotId)
            }
            state.value = state.value.copy(freeSpinAutoPlaySlots = slots)
            operations += "free_spin_auto:$slotId:$enabled"
        }

        override suspend fun awardLevelXp(amount: Int) {
            if (amount <= 0) return
            state.value = state.value.copy(levelXp = state.value.levelXp + amount)
            operations += "award_xp:$amount"
        }

        override suspend fun updateSoundEnabled(enabled: Boolean) {
            state.value = state.value.copy(soundEnabled = enabled)
            operations += "sound:$enabled"
        }

        override suspend fun updateHapticsEnabled(enabled: Boolean) {
            state.value = state.value.copy(hapticsEnabled = enabled)
            operations += "haptics:$enabled"
        }

        override suspend fun <T> reserveSpin(
            slotId: String,
            isFreeSpin: Boolean,
            lineBet: Int,
            lines: Int,
            totalBet: Int,
            selectedBetSnapshot: Int?,
            selectedLinesSnapshot: Int?,
            autoPlayFreeSpins: Boolean,
            createReservation: () -> SpinReservation<T>
        ): SpinReservation<T>? {
            if (reservationIoFailuresRemaining > 0) {
                reservationIoFailuresRemaining -= 1
                operations += "reserve_io_failed"
                throw IOException("test reservation write failure")
            }
            if (recoverBonusBeforeReservation) {
                recoverBonusBeforeReservation = false
                val recoveredState = state.value.withFreeSpinBonusesSnapshot(
                    mergeAwardedFreeSpinBonus(
                        currentBonuses = state.value.freeSpinBonuses,
                        legacySlotId = state.value.freeSpinSlotId,
                        legacyCount = state.value.freeSpinsBalance,
                        legacyLineBet = state.value.freeSpinBet,
                        legacyLines = state.value.freeSpinLines,
                        awardSlotId = slotId,
                        awardCount = 1,
                        awardLineBet = 25,
                        awardLines = 10
                    )
                )
                state.value = recoveredState.copy(
                    coinsBalance = recoveredState.coinsBalance + 400,
                    freeSpinAutoPlaySlots = recoveredState.freeSpinAutoPlaySlots + slotId
                )
                operations += "recover_pending_bonus"
            }
            persistStakeBeforeReservation?.let { (persistedBet, persistedLines) ->
                persistStakeBeforeReservation = null
                state.value = state.value.copy(
                    selectedBet = persistedBet,
                    selectedLines = persistedLines
                )
                operations += "persist_stake:$persistedBet:$persistedLines"
            }
            val currentState = state.value
            if (currentState.hasFreeSpinsForSlot(slotId) != isFreeSpin) return null
            if (
                isFreeSpin &&
                (
                    currentState.freeSpinBetForSlot(slotId) != lineBet ||
                        currentState.freeSpinLinesForSlot(slotId) != lines
                    )
            ) {
                return null
            }
            if (
                !isFreeSpin &&
                (
                    currentState.selectedBet != selectedBetSnapshot ||
                        currentState.selectedLines != selectedLinesSnapshot
                    )
            ) {
                return null
            }
            val reserved = if (isFreeSpin) {
                consumeFreeSpin(slotId)
            } else {
                debitSpinBet(totalBet)
            }
            if (!reserved) return null

            val reservation = createReservation()
            require(
                reservation.settlement.matchesReservation(
                    slotId,
                    isFreeSpin,
                    lineBet,
                    lines,
                    totalBet
                )
            )
            if (
                autoPlayFreeSpins ||
                (!reservation.settlement.isFreeSpin && reservation.settlement.freeSpinsAwarded > 0)
            ) {
                state.value = state.value.copy(
                    freeSpinAutoPlaySlots = state.value.freeSpinAutoPlaySlots + slotId
                )
            }
            latestReservedSettlement = reservation.settlement
            reservationReturnGate?.let { (started, release) ->
                started.complete(Unit)
                release.await()
            }
            if (reservationCommitIoFailuresRemaining > 0) {
                reservationCommitIoFailuresRemaining -= 1
                state.value = currentState
                operations += "reserve_commit_io_failed"
                throw IOException("test reservation commit failure")
            }
            return reservation
        }

        override suspend fun settleSpin(
            settlement: PendingSpinSettlement,
            presentationConsumerId: String?
        ): SpinSettlementReceipt {
            if (settlementIoFailuresRemaining > 0) {
                settlementIoFailuresRemaining -= 1
                operations += "settle_io_failed"
                throw IOException("test settlement write failure")
            }
            val gateThisWrite = settlementWriteGate != null && !settlementWriteWasBlocked
            if (gateThisWrite) {
                settlementWriteWasBlocked = true
                settlementWriteGate?.let { (started, release) ->
                    started.complete(Unit)
                    release.await()
                }
            }
            if (voidNextSettlement) {
                voidNextSettlement = false
                if (settlement.isFreeSpin) {
                    awardFreeSpins(
                        count = 1,
                        lineBet = settlement.lineBet,
                        lines = settlement.lines,
                        slotId = settlement.slotId
                    )
                } else {
                    creditSpinWin(settlement.totalBet)
                }
                operations += "void_settlement:${settlement.id}"
                return SpinSettlementReceipt(
                    updatedState = state.value,
                    creditedWinAmount = 0,
                    applied = false,
                    outcomeSettled = false
                )
            }
            creditSpinWin(settlement.winAmount)
            awardFreeSpins(
                count = settlement.freeSpinsAwarded,
                lineBet = settlement.lineBet,
                lines = settlement.lines,
                slotId = settlement.slotId
            )
            awardLevelXp(settlement.levelXpAwarded)
            val settledState = state.value
            val autoPlaySlots = settledState.freeSpinAutoPlaySlots.toMutableSet().apply {
                if (settlement.isFreeSpin && !settledState.hasFreeSpinsForSlot(settlement.slotId)) {
                    remove(settlement.slotId)
                }
            }
            state.value = settledState.copy(freeSpinAutoPlaySlots = autoPlaySlots)
            latestSettledPresentation = settlement
            return SpinSettlementReceipt(
                updatedState = state.value,
                creditedWinAmount = settlement.winAmount,
                applied = true
            )
        }

        private fun saturatedLongCredit(balance: Long, amount: Int): Long {
            return if (balance > Long.MAX_VALUE - amount.toLong()) {
                Long.MAX_VALUE
            } else {
                balance + amount.toLong()
            }
        }

        override suspend fun recoverPendingSpinSettlement(currentProcessSessionId: String): Boolean = false

        override suspend fun pendingSpinPresentationSlotId(): String? {
            return recoveredPresentation?.slotId ?: latestSettledPresentation?.slotId
        }

        override suspend fun claimSpinPresentation(
            slotId: String,
            currentProcessSessionId: String
        ): PendingSpinSettlement? {
            presentationClaimGate?.let { (started, release) ->
                started.complete(Unit)
                release.await()
            }
            val claimed = recoveredPresentation?.takeIf { it.slotId == slotId }
                ?: latestSettledPresentation?.takeIf { it.slotId == slotId }
            if (claimed != null) {
                operations += "claim_presentation:${claimed.id}"
            }
            return claimed
        }

        override suspend fun acknowledgeSpinPresentation(id: String) {
            if (acknowledgementIoFailuresRemaining > 0) {
                acknowledgementIoFailuresRemaining -= 1
                operations += "ack_presentation_io_failed:$id"
                throw IOException("test presentation acknowledgement failure")
            }
            acknowledgedPresentationIds += id
            if (recoveredPresentation?.id == id) recoveredPresentation = null
            if (latestSettledPresentation?.id == id) latestSettledPresentation = null
            operations += "ack_presentation:$id"
        }

    }

    private class FakeSlotCatalog(private val config: SlotConfig) : SlotCatalog {
        override fun getSlot(slotId: String): SlotConfig = config
    }

    private class CancellingAnalyticsTracker(
        private val cancelOnEvent: String
    ) : AnalyticsTracker {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun track(eventName: String, params: Map<String, Any?>) {
            events += eventName to params
            if (eventName == cancelOnEvent) {
                throw CancellationException("test cancellation after $eventName")
            }
        }
    }

    private object ZeroRng : SlotRng {
        override fun nextInt(bound: Int): Int = 0
    }

    private class CountingRng : SlotRng {
        var calls = 0
            private set

        override fun nextInt(bound: Int): Int {
            calls += 1
            return 0
        }
    }

    private object OneRng : SlotRng {
        override fun nextInt(bound: Int): Int = 1
    }

    private class FeatureEntryThenOrdinaryRng : SlotRng {
        private var calls = 0

        override fun nextInt(bound: Int): Int {
            val stop = if (calls < CONFIG.reels) 0 else 1
            calls += 1
            return stop % bound
        }
    }

    private companion object {
        const val SLAM_STOP_MIN_REVEAL_MS = 1_180L
        const val AUTO_SPIN_NEXT_DELAY_MS = 650L
        val CONFIG = SlotConfig(
            id = "test",
            name = "Test Slot",
            theme = SlotTheme.Violet,
            reels = 5,
            rows = 3,
            paylines = 10,
            wild = "wild",
            scatter = "scatter",
            symbols = listOf("wild", "scatter", "a", "b"),
            bets = listOf(25),
            payouts = mapOf(
                "wild" to mapOf(3 to 1, 4 to 2, 5 to 3),
                "a" to mapOf(3 to 1, 4 to 2, 5 to 3),
                "b" to mapOf(3 to 1, 4 to 2, 5 to 3)
            ),
            scatterBonus = mapOf(5 to 1),
            reelStrips = List(5) { listOf("scatter", "a", "b", "wild") },
            freeSpinReelStrips = List(5) { listOf("scatter", "a", "b", "wild") }
        )
    }
}
