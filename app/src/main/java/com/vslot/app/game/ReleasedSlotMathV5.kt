package com.vslot.app.game

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Immutable implementation used to settle journals written by slot math version 5. */
internal object ReleasedSlotMathV5 {
    const val VERSION = 5
    const val FREE_SPINS_BONUS_AWARD = 5
    const val ASSET_PATH = "released_math/v5/slots_config.json"
    const val ASSET_SHA256 = "0ddef7aa285bb4c2546def887eab1ca8c79c92863f90d8c3968e8bc34d10fc4b"

    private const val CONFIG_DOMAIN = "vslot.slot-config"
    private const val RULES_DOMAIN = "vslot.slot-rules"
    private const val CONFIG_FINGERPRINT_VERSION = 5

    private val releasedPaylineRows = listOf(
        listOf(1, 1, 1, 1, 1),
        listOf(0, 0, 0, 0, 0),
        listOf(2, 2, 2, 2, 2),
        listOf(0, 1, 2, 1, 0),
        listOf(2, 1, 0, 1, 2),
        listOf(0, 0, 1, 2, 2),
        listOf(2, 2, 1, 0, 0),
        listOf(1, 0, 0, 0, 1),
        listOf(1, 2, 2, 2, 1),
        listOf(0, 1, 1, 1, 0)
    )

    val paylineRows: List<List<Int>>
        get() = releasedPaylineRows.map(List<Int>::toList)

    fun spin(
        config: SlotConfig,
        rng: SlotRng,
        bet: Int,
        lines: Int = config.paylines,
        isFreeSpin: Boolean = false
    ): SpinResult {
        require(config.reels == 5) { "V Slot supports five reels." }
        require(config.rows == 3) { "V Slot supports three visible rows." }
        require(bet in config.bets) { "Unsupported bet: $bet" }
        require(lines in 1..config.paylines) { "Unsupported active lines: $lines" }
        require(lines <= releasedPaylineRows.size) {
            "V Slot supports up to ${releasedPaylineRows.size} paylines."
        }
        validateReelStrips(config)

        val stopIndexes = reelStripsFor(config, isFreeSpin).map { strip -> rng.nextInt(strip.size) }
        return evaluateStops(config, stopIndexes, bet, lines, isFreeSpin)
    }

    fun evaluateStops(
        config: SlotConfig,
        stopIndexes: List<Int>,
        bet: Int,
        lines: Int = config.paylines,
        isFreeSpin: Boolean = false
    ): SpinResult {
        validateStopInputs(config, stopIndexes, isFreeSpin)
        val reels = reelStripsFor(config, isFreeSpin).indices.map { reelIndex ->
            visibleReelWindow(config, reelIndex, stopIndexes[reelIndex], isFreeSpin)
        }
        return evaluate(config, reels, bet, lines, stopIndexes, isFreeSpin)
    }

    fun evaluate(
        config: SlotConfig,
        reels: List<List<String>>,
        bet: Int,
        lines: Int = config.paylines,
        stopIndexes: List<Int> = emptyList(),
        isFreeSpin: Boolean = false
    ): SpinResult {
        require(lines in 1..config.paylines) { "Unsupported active lines: $lines" }
        require(lines <= releasedPaylineRows.size) {
            "V Slot supports up to ${releasedPaylineRows.size} paylines."
        }
        validateEvaluationInputs(config, reels, bet, stopIndexes, isFreeSpin)

        val totalBet = checkedMultiply(bet, lines, "Total bet")
        val winningLines = releasedPaylineRows.take(lines).mapIndexedNotNull { index, rows ->
            evaluatePayline(config, reels, rows, index, bet)
        }
        val lineWin = winningLines.fold(0) { total, winningLine ->
            checkedAdd(total, winningLine.amount, "Combined line payout")
        }
        val scatterPositions = reels.flatMapIndexed { reelIndex, symbols ->
            symbols.mapIndexedNotNull { rowIndex, symbol ->
                if (symbol == config.scatter) {
                    SymbolPosition(reel = reelIndex, row = rowIndex)
                } else {
                    null
                }
            }
        }
        val scatterCount = scatterPositions.size
        val scatterMultiplier = config.scatterBonus[scatterCount] ?: 0
        val scatterWin = checkedMultiply(scatterMultiplier, totalBet, "Scatter payout")
        val totalWin = checkedAdd(lineWin, scatterWin, "Total payout")
        val freeSpinsAwarded = if (scatterWin > 0) FREE_SPINS_BONUS_AWARD else 0
        val resultType = when {
            scatterWin > 0 -> ResultType.Bonus
            totalWin > 0 -> ResultType.Win
            else -> ResultType.Lose
        }

        return SpinResult(
            reels = reels,
            bet = bet,
            lines = lines,
            totalBet = totalBet,
            winAmount = totalWin,
            resultType = resultType,
            winningLines = winningLines,
            scatterCount = scatterCount,
            scatterPositions = scatterPositions,
            freeSpinsAwarded = freeSpinsAwarded,
            stopIndexes = stopIndexes,
            isFreeSpin = isFreeSpin
        )
    }

