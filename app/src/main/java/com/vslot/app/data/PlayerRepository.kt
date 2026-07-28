package com.vslot.app.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vslot.app.BuildConfig
import com.vslot.app.ProcessSession
import com.vslot.app.game.SlotEngine
import com.vslot.app.game.SlotRepository
import com.vslot.app.game.SpinSettlementVerifier
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.playerDataStore by preferencesDataStore(
    name = PLAYER_DATA_STORE_NAME,
    corruptionHandler = playerDataStoreCorruptionHandler {
        PlayerStateCheckpointRegistry.read()?.let(PlayerRepository::preferencesFromCheckpoint)
    }
)

internal fun playerDataStoreCorruptionHandler(
    recoverPreferences: () -> Preferences? = { null }
): ReplaceFileCorruptionHandler<Preferences> {
    return ReplaceFileCorruptionHandler {
        recoverPreferences() ?: emptyPreferences()
    }
}

private object PlayerStateCheckpointRegistry {
    @Volatile
    private var store: PlayerStateCheckpointStore? = null

    fun install(context: Context): PlayerStateCheckpointStore {
        store?.let { return it }
        return synchronized(this) {
            store ?: PlayerStateCheckpointStore(
                context.noBackupFilesDir,
                PlayerStateCheckpointStore.DEFAULT_FILE_NAME
            ).also { store = it }
        }
    }

    fun read(): PlayerStateCheckpoint? = store?.read()
}

private object TransactionalPlayerStateStoreRegistry {
    @Volatile
    private var store: TransactionalPlayerStateStore? = null

    fun install(
        context: Context,
        migrateLegacyState: suspend () -> PlayerStateCheckpoint
    ): TransactionalPlayerStateStore {
        store?.let { return it }
        return synchronized(this) {
            store ?: TransactionalPlayerStateStore.create(
                primaryFile = java.io.File(
                    context.noBackupFilesDir,
                    PlayerStateCheckpointStore.PRIMARY_FILE_NAME
                ),
                migrateLegacyState = migrateLegacyState
            ).also { store = it }
        }
    }
}

internal fun <T> Flow<T>.retryTransientPersistenceReads(
    fallbackAfterAttempts: Int = DATA_STORE_READ_FALLBACK_ATTEMPTS,
    fallbackValue: (() -> T)? = null,
    retryDelay: suspend (Long) -> Unit = { delay(it) }
): Flow<T> {
    require(fallbackAfterAttempts > 0) { "At least one read attempt is required before fallback." }
    val retried = retryWhen { cause, attempt ->
        val shouldRetry = cause is IOException &&
            attempt < fallbackAfterAttempts.toLong() - 1L
        if (shouldRetry) retryDelay(persistenceRetryDelayMs(attempt))
        shouldRetry
    }
    return if (fallbackValue == null) {
        retried
    } else {
        retried.catch { cause ->
            if (cause is IOException) emit(fallbackValue()) else throw cause
        }
    }
}

internal fun persistenceRetryDelayMs(attempt: Long): Long {
    val exponent = attempt.coerceIn(0L, DATA_STORE_MAX_RETRY_EXPONENT.toLong()).toInt()
    return (DATA_STORE_RETRY_BASE_DELAY_MS * (1L shl exponent))
        .coerceAtMost(DATA_STORE_RETRY_MAX_DELAY_MS)
}

internal suspend fun <T> retryTransientPersistenceIo(
    maxAttempts: Int = DATA_STORE_WRITE_RETRY_ATTEMPTS,
    retryDelay: suspend (Long) -> Unit = { delay(it) },
    operation: suspend () -> T
): T {
    require(maxAttempts > 0) { "At least one persistence attempt is required." }
    var lastFailure: IOException? = null
    repeat(maxAttempts) { attempt ->
        try {
            return operation()
        } catch (failure: IOException) {
            lastFailure = failure
            if (attempt < maxAttempts - 1) {
                retryDelay(persistenceRetryDelayMs(attempt.toLong()))
            }
        }
    }
    throw checkNotNull(lastFailure)
}

internal suspend fun <T> finishTransientPersistenceIo(
    operation: suspend () -> T
): T = withContext(NonCancellable) {
    retryTransientPersistenceIo(operation = operation)
}

interface PlayerStore {
    val playerState: Flow<PlayerState>

    suspend fun updateSelectedBet(bet: Int)
    suspend fun updateSelectedLines(lines: Int)
    suspend fun updateLastPlayedSlot(slotId: String)
    suspend fun debitSpinBet(totalBet: Int): Boolean
    suspend fun creditSpinWin(winAmount: Int)
    suspend fun consumeFreeSpin(slotId: String): Boolean
    suspend fun awardFreeSpins(count: Int, lineBet: Int, lines: Int, slotId: String)
    suspend fun reconcileFreeSpinStake(
        slotId: String,
        supportedBets: List<Int>,
        maxLines: Int
    ): PlayerState
    suspend fun updateFreeSpinAutoPlay(slotId: String, enabled: Boolean)
    suspend fun awardLevelXp(amount: Int)
    suspend fun updateSoundEnabled(enabled: Boolean)
    suspend fun updateHapticsEnabled(enabled: Boolean)

    suspend fun <T> reserveSpin(
        slotId: String,
        isFreeSpin: Boolean,
        lineBet: Int,
        lines: Int,
        totalBet: Int,
        selectedBetSnapshot: Int? = null,
        selectedLinesSnapshot: Int? = null,
        autoPlayFreeSpins: Boolean = false,
        createReservation: () -> SpinReservation<T>
    ): SpinReservation<T>?

    suspend fun settleSpin(
        settlement: PendingSpinSettlement,
        presentationConsumerId: String? = null
    ): SpinSettlementReceipt

    suspend fun recoverPendingSpinSettlement(currentProcessSessionId: String): Boolean

    suspend fun pendingSpinPresentationSlotId(): String? = null

