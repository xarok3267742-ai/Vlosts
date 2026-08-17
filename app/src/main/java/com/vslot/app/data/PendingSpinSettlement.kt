package com.vslot.app.data

import com.vslot.app.game.CanonicalPayloadWriter
import com.vslot.app.game.SpinResult
import com.vslot.app.game.sha256Hex
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class PendingSpinSettlement(
    val id: String,
    val processSessionId: String,
    val slotId: String,
    val isFreeSpin: Boolean,
    val lineBet: Int,
    val lines: Int,
    val totalBet: Int,
    val winAmount: Int,
    val freeSpinsAwarded: Int,
    val levelXpAwarded: Int,
    val mathVersion: Int,
    val configFingerprint: String,
    val stopIndexes: List<Int>,
    val visualResult: SpinResult? = null
)

internal data class PendingSpinPresentation(
    val settlement: PendingSpinSettlement,
    val claimedByProcessSessionId: String?
)

internal data class PendingSpinRefundEnvelope(
    val settlementId: String,
    val slotId: String,
    val isFreeSpin: Boolean,
    val lineBet: Int,
    val lines: Int,
    val totalBet: Int
) {
    fun isValid(): Boolean {
        return settlementId.isValidIdentifier() &&
            slotId.isValidIdentifier() &&
            lineBet > 0 &&
            lines in PlayerState.MIN_LINES..PlayerState.MAX_LINES &&
            totalBet > 0 &&
            lineBet.toLong() * lines.toLong() == totalBet.toLong()
    }
}

data class SpinReservation<T>(
    val settlement: PendingSpinSettlement,
    val value: T
)

sealed interface SpinReservationAttempt<out T> {
    data class Reserved<T>(val reservation: SpinReservation<T>) : SpinReservationAttempt<T>
    data class BlockedByPendingSpin(
        val recoveryStatus: PendingSpinRecoveryStatus
    ) : SpinReservationAttempt<Nothing>
    data object Rejected : SpinReservationAttempt<Nothing>
}

data class SpinSettlementReceipt(
    val updatedState: PlayerState,
    val creditedWinAmount: Int,
    val applied: Boolean,
    val outcomeSettled: Boolean = applied
)

internal sealed interface PendingSpinJournalDecode {
    data class Decoded(val settlement: PendingSpinSettlement) : PendingSpinJournalDecode
    data class UnsupportedFormat(val version: Int) : PendingSpinJournalDecode
    data object Corrupt : PendingSpinJournalDecode
}

internal data class FreeSpinStake(
    val lineBet: Int,
    val lines: Int
) {
    val totalBet: Long
        get() = lineBet.toLong() * lines.toLong()
}

internal fun reconciledFreeSpinStake(
    lockedLineBet: Int,
    lockedLines: Int,
    supportedBets: List<Int>,
    maxLines: Int
): FreeSpinStake {
    val bets = supportedBets.filter { it > 0 }.distinct().sorted()
    require(bets.isNotEmpty()) { "At least one positive slot bet is required." }
    require(maxLines in PlayerState.MIN_LINES..PlayerState.MAX_LINES) {
        "Slot paylines are outside the supported range."
    }

    if (lockedLineBet in bets && lockedLines in PlayerState.MIN_LINES..maxLines) {
        return FreeSpinStake(lockedLineBet, lockedLines)
    }

    val lockedTotalBet = lockedLineBet.coerceAtLeast(0).toLong() *
        lockedLines.coerceAtLeast(0).toLong()
    return bets
        .flatMap { bet ->
            (PlayerState.MIN_LINES..maxLines).map { lines -> FreeSpinStake(bet, lines) }
        }
        .minWithOrNull(
            compareBy<FreeSpinStake> { stake ->
                kotlin.math.abs(stake.totalBet - lockedTotalBet)
            }.thenBy { stake ->
                if (stake.totalBet >= lockedTotalBet) 0 else 1
            }.thenBy { stake ->
                kotlin.math.abs(stake.lines.toLong() - lockedLines.toLong())
            }.thenBy { stake ->
                kotlin.math.abs(stake.lineBet.toLong() - lockedLineBet.toLong())
            }.thenBy(FreeSpinStake::lineBet)
                .thenBy(FreeSpinStake::lines)
        )
        ?: error("A validated slot stake catalog cannot be empty.")
}