    fun xpForSpin(totalBet: Int, isFreeSpin: Boolean, winAmount: Int): Int {
        val stakeXp = (totalBet / 50).coerceIn(1, 40)
        val winXp = (winAmount / 250).coerceIn(0, 20)
        val baseXp = if (isFreeSpin) 4 else 8
        return baseXp + stakeXp + winXp
    }

    fun supportsInput(
        config: SlotConfig,
        stopIndexes: List<Int>,
        bet: Int,
        lines: Int,
        isFreeSpin: Boolean
    ): Boolean = runCatching {
        require(config.reels == 5 && config.rows == 3)
        require(bet in config.bets)
        require(lines in 1..config.paylines && lines <= releasedPaylineRows.size)
        validateStopInputs(config, stopIndexes, isFreeSpin)
    }.isSuccess

    fun fingerprint(config: SlotConfig): String {
        val canonical = V5CanonicalPayloadWriter().apply {
            writeString(CONFIG_DOMAIN)
            writeInt(CONFIG_FINGERPRINT_VERSION)
            writeString(RULES_DOMAIN)
            writeInt(VERSION)
            writeInt(FREE_SPINS_BONUS_AWARD)
            writeInt(releasedPaylineRows.size)
            releasedPaylineRows.forEach(::writeInts)
            writeString(config.id)
            writeInt(config.reels)
            writeInt(config.rows)
            writeInt(config.paylines)
            writeString(config.wild)
            writeString(config.scatter)
            writeStrings(config.symbols)
            writeInts(config.bets)
            writeInt(config.payouts.size)
            config.payouts.toSortedMap().forEach { (symbol, payouts) ->
                writeString(symbol)
                writeInt(payouts.size)
                payouts.toSortedMap().forEach { (count, multiplier) ->
                    writeInt(count)
                    writeInt(multiplier)
                }
            }
            writeInt(config.scatterBonus.size)
            config.scatterBonus.toSortedMap().forEach { (count, multiplier) ->
                writeInt(count)
                writeInt(multiplier)
            }
            writeInt(config.reelStrips.size)
            config.reelStrips.forEach(::writeStrings)
            writeInt(config.freeSpinReelStrips.size)
            config.freeSpinReelStrips.forEach(::writeStrings)
        }.toByteArray()
        return digestHex(canonical)
    }

    fun verifyReleasedAsset(bytes: ByteArray) {
        require(digestHex(bytes) == ASSET_SHA256) {
            "Released slot math V5 asset does not match its immutable SHA-256 descriptor."
        }
    }

    private fun evaluatePayline(
        config: SlotConfig,
        reels: List<List<String>>,
        rows: List<Int>,
        index: Int,
        bet: Int
    ): WinningLine? {
        val symbols = rows.mapIndexed { reelIndex, rowIndex -> reels[reelIndex][rowIndex] }
        val symbolPriority = config.symbols.withIndex().associate { (priority, symbol) ->
            symbol to priority
        }
        return config.symbols
            .asSequence()
            .filter { it != config.scatter && it in config.payouts }
            .mapNotNull { target -> evaluatePaylineTarget(config, symbols, rows, index, bet, target) }
            .maxWithOrNull(
                compareBy<WinningLine> { it.amount }
                    .thenBy { it.count }
                    .thenBy { -(symbolPriority[it.symbol] ?: Int.MAX_VALUE) }
            )
    }

    private fun evaluatePaylineTarget(
        config: SlotConfig,
        symbols: List<String>,
        rows: List<Int>,
        index: Int,
        bet: Int,
        target: String
    ): WinningLine? {
        val count = matchingSymbolCount(config, symbols, target)
        if (count < 3) return null
        val multiplier = config.payouts[target]?.get(count) ?: return null
        return WinningLine(
            paylineIndex = index,
            symbol = target,
            count = count,
            amount = checkedMultiply(multiplier, bet, "Line payout"),
            positions = (0 until count).map { reelIndex ->
                SymbolPosition(reel = reelIndex, row = rows[reelIndex])
            }
        )
    }