    suspend fun claimSpinPresentation(
        slotId: String,
        currentProcessSessionId: String
    ): PendingSpinSettlement? = null

    suspend fun acknowledgeSpinPresentation(id: String) = Unit
}

interface PlayerSettingsStore {
    val playerState: Flow<PlayerState>

    suspend fun updateSoundEnabled(enabled: Boolean)
    suspend fun updateHapticsEnabled(enabled: Boolean)
    suspend fun updateAnalyticsEnabled(enabled: Boolean)
    suspend fun markPushPermissionAsked()
}

interface DisclaimerStore {
    suspend fun acceptDisclaimer()
}

class PlayerRepository(
    context: Context,
    private val settlementVerifier: SpinSettlementVerifier = SpinSettlementVerifier(
        SlotRepository(context.applicationContext),
        SlotEngine()
    )
) : PlayerStore, PlayerSettingsStore, DisclaimerStore {
    private val legacyCheckpointStore = PlayerStateCheckpointRegistry.install(context.applicationContext)
    private val legacyDataStore = context.playerDataStore
    private val stateStore = TransactionalPlayerStateStoreRegistry.install(context.applicationContext) {
        migrateLegacyPlayerState()
    }

    override val playerState: Flow<PlayerState> = stateStore.data
        .retryTransientPersistenceReads()
        .map { preferencesFromCheckpoint(it).toPlayerState() }

    override suspend fun acceptDisclaimer() {
        editPlayerState { it[Keys.DisclaimerAccepted] = true }
    }

    suspend fun resetForDebug() {
        check(BuildConfig.QA_ENABLED) { "Player state reset is only available in QA builds." }
        editPlayerState { it.clearPlayerStatePreservingRevision() }
    }

    internal suspend fun replacePendingSpinJournalForDebug(serialized: String) {
        check(BuildConfig.QA_ENABLED) { "Spin journal replacement is only available in QA builds." }
        editPlayerState { preferences ->
            preferences[Keys.PendingSpinSettlement] = serialized
        }
    }

    override suspend fun updateSelectedBet(bet: Int) {
        editPlayerState { it[Keys.SelectedBet] = bet }
    }

    override suspend fun updateSelectedLines(lines: Int) {
        editPlayerState { it[Keys.SelectedLines] = lines.coerceIn(PlayerState.MIN_LINES, PlayerState.MAX_LINES) }
    }

    override suspend fun updateLastPlayedSlot(slotId: String) {
        editPlayerState { it[Keys.LastPlayedSlot] = PlayerState.normalizedLastPlayedSlot(slotId) }
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
        if (
            slotId.isBlank() ||
            lineBet <= 0 ||
            lines !in PlayerState.MIN_LINES..PlayerState.MAX_LINES ||
            lineBet.toLong() * lines.toLong() != totalBet.toLong() ||
            (!isFreeSpin && selectedBetSnapshot == null) ||
            (!isFreeSpin && selectedLinesSnapshot == null)
        ) {
            return null
        }
        require(!autoPlayFreeSpins || isFreeSpin) {
            "Free-spin autoplay can only be reserved with a free spin."
        }
        var reservation: SpinReservation<T>? = null
        editPlayerState { preferences ->
            preferences.applyPersistedPendingSpinSettlement(ProcessSession.id)
            if (preferences.contains(Keys.PendingSpinSettlement)) {
                return@editPlayerState
            }
            val pendingPresentation = preferences[Keys.PendingSpinPresentation]?.let(
                ::deserializePendingSpinPresentation
            )
            if (
                pendingPresentation?.settlement?.let(settlementVerifier::verify) != null
            ) {
                return@editPlayerState
            }
            if (preferences.contains(Keys.PendingSpinPresentation)) {
                preferences.remove(Keys.PendingSpinPresentation)
            }
            if (preferences.hasFreeSpinForSlot(slotId) != isFreeSpin) return@editPlayerState
            if (isFreeSpin && !preferences.freeSpinStakeMatches(slotId, lineBet, lines)) {
                return@editPlayerState
            }
            if (!isFreeSpin) {
                val persistedBet = preferences[Keys.SelectedBet] ?: PlayerState.DEFAULT_BET
                val persistedLines = preferences[Keys.SelectedLines] ?: PlayerState.DEFAULT_LINES
                if (persistedBet != selectedBetSnapshot || persistedLines != selectedLinesSnapshot) {
                    return@editPlayerState
                }
            }
            val wagerReserved = if (isFreeSpin) {
                preferences.consumeFreeSpin(slotId, preserveAutoPlayMarker = true)
            } else {
                preferences.debitSpinBet(totalBet)
            }
            if (!wagerReserved) return@editPlayerState

            val candidate = createReservation()
            val verifiedSettlement = requireNotNull(
                settlementVerifier.verify(candidate.settlement)
            ) {
                "Spin settlement does not match the active slot math."
            }
            require(verifiedSettlement.matchesReservation(slotId, isFreeSpin, lineBet, lines, totalBet)) {
                "Spin settlement does not match the reserved wager."
            }
            if (
                autoPlayFreeSpins ||
                (!verifiedSettlement.isFreeSpin && verifiedSettlement.freeSpinsAwarded > 0)
            ) {
                val autoPlaySlots = preferences.readFreeSpinAutoPlaySlots().toMutableSet().apply {
                    add(slotId)
                }
                preferences.writeFreeSpinAutoPlaySlots(autoPlaySlots)
            }
            preferences[Keys.PendingSpinRefundEnvelope] = verifiedSettlement
                .toRefundEnvelope()
                .serialize()
            preferences[Keys.PendingSpinSettlement] = verifiedSettlement.serialize()
            reservation = candidate
        }
        return reservation
    }

    suspend fun settleSpin(settlement: PendingSpinSettlement): SpinSettlementReceipt {
        return settleSpin(settlement, presentationConsumerId = null)
    }

    override suspend fun settleSpin(
        settlement: PendingSpinSettlement,
        presentationConsumerId: String?
    ): SpinSettlementReceipt {
        requireNotNull(settlementVerifier.verify(settlement)) {
            "Cannot settle an outcome that does not match the active slot math."
        }
        var creditedWinAmount = 0
        var applied = false
        var outcomeSettled = false
        val updatedState = editPlayerState { preferences ->
            preferences.persistedPresentationWinAmount(settlement.id)?.let { persistedWinAmount ->
                creditedWinAmount = persistedWinAmount
                outcomeSettled = true
            }
            val serialized = preferences[Keys.PendingSpinSettlement] ?: return@editPlayerState
            val decodedSettlement = deserializePendingSpinSettlement(serialized)
            val pendingSettlement = decodedSettlement?.let(settlementVerifier::verify)
            if (pendingSettlement == null) {
                preferences.refundAndClearVoidedSpin(decodedSettlement)
                return@editPlayerState
            }
            // A newer reservation can apply this outcome before writing its own journal.
            if (pendingSettlement.id != settlement.id) return@editPlayerState
            creditedWinAmount = preferences.applySpinSettlement(
                pendingSettlement,
                claimedByProcessSessionId = presentationConsumerId
                    ?: pendingSettlement.processSessionId
            )
            applied = true
            outcomeSettled = true
        }.toPlayerState()
        return SpinSettlementReceipt(
            updatedState = updatedState,
            creditedWinAmount = creditedWinAmount,
            applied = applied,
            outcomeSettled = outcomeSettled
        )
    }

    override suspend fun recoverPendingSpinSettlement(currentProcessSessionId: String): Boolean {
        require(currentProcessSessionId.isNotBlank()) { "Process session id is required for recovery." }
        var recovered = false
        editPlayerState { preferences ->
            recovered = preferences.applyPersistedPendingSpinSettlement(currentProcessSessionId)
        }
        return recovered
    }

    override suspend fun pendingSpinPresentationSlotId(): String? {
        var pendingSlotId: String? = null
        editPlayerState { preferences ->
            val serialized = preferences[Keys.PendingSpinPresentation] ?: return@editPlayerState
            val presentation = deserializePendingSpinPresentation(serialized)
            val verified = presentation?.settlement?.let(settlementVerifier::verify)
            if (presentation == null || verified == null) {
                preferences.remove(Keys.PendingSpinPresentation)
                return@editPlayerState
            }
            pendingSlotId = verified.slotId
        }
        return pendingSlotId
    }

    override suspend fun claimSpinPresentation(
        slotId: String,
        currentProcessSessionId: String
    ): PendingSpinSettlement? {
        require(slotId.isNotBlank()) { "Slot id is required to claim a spin presentation." }
        require(currentProcessSessionId.isNotBlank()) {
            "Process session id is required to claim a spin presentation."
        }
        var claimedSettlement: PendingSpinSettlement? = null
        editPlayerState { preferences ->
            preferences.applyPersistedPendingSpinSettlement(currentProcessSessionId)

            val serialized = preferences[Keys.PendingSpinPresentation] ?: return@editPlayerState
            val presentation = deserializePendingSpinPresentation(serialized)
            val verifiedSettlement = presentation?.settlement?.let(settlementVerifier::verify)
            if (presentation == null || verifiedSettlement == null) {
                preferences.remove(Keys.PendingSpinPresentation)
                return@editPlayerState
            }
            if (verifiedSettlement.slotId != slotId) return@editPlayerState
            val claimedBy = presentation.claimedByProcessSessionId
            if (
                claimedBy != null &&
                claimedBy != currentProcessSessionId &&
                (
                    claimedBy == ProcessSession.id ||
                        ProcessSession.isPresentationConsumerActive(claimedBy)
                    )
            ) {
                return@editPlayerState
            }

            preferences[Keys.PendingSpinPresentation] = verifiedSettlement.serializePresentation(
                claimedByProcessSessionId = currentProcessSessionId
            )
            claimedSettlement = verifiedSettlement
        }
        return claimedSettlement
    }

    override suspend fun acknowledgeSpinPresentation(id: String) {
        if (id.isBlank()) return
        editPlayerState { preferences ->
            val serialized = preferences[Keys.PendingSpinPresentation] ?: return@editPlayerState
            val presentation = deserializePendingSpinPresentation(serialized)
            val verifiedSettlement = presentation?.settlement?.let(settlementVerifier::verify)
            if (presentation == null || verifiedSettlement == null || verifiedSettlement.id == id) {
                preferences.remove(Keys.PendingSpinPresentation)
            }
        }
    }

    override suspend fun debitSpinBet(totalBet: Int): Boolean {
        if (totalBet <= 0) return false
        var debited = false
        editPlayerState { preferences ->
            debited = preferences.debitSpinBet(totalBet)
        }
        return debited
    }

    override suspend fun creditSpinWin(winAmount: Int) {
        if (winAmount <= 0) return
        editPlayerState { preferences ->
            val currentBalance = preferences.readPersistedCoinsBalance()
            preferences.writePersistedCoinsBalance(
                saturatedNonNegativeAdd(currentBalance, winAmount)
            )
        }
    }

    override suspend fun consumeFreeSpin(slotId: String): Boolean {
        var consumed = false
        editPlayerState { preferences ->
            consumed = preferences.consumeFreeSpin(slotId)
        }
        return consumed
    }

    override suspend fun awardFreeSpins(count: Int, lineBet: Int, lines: Int, slotId: String) {
        if (count <= 0) return
        if (lineBet <= 0 || lines <= 0) return
        editPlayerState { preferences ->
            if (slotId.isBlank()) {
                val currentFreeSpins = preferences[Keys.FreeSpinsBalance] ?: 0
                preferences.remove(Keys.FreeSpinBonuses)
                preferences[Keys.FreeSpinsBalance] = saturatedNonNegativeAdd(currentFreeSpins, count)
                if (lineBet > 0 && lines > 0) {
                    preferences[Keys.FreeSpinBet] = lineBet
                    preferences[Keys.FreeSpinLines] = lines.coerceIn(PlayerState.MIN_LINES, PlayerState.MAX_LINES)
                }
                preferences.remove(Keys.FreeSpinSlotId)
            return@editPlayerState
            }

            val bonuses = mergeAwardedFreeSpinBonus(
                currentBonuses = preferences.readFreeSpinBonuses(),
                legacySlotId = preferences[Keys.FreeSpinSlotId].orEmpty(),
                legacyCount = preferences[Keys.FreeSpinsBalance] ?: 0,
                legacyLineBet = preferences[Keys.FreeSpinBet] ?: 0,
                legacyLines = preferences[Keys.FreeSpinLines] ?: 0,
                awardSlotId = slotId,
                awardCount = count,
                awardLineBet = lineBet,
                awardLines = lines
            )
            preferences.writeFreeSpinBonuses(bonuses)
        }
    }

    override suspend fun reconcileFreeSpinStake(
        slotId: String,
        supportedBets: List<Int>,
        maxLines: Int
    ): PlayerState {
        require(slotId.isNotBlank()) { "Slot id is required for free-spin reconciliation." }
        val validatedBets = supportedBets.filter { it > 0 }.distinct().sorted()
        require(validatedBets.isNotEmpty()) { "At least one positive slot bet is required." }
        require(maxLines in PlayerState.MIN_LINES..PlayerState.MAX_LINES) {
            "Slot paylines are outside the supported range."
        }

        return editPlayerState { preferences ->
            val bonuses = preferences.readFreeSpinBonuses().toMutableMap()
            val persistedBonus = bonuses[slotId]
            if (persistedBonus != null) {
                val reconciledStake = reconciledFreeSpinStake(
                    lockedLineBet = persistedBonus.lineBet,
                    lockedLines = persistedBonus.lines,
                    supportedBets = validatedBets,
                    maxLines = maxLines
                )
                if (
                    reconciledStake.lineBet != persistedBonus.lineBet ||
                    reconciledStake.lines != persistedBonus.lines
                ) {
                    bonuses[slotId] = persistedBonus.copy(
                        lineBet = reconciledStake.lineBet,
                        lines = reconciledStake.lines
                    )
                    preferences.writeFreeSpinBonuses(bonuses)
                }
                return@editPlayerState
            }

            if (bonuses.isNotEmpty()) return@editPlayerState
            val legacyCount = preferences[Keys.FreeSpinsBalance] ?: 0
            val legacySlotId = preferences[Keys.FreeSpinSlotId].orEmpty()
            if (legacyCount <= 0 || (legacySlotId.isNotBlank() && legacySlotId != slotId)) {
                return@editPlayerState
            }
            val reconciledStake = reconciledFreeSpinStake(
                lockedLineBet = (preferences[Keys.FreeSpinBet] ?: 0)
                    .takeIf { it > 0 }
                    ?: (preferences[Keys.SelectedBet] ?: PlayerState.DEFAULT_BET),
                lockedLines = (preferences[Keys.FreeSpinLines] ?: 0)
                    .takeIf { it > 0 }
                    ?: (preferences[Keys.SelectedLines] ?: PlayerState.DEFAULT_LINES),
                supportedBets = validatedBets,
                maxLines = maxLines
            )
            preferences.writeFreeSpinBonuses(
                mapOf(
                    slotId to FreeSpinBonus(
                        slotId = slotId,
                        count = legacyCount,
                        lineBet = reconciledStake.lineBet,
                        lines = reconciledStake.lines
                    )
                )
            )
        }.toPlayerState()
    }

    override suspend fun updateFreeSpinAutoPlay(slotId: String, enabled: Boolean) {
        if (slotId.isBlank()) return
        editPlayerState { preferences ->
            val slots = preferences.readFreeSpinAutoPlaySlots().toMutableSet()
            if (enabled) {
                slots.add(slotId)
            } else {
                slots.remove(slotId)
            }
            preferences.writeFreeSpinAutoPlaySlots(slots)
        }
    }

    override suspend fun awardLevelXp(amount: Int) {
        if (amount <= 0) return
        editPlayerState { preferences ->
            val currentXp = (preferences[Keys.LevelXp] ?: 0).coerceIn(0, PlayerState.maxLevelXp())
            val awardedXp = currentXp.toLong() + amount.toLong()
            preferences[Keys.LevelXp] = awardedXp
                .coerceIn(0L, PlayerState.maxLevelXp().toLong())
                .toInt()
        }
    }

    override suspend fun updateSoundEnabled(enabled: Boolean) {
        editPlayerState { it[Keys.SoundEnabled] = enabled }
    }

    override suspend fun updateHapticsEnabled(enabled: Boolean) {
        editPlayerState { it[Keys.HapticsEnabled] = enabled }
    }

    override suspend fun updateAnalyticsEnabled(enabled: Boolean) {
        editPlayerState { it[Keys.AnalyticsEnabled] = enabled }
    }

    suspend fun claimDailyBonus(now: Long = System.currentTimeMillis()): DailyBonusClaimResult {
        var claimed = false
        var creditedAmount = 0
        var balanceAfter = PlayerState.STARTING_BALANCE
        editPlayerState { preferences ->
            val safeNow = now.coerceAtLeast(0L)
            val persistedLastClaim = preferences[Keys.LastDailyBonusTimestamp] ?: 0L
            val lastClaim = PlayerState.normalizedDailyBonusTimestamp(persistedLastClaim)
            val currentBalance = preferences.readPersistedCoinsBalance()

            if (lastClaim != persistedLastClaim) {
                preferences[Keys.LastDailyBonusTimestamp] = lastClaim
            }
            if (safeNow == 0L) {
                balanceAfter = currentBalance
                return@editPlayerState
            }
            if (PlayerState.dailyBonusRemainingMs(lastClaim, safeNow) <= 0L) {
                balanceAfter = saturatedNonNegativeAdd(currentBalance, PlayerState.DAILY_BONUS_AMOUNT)
                creditedAmount = (balanceAfter - currentBalance).toInt()
                preferences.writePersistedCoinsBalance(balanceAfter)
                preferences[Keys.LastDailyBonusTimestamp] = safeNow
                claimed = true
            } else {
                balanceAfter = currentBalance
            }
        }
        return DailyBonusClaimResult(
            claimed = claimed,
            amount = if (claimed) creditedAmount else 0,
            balanceAfter = balanceAfter
        )
    }

    override suspend fun markPushPermissionAsked() {
        editPlayerState { it[Keys.PushPermissionAsked] = true }
    }

    private suspend fun editPlayerState(
        transform: suspend (MutablePreferences) -> Unit
    ): Preferences {
        val updatedCheckpoint = stateStore.update { checkpoint ->
            val preferences = preferencesFromCheckpoint(checkpoint).toMutablePreferences()
            val valuesBefore = preferences.asMap().toMap()
            preferences.migrateCoinsBalance()
            transform(preferences)
            val changed = preferences.asMap() != valuesBefore
            if (changed) {
                val currentRevision = preferences[Keys.Revision] ?: 0L
                preferences[Keys.Revision] = nextPlayerStateRevision(currentRevision)
            }
            preferences.toCheckpoint()
        }
        return preferencesFromCheckpoint(updatedCheckpoint)
    }

    private suspend fun migrateLegacyPlayerState(): PlayerStateCheckpoint {
        val legacyPreferences = legacyDataStore.data
            .retryTransientPersistenceReads(
                fallbackValue = {
                    legacyCheckpointStore.read()
                        ?.let(::preferencesFromCheckpoint)
                        ?: emptyPreferences()
                }
            )
            .first()
        val normalizedPreferences = legacyPreferences.toMutablePreferences().apply {
            migrateCoinsBalance()
        }.toPreferences()
        val dataStoreCheckpoint = normalizedPreferences.toCheckpoint()
        val legacyCheckpoint = legacyCheckpointStore.read()
        return legacyCheckpoint
            ?.takeIf { it.generation > dataStoreCheckpoint.generation }
            ?: dataStoreCheckpoint
    }

    private fun Preferences.toCheckpoint(): PlayerStateCheckpoint {
        return PlayerStateCheckpoint(
            generation = this[Keys.Revision] ?: 0L,
            playerState = toPlayerState(),
            rawPendingSpinSettlement = this[Keys.PendingSpinSettlement],
            rawPendingSpinRefundEnvelope = this[Keys.PendingSpinRefundEnvelope],
            rawPendingSpinPresentation = this[Keys.PendingSpinPresentation],
            migrationComplete = true
        )
    }

    internal object Keys {
        val CoinsBalanceLong = longPreferencesKey("coinsBalanceLong")
        val LegacyCoinsBalance = intPreferencesKey("coinsBalance")
        val LastDailyBonusTimestamp = longPreferencesKey("lastDailyBonusTimestamp")
        val SelectedBet = intPreferencesKey("selectedBet")
        val SelectedLines = intPreferencesKey("selectedLines")
        val FreeSpinsBalance = intPreferencesKey("freeSpinsBalance")
        val FreeSpinBet = intPreferencesKey("freeSpinBet")
        val FreeSpinLines = intPreferencesKey("freeSpinLines")
        val FreeSpinSlotId = stringPreferencesKey("freeSpinSlotId")
        val FreeSpinBonuses = stringPreferencesKey("freeSpinBonuses")
        val FreeSpinAutoPlaySlots = stringSetPreferencesKey("freeSpinAutoPlaySlots")
        val LevelXp = intPreferencesKey("levelXp")
        val DisclaimerAccepted = booleanPreferencesKey("disclaimerAccepted")
        val PushPermissionAsked = booleanPreferencesKey("pushPermissionAsked")
        val SoundEnabled = booleanPreferencesKey("soundEnabled")
        val HapticsEnabled = booleanPreferencesKey("hapticsEnabled")
        val AnalyticsEnabled = booleanPreferencesKey("analyticsEnabled")
        val LastPlayedSlot = stringPreferencesKey("lastPlayedSlot")
        val PendingSpinSettlement = stringPreferencesKey("pendingSpinSettlement")
        val PendingSpinRefundEnvelope = stringPreferencesKey("pendingSpinRefundEnvelope")
        val PendingSpinPresentation = stringPreferencesKey("pendingSpinPresentation")
        val Revision = longPreferencesKey("stateRevision")
    }

    private fun Preferences.toPlayerState(): PlayerState {
        val freeSpinBonuses = readFreeSpinBonuses()
        val legacyFreeSpinsBalance = this[Keys.FreeSpinsBalance] ?: 0
        val firstBonus = freeSpinBonuses.values.firstOrNull()
        return PlayerState(
            coinsBalance = readPersistedCoinsBalance(),
            lastDailyBonusTimestamp = this[Keys.LastDailyBonusTimestamp] ?: 0L,
            selectedBet = this[Keys.SelectedBet] ?: PlayerState.DEFAULT_BET,
            selectedLines = this[Keys.SelectedLines] ?: PlayerState.DEFAULT_LINES,
            freeSpinsBalance = if (freeSpinBonuses.isNotEmpty()) {
                saturatedNonNegativeSum(freeSpinBonuses.values.map { it.count })
            } else {
                legacyFreeSpinsBalance
            },
            freeSpinBet = firstBonus?.lineBet ?: (this[Keys.FreeSpinBet] ?: 0),
            freeSpinLines = firstBonus?.lines ?: (this[Keys.FreeSpinLines] ?: 0),
            freeSpinSlotId = firstBonus?.slotId ?: this[Keys.FreeSpinSlotId].orEmpty(),
            freeSpinBonuses = freeSpinBonuses,
            freeSpinAutoPlaySlots = readFreeSpinAutoPlaySlots(),
            levelXp = this[Keys.LevelXp] ?: 0,
            disclaimerAccepted = this[Keys.DisclaimerAccepted] ?: false,
            pushPermissionAsked = this[Keys.PushPermissionAsked] ?: false,
            soundEnabled = this[Keys.SoundEnabled] ?: true,
            hapticsEnabled = this[Keys.HapticsEnabled] ?: true,
            analyticsEnabled = this[Keys.AnalyticsEnabled] ?: false,
            lastPlayedSlot = this[Keys.LastPlayedSlot] ?: PlayerState.DEFAULT_SLOT_ID
        ).normalized()
    }

    private fun MutablePreferences.persistedPresentationWinAmount(settlementId: String): Int? {
        val serialized = this[Keys.PendingSpinPresentation] ?: return null
        val presentation = deserializePendingSpinPresentation(serialized)
        val verified = presentation?.settlement?.let(settlementVerifier::verify)
        if (verified == null) {
            remove(Keys.PendingSpinPresentation)
            return null
        }
        return verified
            .takeIf { it.id == settlementId }
            ?.winAmount
    }

    private fun MutablePreferences.debitSpinBet(totalBet: Int): Boolean {
        if (totalBet <= 0) return false
        val currentBalance = readPersistedCoinsBalance()
        if (currentBalance < totalBet.toLong()) return false

        writePersistedCoinsBalance(currentBalance - totalBet.toLong())
        return true
    }

    private fun MutablePreferences.consumeFreeSpin(
        slotId: String,
        preserveAutoPlayMarker: Boolean = false
    ): Boolean {
        val bonuses = readFreeSpinBonuses().toMutableMap()
        val currentBonus = bonuses[slotId]
        if (currentBonus != null) {
            val remainingFreeSpins = (currentBonus.count - 1).coerceAtLeast(0)
            if (remainingFreeSpins > 0) {
                bonuses[slotId] = currentBonus.copy(count = remainingFreeSpins)
            } else {
                bonuses.remove(slotId)
            }
            writeFreeSpinBonuses(bonuses)
            if (!preserveAutoPlayMarker && !hasFreeSpinForSlot(slotId)) {
                removeFreeSpinAutoPlaySlot(slotId)
            }
            return true
        }

        val currentFreeSpins = this[Keys.FreeSpinsBalance] ?: 0
        if (currentFreeSpins <= 0) return false
        val legacySlotId = this[Keys.FreeSpinSlotId].orEmpty()
        if (legacySlotId.isNotBlank() && legacySlotId != slotId) return false

        val remainingFreeSpins = (currentFreeSpins - 1).coerceAtLeast(0)
        if (remainingFreeSpins > 0) {
            this[Keys.FreeSpinsBalance] = remainingFreeSpins
        } else {
            clearFreeSpinMirror()
        }
        if (!preserveAutoPlayMarker && remainingFreeSpins <= 0) {
            removeFreeSpinAutoPlaySlot(slotId)
        }
        return true
    }

    private fun MutablePreferences.hasFreeSpinForSlot(slotId: String): Boolean {
        val currentBonus = readFreeSpinBonuses()[slotId]
        if (currentBonus != null) return currentBonus.count > 0

        val legacyCount = this[Keys.FreeSpinsBalance] ?: 0
        if (legacyCount <= 0) return false
        val legacySlotId = this[Keys.FreeSpinSlotId].orEmpty()
        return legacySlotId.isBlank() || legacySlotId == slotId
    }

    private fun androidx.datastore.preferences.core.Preferences.freeSpinStakeMatches(
        slotId: String,
        lineBet: Int,
        lines: Int
    ): Boolean {
        val currentBonus = readFreeSpinBonuses()[slotId]
        if (currentBonus != null) {
            return currentBonus.lineBet == lineBet && currentBonus.lines == lines
        }

        val legacyCount = this[Keys.FreeSpinsBalance] ?: 0
        if (legacyCount <= 0) return false
        val legacySlotId = this[Keys.FreeSpinSlotId].orEmpty()
        return (legacySlotId.isBlank() || legacySlotId == slotId) &&
            this[Keys.FreeSpinBet] == lineBet &&
            this[Keys.FreeSpinLines] == lines
    }

    private fun MutablePreferences.applyPersistedPendingSpinSettlement(
        currentProcessSessionId: String? = null
    ): Boolean {
        val serialized = this[Keys.PendingSpinSettlement] ?: return false
        val decodedSettlement = deserializePendingSpinSettlement(serialized)
        val settlement = decodedSettlement?.let(settlementVerifier::verify)
        if (settlement == null) {
            refundAndClearVoidedSpin(decodedSettlement)
            return false
        }
        if (shouldDeferPendingSpinRecovery(settlement, currentProcessSessionId)) return false
        // Reservation rollover belongs to the spin's process; explicit cross-process recovery is unclaimed.
        applySpinSettlement(
            settlement,
            claimedByProcessSessionId = if (currentProcessSessionId == null) {
                settlement.processSessionId
            } else {
                null
            }
        )
        return true
    }

    private fun MutablePreferences.refundAndClearVoidedSpin(
        decodedSettlement: PendingSpinSettlement?
    ) {
        val refundEnvelope = this[Keys.PendingSpinRefundEnvelope]
            ?.let(::deserializePendingSpinRefundEnvelope)
            ?: decodedSettlement?.toRefundEnvelope()
        refundEnvelope?.let { refund -> refundVoidedSpin(refund) }
        remove(Keys.PendingSpinSettlement)
        remove(Keys.PendingSpinRefundEnvelope)
    }

    private fun MutablePreferences.refundVoidedSpin(refund: PendingSpinRefundEnvelope) {
        if (refund.isFreeSpin) {
            val restoredBonuses = mergeAwardedFreeSpinBonus(
                currentBonuses = readFreeSpinBonuses(),
                legacySlotId = this[Keys.FreeSpinSlotId].orEmpty(),
                legacyCount = this[Keys.FreeSpinsBalance] ?: 0,
                legacyLineBet = this[Keys.FreeSpinBet] ?: 0,
                legacyLines = this[Keys.FreeSpinLines] ?: 0,
                awardSlotId = refund.slotId,
                awardCount = 1,
                awardLineBet = refund.lineBet,
                awardLines = refund.lines
            )
            writeFreeSpinBonuses(restoredBonuses)
            return
        }
        val currentBalance = readPersistedCoinsBalance()
        writePersistedCoinsBalance(
            saturatedNonNegativeAdd(currentBalance, refund.totalBet)
        )
        if (!hasFreeSpinForSlot(refund.slotId)) {
            removeFreeSpinAutoPlaySlot(refund.slotId)
        }
    }

    private fun MutablePreferences.applySpinSettlement(
        settlement: PendingSpinSettlement,
        claimedByProcessSessionId: String?
    ): Int {
        require(settlement.validVisualResultOrNull() != null) {
            "Cannot apply an unverified spin settlement."
        }
        val currentBalance = readPersistedCoinsBalance()
        val updatedBalance = saturatedNonNegativeAdd(currentBalance, settlement.winAmount)
        writePersistedCoinsBalance(updatedBalance)

        if (settlement.freeSpinsAwarded > 0) {
            val bonuses = mergeAwardedFreeSpinBonus(
                currentBonuses = readFreeSpinBonuses(),
                legacySlotId = this[Keys.FreeSpinSlotId].orEmpty(),
                legacyCount = this[Keys.FreeSpinsBalance] ?: 0,
                legacyLineBet = this[Keys.FreeSpinBet] ?: 0,
                legacyLines = this[Keys.FreeSpinLines] ?: 0,
                awardSlotId = settlement.slotId,
                awardCount = settlement.freeSpinsAwarded,
                awardLineBet = settlement.lineBet,
                awardLines = settlement.lines
            )
            writeFreeSpinBonuses(bonuses)
        }

        val autoPlaySlots = readFreeSpinAutoPlaySlots().toMutableSet().apply {
            if (settlement.isFreeSpin && !hasFreeSpinForSlot(settlement.slotId)) {
                remove(settlement.slotId)
            }
        }
        writeFreeSpinAutoPlaySlots(autoPlaySlots)

        val currentXp = (this[Keys.LevelXp] ?: 0).coerceIn(0, PlayerState.maxLevelXp())
        this[Keys.LevelXp] = (currentXp.toLong() + settlement.levelXpAwarded.toLong())
            .coerceIn(0L, PlayerState.maxLevelXp().toLong())
            .toInt()
        this[Keys.PendingSpinPresentation] = settlement.serializePresentation(
            claimedByProcessSessionId = claimedByProcessSessionId
        )
        remove(Keys.PendingSpinSettlement)
        remove(Keys.PendingSpinRefundEnvelope)
        return settlement.winAmount
    }

    private fun androidx.datastore.preferences.core.Preferences.readFreeSpinBonuses(): Map<String, FreeSpinBonus> {
        val serialized = this[Keys.FreeSpinBonuses].orEmpty()
        if (serialized.isBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(serialized)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val slotId = item.optString("slotId")
                    val count = item.optInt("count", 0)
                    val lineBet = item.optInt("lineBet", 0)
                    val lines = item.optInt("lines", 0)
                    if (slotId.isNotBlank() && count > 0 && lineBet > 0 && lines > 0) {
                        put(
                            slotId,
                            FreeSpinBonus(
                                slotId = slotId,
                                count = count,
                                lineBet = lineBet,
                                lines = lines
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun MutablePreferences.writeFreeSpinBonuses(bonuses: Map<String, FreeSpinBonus>) {
        val normalizedBonuses = normalizedFreeSpinBonuses(bonuses)

        if (normalizedBonuses.isEmpty()) {
            remove(Keys.FreeSpinBonuses)
            clearFreeSpinMirror()
            return
        }

        this[Keys.FreeSpinBonuses] = JSONArray().apply {
            normalizedBonuses.forEach { bonus ->
                put(
                    JSONObject()
                        .put("slotId", bonus.slotId)
                        .put("count", bonus.count)
                        .put("lineBet", bonus.lineBet)
                        .put("lines", bonus.lines)
                )
            }
        }.toString()

        val firstBonus = normalizedBonuses.first()
        this[Keys.FreeSpinsBalance] = saturatedNonNegativeSum(normalizedBonuses.map { it.count })
        this[Keys.FreeSpinBet] = firstBonus.lineBet
        this[Keys.FreeSpinLines] = firstBonus.lines
        this[Keys.FreeSpinSlotId] = firstBonus.slotId
    }

    private fun androidx.datastore.preferences.core.Preferences.readFreeSpinAutoPlaySlots(): Set<String> {
        return this[Keys.FreeSpinAutoPlaySlots]
            .orEmpty()
            .filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    private fun MutablePreferences.writeFreeSpinAutoPlaySlots(slots: Set<String>) {
        val normalizedSlots = slots.filterTo(mutableSetOf()) { it.isNotBlank() }
        if (normalizedSlots.isEmpty()) {
            remove(Keys.FreeSpinAutoPlaySlots)
        } else {
            this[Keys.FreeSpinAutoPlaySlots] = normalizedSlots
        }
    }

    private fun MutablePreferences.removeFreeSpinAutoPlaySlot(slotId: String) {
        val slots = readFreeSpinAutoPlaySlots().toMutableSet()
        if (slots.remove(slotId)) {
            writeFreeSpinAutoPlaySlots(slots)
        }
    }

    private fun MutablePreferences.clearFreeSpinMirror() {
        this[Keys.FreeSpinsBalance] = 0
        remove(Keys.FreeSpinBet)
        remove(Keys.FreeSpinLines)
        remove(Keys.FreeSpinSlotId)
    }

    internal companion object {
        fun preferencesFromCheckpoint(checkpoint: PlayerStateCheckpoint): Preferences {
            val state = checkpoint.playerState.normalized()
            val preferences = emptyPreferences().toMutablePreferences()
            preferences[Keys.CoinsBalanceLong] = state.coinsBalance
            preferences[Keys.LastDailyBonusTimestamp] = state.lastDailyBonusTimestamp
            preferences[Keys.SelectedBet] = state.selectedBet
            preferences[Keys.SelectedLines] = state.selectedLines
            preferences[Keys.LevelXp] = state.levelXp
            preferences[Keys.DisclaimerAccepted] = state.disclaimerAccepted
            preferences[Keys.PushPermissionAsked] = state.pushPermissionAsked
            preferences[Keys.SoundEnabled] = state.soundEnabled
            preferences[Keys.HapticsEnabled] = state.hapticsEnabled
            preferences[Keys.AnalyticsEnabled] = state.analyticsEnabled
            preferences[Keys.LastPlayedSlot] = state.lastPlayedSlot
            preferences[Keys.Revision] = checkpoint.generation

            val bonuses = normalizedFreeSpinBonuses(state.freeSpinBonuses)
            if (bonuses.isNotEmpty()) {
                preferences[Keys.FreeSpinBonuses] = JSONArray().apply {
                    bonuses.forEach { bonus ->
                        put(
                            JSONObject()
                                .put("slotId", bonus.slotId)
                                .put("count", bonus.count)
                                .put("lineBet", bonus.lineBet)
                                .put("lines", bonus.lines)
                        )
                    }
                }.toString()
                val firstBonus = bonuses.first()
                preferences[Keys.FreeSpinsBalance] = saturatedNonNegativeSum(bonuses.map { it.count })
                preferences[Keys.FreeSpinBet] = firstBonus.lineBet
                preferences[Keys.FreeSpinLines] = firstBonus.lines
                preferences[Keys.FreeSpinSlotId] = firstBonus.slotId
            } else if (state.freeSpinsBalance > 0) {
                preferences[Keys.FreeSpinsBalance] = state.freeSpinsBalance
                if (state.freeSpinBet > 0) preferences[Keys.FreeSpinBet] = state.freeSpinBet
                if (state.freeSpinLines > 0) preferences[Keys.FreeSpinLines] = state.freeSpinLines
                if (state.freeSpinSlotId.isNotBlank()) {
                    preferences[Keys.FreeSpinSlotId] = state.freeSpinSlotId
                }
            }
            if (state.freeSpinAutoPlaySlots.isNotEmpty()) {
                preferences[Keys.FreeSpinAutoPlaySlots] = state.freeSpinAutoPlaySlots
            }
            checkpoint.rawPendingSpinSettlement?.let { serialized ->
                preferences[Keys.PendingSpinSettlement] = serialized
            }
            checkpoint.rawPendingSpinRefundEnvelope?.let { serialized ->
                preferences[Keys.PendingSpinRefundEnvelope] = serialized
            }
            checkpoint.rawPendingSpinPresentation?.let { serialized ->
                preferences[Keys.PendingSpinPresentation] = serialized
            }
            return preferences.toPreferences()
        }
    }
}

internal fun Preferences.readPersistedCoinsBalance(): Long {
    return (
        this[PlayerRepository.Keys.CoinsBalanceLong]
            ?: this[PlayerRepository.Keys.LegacyCoinsBalance]?.toLong()
            ?: PlayerState.STARTING_BALANCE
        ).coerceAtLeast(0L)
}

internal fun MutablePreferences.writePersistedCoinsBalance(balance: Long) {
    this[PlayerRepository.Keys.CoinsBalanceLong] = balance.coerceAtLeast(0L)
    remove(PlayerRepository.Keys.LegacyCoinsBalance)
}

internal fun MutablePreferences.migrateCoinsBalance() {
    writePersistedCoinsBalance(readPersistedCoinsBalance())
}

internal fun MutablePreferences.clearPlayerStatePreservingRevision() {
    val revision = this[PlayerRepository.Keys.Revision]
    clear()
    if (revision != null) {
        this[PlayerRepository.Keys.Revision] = revision
    }
}

internal fun nextPlayerStateRevision(currentRevision: Long): Long {
    require(currentRevision >= 0L) { "Player state revision cannot be negative." }
    check(currentRevision < Long.MAX_VALUE) { "Player state revision overflow." }
    return currentRevision + 1L
}

internal fun shouldDeferPendingSpinRecovery(
    settlement: PendingSpinSettlement,
    currentProcessSessionId: String?
): Boolean {
    return currentProcessSessionId.belongsToProcessSession(settlement.processSessionId) &&
        ProcessSession.isSpinSettlementActive(settlement.id)
}

internal fun String?.belongsToProcessSession(processSessionId: String): Boolean {
    if (this == null || processSessionId.isBlank()) return false
    return this == processSessionId || startsWith("$processSessionId:")
}

private const val PLAYER_DATA_STORE_NAME = "v_slot_player"
private const val DATA_STORE_RETRY_BASE_DELAY_MS = 100L
private const val DATA_STORE_RETRY_MAX_DELAY_MS = 5_000L
private const val DATA_STORE_MAX_RETRY_EXPONENT = 6
private const val DATA_STORE_READ_FALLBACK_ATTEMPTS = 3
private const val DATA_STORE_WRITE_RETRY_ATTEMPTS = 2
