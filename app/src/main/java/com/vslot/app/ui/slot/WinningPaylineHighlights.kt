package com.vslot.app.ui.slot

import com.vslot.app.game.WinningLine

internal object WinningPaylineHighlights {
    fun cellIndexes(line: WinningLine?, reelCount: Int): Set<Int> {
        if (line == null || reelCount <= 0) return emptySet()
        return line.positions
            .map { position -> position.row * reelCount + position.reel }
            .toSet()
    }
}