    private fun matchingSymbolCount(config: SlotConfig, symbols: List<String>, target: String): Int {
        var count = 0
        for (symbol in symbols) {
            if (symbol == config.scatter) break
            if (symbol == target || symbol == config.wild) {
                count += 1
            } else {
                break
            }
        }
        return count
    }

    private fun visibleReelWindow(
        config: SlotConfig,
        reelIndex: Int,
        stopIndex: Int,
        isFreeSpin: Boolean
    ): List<String> {
        val strip = reelStripsFor(config, isFreeSpin)[reelIndex]
        return List(config.rows) { row -> strip[(stopIndex + row).mod(strip.size)] }
    }

    private fun validateReelStrips(config: SlotConfig) {
        validateReelStripSet(config, config.reelStrips, "paid")
        validateReelStripSet(config, config.freeSpinReelStrips, "free-spin")
    }

    private fun validateReelStripSet(config: SlotConfig, strips: List<List<String>>, mode: String) {
        require(strips.size == config.reels) {
            "Slot ${config.id} must define ${config.reels} $mode reel strips."
        }
        strips.forEachIndexed { reelIndex, strip ->
            require(strip.size >= config.rows) {
                "$mode reel strip $reelIndex for ${config.id} must contain at least ${config.rows} symbols."
            }
            require(strip.all { it in config.symbols }) {
                "$mode reel strip $reelIndex for ${config.id} contains an unknown symbol."
            }
        }
    }

    private fun validateStopInputs(config: SlotConfig, stopIndexes: List<Int>, isFreeSpin: Boolean) {
        validateReelStrips(config)
        val strips = reelStripsFor(config, isFreeSpin)
        require(stopIndexes.size == config.reels) {
            "Evaluation stop indexes for ${config.id} must contain one index per reel."
        }
        stopIndexes.forEachIndexed { reelIndex, stopIndex ->
            require(stopIndex in strips[reelIndex].indices) {
                "Evaluation stop index $stopIndex for ${config.id} reel $reelIndex exceeds reel strip length."
            }
        }
    }

    private fun validateEvaluationInputs(
        config: SlotConfig,
        reels: List<List<String>>,
        bet: Int,
        stopIndexes: List<Int>,
        isFreeSpin: Boolean
    ) {
        require(config.reels == 5) { "V Slot supports five reels." }
        require(config.rows == 3) { "V Slot supports three visible rows." }
        require(bet in config.bets) { "Unsupported bet: $bet" }
        require(reels.size == config.reels) {
            "Evaluation for ${config.id} must contain ${config.reels} reels."
        }
        reels.forEachIndexed { reelIndex, symbols ->
            require(symbols.size == config.rows) {
                "Evaluation reel $reelIndex for ${config.id} must contain ${config.rows} visible symbols."
            }
            require(symbols.all { it in config.symbols }) {
                "Evaluation reel $reelIndex for ${config.id} contains an unknown symbol."
            }
        }
        require(stopIndexes.isEmpty() || stopIndexes.size == config.reels) {
            "Evaluation stop indexes for ${config.id} must be empty or contain one index per reel."
        }
        require(stopIndexes.all { it >= 0 }) {
            "Evaluation stop indexes for ${config.id} must be non-negative."
        }
        if (stopIndexes.isNotEmpty()) {
            val strips = reelStripsFor(config, isFreeSpin)
            require(strips.size == config.reels) {
                "Evaluation stop indexes for ${config.id} require configured reel strips."
            }
            stopIndexes.forEachIndexed { reelIndex, stopIndex ->
                require(stopIndex < strips[reelIndex].size) {
                    "Evaluation stop index $stopIndex for ${config.id} reel $reelIndex exceeds reel strip length."
                }
            }
            val expectedReels = strips.indices.map { reelIndex ->
                visibleReelWindow(config, reelIndex, stopIndexes[reelIndex], isFreeSpin)
            }
            require(reels == expectedReels) {
                "Evaluation reel windows for ${config.id} must match the supplied stop indexes."
            }
        }
    }

    private fun reelStripsFor(config: SlotConfig, isFreeSpin: Boolean): List<List<String>> =
        if (isFreeSpin) config.freeSpinReelStrips else config.reelStrips

    private fun checkedMultiply(left: Int, right: Int, description: String): Int {
        return try {
            Math.multiplyExact(left, right)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("$description exceeds the supported integer range.", error)
        }
    }

    private fun checkedAdd(left: Int, right: Int, description: String): Int {
        return try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("$description exceeds the supported integer range.", error)
        }
    }

    private fun digestHex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(LOWER_HEX[value ushr 4])
                append(LOWER_HEX[value and 0x0f])
            }
        }
    }

    private const val LOWER_HEX = "0123456789abcdef"
}

