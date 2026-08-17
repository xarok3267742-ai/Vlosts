package com.vslot.app.game

import java.security.SecureRandom

data class SlotConfig(
    val id: String,
    val name: String,
    val theme: SlotTheme,
    val reels: Int,
    val rows: Int,
    val paylines: Int,
    val wild: String,
    val scatter: String,
    val symbols: List<String>,
    val bets: List<Int>,
    val payouts: Map<String, Map<Int, Int>>,
    val scatterBonus: Map<Int, Int>,
    val reelStrips: List<List<String>>,
    val freeSpinReelStrips: List<List<String>>
)

enum class SlotTheme {
    Violet,
    Roman,
    Neon,
    Pharaoh,
    Ocean
}

data class WinningLine(
    val paylineIndex: Int,
    val symbol: String,
    val count: Int,
    val amount: Int,
    val positions: List<SymbolPosition>
)

data class SymbolPosition(
    val reel: Int,
    val row: Int
)

data class SpinResult(
    val reels: List<List<String>>,
    val bet: Int,
    val lines: Int = 10,
    val totalBet: Int,
    val winAmount: Int,
    val resultType: ResultType,
    val winningLines: List<WinningLine>,
    val scatterCount: Int,
    val scatterPositions: List<SymbolPosition>,
    val freeSpinsAwarded: Int = 0,
    val stopIndexes: List<Int> = emptyList(),
    val isFreeSpin: Boolean = false
)

enum class ResultType {
    Lose,
    Win,
    Bonus
}

enum class NetOutcome {
    Loss,
    PartialReturn,
    BreakEven,
    NetWin,
    Bonus
}

val SpinResult.netOutcome: NetOutcome
    get() = when {
        resultType == ResultType.Bonus || freeSpinsAwarded > 0 -> NetOutcome.Bonus
        resultType == ResultType.Lose -> NetOutcome.Loss
        winAmount <= 0 -> NetOutcome.Loss
        isFreeSpin -> NetOutcome.NetWin
        winAmount < totalBet -> NetOutcome.PartialReturn
        winAmount == totalBet -> NetOutcome.BreakEven
        else -> NetOutcome.NetWin
    }

val SpinResult.netAmount: Long
    get() = winAmount.toLong() - if (isFreeSpin) 0L else totalBet.toLong()

interface SlotRng {
    fun nextInt(bound: Int): Int
}

class SecureSlotRng(
    private val random: SecureRandom = SecureRandom()
) : SlotRng {
    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}
