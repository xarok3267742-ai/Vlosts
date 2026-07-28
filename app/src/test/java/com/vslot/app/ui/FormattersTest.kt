package com.vslot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test
    fun `coin formatter groups long balance above int max using Russian spacing`() {
        val formatted = (Int.MAX_VALUE.toLong() + 1L)
            .asCoins()
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')

        assertEquals("2 147 483 648", formatted)
    }
}
