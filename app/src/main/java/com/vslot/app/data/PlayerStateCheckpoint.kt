package com.vslot.app.data

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class PlayerStateCheckpoint(
    val generation: Long,
    val playerState: PlayerState,
    val rawPendingSpinSettlement: String? = null,
    val rawPendingSpinRefundEnvelope: String? = null,
    val rawPendingSpinPresentation: String? = null,
    val migrationComplete: Boolean = false,
    val schemaVersion: Int = PlayerStateCheckpointCodec.CURRENT_SCHEMA_VERSION
)

internal object PlayerStateCheckpointCodec {
    const val CURRENT_SCHEMA_VERSION = 4
    const val MAX_FILE_BYTES = 512 * 1024
    const val MAX_PENDING_JOURNAL_CHARS = 128 * 1024
    const val MAX_PENDING_JOURNAL_BYTES = 256 * 1024
    const val MAX_STATE_COLLECTION_SIZE = 128
    const val MAX_STATE_STRING_CHARS = 128
    const val MAX_STATE_STRING_BYTES = 512

    fun encode(checkpoint: PlayerStateCheckpoint): String {
        require(checkpoint.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported checkpoint schema version: ${checkpoint.schemaVersion}."
        }
        require(checkpoint.generation >= 0L) { "Checkpoint generation cannot be negative." }
        require(checkpoint.playerState.hasValidShape()) {
            "Player state exceeds checkpoint bounds."
        }
        require(checkpoint.rawPendingSpinSettlement.isValidPendingJournal()) {
            "Pending spin settlement exceeds checkpoint bounds."
        }
        require(checkpoint.rawPendingSpinRefundEnvelope.isValidPendingJournal()) {
            "Pending spin refund envelope exceeds checkpoint bounds."
        }
        require(checkpoint.rawPendingSpinPresentation.isValidPendingJournal()) {
            "Pending spin presentation exceeds checkpoint bounds."
        }

        val normalizedState = checkpoint.playerState.normalized()
        check(normalizedState.hasValidShape()) { "Normalized player state exceeds checkpoint bounds." }
        val payload = JSONObject()
            .put(KEY_GENERATION, checkpoint.generation)
            .put(KEY_PLAYER_STATE, normalizedState.toJson())
            .put(
                KEY_PENDING_SPIN_SETTLEMENT,
                checkpoint.rawPendingSpinSettlement ?: JSONObject.NULL
            )
            .put(
                KEY_PENDING_SPIN_REFUND_ENVELOPE,
                checkpoint.rawPendingSpinRefundEnvelope ?: JSONObject.NULL
            )
            .put(
                KEY_PENDING_SPIN_PRESENTATION,
                checkpoint.rawPendingSpinPresentation ?: JSONObject.NULL
            )
            .put(KEY_MIGRATION_COMPLETE, checkpoint.migrationComplete)
        val canonicalPayload = canonicalJson(payload)
        val checksum = sha256Hex(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
        val encoded = buildString(canonicalPayload.length + CHECKSUM_HEX_LENGTH + 64) {
            append('{')
            appendCanonicalString(KEY_CHECKSUM)
            append(':')
            appendCanonicalString(checksum)
            append(',')
            appendCanonicalString(KEY_PAYLOAD)
            append(':')
            append(canonicalPayload)
            append(',')
            appendCanonicalString(KEY_SCHEMA_VERSION)
            append(':')
            append(CURRENT_SCHEMA_VERSION)
            append('}')
        }
        require(encoded.utf8Size() <= MAX_FILE_BYTES) {
            "Encoded checkpoint exceeds $MAX_FILE_BYTES bytes."
        }
        return encoded
    }

    fun decode(encoded: String): PlayerStateCheckpoint? {
        if (!encoded.hasWellFormedUtf16() || encoded.utf8Size() > MAX_FILE_BYTES) return null
        return decodeJson(encoded)
    }

    fun decode(encoded: ByteArray): PlayerStateCheckpoint? {
        if (encoded.size > MAX_FILE_BYTES) return null
        val json = encoded.decodeUtf8OrNull() ?: return null
        return decodeJson(json)
    }

    private fun decodeJson(encoded: String): PlayerStateCheckpoint? {
        if (encoded.isBlank() || !encoded.hasAcceptableJsonNesting()) return null
        return try {
            val envelope = JSONObject(encoded)
            if (!envelope.hasExactly(ENVELOPE_KEYS)) return null
            val schemaVersion = envelope.requiredInt(KEY_SCHEMA_VERSION) ?: return null
            if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) return null
            val checksum = envelope.requiredString(
                key = KEY_CHECKSUM,
                maxChars = CHECKSUM_HEX_LENGTH,
                maxBytes = CHECKSUM_HEX_LENGTH
            ) ?: return null
            if (!CHECKSUM_PATTERN.matches(checksum)) return null
            val payload = envelope.opt(KEY_PAYLOAD) as? JSONObject ?: return null
            val expectedPayloadKeys = when {
                schemaVersion >= 3 -> PAYLOAD_KEYS
                schemaVersion >= 2 -> VERSION_2_PAYLOAD_KEYS
                else -> LEGACY_PAYLOAD_KEYS
            }
            if (!payload.hasExactly(expectedPayloadKeys)) return null

            val canonicalPayload = canonicalJson(payload)
            val actualChecksum = sha256Hex(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
            if (!checksumsMatch(checksum, actualChecksum)) return null

            payload.toCheckpoint(schemaVersion)
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    private fun JSONObject.toCheckpoint(schemaVersion: Int): PlayerStateCheckpoint? {
        val generation = requiredLong(KEY_GENERATION)?.takeIf { it >= 0L } ?: return null
        val stateJson = opt(KEY_PLAYER_STATE) as? JSONObject ?: return null
        val state = stateJson.toPlayerState(schemaVersion) ?: return null
        if (!state.hasValidShape()) return null
        val pendingSettlement = boundedNullableString(KEY_PENDING_SPIN_SETTLEMENT)
        if (!pendingSettlement.isValid) return null
        val pendingRefundEnvelope = if (schemaVersion >= 2) {
            boundedNullableString(KEY_PENDING_SPIN_REFUND_ENVELOPE)
        } else {
            NullableStringRead.Valid(null)
        }
        if (!pendingRefundEnvelope.isValid) return null
        val pendingPresentation = boundedNullableString(KEY_PENDING_SPIN_PRESENTATION)
        if (!pendingPresentation.isValid) return null

        val normalizedState = state.normalized()
        if (!normalizedState.hasValidShape()) return null
        return PlayerStateCheckpoint(
            schemaVersion = schemaVersion,
            generation = generation,
            playerState = normalizedState,
            rawPendingSpinSettlement = pendingSettlement.value,
            rawPendingSpinRefundEnvelope = pendingRefundEnvelope.value,
            rawPendingSpinPresentation = pendingPresentation.value,
            migrationComplete = if (schemaVersion >= 3) {
                requiredBoolean(KEY_MIGRATION_COMPLETE) ?: return null
            } else {
                true
            }
        )
    }

    private fun PlayerState.toJson(): JSONObject {
        return JSONObject()
            .put(KEY_COINS_BALANCE, coinsBalance)
            .put(KEY_LAST_DAILY_BONUS_TIMESTAMP, lastDailyBonusTimestamp)
            .put(KEY_SELECTED_BET, selectedBet)
            .put(KEY_SELECTED_LINES, selectedLines)
            .put(KEY_FREE_SPINS_BALANCE, freeSpinsBalance)
            .put(KEY_FREE_SPIN_BET, freeSpinBet)
            .put(KEY_FREE_SPIN_LINES, freeSpinLines)
            .put(KEY_FREE_SPIN_SLOT_ID, freeSpinSlotId)
            .put(
                KEY_FREE_SPIN_BONUSES,
                JSONArray().apply {
                    normalizedFreeSpinBonuses(freeSpinBonuses).forEach { bonus ->
                        put(
                            JSONObject()
                                .put(KEY_SLOT_ID, bonus.slotId)
                                .put(KEY_COUNT, bonus.count)
                                .put(KEY_LINE_BET, bonus.lineBet)
                                .put(KEY_LINES, bonus.lines)
                        )
                    }
                }
            )
            .put(
                KEY_FREE_SPIN_AUTO_PLAY_SLOTS,
                JSONArray().apply {
                    freeSpinAutoPlaySlots.sorted().forEach(::put)
                }
            )
            .put(
                KEY_FREE_SPIN_FEATURE_TOTAL_WINS,
                JSONArray().apply {
                    normalizedFreeSpinFeatureTotalWins(freeSpinFeatureTotalWins).forEach { (slotId, totalWin) ->
                        put(
                            JSONObject()
                                .put(KEY_SLOT_ID, slotId)
                                .put(KEY_TOTAL_WIN, totalWin)
                        )
                    }
                }
            )
            .put(KEY_LEVEL_XP, levelXp)
            .put(KEY_DISCLAIMER_ACCEPTED, disclaimerAccepted)
            .put(KEY_PUSH_PERMISSION_ASKED, pushPermissionAsked)
            .put(KEY_SOUND_ENABLED, soundEnabled)
            .put(KEY_HAPTICS_ENABLED, hapticsEnabled)
            .put(KEY_ANALYTICS_ENABLED, analyticsEnabled)
            .put(KEY_LAST_PLAYED_SLOT, lastPlayedSlot)
    }

    private fun JSONObject.toPlayerState(schemaVersion: Int): PlayerState? {
        val expectedKeys = if (schemaVersion >= 4) {
            PLAYER_STATE_KEYS
        } else {
            LEGACY_PLAYER_STATE_KEYS
        }
        if (!hasExactly(expectedKeys)) return null
        val bonusesArray = opt(KEY_FREE_SPIN_BONUSES) as? JSONArray ?: return null
        if (bonusesArray.length() > MAX_STATE_COLLECTION_SIZE) return null
        val bonuses = LinkedHashMap<String, FreeSpinBonus>(bonusesArray.length())
        for (index in 0 until bonusesArray.length()) {
            val item = bonusesArray.opt(index) as? JSONObject ?: return null
            if (!item.hasExactly(FREE_SPIN_BONUS_KEYS)) return null
            val slotId = item.requiredStateString(KEY_SLOT_ID) ?: return null
            if (bonuses.containsKey(slotId)) return null
            bonuses[slotId] = FreeSpinBonus(
                slotId = slotId,
                count = item.requiredInt(KEY_COUNT) ?: return null,
                lineBet = item.requiredInt(KEY_LINE_BET) ?: return null,
                lines = item.requiredInt(KEY_LINES) ?: return null
            )
        }

        val autoPlayArray = opt(KEY_FREE_SPIN_AUTO_PLAY_SLOTS) as? JSONArray ?: return null
        if (autoPlayArray.length() > MAX_STATE_COLLECTION_SIZE) return null
        val autoPlaySlots = LinkedHashSet<String>(autoPlayArray.length())
        for (index in 0 until autoPlayArray.length()) {
            val slotId = autoPlayArray.requiredStateString(index) ?: return null
            if (!autoPlaySlots.add(slotId)) return null
        }

        val featureTotalWins = LinkedHashMap<String, Int>()
        if (schemaVersion >= 4) {
            val totalWinsArray = opt(KEY_FREE_SPIN_FEATURE_TOTAL_WINS) as? JSONArray ?: return null
            if (totalWinsArray.length() > MAX_STATE_COLLECTION_SIZE) return null
            for (index in 0 until totalWinsArray.length()) {
                val item = totalWinsArray.opt(index) as? JSONObject ?: return null
                if (!item.hasExactly(FREE_SPIN_FEATURE_TOTAL_WIN_KEYS)) return null
                val slotId = item.requiredStateString(KEY_SLOT_ID) ?: return null
                val totalWin = item.requiredInt(KEY_TOTAL_WIN)?.takeIf { it >= 0 } ?: return null
                if (featureTotalWins.put(slotId, totalWin) != null) return null
            }
        }

        return PlayerState(
            coinsBalance = requiredLong(KEY_COINS_BALANCE) ?: return null,
            lastDailyBonusTimestamp = requiredLong(KEY_LAST_DAILY_BONUS_TIMESTAMP) ?: return null,
            selectedBet = requiredInt(KEY_SELECTED_BET) ?: return null,
            selectedLines = requiredInt(KEY_SELECTED_LINES) ?: return null,
            freeSpinsBalance = requiredInt(KEY_FREE_SPINS_BALANCE) ?: return null,
            freeSpinBet = requiredInt(KEY_FREE_SPIN_BET) ?: return null,
            freeSpinLines = requiredInt(KEY_FREE_SPIN_LINES) ?: return null,
            freeSpinSlotId = requiredStateString(KEY_FREE_SPIN_SLOT_ID) ?: return null,
            freeSpinBonuses = bonuses,
            freeSpinAutoPlaySlots = autoPlaySlots,
            freeSpinFeatureTotalWins = featureTotalWins,
            levelXp = requiredInt(KEY_LEVEL_XP) ?: return null,
            disclaimerAccepted = requiredBoolean(KEY_DISCLAIMER_ACCEPTED) ?: return null,
            pushPermissionAsked = requiredBoolean(KEY_PUSH_PERMISSION_ASKED) ?: return null,
            soundEnabled = requiredBoolean(KEY_SOUND_ENABLED) ?: return null,
            hapticsEnabled = requiredBoolean(KEY_HAPTICS_ENABLED) ?: return null,
            analyticsEnabled = requiredBoolean(KEY_ANALYTICS_ENABLED) ?: return null,
            lastPlayedSlot = requiredStateString(KEY_LAST_PLAYED_SLOT) ?: return null
        )
    }

    private fun JSONObject.boundedNullableString(key: String): NullableStringRead {
        if (!has(key)) return NullableStringRead.Invalid
        val value = opt(key)
        if (value === JSONObject.NULL) return NullableStringRead.Valid(null)
        val string = value as? String ?: return NullableStringRead.Invalid
        return if (string.isValidPendingJournal()) {
            NullableStringRead.Valid(string)
        } else {
            NullableStringRead.Invalid
        }
    }

    private fun PlayerState.hasValidShape(): Boolean {
        if (freeSpinBonuses.size > MAX_STATE_COLLECTION_SIZE) return false
        if (freeSpinAutoPlaySlots.size > MAX_STATE_COLLECTION_SIZE) return false
        if (freeSpinFeatureTotalWins.size > MAX_STATE_COLLECTION_SIZE) return false
        if (!freeSpinSlotId.isValidStateString()) return false
        if (!lastPlayedSlot.isValidStateString()) return false
        if (
            freeSpinBonuses.any { (key, bonus) ->
                !key.isValidStateString() || !bonus.slotId.isValidStateString()
            }
        ) {
            return false
        }
        if (freeSpinFeatureTotalWins.any { (slotId, totalWin) ->
                !slotId.isValidStateString() || totalWin < 0
            }
        ) {
            return false
        }
        return freeSpinAutoPlaySlots.all { it.isValidStateString() }
    }

    private fun String?.isValidPendingJournal(): Boolean {
        if (this == null) return true
        return length <= MAX_PENDING_JOURNAL_CHARS &&
            hasWellFormedUtf16() &&
            utf8Size() <= MAX_PENDING_JOURNAL_BYTES
    }

    private fun String.isValidStateString(): Boolean {
        return length <= MAX_STATE_STRING_CHARS &&
            hasWellFormedUtf16() &&
            utf8Size() <= MAX_STATE_STRING_BYTES
    }

    private fun JSONObject.requiredStateString(key: String): String? {
        return requiredString(key, MAX_STATE_STRING_CHARS, MAX_STATE_STRING_BYTES)
    }

    private fun JSONArray.requiredStateString(index: Int): String? {
        val value = opt(index) as? String ?: return null
        return value.takeIf { it.isValidStateString() }
    }

    private fun JSONObject.requiredString(
        key: String,
        maxChars: Int,
        maxBytes: Int
    ): String? {
        if (!has(key)) return null
        val value = opt(key) as? String ?: return null
        return value.takeIf {
            it.length <= maxChars && it.hasWellFormedUtf16() && it.utf8Size() <= maxBytes
        }
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean? {
        if (!has(key)) return null
        return opt(key) as? Boolean
    }

    private fun JSONObject.requiredInt(key: String): Int? {
        val value = requiredLong(key) ?: return null
        return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun JSONObject.requiredLong(key: String): Long? {
        if (!has(key)) return null
        return opt(key).exactLongOrNull()
    }

    private fun Any?.exactLongOrNull(): Long? {
        return when (this) {
            is Byte -> toLong()
            is Short -> toLong()
            is Int -> toLong()
            is Long -> this
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

    internal fun canonicalJson(value: Any?): String {
        return buildString { appendCanonicalJson(value) }
    }

    private fun StringBuilder.appendCanonicalJson(value: Any?) {
        when {
            value === JSONObject.NULL || value == null -> append("null")
            value is String -> appendCanonicalString(value)
            value is Boolean -> append(if (value) "true" else "false")
            value is Byte || value is Short || value is Int || value is Long -> append(value)
            value is JSONObject -> {
                append('{')
                val keys = buildList {
                    val iterator = value.keys()
                    while (iterator.hasNext()) add(iterator.next())
                }.sorted()
                keys.forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    appendCanonicalString(key)
                    append(':')
                    appendCanonicalJson(value.get(key))
                }
                append('}')
            }
            value is JSONArray -> {
                append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) append(',')
                    appendCanonicalJson(value.get(index))
                }
                append(']')
            }
            else -> throw IllegalArgumentException("Unsupported canonical JSON value.")
        }
    }

    private fun StringBuilder.appendCanonicalString(value: String) {
        require(value.hasWellFormedUtf16()) { "JSON strings must contain well-formed UTF-16." }
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < ASCII_SPACE) {
                        append("\\u")
                        append(HEX_DIGITS[(character.code ushr 12) and 0xF])
                        append(HEX_DIGITS[(character.code ushr 8) and 0xF])
                        append(HEX_DIGITS[(character.code ushr 4) and 0xF])
                        append(HEX_DIGITS[character.code and 0xF])
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun String.hasWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) {
                        return false
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun String.hasAcceptableJsonNesting(): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > MAX_JSON_NESTING_DEPTH) return false
                    }
                    '}', ']' -> {
                        depth -= 1
                        if (depth < 0) return false
                    }
                }
            }
        }
        return !inString && !escaped && depth == 0
    }

    private fun String.utf8Size(): Int {
        return toByteArray(StandardCharsets.UTF_8).size
    }

    private fun ByteArray.decodeUtf8OrNull(): String? {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(bytes)
        return buildString(CHECKSUM_HEX_LENGTH) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0xF])
            }
        }
    }

    private fun checksumsMatch(expected: String, actual: String): Boolean {
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            actual.toByteArray(StandardCharsets.US_ASCII)
        )
    }

    private sealed interface NullableStringRead {
        val isValid: Boolean
        val value: String?

        data class Valid(override val value: String?) : NullableStringRead {
            override val isValid = true
        }

        data object Invalid : NullableStringRead {
            override val isValid = false
            override val value: String? = null
        }
    }

    private const val MAX_JSON_NESTING_DEPTH = 16
    private const val CHECKSUM_HEX_LENGTH = 64
    private const val SHA_256 = "SHA-256"
    private const val ASCII_SPACE = 0x20
    private const val HEX_DIGITS = "0123456789abcdef"
    private val CHECKSUM_PATTERN = Regex("[0-9a-f]{$CHECKSUM_HEX_LENGTH}")

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_CHECKSUM = "checksum"
    private const val KEY_GENERATION = "generation"
    private const val KEY_PLAYER_STATE = "playerState"
    private const val KEY_PENDING_SPIN_SETTLEMENT = "pendingSpinSettlement"
    private const val KEY_PENDING_SPIN_REFUND_ENVELOPE = "pendingSpinRefundEnvelope"
    private const val KEY_PENDING_SPIN_PRESENTATION = "pendingSpinPresentation"
    private const val KEY_MIGRATION_COMPLETE = "migrationComplete"
    private const val KEY_COINS_BALANCE = "coinsBalance"
    private const val KEY_LAST_DAILY_BONUS_TIMESTAMP = "lastDailyBonusTimestamp"
    private const val KEY_SELECTED_BET = "selectedBet"
    private const val KEY_SELECTED_LINES = "selectedLines"
    private const val KEY_FREE_SPINS_BALANCE = "freeSpinsBalance"
    private const val KEY_FREE_SPIN_BET = "freeSpinBet"
    private const val KEY_FREE_SPIN_LINES = "freeSpinLines"
    private const val KEY_FREE_SPIN_SLOT_ID = "freeSpinSlotId"
    private const val KEY_FREE_SPIN_BONUSES = "freeSpinBonuses"
    private const val KEY_FREE_SPIN_AUTO_PLAY_SLOTS = "freeSpinAutoPlaySlots"
    private const val KEY_FREE_SPIN_FEATURE_TOTAL_WINS = "freeSpinFeatureTotalWins"
    private const val KEY_LEVEL_XP = "levelXp"
    private const val KEY_DISCLAIMER_ACCEPTED = "disclaimerAccepted"
    private const val KEY_PUSH_PERMISSION_ASKED = "pushPermissionAsked"
    private const val KEY_SOUND_ENABLED = "soundEnabled"
    private const val KEY_HAPTICS_ENABLED = "hapticsEnabled"
    private const val KEY_ANALYTICS_ENABLED = "analyticsEnabled"
    private const val KEY_LAST_PLAYED_SLOT = "lastPlayedSlot"
    private const val KEY_SLOT_ID = "slotId"
    private const val KEY_COUNT = "count"
    private const val KEY_LINE_BET = "lineBet"
    private const val KEY_LINES = "lines"
    private const val KEY_TOTAL_WIN = "totalWin"

    private val ENVELOPE_KEYS = setOf(KEY_SCHEMA_VERSION, KEY_PAYLOAD, KEY_CHECKSUM)
    private val SUPPORTED_SCHEMA_VERSIONS = 1..CURRENT_SCHEMA_VERSION
    private val LEGACY_PAYLOAD_KEYS = setOf(
        KEY_GENERATION,
        KEY_PLAYER_STATE,
        KEY_PENDING_SPIN_SETTLEMENT,
        KEY_PENDING_SPIN_PRESENTATION
    )
    private val VERSION_2_PAYLOAD_KEYS = setOf(
        KEY_GENERATION,
        KEY_PLAYER_STATE,
        KEY_PENDING_SPIN_SETTLEMENT,
        KEY_PENDING_SPIN_REFUND_ENVELOPE,
        KEY_PENDING_SPIN_PRESENTATION
    )
    private val PAYLOAD_KEYS = VERSION_2_PAYLOAD_KEYS + KEY_MIGRATION_COMPLETE
    private val LEGACY_PLAYER_STATE_KEYS = setOf(
        KEY_COINS_BALANCE,
        KEY_LAST_DAILY_BONUS_TIMESTAMP,
        KEY_SELECTED_BET,
        KEY_SELECTED_LINES,
        KEY_FREE_SPINS_BALANCE,
        KEY_FREE_SPIN_BET,
        KEY_FREE_SPIN_LINES,
        KEY_FREE_SPIN_SLOT_ID,
        KEY_FREE_SPIN_BONUSES,
        KEY_FREE_SPIN_AUTO_PLAY_SLOTS,
        KEY_LEVEL_XP,
        KEY_DISCLAIMER_ACCEPTED,
        KEY_PUSH_PERMISSION_ASKED,
        KEY_SOUND_ENABLED,
        KEY_HAPTICS_ENABLED,
        KEY_ANALYTICS_ENABLED,
        KEY_LAST_PLAYED_SLOT
    )
    private val PLAYER_STATE_KEYS = LEGACY_PLAYER_STATE_KEYS + KEY_FREE_SPIN_FEATURE_TOTAL_WINS
    private val FREE_SPIN_BONUS_KEYS = setOf(KEY_SLOT_ID, KEY_COUNT, KEY_LINE_BET, KEY_LINES)
    private val FREE_SPIN_FEATURE_TOTAL_WIN_KEYS = setOf(KEY_SLOT_ID, KEY_TOTAL_WIN)
}

