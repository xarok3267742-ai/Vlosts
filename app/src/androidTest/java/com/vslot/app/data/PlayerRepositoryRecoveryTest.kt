package com.vslot.app.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vslot.app.AppGraph
import com.vslot.app.MainActivity
import com.vslot.app.ProcessSession
import com.vslot.app.game.ResultType
import com.vslot.app.game.ReleasedSlotMathV5
import com.vslot.app.game.SlotEngine
import com.vslot.app.game.SlotMathIdentity
import com.vslot.app.game.SpinResult
import com.vslot.app.game.SymbolPosition
import com.vslot.app.game.WinningLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerRepositoryRecoveryTest {
    @Test
    fun paidSpinOutcomeRecoversExactlyOnceFromDataStoreJournal() = runBlocking {
        val repository = AppGraph.playerRepository
        val context = ApplicationProvider.getApplicationContext<Context>()
        val releasedAsset = context.assets.open(ReleasedSlotMathV5.ASSET_PATH).use { it.readBytes() }
        ReleasedSlotMathV5.verifyReleasedAsset(releasedAsset)
        repository.recoverPendingSpinSettlement(ProcessSession.id)
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = settlementForStops(
            id = "instrumented-recovery-spin",
            processSessionId = "previous-process-session",
            isFreeSpin = false,
            stops = BONUS_STOPS
        )

        try {
            val reservation = repository.reserveSpin(
                slotId = SLOT_ID,
                isFreeSpin = false,
                lineBet = 25,
                lines = 10,
                totalBet = 250,
                selectedBetSnapshot = 25,
                selectedLinesSnapshot = 10
            ) {
                SpinReservation(settlement, Unit)
            }

            assertNotNull(reservation)
            val reservedState = repository.playerState.first()
            assertEquals(PlayerState.STARTING_BALANCE - 250, reservedState.coinsBalance)
            assertEquals(0, reservedState.freeSpinsBalance)
            assertEquals(0, reservedState.levelXp)

            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ).use {
                withTimeout(RECOVERY_TIMEOUT_MS) {
                    repository.playerState.first { state ->
                        state.coinsBalance == PlayerState.STARTING_BALANCE - 250 + settlement.winAmount &&
                            state.freeSpinsForSlot(SLOT_ID) == settlement.freeSpinsAwarded &&
                            state.levelXp == settlement.levelXpAwarded
                    }
                }
            }
            val recoveredState = repository.playerState.first()
            assertEquals(
                PlayerState.STARTING_BALANCE - 250 + settlement.winAmount,
                recoveredState.coinsBalance
            )
            assertEquals(settlement.freeSpinsAwarded, recoveredState.freeSpinsForSlot(SLOT_ID))
            assertEquals(25, recoveredState.freeSpinBetForSlot(SLOT_ID))
            assertEquals(10, recoveredState.freeSpinLinesForSlot(SLOT_ID))
            assertEquals(true, recoveredState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
            assertEquals(settlement.levelXpAwarded, recoveredState.levelXp)

            assertFalse(repository.recoverPendingSpinSettlement(ProcessSession.id))
            assertEquals(recoveredState, repository.playerState.first())
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun explicitStopMarkerSurvivesPaidBonusRecovery() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.recoverPendingSpinSettlement(ProcessSession.id)
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = settlementForStops(
            id = "instrumented-stopped-bonus-spin",
            processSessionId = "stopped-bonus-process",
            isFreeSpin = false,
            stops = BONUS_STOPS
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(settlement, Unit)
                }
            )
            assertTrue(repository.playerState.first().shouldAutoPlayFreeSpinsForSlot(SLOT_ID))

            repository.updateFreeSpinAutoPlay(SLOT_ID, enabled = false)
            assertFalse(repository.playerState.first().shouldAutoPlayFreeSpinsForSlot(SLOT_ID))

            assertTrue(repository.recoverPendingSpinSettlement("stopped-bonus-recovery"))
            val recovered = repository.playerState.first()
            assertEquals(settlement.freeSpinsAwarded, recovered.freeSpinsForSlot(SLOT_ID))
            assertFalse(recovered.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun corruptJournalsRefundButUnsupportedMathIsPreservedAndBlocksNewWagers() = runBlocking {
        val repository = AppGraph.playerRepository
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = settlementForStops(
            id = "instrumented-invalid-recovery",
            processSessionId = "previous-invalid-process",
            isFreeSpin = false,
            stops = WIN_STOPS
        )
        val invalidJournals = listOf(
            "not-json",
            base.copy(stopIndexes = NO_WIN_STOPS).serialize(),
            base.copy(winAmount = base.winAmount + 1).serialize(),
            base.copy(slotId = "unknown-slot").serialize(),
            base.copy(configFingerprint = "0".repeat(64)).serialize()
        )
        val unsupportedMathJournals = listOf(
            """{"version":2,"id":"legacy"}""",
            base.copy(mathVersion = SlotMathIdentity.VERSION + 1).serialize()
        )

        try {
            invalidJournals.forEachIndexed { index, invalidJournal ->
                repository.resetForDebug()
                repository.updateSelectedBet(base.lineBet)
                assertNotNull(
                    repository.reserveSpin(
                        slotId = base.slotId,
                        isFreeSpin = false,
                        lineBet = base.lineBet,
                        lines = base.lines,
                        totalBet = base.totalBet,
                        selectedBetSnapshot = base.lineBet,
                        selectedLinesSnapshot = base.lines
                    ) {
                        SpinReservation(base.copy(id = "invalid-recovery-$index"), Unit)
                    }
                )
                repository.replacePendingSpinJournalForDebug(invalidJournal)

                assertFalse(repository.recoverPendingSpinSettlement("invalid-verifier-$index"))
                assertEquals(PlayerState.STARTING_BALANCE, repository.playerState.first().coinsBalance)
                assertNull(
                    PlayerStateCheckpointStore(
                        context.noBackupFilesDir,
                        PlayerStateCheckpointStore.PRIMARY_FILE_NAME
                    ).read()?.rawPendingSpinSettlement
                )
            }

            unsupportedMathJournals.forEachIndexed { index, unsupportedJournal ->
                repository.resetForDebug()
                repository.updateSelectedBet(base.lineBet)
                assertNotNull(
                    repository.reserveSpin(
                        slotId = base.slotId,
                        isFreeSpin = false,
                        lineBet = base.lineBet,
                        lines = base.lines,
                        totalBet = base.totalBet,
                        selectedBetSnapshot = base.lineBet,
                        selectedLinesSnapshot = base.lines
                    ) {
                        SpinReservation(base.copy(id = "unsupported-recovery-$index"), Unit)
                    }
                )
                repository.replacePendingSpinJournalForDebug(unsupportedJournal)
                val reservedBalance = PlayerState.STARTING_BALANCE - base.totalBet

                assertFalse(repository.recoverPendingSpinSettlement("unsupported-math-$index"))
                assertEquals(PendingSpinRecoveryStatus.UnsupportedMath, repository.pendingSpinRecoveryStatus())
                assertEquals(reservedBalance, repository.playerState.first().coinsBalance)
                assertEquals(
                    unsupportedJournal,
                    PlayerStateCheckpointStore(
                        context.noBackupFilesDir,
                        PlayerStateCheckpointStore.PRIMARY_FILE_NAME
                    ).read()?.rawPendingSpinSettlement
                )

                val secondReservation = repository.reserveSpinAttempt<Unit>(
                    slotId = base.slotId,
                    isFreeSpin = false,
                    lineBet = base.lineBet,
                    lines = base.lines,
                    totalBet = base.totalBet,
                    selectedBetSnapshot = base.lineBet,
                    selectedLinesSnapshot = base.lines
                ) {
                    error("Unsupported pending math must block a new outcome.")
                }
                assertTrue(secondReservation is SpinReservationAttempt.BlockedByPendingSpin)
                assertEquals(
                    PendingSpinRecoveryStatus.UnsupportedMath,
                    (secondReservation as SpinReservationAttempt.BlockedByPendingSpin).recoveryStatus
                )
                assertEquals(reservedBalance, repository.playerState.first().coinsBalance)
            }
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun freeSpinReservationRejectsStakeThatDoesNotMatchTheLockedFeatureStake() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.awardFreeSpins(count = 1, lineBet = 25, lines = 10, slotId = SLOT_ID)

        try {
            val reservation = repository.reserveSpin<Unit>(
                slotId = SLOT_ID,
                isFreeSpin = true,
                lineBet = 25,
                lines = 9,
                totalBet = 225
            ) {
                error("A mismatched free-spin stake must be rejected before outcome generation.")
            }

            assertNull(reservation)
            val state = repository.playerState.first()
            assertEquals(1, state.freeSpinsForSlot(SLOT_ID))
            assertEquals(25, state.freeSpinBetForSlot(SLOT_ID))
            assertEquals(10, state.freeSpinLinesForSlot(SLOT_ID))
            assertFreeSpinFeatureTotalSurvivesRepositoryRestartAndIncludesFinalSpin()
        } finally {
            repository.resetForDebug()
        }
    }

    private suspend fun assertFreeSpinFeatureTotalSurvivesRepositoryRestartAndIncludesFinalSpin() {
        val repository = AppGraph.playerRepository
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository.resetForDebug()
        repository.awardFreeSpins(count = 2, lineBet = 25, lines = 10, slotId = SLOT_ID)
        val firstSettlement = settlementForStops(
            id = "instrumented-free-total-first",
            processSessionId = ProcessSession.id,
            isFreeSpin = true,
            stops = WIN_STOPS
        )
        val finalSettlement = firstSettlement.copy(id = "instrumented-free-total-final")

        try {
            assertEquals(0, repository.playerState.first().freeSpinFeatureTotalWinForSlot(SLOT_ID))
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250
                ) { SpinReservation(firstSettlement, Unit) }
            )
            repository.settleSpin(firstSettlement)
            repository.acknowledgeSpinPresentation(firstSettlement.id)
            assertEquals(
                firstSettlement.winAmount,
                repository.playerState.first().freeSpinFeatureTotalWinForSlot(SLOT_ID)
            )

            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250
                ) { SpinReservation(finalSettlement, Unit) }
            )
            repository.settleSpin(finalSettlement)

            val restartedState = PlayerRepository(context).playerState.first()
            assertEquals(0, restartedState.freeSpinsForSlot(SLOT_ID))
            assertEquals(
                firstSettlement.winAmount + finalSettlement.winAmount,
                restartedState.freeSpinFeatureTotalWinForSlot(SLOT_ID)
            )
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun persistedUpgradeFreeSpinStakeReconcilesAcrossRepositoryRestartExactlyOnce() = runBlocking {
        val repository = AppGraph.playerRepository
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository.resetForDebug()
        repository.awardFreeSpins(count = 3, lineBet = 20, lines = 10, slotId = SLOT_ID)

        try {
            val restartedRepository = PlayerRepository(context)
            val config = checkNotNull(AppGraph.slotRepository.getSlotExact(SLOT_ID))
            val migrated = restartedRepository.reconcileFreeSpinStake(
                slotId = SLOT_ID,
                supportedBets = config.bets,
                maxLines = config.paylines
            )
            val checkpointStore = PlayerStateCheckpointStore(
                context.noBackupFilesDir,
                PlayerStateCheckpointStore.PRIMARY_FILE_NAME
            )
            val migratedGeneration = checkNotNull(checkpointStore.read()).generation
            val repeated = restartedRepository.reconcileFreeSpinStake(
                slotId = SLOT_ID,
                supportedBets = config.bets.reversed(),
                maxLines = config.paylines
            )
            val repeatedGeneration = checkNotNull(checkpointStore.read()).generation

            assertEquals(3, migrated.freeSpinsForSlot(SLOT_ID))
            assertEquals(25, migrated.freeSpinBetForSlot(SLOT_ID))
            assertEquals(8, migrated.freeSpinLinesForSlot(SLOT_ID))
            assertEquals(200, migrated.freeSpinBetForSlot(SLOT_ID) * migrated.freeSpinLinesForSlot(SLOT_ID))
            assertEquals(migrated, repeated)
            assertEquals(migratedGeneration, repeatedGeneration)
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun directSettleMalformedPaidJournalRefundsAndDoesNotCreatePresentation() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = settlementForStops(
            id = "instrumented-direct-malformed-paid",
            processSessionId = ProcessSession.id,
            isFreeSpin = false,
            stops = WIN_STOPS
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) { SpinReservation(settlement, Unit) }
            )
            assertEquals(PlayerState.STARTING_BALANCE - 250, repository.playerState.first().coinsBalance)
            repository.replacePendingSpinJournalForDebug("not-json")

            val receipt = repository.settleSpin(settlement)

            assertFalse(receipt.applied)
            assertFalse(receipt.outcomeSettled)
            assertEquals(0, receipt.creditedWinAmount)
            assertEquals(PlayerState.STARTING_BALANCE, receipt.updatedState.coinsBalance)
            assertNull(repository.claimSpinPresentation(SLOT_ID, ProcessSession.id))
            val repeated = repository.settleSpin(settlement)
            assertFalse(repeated.applied)
            assertFalse(repeated.outcomeSettled)
            assertEquals(PlayerState.STARTING_BALANCE, repeated.updatedState.coinsBalance)

            val replacement = settlement.copy(id = "instrumented-direct-malformed-paid-retry")
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) { SpinReservation(replacement, Unit) }
            )
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun directSettleVerifierIncompatibleFreeJournalRestoresSpinExactlyOnce() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.awardFreeSpins(count = 1, lineBet = 25, lines = 10, slotId = SLOT_ID)
        val settlement = settlementForStops(
            id = "instrumented-direct-incompatible-free",
            processSessionId = ProcessSession.id,
            isFreeSpin = true,
            stops = WIN_STOPS
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    autoPlayFreeSpins = true
                ) { SpinReservation(settlement, Unit) }
            )
            assertEquals(0, repository.playerState.first().freeSpinsForSlot(SLOT_ID))
            repository.replacePendingSpinJournalForDebug(
                settlement.copy(configFingerprint = "0".repeat(64)).serialize()
            )

            val receipt = repository.settleSpin(settlement)

            assertFalse(receipt.applied)
            assertFalse(receipt.outcomeSettled)
            assertEquals(1, receipt.updatedState.freeSpinsForSlot(SLOT_ID))
            assertEquals(25, receipt.updatedState.freeSpinBetForSlot(SLOT_ID))
            assertEquals(10, receipt.updatedState.freeSpinLinesForSlot(SLOT_ID))
            assertTrue(receipt.updatedState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
            assertNull(repository.claimSpinPresentation(SLOT_ID, ProcessSession.id))
            val repeated = repository.settleSpin(settlement)
            assertEquals(1, repeated.updatedState.freeSpinsForSlot(SLOT_ID))

            val replacement = settlement.copy(id = "instrumented-direct-incompatible-free-retry")
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    autoPlayFreeSpins = true
                ) { SpinReservation(replacement, Unit) }
            )
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun activityRecreationDoesNotSettleLiveSpinFromCurrentProcess() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = settlementForStops(
            id = "instrumented-live-spin",
            processSessionId = ProcessSession.id,
            isFreeSpin = false,
            stops = BONUS_STOPS
        )

        try {
            val reservation = repository.reserveSpin(
                slotId = SLOT_ID,
                isFreeSpin = false,
                lineBet = 25,
                lines = 10,
                totalBet = 250,
                selectedBetSnapshot = 25,
                selectedLinesSnapshot = 10
            ) {
                SpinReservation(settlement, Unit)
            }
            assertNotNull(reservation)
            ProcessSession.registerSpinSettlement(settlement.id)

            val context = ApplicationProvider.getApplicationContext<Context>()
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ).use {
                delay(LIVE_SPIN_GUARD_WAIT_MS)
                val liveState = repository.playerState.first()
                assertEquals(PlayerState.STARTING_BALANCE - 250, liveState.coinsBalance)
                assertEquals(0, liveState.freeSpinsBalance)
                assertEquals(0, liveState.levelXp)
            }

            val settledState = repository.settleSpin(settlement).updatedState
            assertEquals(
                PlayerState.STARTING_BALANCE - 250 + settlement.winAmount,
                settledState.coinsBalance
            )
            assertEquals(settlement.freeSpinsAwarded, settledState.freeSpinsForSlot(SLOT_ID))
            assertEquals(true, settledState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
            assertEquals(settlement.levelXpAwarded, settledState.levelXp)
        } finally {
            ProcessSession.releaseSpinSettlement(settlement.id)
            repository.resetForDebug()
        }
    }

    @Test
    fun scopedPresentationConsumerCannotClaimLiveSpinFromItsOwnProcess() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = settlementForStops(
            id = "instrumented-live-spin-scoped-consumer",
            processSessionId = ProcessSession.id,
            isFreeSpin = false,
            stops = BONUS_STOPS
        )
        val scopedConsumer = "${ProcessSession.id}:second-view-model"

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(settlement, Unit)
                }
            )
            ProcessSession.registerSpinSettlement(settlement.id)

            assertNull(repository.claimSpinPresentation(SLOT_ID, scopedConsumer))
            val liveState = repository.playerState.first()
            assertEquals(PlayerState.STARTING_BALANCE - 250, liveState.coinsBalance)
            assertEquals(0, liveState.freeSpinsBalance)
            assertEquals(0, liveState.levelXp)

            ProcessSession.releaseSpinSettlement(settlement.id)
            assertEquals(settlement, repository.claimSpinPresentation(SLOT_ID, scopedConsumer))
        } finally {
            ProcessSession.releaseSpinSettlement(settlement.id)
            repository.resetForDebug()
        }
    }

    @Test
    fun activeSettlementOwnerBlocksASecondReservationWithoutOverwritingJournal() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val firstSettlement = settlementForStops(
            id = "instrumented-active-owner-first",
            processSessionId = ProcessSession.id,
            isFreeSpin = false,
            stops = WIN_STOPS
        )
        val secondSettlement = firstSettlement.copy(id = "instrumented-active-owner-second")

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) { SpinReservation(firstSettlement, Unit) }
            )
            ProcessSession.registerSpinSettlement(firstSettlement.id)
            var secondOutcomeCreated = false

            assertNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    secondOutcomeCreated = true
                    SpinReservation(secondSettlement, Unit)
                }
            )
            assertFalse(secondOutcomeCreated)
            assertEquals(
                PlayerState.STARTING_BALANCE - firstSettlement.totalBet,
                repository.playerState.first().coinsBalance
            )

            val settled = repository.settleSpin(firstSettlement)
            assertTrue(settled.applied)
            assertEquals(
                PlayerState.STARTING_BALANCE - firstSettlement.totalBet + firstSettlement.winAmount,
                settled.updatedState.coinsBalance
            )
        } finally {
            ProcessSession.releaseSpinSettlement(firstSettlement.id)
            repository.resetForDebug()
        }
    }

    @Test
    fun verifierRejectedFreeSpinRestoresFeatureSpinWithoutCreditingCoins() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.awardFreeSpins(count = 1, lineBet = 25, lines = 10, slotId = SLOT_ID)
        val settlement = settlementForStops(
            id = "instrumented-free-refund",
            processSessionId = "previous-free-refund-process",
            isFreeSpin = true,
            stops = NO_WIN_STOPS
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250
                ) { SpinReservation(settlement, Unit) }
            )
            assertEquals(0, repository.playerState.first().freeSpinsForSlot(SLOT_ID))
            repository.replacePendingSpinJournalForDebug(
                settlement.copy(slotId = "unknown-slot").serialize()
            )

            assertFalse(repository.recoverPendingSpinSettlement("free-refund-recovery"))
            val restored = repository.playerState.first()
            assertEquals(PlayerState.STARTING_BALANCE, restored.coinsBalance)
            assertEquals(1, restored.freeSpinsForSlot(SLOT_ID))
            assertEquals(25, restored.freeSpinBetForSlot(SLOT_ID))
            assertEquals(10, restored.freeSpinLinesForSlot(SLOT_ID))
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun freeSpinAutoplayIntentPersistsWithReservationAndClearsAfterFinalSettlement() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.awardFreeSpins(count = 2, lineBet = 25, lines = 10, slotId = SLOT_ID)
        val recoveredSettlement = settlementForStops(
            id = "instrumented-free-spin-recovery",
            processSessionId = "previous-free-spin-process",
            isFreeSpin = true,
            stops = NO_WIN_STOPS
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    autoPlayFreeSpins = true
                ) {
                    SpinReservation(recoveredSettlement, Unit)
                }
            )
            val reservedState = repository.playerState.first()
            assertEquals(1, reservedState.freeSpinsForSlot(SLOT_ID))
            assertTrue(reservedState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))

            assertTrue(repository.recoverPendingSpinSettlement(ProcessSession.id))
            val recoveredState = repository.playerState.first()
            assertEquals(1, recoveredState.freeSpinsForSlot(SLOT_ID))
            assertTrue(recoveredState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
            assertEquals(
                recoveredSettlement,
                repository.claimSpinPresentation(SLOT_ID, ProcessSession.id)
            )
            repository.acknowledgeSpinPresentation(recoveredSettlement.id)

            val finalSettlement = recoveredSettlement.copy(
                id = "instrumented-final-free-spin",
                processSessionId = ProcessSession.id
            )
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    autoPlayFreeSpins = true
                ) {
                    SpinReservation(finalSettlement, Unit)
                }
            )
            val finalReservedState = repository.playerState.first()
            assertEquals(0, finalReservedState.freeSpinsForSlot(SLOT_ID))
            assertTrue(finalReservedState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))

            val settledState = repository.settleSpin(finalSettlement).updatedState
            assertEquals(0, settledState.freeSpinsForSlot(SLOT_ID))
            assertFalse(settledState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun lateSettlementDoesNotOverwriteAnewerPendingSpinJournal() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val firstSettlement = paidSettlement(
            id = "instrumented-overlap-first",
            winAmount = 400,
            levelXpAwarded = 14,
            visualResult = visualResult(winAmount = 400, freeSpinsAwarded = 0)
        )
        val secondSettlement = paidSettlement(
            id = "instrumented-overlap-second",
            winAmount = 100,
            levelXpAwarded = 9
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(firstSettlement, Unit)
                }
            )
            assertNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(secondSettlement, Unit)
                }
            )
            assertEquals(SLOT_ID, repository.pendingSpinPresentationSlotId())
            assertEquals(
                firstSettlement,
                repository.claimSpinPresentation(SLOT_ID, ProcessSession.id)
            )
            repository.acknowledgeSpinPresentation(firstSettlement.id)
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(secondSettlement, Unit)
                }
            )

            val stateWithSecondJournal = repository.playerState.first()
            assertEquals(
                PlayerState.STARTING_BALANCE - firstSettlement.totalBet +
                    firstSettlement.winAmount - secondSettlement.totalBet,
                stateWithSecondJournal.coinsBalance
            )
            assertEquals(firstSettlement.levelXpAwarded, stateWithSecondJournal.levelXp)

            val stateAfterLateFirstSettlement = repository.settleSpin(firstSettlement).updatedState
            assertEquals(stateWithSecondJournal, stateAfterLateFirstSettlement)

            val finalState = repository.settleSpin(secondSettlement).updatedState
            assertEquals(
                stateWithSecondJournal.coinsBalance + secondSettlement.winAmount,
                finalState.coinsBalance
            )
            assertEquals(
                firstSettlement.levelXpAwarded + secondSettlement.levelXpAwarded,
                finalState.levelXp
            )
            assertFalse(repository.recoverPendingSpinSettlement("overlap-verifier"))
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun pendingPresentationBlocksAnotherSlotReservationUntilAcknowledged() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val firstSettlement = paidSettlement(
            id = "instrumented-presentation-owner",
            winAmount = 100,
            levelXpAwarded = 9,
            visualResult = visualResult(winAmount = 100, freeSpinsAwarded = 0)
        )
        val otherSlotSettlement = paidSettlement(
            id = "instrumented-presentation-contender",
            winAmount = 0,
            levelXpAwarded = 4,
            slotId = OTHER_SLOT_ID
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(firstSettlement, Unit)
                }
            )
            repository.settleSpin(firstSettlement)
            val balanceWithPendingPresentation = repository.playerState.first().coinsBalance
            var contenderCreated = false

            assertNull(
                repository.reserveSpin(
                    slotId = OTHER_SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    contenderCreated = true
                    SpinReservation(otherSlotSettlement, Unit)
                }
            )

            assertFalse(contenderCreated)
            assertEquals(balanceWithPendingPresentation, repository.playerState.first().coinsBalance)
            assertEquals(SLOT_ID, repository.pendingSpinPresentationSlotId())
            assertEquals(
                firstSettlement,
                repository.claimSpinPresentation(SLOT_ID, ProcessSession.id)
            )

            repository.acknowledgeSpinPresentation(firstSettlement.id)

            assertNotNull(
                repository.reserveSpin(
                    slotId = OTHER_SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    contenderCreated = true
                    SpinReservation(otherSlotSettlement, Unit)
                }
            )
            assertTrue(contenderCreated)
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun recoveringBonusRejectsStalePaidModeBeforeRngAndAllowsFreeReservation() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val bonusSettlement = paidSettlement(
            id = "instrumented-mode-change-bonus",
            winAmount = 400,
            levelXpAwarded = 14,
            freeSpinsAwarded = 2
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(bonusSettlement, Unit)
                }
            )

            var stalePaidRngCreated = false
            val stalePaidReservation = repository.reserveSpin(
                slotId = SLOT_ID,
                isFreeSpin = false,
                lineBet = 25,
                lines = 10,
                totalBet = 250,
                selectedBetSnapshot = 25,
                selectedLinesSnapshot = 10
            ) {
                stalePaidRngCreated = true
                SpinReservation(
                    paidSettlement(
                        id = "instrumented-mode-change-stale-paid",
                        winAmount = 0,
                        levelXpAwarded = 8
                    ),
                    Unit
                )
            }
            assertEquals(null, stalePaidReservation)
            assertFalse(stalePaidRngCreated)

            val recoveredState = repository.playerState.first()
            assertEquals(
                PlayerState.STARTING_BALANCE - bonusSettlement.totalBet +
                    bonusSettlement.winAmount,
                recoveredState.coinsBalance
            )
            assertEquals(
                bonusSettlement.freeSpinsAwarded,
                recoveredState.freeSpinsForSlot(SLOT_ID)
            )
            assertTrue(recoveredState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
            assertEquals(
                bonusSettlement,
                repository.claimSpinPresentation(SLOT_ID, ProcessSession.id)
            )
            repository.acknowledgeSpinPresentation(bonusSettlement.id)

            val freeSettlement = settlementForStops(
                id = "instrumented-mode-change-free",
                processSessionId = ProcessSession.id,
                isFreeSpin = true,
                stops = NO_WIN_STOPS
            )
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = true,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    autoPlayFreeSpins = true
                ) {
                    SpinReservation(freeSettlement, Unit)
                }
            )
            val settledState = repository.settleSpin(freeSettlement).updatedState
            assertEquals(
                bonusSettlement.freeSpinsAwarded - 1,
                settledState.freeSpinsForSlot(SLOT_ID)
            )
            assertTrue(settledState.shouldAutoPlayFreeSpinsForSlot(SLOT_ID))
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun stalePaidStakeIsRejectedBeforeDebitAndRngThenLatestStakeCanReserve() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        repository.updateSelectedLines(10)

        try {
            repository.updateSelectedBet(10)
            var staleRngCreated = false
            val staleReservation = repository.reserveSpin(
                slotId = SLOT_ID,
                isFreeSpin = false,
                lineBet = 25,
                lines = 10,
                totalBet = 250,
                selectedBetSnapshot = 25,
                selectedLinesSnapshot = 10
            ) {
                staleRngCreated = true
                SpinReservation(
                    paidSettlement(
                        id = "instrumented-stale-stake",
                        winAmount = 0,
                        levelXpAwarded = 8
                    ),
                    Unit
                )
            }

            assertEquals(null, staleReservation)
            assertFalse(staleRngCreated)
            assertEquals(PlayerState.STARTING_BALANCE, repository.playerState.first().coinsBalance)

            val latestSettlement = settlementForStops(
                id = "instrumented-latest-stake",
                processSessionId = ProcessSession.id,
                isFreeSpin = false,
                stops = NO_WIN_STOPS,
                bet = 10
            )
            var latestRngCreated = false
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 10,
                    lines = 10,
                    totalBet = 100,
                    selectedBetSnapshot = 10,
                    selectedLinesSnapshot = 10
                ) {
                    latestRngCreated = true
                    SpinReservation(latestSettlement, Unit)
                }
            )
            assertTrue(latestRngCreated)
            assertEquals(PlayerState.STARTING_BALANCE - 100, repository.playerState.first().coinsBalance)
            val settledState = repository.settleSpin(latestSettlement).updatedState
            assertEquals(PlayerState.STARTING_BALANCE - 100, settledState.coinsBalance)
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun recoveredVisualRemainsClaimableUntilAcknowledged() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = paidSettlement(
            id = "instrumented-recovered-presentation",
            winAmount = 400,
            levelXpAwarded = 14,
            freeSpinsAwarded = 5,
            processSessionId = "previous-presentation-process",
            visualResult = visualResult(winAmount = 400, freeSpinsAwarded = 5)
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(settlement, Unit)
                }
            )

            val claimed = repository.claimSpinPresentation(
                slotId = SLOT_ID,
                currentProcessSessionId = ProcessSession.id
            )

            assertEquals(settlement, claimed)
            val recoveredState = repository.playerState.first()
            assertEquals(
                PlayerState.STARTING_BALANCE - settlement.totalBet + settlement.winAmount,
                recoveredState.coinsBalance
            )
            assertEquals(settlement.freeSpinsAwarded, recoveredState.freeSpinsForSlot(SLOT_ID))
            assertEquals(settlement.levelXpAwarded, recoveredState.levelXp)
            assertEquals(settlement, repository.claimSpinPresentation(SLOT_ID, ProcessSession.id))
            assertFalse(repository.recoverPendingSpinSettlement(ProcessSession.id))

            repository.acknowledgeSpinPresentation(settlement.id)
            assertNull(repository.claimSpinPresentation(SLOT_ID, "later-presentation-process"))
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun settledVisualCanReplayInSameProcessAndRequiresMatchingAcknowledgement() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = paidSettlement(
            id = "instrumented-settled-presentation",
            winAmount = 100,
            levelXpAwarded = 9,
            visualResult = visualResult(winAmount = 100, freeSpinsAwarded = 0)
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(settlement, Unit)
                }
            )
            repository.settleSpin(settlement)

            assertEquals(settlement, repository.claimSpinPresentation(SLOT_ID, ProcessSession.id))
            assertNull(repository.claimSpinPresentation(OTHER_SLOT_ID, "presentation-replayer"))
            repository.acknowledgeSpinPresentation("wrong-presentation-id")

            assertNull(repository.claimSpinPresentation(SLOT_ID, "presentation-replayer"))
            assertEquals(
                settlement,
                repository.claimSpinPresentation(SLOT_ID, ProcessSession.id)
            )

            repository.acknowledgeSpinPresentation(settlement.id)
            assertNull(repository.claimSpinPresentation(SLOT_ID, "next-presentation-replayer"))
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun activePresentationConsumerExcludesAnotherViewModelUntilLeaseEnds() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.updateSelectedBet(25)
        val settlement = settlementForStops(
            id = "instrumented-exclusive-presentation",
            processSessionId = ProcessSession.id,
            isFreeSpin = false,
            stops = WIN_STOPS
        )
        val firstConsumer = "${ProcessSession.id}:first-consumer"
        val secondConsumer = "${ProcessSession.id}:second-consumer"

        try {
            ProcessSession.registerPresentationConsumer(firstConsumer)
            ProcessSession.registerPresentationConsumer(secondConsumer)
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) { SpinReservation(settlement, Unit) }
            )
            repository.settleSpin(settlement, firstConsumer)

            assertNull(repository.claimSpinPresentation(SLOT_ID, secondConsumer))
            ProcessSession.releasePresentationConsumer(firstConsumer)
            assertEquals(settlement, repository.claimSpinPresentation(SLOT_ID, secondConsumer))
        } finally {
            ProcessSession.releasePresentationConsumer(firstConsumer)
            ProcessSession.releasePresentationConsumer(secondConsumer)
            repository.resetForDebug()
        }
    }

    @Test
    fun settlementAboveIntMaxCreditsAndReplaysTheFullWin() = runBlocking {
        val repository = AppGraph.playerRepository
        repository.resetForDebug()
        repository.creditSpinWin(Int.MAX_VALUE)
        repository.updateSelectedBet(25)
        val balanceBeforeSpin = repository.playerState.first().coinsBalance
        val settlement = paidSettlement(
            id = "instrumented-capped-presentation",
            winAmount = 400,
            levelXpAwarded = 9,
            visualResult = visualResult(winAmount = 400, freeSpinsAwarded = 0)
        )

        try {
            assertNotNull(
                repository.reserveSpin(
                    slotId = SLOT_ID,
                    isFreeSpin = false,
                    lineBet = 25,
                    lines = 10,
                    totalBet = 250,
                    selectedBetSnapshot = 25,
                    selectedLinesSnapshot = 10
                ) {
                    SpinReservation(settlement, Unit)
                }
            )

            val receipt = repository.settleSpin(settlement)
            val expectedBalance = balanceBeforeSpin - settlement.totalBet + settlement.winAmount

            assertTrue(receipt.applied)
            assertEquals(settlement.winAmount, receipt.creditedWinAmount)
            assertEquals(expectedBalance, receipt.updatedState.coinsBalance)
            val repeatedReceipt = repository.settleSpin(settlement)
            assertFalse(repeatedReceipt.applied)
            assertEquals(settlement.winAmount, repeatedReceipt.creditedWinAmount)
            assertEquals(expectedBalance, repeatedReceipt.updatedState.coinsBalance)
            val presentation = repository.claimSpinPresentation(SLOT_ID, ProcessSession.id)
            assertNotNull(presentation)
            assertEquals(settlement.winAmount, presentation?.winAmount)
            assertEquals(settlement.visualResult, presentation?.visualResult)
        } finally {
            repository.resetForDebug()
        }
    }

    @Test
    fun dailyBonusCreditsTheFullAmountAboveIntMaxAndBlocksClockRollbackAfterRestart() = runBlocking {
        val repository = AppGraph.playerRepository
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalTime = 123_456L
        val advancedClaimTime = originalTime + PlayerState.DAILY_BONUS_INTERVAL_MS
        repository.resetForDebug()
        repository.creditSpinWin(Int.MAX_VALUE)
        assertTrue(repository.debitSpinBet(400))
        val balanceBeforeBonus = repository.playerState.first().coinsBalance

        try {
            assertFalse(repository.claimDailyBonus(now = 0L).claimed)
            assertEquals(balanceBeforeBonus, repository.playerState.first().coinsBalance)
            val result = repository.claimDailyBonus(now = originalTime)

            assertTrue(result.claimed)
            assertEquals(PlayerState.DAILY_BONUS_AMOUNT, result.amount)
            assertEquals(balanceBeforeBonus + PlayerState.DAILY_BONUS_AMOUNT, result.balanceAfter)
            assertFalse(repository.claimDailyBonus(now = originalTime).claimed)
            assertTrue(repository.claimDailyBonus(now = advancedClaimTime).claimed)
            val balanceAfterAdvancedClaim = repository.playerState.first().coinsBalance

            assertFalse(repository.claimDailyBonus(now = originalTime).claimed)
            assertEquals(balanceAfterAdvancedClaim, repository.playerState.first().coinsBalance)
            assertEquals(
                advancedClaimTime,
                repository.playerState.first().lastDailyBonusTimestamp
            )

            val restartedRepository = PlayerRepository(context)
            assertFalse(restartedRepository.claimDailyBonus(now = originalTime).claimed)
            val restartedState = restartedRepository.playerState.first()
            assertEquals(balanceAfterAdvancedClaim, restartedState.coinsBalance)
            assertEquals(advancedClaimTime, restartedState.lastDailyBonusTimestamp)
        } finally {
            repository.resetForDebug()
        }
    }

    private fun paidSettlement(
        id: String,
        winAmount: Int,
        levelXpAwarded: Int,
        freeSpinsAwarded: Int = 0,
        processSessionId: String = ProcessSession.id,
        visualResult: SpinResult? = null,
        slotId: String = SLOT_ID
    ): PendingSpinSettlement {
        check(levelXpAwarded >= 0)
        check(visualResult == null || visualResult.stopIndexes.size == 5)
        val stops = when {
            freeSpinsAwarded > 0 -> BONUS_STOPS
            winAmount > 0 -> WIN_STOPS
            else -> NO_WIN_STOPS
        }
        return settlementForStops(
            id = id,
            processSessionId = processSessionId,
            isFreeSpin = false,
            stops = stops,
            slotId = slotId
        )
    }

    private fun settlementForStops(
        id: String,
        processSessionId: String,
        isFreeSpin: Boolean,
        stops: List<Int>,
        bet: Int = 25,
        lines: Int = 10,
        slotId: String = SLOT_ID
    ): PendingSpinSettlement {
        val config = checkNotNull(AppGraph.slotRepository.getSlotExact(slotId))
        val result = SlotEngine().evaluateStops(
            config,
            stops,
            bet,
            lines,
            isFreeSpin = isFreeSpin
        )
        return PendingSpinSettlement(
            id = id,
            processSessionId = processSessionId,
            slotId = slotId,
            isFreeSpin = isFreeSpin,
            lineBet = result.bet,
            lines = result.lines,
            totalBet = result.totalBet,
            winAmount = result.winAmount,
            freeSpinsAwarded = result.freeSpinsAwarded,
            levelXpAwarded = PlayerState.xpForSpin(
                result.totalBet,
                isFreeSpin,
                result.winAmount
            ),
            mathVersion = SlotMathIdentity.VERSION,
            configFingerprint = SlotMathIdentity.fingerprint(config),
            stopIndexes = stops,
            visualResult = result
        )
    }

    private fun visualResult(winAmount: Int, freeSpinsAwarded: Int): SpinResult {
        val scatterPositions = if (freeSpinsAwarded > 0) {
            listOf(
                SymbolPosition(reel = 0, row = 2),
                SymbolPosition(reel = 1, row = 1),
                SymbolPosition(reel = 2, row = 2)
            )
        } else {
            emptyList()
        }
        return SpinResult(
            reels = listOf(
                listOf("violet", "wild", "scatter"),
                listOf("violet", "scatter", "crown"),
                listOf("violet", "gem", "scatter"),
                listOf("ring", "gem", "crown"),
                listOf("ring", "wild", "crown")
            ),
            bet = 25,
            lines = 10,
            totalBet = 250,
            winAmount = winAmount,
            resultType = if (freeSpinsAwarded > 0) ResultType.Bonus else ResultType.Win,
            winningLines = listOf(
                WinningLine(
                    paylineIndex = 0,
                    symbol = "violet",
                    count = 3,
                    amount = winAmount,
                    positions = listOf(
                        SymbolPosition(reel = 0, row = 0),
                        SymbolPosition(reel = 1, row = 0),
                        SymbolPosition(reel = 2, row = 0)
                    )
                )
            ),
            scatterCount = scatterPositions.size,
            scatterPositions = scatterPositions,
            freeSpinsAwarded = freeSpinsAwarded,
            stopIndexes = listOf(4, 8, 15, 16, 23)
        )
    }

    private companion object {
        const val SLOT_ID = "violet_fortune"
        const val OTHER_SLOT_ID = "roman_reels"
        const val RECOVERY_TIMEOUT_MS = 5_000L
        const val LIVE_SPIN_GUARD_WAIT_MS = 600L
        val NO_WIN_STOPS = listOf(0, 1, 2, 3, 0)
        val WIN_STOPS = listOf(4, 8, 15, 16, 23)
        val BONUS_STOPS = listOf(0, 0, 17, 20, 15)
    }
}
