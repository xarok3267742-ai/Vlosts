package com.vslot.app.ui.slot

import com.vslot.app.game.SlotConfig

internal fun initialSlotReels(config: SlotConfig): List<List<String>> {
    require(config.reelStrips.size == config.reels) {
        "Slot ${config.id} must define one strip per visible reel."
    }
    return config.reelStrips.mapIndexed { reelIndex, strip ->
        require(strip.size >= config.rows) {
            "Slot ${config.id} reel $reelIndex cannot fill its visible window."
        }
        List(config.rows) { rowIndex -> strip[rowIndex] }
    }
}
