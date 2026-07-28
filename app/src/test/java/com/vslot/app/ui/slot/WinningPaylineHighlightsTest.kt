package com.vslot.app.ui.slot

import com.vslot.app.game.SymbolPosition
import com.vslot.app.game.WinningLine
import org.junit.Assert.assertEquals
import org.junit.Test

class WinningPaylineHighlightsTest {
    @Test
    fun `carousel highlights only cells belonging to its visible payline`() {
        val firstLine = line(
            paylineIndex = 0,
            positions = listOf(SymbolPosition(0, 0), SymbolPosition(1, 0), SymbolPosition(2, 0))
        )
        val secondLine = line(
            paylineIndex = 1,
            positions = listOf(SymbolPosition(0, 2), SymbolPosition(1, 1), SymbolPosition(2, 2))
        )

        assertEquals(setOf(0, 1, 2), WinningPaylineHighlights.cellIndexes(firstLine, 5))
        assertEquals(setOf(10, 6, 12), WinningPaylineHighlights.cellIndexes(secondLine, 5))
    }

    private fun line(paylineIndex: Int, positions: List<SymbolPosition>): WinningLine {
        return WinningLine(
            paylineIndex = paylineIndex,
            symbol = "A",
            count = positions.size,
            amount = 100,
            positions = positions
        )
    }
}