internal class ReleasedSlotMathV5ConfigParser {
    fun parse(json: String): List<SlotConfig> {
        val slots = JSONObject(json).getJSONArray("slots").let { array ->
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toSlotConfig())
            }
        }
        require(slots.isNotEmpty()) { "Released slot math V5 must define at least one slot." }
        require(slots.map(SlotConfig::id).distinct().size == slots.size) {
            "Released slot math V5 contains duplicate slot ids."
        }
        slots.forEach(::validateSlot)
        return slots
    }

    private fun JSONObject.toSlotConfig(): SlotConfig {
        return SlotConfig(
            id = getString("id"),
            name = getString("name"),
            theme = parseTheme(getString("theme")),
            reels = getInt("reels"),
            rows = getInt("rows"),
            paylines = getInt("paylines"),
            wild = getString("wild"),
            scatter = getString("scatter"),
            symbols = getJSONArray("symbols").toStringList(),
            bets = getJSONArray("bets").toIntList(),
            payouts = getJSONObject("payouts").toPayouts(),
            scatterBonus = getJSONObject("scatterBonus").toIntMap(),
            reelStrips = getJSONArray("reelStrips").toStringListList(),
            freeSpinReelStrips = getJSONArray("freeSpinReelStrips").toStringListList()
        )
    }

    private fun validateSlot(slot: SlotConfig) {
        require(slot.id.isNotBlank() && slot.name.isNotBlank()) {
            "Released slot math V5 slot identity must not be blank."
        }
        require(slot.reels == 5 && slot.rows == 3) {
            "Released slot math V5 requires a 5x3 reel window."
        }
        require(slot.paylines in 1..ReleasedSlotMathV5.paylineRows.size) {
            "Released slot math V5 contains an unsupported payline count."
        }
        require(slot.symbols.isNotEmpty() && slot.symbols.distinct().size == slot.symbols.size) {
            "Released slot math V5 symbols must be non-empty and unique."
        }
        require(slot.wild in slot.symbols && slot.scatter in slot.symbols && slot.wild != slot.scatter) {
            "Released slot math V5 requires distinct configured wild and scatter symbols."
        }
        require(slot.bets.isNotEmpty() && slot.bets.all { it > 0 }) {
            "Released slot math V5 bets must be positive."
        }
        require(slot.payouts.keys == slot.symbols.filterNot { it == slot.scatter }.toSet()) {
            "Released slot math V5 payout symbols do not match the configured symbols."
        }
        require(slot.payouts.values.all { payouts -> PAYOUT_COUNTS.all { (payouts[it] ?: 0) > 0 } }) {
            "Released slot math V5 line payouts are incomplete."
        }
        require(PAYOUT_COUNTS.all { (slot.scatterBonus[it] ?: 0) > 0 }) {
            "Released slot math V5 scatter payouts are incomplete."
        }
        listOf(slot.reelStrips, slot.freeSpinReelStrips).forEach { strips ->
            require(strips.size == slot.reels)
            require(strips.all { strip ->
                strip.size >= slot.rows && strip.all { it in slot.symbols }
            })
        }
    }

    private fun parseTheme(value: String): SlotTheme = when (value) {
        "violet" -> SlotTheme.Violet
        "roman" -> SlotTheme.Roman
        "neon" -> SlotTheme.Neon
        "pharaoh" -> SlotTheme.Pharaoh
        "ocean" -> SlotTheme.Ocean
        else -> error("Unsupported released slot math V5 theme: $value")
    }

    private fun JSONArray.toStringListList(): List<List<String>> = buildList {
        for (index in 0 until length()) add(getJSONArray(index).toStringList())
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }

    private fun JSONArray.toIntList(): List<Int> = buildList {
        for (index in 0 until length()) add(getInt(index))
    }

    private fun JSONObject.toPayouts(): Map<String, Map<Int, Int>> =
        keys().asSequence().associateWith { symbol -> getJSONObject(symbol).toIntMap() }

    private fun JSONObject.toIntMap(): Map<Int, Int> =
        keys().asSequence().associate { key -> key.toInt() to getInt(key) }

    private companion object {
        val PAYOUT_COUNTS = setOf(3, 4, 5)
    }
}

private class V5CanonicalPayloadWriter {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)

    fun writeInt(value: Int) {
        output.writeInt(value)
    }

    fun writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(encoded.size)
        output.write(encoded)
    }

    fun writeInts(values: List<Int>) {
        writeInt(values.size)
        values.forEach(::writeInt)
    }

    fun writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach(::writeString)
    }

    fun toByteArray(): ByteArray {
        output.flush()
        return bytes.toByteArray()
    }
}
