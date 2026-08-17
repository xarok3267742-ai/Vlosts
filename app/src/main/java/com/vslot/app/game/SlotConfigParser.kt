package com.vslot.app.game

import org.json.JSONObject

class SlotConfigParser {
    fun parse(json: String): List<SlotConfig> {
        val root = JSONObject(json)
        val slots = root.getJSONArray("slots")
        return buildList {
            for (index in 0 until slots.length()) {
                val slot = slots.getJSONObject(index)
                add(slot.toSlotConfig())
            }
        }.also(::validateSlots)
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

    private fun parseTheme(theme: String): SlotTheme {
        return when (theme) {
            "violet" -> SlotTheme.Violet
            "roman" -> SlotTheme.Roman
            "neon" -> SlotTheme.Neon
            "pharaoh" -> SlotTheme.Pharaoh
            "ocean" -> SlotTheme.Ocean
            else -> error("Unsupported slot theme: $theme")
        }
    }

    private fun validateSlots(slots: List<SlotConfig>) {
        require(slots.isNotEmpty()) { "Slot config must define at least one slot." }
        val duplicateIds = slots.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate slot ids: ${duplicateIds.joinToString()}" }
        slots.forEach(::validateSlot)
    }

    private fun validateSlot(slot: SlotConfig) {
        require(slot.id.isNotBlank()) { "Slot id must not be blank." }
        require(slot.name.isNotBlank()) { "Slot ${slot.id} must define a display name." }
        require(slot.reels == SUPPORTED_REELS) { "Slot ${slot.id} must define $SUPPORTED_REELS reels." }
        require(slot.rows == SUPPORTED_ROWS) { "Slot ${slot.id} must define $SUPPORTED_ROWS visible rows." }
        require(slot.paylines in 1..SlotEngine.PAYLINE_ROWS.size) {
            "Slot ${slot.id} must define 1..${SlotEngine.PAYLINE_ROWS.size} paylines."
        }
        require(slot.symbols.isNotEmpty()) { "Slot ${slot.id} must define symbols." }
        require(slot.symbols.none { it.isBlank() }) { "Slot ${slot.id} contains a blank symbol." }
        require(slot.symbols.distinct().size == slot.symbols.size) { "Slot ${slot.id} contains duplicate symbols." }
        require(slot.wild in slot.symbols) { "Slot ${slot.id} wild symbol must be listed in symbols." }
        require(slot.scatter in slot.symbols) { "Slot ${slot.id} scatter symbol must be listed in symbols." }
        require(slot.wild != slot.scatter) { "Slot ${slot.id} wild and scatter symbols must differ." }
        require(slot.bets.isNotEmpty()) { "Slot ${slot.id} must define bet options." }
        require(slot.bets.all { it > 0 }) { "Slot ${slot.id} bets must be positive." }
        require(slot.bets == slot.bets.distinct().sorted()) { "Slot ${slot.id} bets must be unique and ascending." }
        require(slot.payouts.keys.none { it == slot.scatter }) {
            "Slot ${slot.id} scatter must use scatterBonus instead of line payouts."
        }

        val lineSymbols = slot.symbols.filterNot { it == slot.scatter }
        lineSymbols.forEach { symbol ->
            val payouts = slot.payouts[symbol]
            require(!payouts.isNullOrEmpty()) { "Slot ${slot.id} missing payouts for $symbol." }
            PAYOUT_COUNTS.forEach { count ->
                require((payouts[count] ?: 0) > 0) { "Slot ${slot.id} $symbol missing ${count}x payout." }
            }
            require(payouts.keys.all { it in PAYOUT_COUNTS }) {
                "Slot ${slot.id} $symbol contains unsupported payout counts."
            }
            require(payouts.strictlyIncreaseWithPayoutCount()) {
                "Slot ${slot.id} $symbol payouts must increase with match length."
            }
        }
        require(slot.payouts.keys.all { it in lineSymbols }) { "Slot ${slot.id} contains payouts for unknown symbols." }
        PAYOUT_COUNTS.forEach { count ->
            require((slot.scatterBonus[count] ?: 0) > 0) { "Slot ${slot.id} missing $count scatter bonus." }
        }
        require(slot.scatterBonus.keys.all { it in PAYOUT_COUNTS }) {
            "Slot ${slot.id} contains unsupported scatter bonus counts."
        }
        require(slot.scatterBonus.strictlyIncreaseWithPayoutCount()) {
            "Slot ${slot.id} scatter bonuses must increase with scatter count."
        }
        validateEconomyRange(slot)

        validateReelStripSet(slot, slot.reelStrips, "paid")
        validateReelStripSet(slot, slot.freeSpinReelStrips, "free-spin")
        slot.reelStrips.zip(slot.freeSpinReelStrips).forEachIndexed { reelIndex, (paid, free) ->
            validateFreeSpinFeatureWeights(slot, reelIndex, paid, free)
        }
        require(slot.freeSpinReelStrips != slot.reelStrips) {
            "Slot ${slot.id} must define a distinct physical free-spin reel order."
        }
    }

    private fun validateFreeSpinFeatureWeights(
        slot: SlotConfig,
        reelIndex: Int,
        paid: List<String>,
        free: List<String>
    ) {
        require(paid.size == free.size) {
            "Slot ${slot.id} free-spin reel $reelIndex must preserve the paid-reel length."
        }
        val paidCounts = paid.groupingBy { it }.eachCount()
        val freeCounts = free.groupingBy { it }.eachCount()
        if (reelIndex != FREE_SPIN_ENHANCED_WILD_REEL_INDEX) {
            require(paidCounts == freeCounts) {
                "Slot ${slot.id} free-spin reel $reelIndex must preserve the reviewed paid-reel symbol weights."
            }
            return
        }

        require(freeCounts.getValue(slot.wild) == paidCounts.getValue(slot.wild) + FREE_SPIN_EXTRA_WILDS) {
            "Slot ${slot.id} enhanced free-spin reel must contain exactly one additional wild."
        }
        require(freeCounts.getValue(slot.scatter) == paidCounts.getValue(slot.scatter)) {
            "Slot ${slot.id} enhanced free-spin reel must preserve the scatter weight."
        }
        val reducedSymbols = slot.symbols.filter { symbol ->
            symbol != slot.wild && freeCounts.getValue(symbol) == paidCounts.getValue(symbol) - 1
        }
        require(reducedSymbols.size == 1 && reducedSymbols.single() != slot.scatter) {
            "Slot ${slot.id} enhanced free-spin reel must replace one ordinary symbol with the extra wild."
        }
        val replacedSymbol = reducedSymbols.single()
        require(slot.symbols.all { symbol ->
            symbol == slot.wild || symbol == replacedSymbol ||
                freeCounts.getValue(symbol) == paidCounts.getValue(symbol)
        }) {
            "Slot ${slot.id} enhanced free-spin reel contains an unreviewed symbol-weight change."
        }
    }

    private fun validateReelStripSet(
        slot: SlotConfig,
        strips: List<List<String>>,
        mode: String
    ) {
        require(strips.size == slot.reels) {
            "Slot ${slot.id} must define one $mode reel strip per reel."
        }
        strips.forEachIndexed { reelIndex, strip ->
            require(strip.size >= slot.rows) {
                "Slot ${slot.id} $mode reel strip $reelIndex must contain at least ${slot.rows} symbols."
            }
            require(strip.all { it in slot.symbols }) {
                "Slot ${slot.id} $mode reel strip $reelIndex contains an unknown symbol."
            }
            require(strip.count { it == slot.scatter } == EXPECTED_SCATTER_PER_STRIP) {
                "Slot ${slot.id} $mode reel strip $reelIndex must contain exactly one scatter."
            }
            require(slot.symbols.all(strip::contains)) {
                "Slot ${slot.id} $mode reel strip $reelIndex must contain every configured symbol."
            }
        }
    }

    private fun validateEconomyRange(slot: SlotConfig) {
        val maximumBet = slot.bets.last()
        val maximumTotalBet = maximumBet.toLong() * slot.paylines.toLong()
        require(maximumTotalBet <= Int.MAX_VALUE.toLong()) {
            "Slot ${slot.id} maximum total bet exceeds the supported integer range."
        }

        val maximumLineMultiplier = slot.payouts.values
            .flatMap { it.values }
            .max()
        val maximumLinePayout = maximumLineMultiplier.toLong() * maximumBet.toLong()
        require(maximumLinePayout <= Int.MAX_VALUE.toLong()) {
            "Slot ${slot.id} maximum line payout exceeds the supported integer range."
        }

        val maximumScatterMultiplier = slot.scatterBonus.values.max()
        val maximumScatterPayout = maximumScatterMultiplier.toLong() * maximumTotalBet
        require(maximumScatterPayout <= Int.MAX_VALUE.toLong()) {
            "Slot ${slot.id} maximum scatter payout exceeds the supported integer range."
        }

        val maximumCombinedPayout = maximumLinePayout * slot.paylines.toLong() + maximumScatterPayout
        require(maximumCombinedPayout <= Int.MAX_VALUE.toLong()) {
            "Slot ${slot.id} maximum combined payout exceeds the supported integer range."
        }
    }

    private fun Map<Int, Int>.strictlyIncreaseWithPayoutCount(): Boolean {
        val orderedValues = PAYOUT_COUNTS.sorted().map { count -> getValue(count) }
        return orderedValues.zipWithNext().all { (previous, next) -> next > previous }
    }

    private fun org.json.JSONArray.toStringListList(): List<List<String>> {
        return buildList {
            for (index in 0 until length()) {
                add(getJSONArray(index).toStringList())
            }
        }
    }

    private fun org.json.JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                add(getString(index))
            }
        }
    }

    private fun org.json.JSONArray.toIntList(): List<Int> {
        return buildList {
            for (index in 0 until length()) {
                add(getInt(index))
            }
        }
    }

    private fun JSONObject.toPayouts(): Map<String, Map<Int, Int>> {
        return keys().asSequence().associateWith { symbol ->
            getJSONObject(symbol).toIntMap()
        }
    }

    private fun JSONObject.toIntMap(): Map<Int, Int> {
        return keys().asSequence().associate { key ->
            key.toInt() to getInt(key)
        }
    }

    private companion object {
        const val SUPPORTED_REELS = 5
        const val SUPPORTED_ROWS = 3
        const val EXPECTED_SCATTER_PER_STRIP = 1
        const val FREE_SPIN_ENHANCED_WILD_REEL_INDEX = 2
        const val FREE_SPIN_EXTRA_WILDS = 1
        val PAYOUT_COUNTS = setOf(3, 4, 5)
    }
}
