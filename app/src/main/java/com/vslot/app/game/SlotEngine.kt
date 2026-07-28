package com.vslot.app.game

import com.vslot.app.SlotRules

internal fun checkedSlotMultiply(left: Int, right: Int, description: String): Int {
    return try {
        Math.multiplyExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("$description exceeds the supported integer range.", error)
    }
}

internal fun checkedSlotAdd(left: Int, right: Int, description: String): Int {
    return try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("$description exceeds the supported integer range.", error)
    }
}

class SlotEngine(
    private val rng: SlotRng = SecureSlotRng()
) {
    fun spin(
        config: SlotConfig,
        bet: Int,
        lines: Int = config.paylines,
        isFreeSpin: Boolean = false
    ): SpinResult {
        require(config.reels == 5) { "V Slot supports five reels." }
        require(config.rows == 3) { "V Slot supports three visible rows." }
        require(bet in config.bets) { "Unsupported bet: $bet" }
        require(lines in 1..config.paylines) { "Unsupported active lines: $lines" }
        require(lines <= PAYLINE_ROWS.size) { "V Slot supports up to ${PAYLINE_ROWS.size} paylines." }
        validateReelStrips(config)

        val stopIndexes = config.reelStripsFor(isFreeSpin).map { strip -> rng.nextInt(strip.size) }
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
        val reels = config.reelStripsFor(isFreeSpin).mapIndexed { reelIndex, _ ->
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
        require(lines <= PAYLINE_ROWS.size) { "V Slot supports up to ${PAYLINE_ROWS.size} paylines." }
        validateEvaluationInputs(config, reels, bet, stopIndexes, isFreeSpin)

        val totalBet = checkedSlotMultiply(bet, lines, "Total bet")
        val activePaylines = PAYLINE_ROWS.take(lines)
        val winningLines = activePaylines.mapIndexedNotNull { index, rows ->
            evaluatePayline(config, reels, rows, index, bet)
        }
        val lineWin = winningLines.fold(0) { total, winningLine ->
            checkedSlotAdd(total, winningLine.amount, "Combined line payout")
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
        val scatterWin = checkedSlotMultiply(scatterMultiplier, totalBet, "Scatter payout")
        val totalWin = checkedSlotAdd(lineWin, scatterWin, "Total payout")
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
            stopIndexes = stopIndexes
        )
    }

    private fun evaluatePayline(
        config: SlotConfig,
        reels: List<List<String>>,
        rows: List<Int>,
        index: Int,
        bet: Int
    ): WinningLine? {
        val symbols = rows.mapIndexed { reelIndex, rowIndex ->
            reels[reelIndex][rowIndex]
        }
        return config.payouts.keys
            .filter { it != config.scatter }
            .mapNotNull { target -> evaluatePaylineTarget(config, symbols, rows, index, bet, target) }
            .maxWithOrNull(
                compareBy<WinningLine> { it.amount }
                    .thenBy { it.count }
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
            amount = checkedSlotMultiply(multiplier, bet, "Line payout"),
            positions = (0 until count).map { reelIndex ->
                SymbolPosition(
                    reel = reelIndex,
                    row = rows[reelIndex]
                )
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
        return List(config.rows) { row ->
            config.reelSymbolAt(reelIndex, stopIndex + row, isFreeSpin)
        }
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
        val strips = config.reelStripsFor(isFreeSpin)
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
            val strips = config.reelStripsFor(isFreeSpin)
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

    internal companion object {
        const val FREE_SPINS_BONUS_AWARD = SlotRules.FREE_SPINS_BONUS_AWARD
        val PAYLINE_ROWS = listOf(
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
    }
}

internal fun SlotConfig.reelSymbolAt(
    reelIndex: Int,
    logicalPosition: Int,
    isFreeSpin: Boolean
): String {
    val strip = reelStripsFor(isFreeSpin)[reelIndex]
    return strip[logicalPosition.mod(strip.size)]
}

internal fun SlotConfig.reelStripsFor(isFreeSpin: Boolean): List<List<String>> =
    if (isFreeSpin) freeSpinReelStrips else reelStrips