internal class PlayerStateCheckpointStore(
    checkpointFile: File
) {
    private val atomicFile = AtomicFile(checkpointFile)

    constructor(noBackupFilesDir: File, fileName: String) : this(File(noBackupFilesDir, fileName))

    @Synchronized
    fun read(): PlayerStateCheckpoint? {
        val bytes = try {
            atomicFile.openRead().use { input ->
                input.readBounded(PlayerStateCheckpointCodec.MAX_FILE_BYTES)
            }
        } catch (_: FileNotFoundException) {
            return null
        } catch (_: IOException) {
            return null
        }
        return bytes?.let(PlayerStateCheckpointCodec::decode)
    }

    @Synchronized
    @Throws(IOException::class)
    fun write(checkpoint: PlayerStateCheckpoint) {
        val bytes = PlayerStateCheckpointCodec.encode(checkpoint)
            .toByteArray(StandardCharsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            try {
                atomicFile.failWrite(output)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    @Synchronized
    fun clear() {
        atomicFile.delete()
    }

    companion object {
        const val DEFAULT_FILE_NAME = "player_state_checkpoint.json"
        const val PRIMARY_FILE_NAME = "player_state_primary.json"
    }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(available().coerceAtLeast(0), maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val bytesRead = read(buffer)
        if (bytesRead < 0) break
        if (bytesRead == 0) continue
        totalBytes += bytesRead
        if (totalBytes > maxBytes) return null
        output.write(buffer, 0, bytesRead)
    }
    return output.toByteArray()
}
