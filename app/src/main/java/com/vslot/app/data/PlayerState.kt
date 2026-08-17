package com.vslot.app.data

import com.vslot.app.SlotRules
import com.vslot.app.game.ReleasedSlotMathV5

data class PlayerState(
    val coinsBalance: Long = STARTING_BALANCE,
    val lastDailyBonusTimestamp: Long = 0L,
    val selectedBet: Int = DEFAULT_BET,
    val selectedLines: Int = DEFAULT_LINES,
    val freeSpinsBalance: Int = 0,
    val freeSpinBet: Int = 0,
    val freeSpinLines: Int = 0,
    val freeSpinSlotId: String = "",
    val freeSpinBonuses: Map<String, FreeSpinBonus> = emptyMap(),
    val freeSpinAutoPlaySlots: Set<String> = emptySet(),
    val freeSpinFeatureTotalWins: Map<String, Int> = emptyMap(),
    val levelXp: Int = 0,
    val disclaimerAccepted: Boolean = false,
    val pushPermissionAsked: Boolean = false,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val analyticsEnabled: Boolean = false,
    val lastPlayedSlot: String = DEFAULT_SLOT_ID
) {
    private val normalizedLevelXp: Int
        get() = levelXp.coerceIn(0, maxLevelXp())

    val playerLevel: Int
        get() = levelForXp(normalizedLevelXp)

    val xpInCurrentLevel: Int
        get() = (normalizedLevelXp - xpRequiredForLevel(playerLevel)).coerceIn(0, xpForCurrentLevel)

    val xpForCurrentLevel: Int
        get() = xpRequiredForLevel(playerLevel + 1) - xpRequiredForLevel(playerLevel)

    companion object {
        const val STARTING_BALANCE = 10_000L
        const val DAILY_BONUS_AMOUNT = 1_000
        const val DEFAULT_BET = 10
        const val MIN_LINES = 1
        const val MAX_LINES = SlotRules.MAX_PAYLINES
        const val DEFAULT_LINES = MAX_LINES
        const val FREE_SPINS_BONUS_AWARD = SlotRules.FREE_SPINS_BONUS_AWARD
        const val DEFAULT_SLOT_ID = "violet_fortune"
        const val DAILY_BONUS_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val MAX_PLAYER_LEVEL = 99
        private const val LEVEL_BASE_XP = 260
        private const val LEVEL_STEP_XP = 95

        fun xpRequiredForLevel(level: Int): Int {
            val normalizedLevel = level.coerceIn(1, MAX_PLAYER_LEVEL + 1)
            val completedLevels = (normalizedLevel - 1).toLong()
            val requiredXp = completedLevels * LEVEL_BASE_XP +
                (completedLevels * (completedLevels - 1L) / 2L) * LEVEL_STEP_XP
            return requiredXp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        fun maxLevelXp(): Int {
            return xpRequiredForLevel(MAX_PLAYER_LEVEL + 1)
        }

        fun normalizedDailyBonusTimestamp(lastDailyBonusTimestamp: Long): Long {
            return lastDailyBonusTimestamp.coerceAtLeast(0L)
        }

        fun normalizedLastPlayedSlot(slotId: String): String {
            return slotId.takeIf { it.isNotBlank() } ?: DEFAULT_SLOT_ID
        }

        fun dailyBonusRemainingMs(
            lastDailyBonusTimestamp: Long,
            now: Long = System.currentTimeMillis()
        ): Long {
            val safeNow = now.coerceAtLeast(0L)
            val safeLastClaim = normalizedDailyBonusTimestamp(lastDailyBonusTimestamp)
            if (safeLastClaim <= 0L) return 0L
            if (safeLastClaim > safeNow) {
                val clockRollbackMs = safeLastClaim - safeNow
                return if (clockRollbackMs > Long.MAX_VALUE - DAILY_BONUS_INTERVAL_MS) {
                    Long.MAX_VALUE
                } else {
                    clockRollbackMs + DAILY_BONUS_INTERVAL_MS
                }
            }
            val elapsedMs = safeNow - safeLastClaim
            return (DAILY_BONUS_INTERVAL_MS - elapsedMs).coerceAtLeast(0L)
        }

        fun levelForXp(xp: Int): Int {
            val normalizedXp = xp.coerceIn(0, maxLevelXp())
            var level = 1
            while (level < MAX_PLAYER_LEVEL && normalizedXp >= xpRequiredForLevel(level + 1)) {
                level += 1
            }
            return level
        }

        fun xpForSpin(totalBet: Int, isFreeSpin: Boolean, winAmount: Int): Int {
            return ReleasedSlotMathV5.xpForSpin(totalBet, isFreeSpin, winAmount)
        }
    }

    fun isDailyBonusAvailable(now: Long = System.currentTimeMillis()): Boolean {
        return dailyBonusRemainingMs(lastDailyBonusTimestamp, now) <= 0L
    }

    fun freeSpinsForSlot(slotId: String): Int {
        val bonus = freeSpinBonuses[slotId]
        if (bonus != null) return bonus.count
        if (freeSpinsBalance <= 0) return 0
        return if (freeSpinSlotId.isBlank() || freeSpinSlotId == slotId) freeSpinsBalance else 0
    }

    fun hasFreeSpinsForSlot(slotId: String): Boolean {
        return freeSpinsForSlot(slotId) > 0
    }

    fun shouldAutoPlayFreeSpinsForSlot(slotId: String): Boolean {
        return slotId.isNotBlank() && slotId in freeSpinAutoPlaySlots
    }

    fun freeSpinFeatureTotalWinForSlot(slotId: String): Int? {
        return freeSpinFeatureTotalWins[slotId]?.coerceAtLeast(0)
    }

    fun freeSpinBetForSlot(slotId: String): Int {
        val bonus = freeSpinBonuses[slotId]
        if (bonus != null) return bonus.lineBet
        if (freeSpinsBalance <= 0) return 0
        return if (freeSpinSlotId.isBlank() || freeSpinSlotId == slotId) freeSpinBet else 0
    }

    fun freeSpinLinesForSlot(slotId: String): Int {
        val bonus = freeSpinBonuses[slotId]
        if (bonus != null) return bonus.lines
        if (freeSpinsBalance <= 0) return 0
        return if (freeSpinSlotId.isBlank() || freeSpinSlotId == slotId) freeSpinLines else 0
    }
}

data class FreeSpinBonus(
    val slotId: String,
    val count: Int,
    val lineBet: Int,
    val lines: Int
)

internal fun normalizedFreeSpinBonuses(bonuses: Map<String, FreeSpinBonus>): List<FreeSpinBonus> {
    return bonuses.values
        .filter { it.slotId.isNotBlank() && it.count > 0 && it.lineBet > 0 && it.lines > 0 }
        .map { it.copy(lines = it.lines.coerceIn(PlayerState.MIN_LINES, PlayerState.MAX_LINES)) }
        .sortedBy { it.slotId }
}

internal fun normalizedFreeSpinFeatureTotalWins(totalWins: Map<String, Int>): Map<String, Int> {
    return totalWins.entries
        .asSequence()
        .filter { (slotId, totalWin) -> slotId.isNotBlank() && totalWin >= 0 }
        .sortedBy(Map.Entry<String, Int>::key)
        .associateTo(linkedMapOf()) { it.key to it.value }
}

internal fun saturatedNonNegativeSum(values: Iterable<Int>): Int {
    var sum = 0L
    values.forEach { value ->
        if (value > 0) {
            sum = (sum + value.toLong()).coerceAtMost(Int.MAX_VALUE.toLong())
        }
    }
    return sum.toInt()
}

internal fun saturatedNonNegativeAdd(first: Int, second: Int): Int {
    return saturatedNonNegativeSum(listOf(first, second))
}

internal fun saturatedNonNegativeAdd(first: Long, second: Int): Long {
    val normalizedFirst = first.coerceAtLeast(0L)
    val normalizedSecond = second.coerceAtLeast(0).toLong()
    return if (normalizedFirst > Long.MAX_VALUE - normalizedSecond) {
        Long.MAX_VALUE
    } else {
        normalizedFirst + normalizedSecond
    }
}

internal fun mergeAwardedFreeSpinBonus(
    currentBonuses: Map<String, FreeSpinBonus>,
    legacySlotId: String,
    legacyCount: Int,
    legacyLineBet: Int,
    legacyLines: Int,
    awardSlotId: String,
    awardCount: Int,
    awardLineBet: Int,
    awardLines: Int
): Map<String, FreeSpinBonus> {
    if (awardSlotId.isBlank() || awardCount <= 0 || awardLineBet <= 0 || awardLines <= 0) {
        return currentBonuses
    }

    val bonuses = currentBonuses.toMutableMap()
    if (bonuses.isEmpty() && legacyCount > 0 && legacyLineBet > 0 && legacyLines > 0) {
        val migratedSlotId = legacySlotId.ifBlank { awardSlotId }
        bonuses[migratedSlotId] = FreeSpinBonus(
            slotId = migratedSlotId,
            count = legacyCount,
            lineBet = legacyLineBet,
            lines = legacyLines
        )
    }

    val existingBonus = bonuses[awardSlotId]
    bonuses[awardSlotId] = FreeSpinBonus(
        slotId = awardSlotId,
        count = saturatedNonNegativeAdd(existingBonus?.count ?: 0, awardCount),
        lineBet = awardLineBet,
        lines = awardLines
    )
    return bonuses
}

internal fun PlayerState.withFreeSpinBonusesSnapshot(
    bonuses: Map<String, FreeSpinBonus>
): PlayerState {
    val normalizedBonuses = normalizedFreeSpinBonuses(bonuses)
    val firstBonus = normalizedBonuses.firstOrNull()
    return copy(
        freeSpinsBalance = saturatedNonNegativeSum(normalizedBonuses.map { it.count }),
        freeSpinBet = firstBonus?.lineBet ?: 0,
        freeSpinLines = firstBonus?.lines ?: 0,
        freeSpinSlotId = firstBonus?.slotId.orEmpty(),
        freeSpinBonuses = normalizedBonuses.associateBy { it.slotId }
    )
}

internal fun PlayerState.normalized(): PlayerState {
    val normalizedBonuses = normalizedFreeSpinBonuses(freeSpinBonuses)
    val firstBonus = normalizedBonuses.firstOrNull()
    val normalizedFreeSpinsBalance = if (normalizedBonuses.isNotEmpty()) {
        saturatedNonNegativeSum(normalizedBonuses.map { it.count })
    } else {
        freeSpinsBalance.coerceAtLeast(0)
    }
    return copy(
        coinsBalance = coinsBalance.coerceAtLeast(0),
        lastDailyBonusTimestamp = PlayerState.normalizedDailyBonusTimestamp(lastDailyBonusTimestamp),
        selectedBet = selectedBet.takeIf { it > 0 } ?: PlayerState.DEFAULT_BET,
        selectedLines = selectedLines.coerceIn(PlayerState.MIN_LINES, PlayerState.MAX_LINES),
        freeSpinsBalance = normalizedFreeSpinsBalance,
        freeSpinBet = firstBonus?.lineBet ?: freeSpinBet.coerceAtLeast(0),
        freeSpinLines = firstBonus?.lines ?: freeSpinLines.coerceIn(0, PlayerState.MAX_LINES),
        freeSpinSlotId = firstBonus?.slotId ?: freeSpinSlotId.takeIf { normalizedFreeSpinsBalance > 0 }.orEmpty(),
        freeSpinBonuses = normalizedBonuses.associateBy { it.slotId },
        freeSpinAutoPlaySlots = freeSpinAutoPlaySlots.filterTo(mutableSetOf()) { it.isNotBlank() },
        freeSpinFeatureTotalWins = normalizedFreeSpinFeatureTotalWins(freeSpinFeatureTotalWins),
        levelXp = levelXp.coerceIn(0, PlayerState.maxLevelXp()),
        lastPlayedSlot = PlayerState.normalizedLastPlayedSlot(lastPlayedSlot)
    )
}

data class DailyBonusClaimResult(
    val claimed: Boolean,
    val amount: Int,
    val balanceAfter: Long
)
