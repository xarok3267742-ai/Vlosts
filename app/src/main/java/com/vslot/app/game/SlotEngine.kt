package com.vslot.app.game

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
    ): SpinResult = ReleasedSlotMathV5.spin(config, rng, bet, lines, isFreeSpin)

    fun evaluateStops(
        config: SlotConfig,
        stopIndexes: List<Int>,
        bet: Int,
        lines: Int = config.paylines,
        isFreeSpin: Boolean = false
    ): SpinResult = ReleasedSlotMathV5.evaluateStops(
        config,
        stopIndexes,
        bet,
        lines,
        isFreeSpin
    )

    fun evaluate(
        config: SlotConfig,
        reels: List<List<String>>,
        bet: Int,
        lines: Int = config.paylines,
        stopIndexes: List<Int> = emptyList(),
        isFreeSpin: Boolean = false
    ): SpinResult = ReleasedSlotMathV5.evaluate(
        config,
        reels,
        bet,
        lines,
        stopIndexes,
        isFreeSpin
    )

    internal companion object {
        const val FREE_SPINS_BONUS_AWARD = ReleasedSlotMathV5.FREE_SPINS_BONUS_AWARD
        val PAYLINE_ROWS: List<List<Int>>
            get() = ReleasedSlotMathV5.paylineRows
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