internal fun PendingSpinSettlement.isValid(): Boolean {
    return id.isValidIdentifier() &&
        processSessionId.isValidIdentifier() &&
        slotId.isValidIdentifier() &&
        mathVersion > 0 &&
        CONFIG_FINGERPRINT_PATTERN.matches(configFingerprint) &&
        lineBet > 0 &&
        lines > 0 &&
        totalBet > 0 &&
        lineBet.toLong() * lines.toLong() == totalBet.toLong() &&
        winAmount >= 0 &&
        freeSpinsAwarded >= 0 &&
        levelXpAwarded >= 0 &&
        stopIndexes.size == REQUIRED_STOP_INDEXES &&
        stopIndexes.all { it >= 0 }
}

internal fun PendingSpinSettlement.matchesReservation(
    slotId: String,
    isFreeSpin: Boolean,
    lineBet: Int,
    lines: Int,
    totalBet: Int
): Boolean {
    return isValid() &&
        this.slotId == slotId &&
        this.isFreeSpin == isFreeSpin &&
        this.lineBet == lineBet &&
        this.lines == lines &&
        this.totalBet == totalBet
}

internal fun PendingSpinSettlement.serialize(): String {
    require(isValid()) { "Cannot persist an invalid spin settlement." }
    return JSONObject()
        .put(KEY_VERSION, PENDING_SPIN_JOURNAL_VERSION)
        .put(KEY_ID, id)
        .put(KEY_PROCESS_SESSION_ID, processSessionId)
        .put(KEY_SLOT_ID, slotId)
        .put(KEY_MATH_VERSION, mathVersion)
        .put(KEY_CONFIG_FINGERPRINT, configFingerprint)
        .put(KEY_IS_FREE_SPIN, isFreeSpin)
        .put(KEY_LINE_BET, lineBet)
        .put(KEY_LINES, lines)
        .put(KEY_TOTAL_BET, totalBet)
        .put(KEY_STOP_INDEXES, JSONArray(stopIndexes))
        .put(KEY_WIN_AMOUNT, winAmount)
        .put(KEY_FREE_SPINS_AWARDED, freeSpinsAwarded)
        .put(KEY_LEVEL_XP_AWARDED, levelXpAwarded)
        .put(KEY_CHECKSUM, journalChecksum())
        .toString()
}

internal fun PendingSpinSettlement.toRefundEnvelope(): PendingSpinRefundEnvelope {
    return PendingSpinRefundEnvelope(
        settlementId = id,
        slotId = slotId,
        isFreeSpin = isFreeSpin,
        lineBet = lineBet,
        lines = lines,
        totalBet = totalBet
    )
}

internal fun PendingSpinRefundEnvelope.serialize(): String {
    require(isValid()) { "Cannot persist an invalid spin refund envelope." }
    return JSONObject()
        .put(KEY_REFUND_VERSION, REFUND_ENVELOPE_VERSION)
        .put(KEY_REFUND_SETTLEMENT_ID, settlementId)
        .put(KEY_REFUND_SLOT_ID, slotId)
        .put(KEY_REFUND_IS_FREE_SPIN, isFreeSpin)
        .put(KEY_REFUND_LINE_BET, lineBet)
        .put(KEY_REFUND_LINES, lines)
        .put(KEY_REFUND_TOTAL_BET, totalBet)
        .put(KEY_REFUND_CHECKSUM, refundChecksum())
        .toString()
}

