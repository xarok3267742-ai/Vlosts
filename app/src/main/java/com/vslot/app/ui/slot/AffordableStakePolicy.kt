package com.vslot.app.ui.slot

internal data class AffordableStake(
    val lineBet: Int,
    val lines: Int
)

internal object AffordableStakePolicy {
    fun select(
        balance: Long,
        selectedLines: Int,
        supportedBets: List<Int>,
        maxLines: Int
    ): AffordableStake? {
        val bets = supportedBets.filter { it > 0 }.distinct().sorted()
        val minimumBet = bets.firstOrNull() ?: return null
        val safeBalance = balance.coerceAtLeast(0L)
        if (safeBalance < minimumBet) return null

        val currentLines = selectedLines.coerceIn(1, maxLines.coerceAtLeast(1))
        val lineBetAtCurrentCoverage = bets.lastOrNull { bet ->
            bet.toLong() * currentLines <= safeBalance
        }
        if (lineBetAtCurrentCoverage != null) {
            return AffordableStake(lineBet = lineBetAtCurrentCoverage, lines = currentLines)
        }

        val affordableLines = (safeBalance / minimumBet)
            .coerceAtMost(currentLines.toLong())
            .toInt()
            .coerceAtLeast(1)
        return AffordableStake(lineBet = minimumBet, lines = affordableLines)
    }
}
