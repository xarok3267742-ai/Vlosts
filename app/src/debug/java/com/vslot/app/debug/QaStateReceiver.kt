package com.vslot.app.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vslot.app.AppGraph
import com.vslot.app.ProcessSession
import com.vslot.app.data.PendingSpinSettlement
import com.vslot.app.data.PlayerRepository
import com.vslot.app.data.PlayerState
import com.vslot.app.data.PlayerStateCheckpointStore
import com.vslot.app.data.SpinReservation
import com.vslot.app.data.deserializePendingSpinPresentation
import com.vslot.app.data.deserializePendingSpinSettlement
import com.vslot.app.game.SlotEngine
import com.vslot.app.game.SlotMathIdentity
import com.vslot.app.game.SlotRng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QaStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val orderedBroadcast = isOrderedBroadcast
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var resultCode = Activity.RESULT_OK
            val resultData = try {
                when (intent.getStringExtra(EXTRA_COMMAND).orEmpty()) {
                    COMMAND_PREPARE_PROCESS_DEATH -> prepareProcessDeathRecovery(context)
                    COMMAND_INSPECT_PROCESS_DEATH -> inspectProcessDeathRecovery(context)
                    COMMAND_ACK_PROCESS_DEATH -> acknowledgeProcessDeathPresentation(context)
                    "" -> {
                        seedScenario(context, intent)
                        response("status" to "seeded")
                    }
                    else -> {
                        resultCode = Activity.RESULT_CANCELED
                        response("status" to "error", "error" to "unknown_command")
                    }
                }
            } catch (error: Exception) {
                resultCode = Activity.RESULT_CANCELED
                Log.e(TAG, "QA state command failed", error)
                response("status" to "error", "error" to "command_failed")
            }
            try {
                if (orderedBroadcast) {
                    pendingResult.setResultCode(resultCode)
                    pendingResult.setResultData(resultData)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun seedScenario(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra(EXTRA_SCENARIO).orEmpty()
        AppGraph.clearSlotEngineOverrideForDebug(context)
        when (scenario) {
            SCENARIO_RESET,
            SCENARIO_FIRST_LAUNCH -> {
                resetPlayerState()
            }
            SCENARIO_LOW_WAIT -> {
                val repository = resetPlayerState()
                repository.acceptDisclaimer()
                repository.claimDailyBonus()
                drainBalance(repository)
            }
            SCENARIO_DAILY_WAIT -> {
                val repository = resetPlayerState()
                repository.acceptDisclaimer()
                repository.claimDailyBonus()
            }
            SCENARIO_LOW_BONUS -> {
                val repository = resetPlayerState()
                repository.acceptDisclaimer()
                drainBalance(repository)
            }
            SCENARIO_FREE_SPINS -> {
                val repository = resetPlayerState()
                repository.acceptDisclaimer()
                repository.awardFreeSpins(DEBUG_FREE_SPINS, DEBUG_QA_BET, DEBUG_QA_LINES, slotId = "")
            }
            SCENARIO_SLOT_MULTI_WIN -> {
                seedPlayableSlotState(intent)
                AppGraph.persistSlotEngineOverrideForDebug(context, DEBUG_MULTI_WIN_STOPS)
            }
            SCENARIO_SLOT_BONUS -> {
                seedPlayableSlotState(intent)
                AppGraph.persistSlotEngineOverrideForDebug(context, DEBUG_BONUS_STOPS)
            }
            SCENARIO_PENDING_SPIN_RECOVERY -> {
                seedPendingSpinRecovery()
            }
        }
    }

    private suspend fun prepareProcessDeathRecovery(context: Context): String {
        val repository = resetPlayerState()
        repository.acceptDisclaimer()
        repository.updateSelectedBet(DEBUG_QA_BET)
        repository.updateSelectedLines(DEBUG_QA_LINES)
        repository.updateLastPlayedSlot(DEBUG_QA_SLOT_ID)

        val initialState = repository.playerState.first()
        val config = AppGraph.slotRepository.getSlot(DEBUG_QA_SLOT_ID)
        val visualResult = SlotEngine(FixedStopsRng(DEBUG_PROCESS_DEATH_STOPS))
            .spin(config, DEBUG_QA_BET, DEBUG_QA_LINES)
        check(visualResult.freeSpinsAwarded == 0) {
            "Process-death fixture must not start feature autoplay."
        }
        check(visualResult.winAmount >= visualResult.totalBet * DEBUG_BIG_WIN_MULTIPLIER) {
            "Process-death fixture must keep a modal presentation observable."
        }
        val levelXpAwarded = PlayerState.xpForSpin(
            totalBet = visualResult.totalBet,
            isFreeSpin = false,
            winAmount = visualResult.winAmount
        )
        val settlement = PendingSpinSettlement(
            id = DEBUG_PROCESS_DEATH_SETTLEMENT_ID,
            processSessionId = ProcessSession.id,
            slotId = DEBUG_QA_SLOT_ID,
            isFreeSpin = false,
            lineBet = visualResult.bet,
            lines = visualResult.lines,
            totalBet = visualResult.totalBet,
            winAmount = visualResult.winAmount,
            freeSpinsAwarded = visualResult.freeSpinsAwarded,
            levelXpAwarded = levelXpAwarded,
            mathVersion = SlotMathIdentity.VERSION,
            configFingerprint = SlotMathIdentity.fingerprint(config),
            stopIndexes = visualResult.stopIndexes,
            visualResult = visualResult
        )
        checkNotNull(
            repository.reserveSpin(
                slotId = settlement.slotId,
                isFreeSpin = settlement.isFreeSpin,
                lineBet = settlement.lineBet,
                lines = settlement.lines,
                totalBet = settlement.totalBet,
                selectedBetSnapshot = initialState.selectedBet,
                selectedLinesSnapshot = initialState.selectedLines
            ) {
                SpinReservation(settlement = settlement, value = Unit)
            }
        )
        ProcessSession.registerSpinSettlement(settlement.id)

        val reservedState = repository.playerState.first()
        val checkpoint = readCheckpoint(context)
        val pendingSettlement = checkpoint.rawPendingSpinSettlement
            ?.let(::deserializePendingSpinSettlement)
        check(pendingSettlement?.id == settlement.id) {
            "Committed QA spin was not persisted to the settlement journal."
        }
        check(checkpoint.rawPendingSpinPresentation == null) {
            "Presentation must not exist before cross-process recovery."
        }

        return response(
            "status" to "prepared",
            "settlement_id" to settlement.id,
            "initial_balance" to initialState.coinsBalance,
            "reserved_balance" to reservedState.coinsBalance,
            "expected_balance" to (reservedState.coinsBalance + settlement.winAmount),
            "expected_level_xp" to (reservedState.levelXp + settlement.levelXpAwarded),
            "expected_free_spins" to reservedState.freeSpinsBalance,
            "expected_win" to settlement.winAmount,
            "pending_settlement" to true,
            "pending_presentation" to false
        )
    }

    private suspend fun inspectProcessDeathRecovery(context: Context): String {
        val state = AppGraph.playerRepository.playerState.first()
        val checkpoint = readCheckpoint(context)
        val pendingSettlement = checkpoint.rawPendingSpinSettlement
            ?.let(::deserializePendingSpinSettlement)
        val pendingPresentation = checkpoint.rawPendingSpinPresentation
            ?.let(::deserializePendingSpinPresentation)
        return response(
            "status" to "state",
            "balance" to state.coinsBalance,
            "level_xp" to state.levelXp,
            "free_spins" to state.freeSpinsBalance,
            "pending_settlement" to (pendingSettlement != null),
            "pending_settlement_id" to (pendingSettlement?.id ?: RESPONSE_NONE),
            "pending_presentation" to (pendingPresentation != null),
            "pending_presentation_id" to (pendingPresentation?.settlement?.id ?: RESPONSE_NONE),
            "presentation_claimed" to (pendingPresentation?.claimedByProcessSessionId != null)
        )
    }

    private suspend fun acknowledgeProcessDeathPresentation(context: Context): String {
        val pendingPresentation = readCheckpoint(context).rawPendingSpinPresentation
            ?.let(::deserializePendingSpinPresentation)
            ?: return inspectProcessDeathRecovery(context)
        check(pendingPresentation.settlement.id == DEBUG_PROCESS_DEATH_SETTLEMENT_ID) {
            "Refusing to acknowledge an unrelated presentation."
        }
        check(pendingPresentation.claimedByProcessSessionId != null) {
            "MainActivity must claim the recovered presentation before QA acknowledges it."
        }
        AppGraph.playerRepository.acknowledgeSpinPresentation(
            pendingPresentation.settlement.id
        )
        return inspectProcessDeathRecovery(context)
    }

    private fun readCheckpoint(context: Context) = checkNotNull(
        PlayerStateCheckpointStore(
            context.noBackupFilesDir,
            PlayerStateCheckpointStore.PRIMARY_FILE_NAME
        ).read()
    ) { "Player checkpoint is unavailable." }

    private fun response(vararg fields: Pair<String, Any>): String {
        return buildString {
            append("schema=1")
            fields.forEach { (key, value) ->
                check(RESPONSE_TOKEN.matches(key)) { "Invalid QA response key." }
                val encodedValue = value.toString()
                check(RESPONSE_TOKEN.matches(encodedValue)) { "Invalid QA response value." }
                append(';')
                append(key)
                append('=')
                append(encodedValue)
            }
        }
    }

    private suspend fun resetPlayerState() = AppGraph.playerRepository.also {
        it.resetForDebug()
    }

    private suspend fun seedPlayableSlotState(intent: Intent) {
        val repository = resetPlayerState()
        repository.acceptDisclaimer()
        repository.claimDailyBonus()
        drainBalance(repository)
        repository.updateSelectedBet(intent.getIntExtra(EXTRA_SELECTED_BET, DEBUG_QA_BET).coerceToDebugBet())
        repository.updateSelectedLines(intent.getIntExtra(EXTRA_SELECTED_LINES, DEBUG_QA_LINES).coerceToDebugLines())
        repository.updateLastPlayedSlot(intent.getStringExtra(EXTRA_LAST_SLOT).orEmpty().ifBlank { DEBUG_QA_SLOT_ID })
        repository.awardLevelXp(intent.getIntExtra(EXTRA_LEVEL_XP, 0).coerceAtLeast(0))
        repeat(repository.playerState.first().freeSpinsBalance) {
            repository.consumeFreeSpin(DEBUG_QA_SLOT_ID)
        }
        repository.creditSpinWin(DEBUG_BALANCE_TOP_UP)
    }

    private suspend fun seedPendingSpinRecovery() {
        val repository = resetPlayerState()
        repository.acceptDisclaimer()
        repository.updateSelectedBet(DEBUG_QA_BET)
        repository.updateSelectedLines(DEBUG_QA_LINES)
        val config = checkNotNull(AppGraph.slotRepository.getSlotExact(DEBUG_QA_SLOT_ID))
        val result = SlotEngine(FixedStopsRng(DEBUG_BONUS_STOPS))
            .spin(config, DEBUG_QA_BET, DEBUG_QA_LINES)
        val levelXpAwarded = PlayerState.xpForSpin(
            totalBet = result.totalBet,
            isFreeSpin = false,
            winAmount = result.winAmount
        )
        checkNotNull(
            repository.reserveSpin(
                slotId = DEBUG_QA_SLOT_ID,
                isFreeSpin = false,
                lineBet = DEBUG_QA_BET,
                lines = DEBUG_QA_LINES,
                totalBet = result.totalBet,
                selectedBetSnapshot = DEBUG_QA_BET,
                selectedLinesSnapshot = DEBUG_QA_LINES
            ) {
                SpinReservation(
                    settlement = PendingSpinSettlement(
                        id = "qa-pending-spin",
                        processSessionId = ProcessSession.id,
                        slotId = DEBUG_QA_SLOT_ID,
                        isFreeSpin = false,
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
                    ),
                    value = Unit
                )
            }
        )
    }

    private suspend fun drainBalance(repository: PlayerRepository) {
        val balance = repository.playerState.first().coinsBalance
        if (balance > 0L) {
            check(balance <= Int.MAX_VALUE.toLong()) {
                "QA scenarios only drain a freshly reset balance."
            }
            repository.debitSpinBet(balance.toInt())
        }
    }

    private fun Int.coerceToDebugBet(): Int {
        return if (this in DEBUG_QA_BETS) this else DEBUG_QA_BET
    }

    private fun Int.coerceToDebugLines(): Int {
        return coerceIn(PlayerState.MIN_LINES, PlayerState.DEFAULT_LINES)
    }

    private class FixedStopsRng(private val stops: IntArray) : SlotRng {
        private var index = 0

        override fun nextInt(bound: Int): Int {
            val stop = stops.getOrElse(index) {
                error("Process-death fixture requested too many reel stops.")
            }
            index += 1
            return stop % bound
        }
    }

    private companion object {
        const val TAG = "QaStateReceiver"
        const val ACTION = "com.vslot.app.debug.QA_STATE"
        const val EXTRA_COMMAND = "qa_command"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_SELECTED_BET = "selected_bet"
        const val EXTRA_SELECTED_LINES = "selected_lines"
        const val EXTRA_LAST_SLOT = "last_slot"
        const val EXTRA_LEVEL_XP = "level_xp"
        const val SCENARIO_RESET = "reset"
        const val SCENARIO_FIRST_LAUNCH = "first_launch"
        const val SCENARIO_LOW_WAIT = "low_wait"
        const val SCENARIO_DAILY_WAIT = "daily_wait"
        const val SCENARIO_LOW_BONUS = "low_bonus"
        const val SCENARIO_FREE_SPINS = "free_spins"
        const val SCENARIO_SLOT_MULTI_WIN = "slot_multi_win"
        const val SCENARIO_SLOT_BONUS = "slot_bonus"
        const val SCENARIO_PENDING_SPIN_RECOVERY = "pending_spin_recovery"
        const val COMMAND_PREPARE_PROCESS_DEATH = "prepare_process_death"
        const val COMMAND_INSPECT_PROCESS_DEATH = "inspect_process_death"
        const val COMMAND_ACK_PROCESS_DEATH = "ack_process_death_presentation"
        const val DEBUG_QA_SLOT_ID = "violet_fortune"
        const val DEBUG_QA_BET = 25
        const val DEBUG_QA_LINES = 10
        val DEBUG_QA_BETS = setOf(10, 25, 50, 100, 250)
        const val DEBUG_BALANCE_TOP_UP = 10_000
        const val DEBUG_FREE_SPINS = 5
        const val DEBUG_PROCESS_DEATH_SETTLEMENT_ID = "qa-process-death-spin-v1"
        const val DEBUG_BIG_WIN_MULTIPLIER = 10
        const val RESPONSE_NONE = "none"
        val RESPONSE_TOKEN = Regex("[A-Za-z0-9_.:-]+")
        val DEBUG_MULTI_WIN_STOPS = intArrayOf(0, 5, 11, 1, 0)
        val DEBUG_BONUS_STOPS = intArrayOf(0, 0, 17, 20, 15)
        val DEBUG_PROCESS_DEATH_STOPS = intArrayOf(0, 0, 12, 4, 2)
    }
}