internal fun deserializePendingSpinRefundEnvelope(serialized: String): PendingSpinRefundEnvelope? {
    if (serialized.isBlank() || serialized.length > MAX_SERIALIZED_JOURNAL_CHARS) return null
    return runCatching {
        val json = JSONObject(serialized)
        if (!json.hasExactly(REFUND_ENVELOPE_KEYS)) return@runCatching null
        if (json.requiredInt(KEY_REFUND_VERSION) != REFUND_ENVELOPE_VERSION) return@runCatching null
        val envelope = PendingSpinRefundEnvelope(
            settlementId = json.requiredString(KEY_REFUND_SETTLEMENT_ID) ?: return@runCatching null,
            slotId = json.requiredString(KEY_REFUND_SLOT_ID) ?: return@runCatching null,
            isFreeSpin = json.requiredBoolean(KEY_REFUND_IS_FREE_SPIN) ?: return@runCatching null,
            lineBet = json.requiredInt(KEY_REFUND_LINE_BET) ?: return@runCatching null,
            lines = json.requiredInt(KEY_REFUND_LINES) ?: return@runCatching null,
            totalBet = json.requiredInt(KEY_REFUND_TOTAL_BET) ?: return@runCatching null
        )
        if (!envelope.isValid()) return@runCatching null
        val checksum = json.requiredString(KEY_REFUND_CHECKSUM)
            ?.takeIf(CHECKSUM_PATTERN::matches)
            ?: return@runCatching null
        if (!checksumsMatch(checksum, envelope.refundChecksum())) return@runCatching null
        envelope
    }.getOrNull()
}

private fun PendingSpinRefundEnvelope.refundChecksum(): String {
    val payload = CanonicalPayloadWriter().apply {
        writeString(REFUND_ENVELOPE_DOMAIN)
        writeInt(REFUND_ENVELOPE_VERSION)
        writeString(settlementId)
        writeString(slotId)
        writeBoolean(isFreeSpin)
        writeInt(lineBet)
        writeInt(lines)
        writeInt(totalBet)
    }.toByteArray()
    return sha256Hex(payload)
}

internal fun decodePendingSpinSettlement(serialized: String): PendingSpinJournalDecode {
    if (serialized.isBlank() || serialized.length > MAX_SERIALIZED_JOURNAL_CHARS) {
        return PendingSpinJournalDecode.Corrupt
    }
    return try {
        val json = JSONObject(serialized)
        val version = json.requiredInt(KEY_VERSION)
            ?: return PendingSpinJournalDecode.Corrupt
        if (version != PENDING_SPIN_JOURNAL_VERSION) {
            return PendingSpinJournalDecode.UnsupportedFormat(version)
        }
        if (!json.hasExactly(JOURNAL_KEYS)) return PendingSpinJournalDecode.Corrupt
        val settlement = PendingSpinSettlement(
            id = json.requiredString(KEY_ID) ?: return PendingSpinJournalDecode.Corrupt,
            processSessionId = json.requiredString(KEY_PROCESS_SESSION_ID)
                ?: return PendingSpinJournalDecode.Corrupt,
            slotId = json.requiredString(KEY_SLOT_ID) ?: return PendingSpinJournalDecode.Corrupt,
            mathVersion = json.requiredInt(KEY_MATH_VERSION)
                ?: return PendingSpinJournalDecode.Corrupt,
            configFingerprint = json.requiredString(KEY_CONFIG_FINGERPRINT)
                ?: return PendingSpinJournalDecode.Corrupt,
            isFreeSpin = json.requiredBoolean(KEY_IS_FREE_SPIN)
                ?: return PendingSpinJournalDecode.Corrupt,
            lineBet = json.requiredInt(KEY_LINE_BET) ?: return PendingSpinJournalDecode.Corrupt,
            lines = json.requiredInt(KEY_LINES) ?: return PendingSpinJournalDecode.Corrupt,
            totalBet = json.requiredInt(KEY_TOTAL_BET) ?: return PendingSpinJournalDecode.Corrupt,
            stopIndexes = json.requiredIntList(KEY_STOP_INDEXES, REQUIRED_STOP_INDEXES)
                ?: return PendingSpinJournalDecode.Corrupt,
            winAmount = json.requiredInt(KEY_WIN_AMOUNT) ?: return PendingSpinJournalDecode.Corrupt,
            freeSpinsAwarded = json.requiredInt(KEY_FREE_SPINS_AWARDED)
                ?: return PendingSpinJournalDecode.Corrupt,
            levelXpAwarded = json.requiredInt(KEY_LEVEL_XP_AWARDED)
                ?: return PendingSpinJournalDecode.Corrupt
        )
        if (!settlement.isValid()) return PendingSpinJournalDecode.Corrupt
        val checksum = json.requiredString(KEY_CHECKSUM)
            ?.takeIf(CHECKSUM_PATTERN::matches)
            ?: return PendingSpinJournalDecode.Corrupt
        if (!checksumsMatch(checksum, settlement.journalChecksum())) {
            return PendingSpinJournalDecode.Corrupt
        }
        PendingSpinJournalDecode.Decoded(settlement)
    } catch (_: Exception) {
        PendingSpinJournalDecode.Corrupt
    }
}

internal fun deserializePendingSpinSettlement(serialized: String): PendingSpinSettlement? {
    return (decodePendingSpinSettlement(serialized) as? PendingSpinJournalDecode.Decoded)
        ?.settlement
}

internal fun PendingSpinSettlement.serializePresentation(
    claimedByProcessSessionId: String?
): String {
    require(validVisualResultOrNull() != null) {
        "Cannot persist a spin presentation without a verified visual result."
    }
    require(claimedByProcessSessionId == null || claimedByProcessSessionId.isValidIdentifier()) {
        "Cannot persist a spin presentation with an invalid process session id."
    }
    return JSONObject()
        .put(KEY_VERSION, PRESENTATION_VERSION)
        .put(KEY_PRESENTATION_SETTLEMENT, JSONObject(serialize()))
        .apply {
            claimedByProcessSessionId?.let { processSessionId ->
                put(KEY_CLAIMED_BY_PROCESS_SESSION_ID, processSessionId)
            }
        }
        .toString()
}

internal fun deserializePendingSpinPresentation(serialized: String): PendingSpinPresentation? {
    if (serialized.isBlank() || serialized.length > MAX_SERIALIZED_JOURNAL_CHARS) return null
    return runCatching {
        val json = JSONObject(serialized)
        val expectedKeys = if (json.has(KEY_CLAIMED_BY_PROCESS_SESSION_ID)) {
            PRESENTATION_KEYS_WITH_CLAIM
        } else {
            PRESENTATION_KEYS
        }
        if (!json.hasExactly(expectedKeys)) return@runCatching null
        if (json.requiredInt(KEY_VERSION) != PRESENTATION_VERSION) return@runCatching null
        val settlementJson = json.opt(KEY_PRESENTATION_SETTLEMENT) as? JSONObject
            ?: return@runCatching null
        val settlement = deserializePendingSpinSettlement(settlementJson.toString())
            ?: return@runCatching null
        val claimedByProcessSessionId = if (json.has(KEY_CLAIMED_BY_PROCESS_SESSION_ID)) {
            json.requiredString(KEY_CLAIMED_BY_PROCESS_SESSION_ID)
                ?.takeIf(String::isValidIdentifier)
                ?: return@runCatching null
        } else {
            null
        }
        PendingSpinPresentation(settlement, claimedByProcessSessionId)
    }.getOrNull()
}

internal fun PendingSpinSettlement.validVisualResultOrNull(): SpinResult? {
    return visualResult?.takeIf { visual -> visual.isValidFor(this) }
}

internal fun PendingSpinSettlement.canonicalJournalPayload(): ByteArray {
    require(isValid()) { "Cannot canonicalize an invalid spin settlement." }
    return CanonicalPayloadWriter().apply {
        writeString(JOURNAL_DOMAIN)
        writeInt(PENDING_SPIN_JOURNAL_VERSION)
        writeString(id)
        writeString(processSessionId)
        writeString(slotId)
        writeInt(mathVersion)
        writeString(configFingerprint)
        writeBoolean(isFreeSpin)
        writeInt(lineBet)
        writeInt(lines)
        writeInt(totalBet)
        writeInts(stopIndexes)
        writeInt(winAmount)
        writeInt(freeSpinsAwarded)
        writeInt(levelXpAwarded)
    }.toByteArray()
}

internal fun PendingSpinSettlement.journalChecksum(): String {
    return sha256Hex(canonicalJournalPayload())
}

internal fun PlayerState.applyPendingSpinSettlement(settlement: PendingSpinSettlement): PlayerState {
    require(settlement.isValid()) { "Cannot apply an invalid spin settlement." }
    val hadFreeSpinsBeforeAward = hasFreeSpinsForSlot(settlement.slotId)
    val stateWithBonus = if (settlement.freeSpinsAwarded > 0) {
        val bonuses = mergeAwardedFreeSpinBonus(
            currentBonuses = freeSpinBonuses,
            legacySlotId = freeSpinSlotId,
            legacyCount = freeSpinsBalance,
            legacyLineBet = freeSpinBet,
            legacyLines = freeSpinLines,
            awardSlotId = settlement.slotId,
            awardCount = settlement.freeSpinsAwarded,
            awardLineBet = settlement.lineBet,
            awardLines = settlement.lines
        )
        withFreeSpinBonusesSnapshot(bonuses)
    } else {
        this
    }
    val updatedXp = (stateWithBonus.levelXp.toLong() + settlement.levelXpAwarded.toLong())
        .coerceIn(0L, PlayerState.maxLevelXp().toLong())
        .toInt()
    val autoPlaySlots = stateWithBonus.freeSpinAutoPlaySlots.toMutableSet().apply {
        if (settlement.isFreeSpin && !stateWithBonus.hasFreeSpinsForSlot(settlement.slotId)) {
            remove(settlement.slotId)
        }
    }
    val featureTotalWins = stateWithBonus.freeSpinFeatureTotalWins.toMutableMap().apply {
        if (!settlement.isFreeSpin && settlement.freeSpinsAwarded > 0 && !hadFreeSpinsBeforeAward) {
            this[settlement.slotId] = 0
        }
        if (settlement.isFreeSpin) {
            this[settlement.slotId] = saturatedNonNegativeAdd(
                this[settlement.slotId] ?: 0,
                settlement.winAmount
            )
        }
    }
    return stateWithBonus.copy(
        coinsBalance = saturatedNonNegativeAdd(stateWithBonus.coinsBalance, settlement.winAmount),
        levelXp = updatedXp,
        freeSpinAutoPlaySlots = autoPlaySlots,
        freeSpinFeatureTotalWins = featureTotalWins
    )
}

private fun JSONObject.requiredString(key: String): String? {
    if (!has(key)) return null
    return (opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_IDENTIFIER_LENGTH }
}

private fun JSONObject.requiredBoolean(key: String): Boolean? {
    if (!has(key)) return null
    return opt(key) as? Boolean
}

private fun JSONObject.requiredInt(key: String): Int? {
    if (!has(key)) return null
    return when (val value = opt(key)) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }
}

private fun JSONObject.requiredIntList(key: String, requiredSize: Int): List<Int>? {
    val array = opt(key) as? JSONArray ?: return null
    if (array.length() != requiredSize) return null
    return buildList(requiredSize) {
        for (index in 0 until array.length()) {
            add(array.requiredInt(index) ?: return null)
        }
    }
}

private fun JSONArray.requiredInt(index: Int): Int? {
    return when (val value = opt(index)) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }
}

private fun JSONObject.hasExactly(expected: Set<String>): Boolean {
    val seen = HashSet<String>(expected.size)
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key !in expected || !seen.add(key)) return false
    }
    return seen.size == expected.size
}

private fun SpinResult.isValidFor(settlement: PendingSpinSettlement): Boolean {
    if (
        bet != settlement.lineBet ||
        lines != settlement.lines ||
        totalBet != settlement.totalBet ||
        winAmount != settlement.winAmount ||
        freeSpinsAwarded != settlement.freeSpinsAwarded ||
        stopIndexes != settlement.stopIndexes ||
        isFreeSpin != settlement.isFreeSpin
    ) {
        return false
    }
    if (reels.size != REQUIRED_STOP_INDEXES) return false
    val rowCount = reels.firstOrNull()?.size ?: return false
    if (rowCount <= 0 || reels.any { reel -> reel.size != rowCount }) return false
    return reels.flatten().all(String::isValidIdentifier)
}

private fun String.isValidIdentifier(): Boolean {
    return isNotBlank() && length <= MAX_IDENTIFIER_LENGTH && hasWellFormedUtf16()
}

private fun String.hasWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            Character.isHighSurrogate(this[index]) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(this[index]) -> return false
            else -> index += 1
        }
    }
    return true
}

private fun checksumsMatch(expected: String, actual: String): Boolean {
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.US_ASCII),
        actual.toByteArray(StandardCharsets.US_ASCII)
    )
}

internal const val PENDING_SPIN_JOURNAL_VERSION = 3
private const val PRESENTATION_VERSION = 2
private const val REFUND_ENVELOPE_VERSION = 1
private const val JOURNAL_DOMAIN = "vslot.pending-spin-settlement"
private const val REFUND_ENVELOPE_DOMAIN = "vslot.pending-spin-refund"
private const val MAX_IDENTIFIER_LENGTH = 128
private const val REQUIRED_STOP_INDEXES = 5
private const val MAX_SERIALIZED_JOURNAL_CHARS = 128 * 1024
private val CONFIG_FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
private val CHECKSUM_PATTERN = Regex("[0-9a-f]{64}")
private const val KEY_VERSION = "version"
private const val KEY_ID = "id"
private const val KEY_PROCESS_SESSION_ID = "processSessionId"
private const val KEY_SLOT_ID = "slotId"
private const val KEY_MATH_VERSION = "mathVersion"
private const val KEY_CONFIG_FINGERPRINT = "configFingerprint"
private const val KEY_IS_FREE_SPIN = "isFreeSpin"
private const val KEY_LINE_BET = "lineBet"
private const val KEY_LINES = "lines"
private const val KEY_TOTAL_BET = "totalBet"
private const val KEY_STOP_INDEXES = "stopIndexes"
private const val KEY_WIN_AMOUNT = "winAmount"
private const val KEY_FREE_SPINS_AWARDED = "freeSpinsAwarded"
private const val KEY_LEVEL_XP_AWARDED = "levelXpAwarded"
private const val KEY_CHECKSUM = "checksum"
private const val KEY_PRESENTATION_SETTLEMENT = "settlement"
private const val KEY_CLAIMED_BY_PROCESS_SESSION_ID = "claimedByProcessSessionId"
private const val KEY_REFUND_VERSION = "version"
private const val KEY_REFUND_SETTLEMENT_ID = "settlementId"
private const val KEY_REFUND_SLOT_ID = "slotId"
private const val KEY_REFUND_IS_FREE_SPIN = "isFreeSpin"
private const val KEY_REFUND_LINE_BET = "lineBet"
private const val KEY_REFUND_LINES = "lines"
private const val KEY_REFUND_TOTAL_BET = "totalBet"
private const val KEY_REFUND_CHECKSUM = "checksum"
private val JOURNAL_KEYS = setOf(
    KEY_VERSION,
    KEY_ID,
    KEY_PROCESS_SESSION_ID,
    KEY_SLOT_ID,
    KEY_MATH_VERSION,
    KEY_CONFIG_FINGERPRINT,
    KEY_IS_FREE_SPIN,
    KEY_LINE_BET,
    KEY_LINES,
    KEY_TOTAL_BET,
    KEY_STOP_INDEXES,
    KEY_WIN_AMOUNT,
    KEY_FREE_SPINS_AWARDED,
    KEY_LEVEL_XP_AWARDED,
    KEY_CHECKSUM
)
private val PRESENTATION_KEYS = setOf(KEY_VERSION, KEY_PRESENTATION_SETTLEMENT)
private val PRESENTATION_KEYS_WITH_CLAIM = PRESENTATION_KEYS + KEY_CLAIMED_BY_PROCESS_SESSION_ID
private val REFUND_ENVELOPE_KEYS = setOf(
    KEY_REFUND_VERSION,
    KEY_REFUND_SETTLEMENT_ID,
    KEY_REFUND_SLOT_ID,
    KEY_REFUND_IS_FREE_SPIN,
    KEY_REFUND_LINE_BET,
    KEY_REFUND_LINES,
    KEY_REFUND_TOTAL_BET,
    KEY_REFUND_CHECKSUM
)
