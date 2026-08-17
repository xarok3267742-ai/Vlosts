package com.vslot.app.ui.slot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AffordableStakePolicyTest {
    @Test
    fun `keeps line coverage and lowers line bet when possible`() {
        assertEquals(
            AffordableStake(lineBet = 10, lines = 10),
            AffordableStakePolicy.select(
                balance = 145,
                selectedLines = 10,
                supportedBets = listOf(10, 25, 50),
                maxLines = 10
            )
        )
    }

    @Test
    fun `lowers line count only when minimum bet cannot cover current lines`() {
        assertEquals(
            AffordableStake(lineBet = 10, lines = 4),
            AffordableStakePolicy.select(
                balance = 49,
                selectedLines = 10,
                supportedBets = listOf(10, 25, 50),
                maxLines = 10
            )
        )
    }

    @Test
    fun `returns null when even the minimum stake is unavailable`() {
        assertNull(
            AffordableStakePolicy.select(
                balance = 9,
                selectedLines = 10,
                supportedBets = listOf(10, 25, 50),
                maxLines = 10
            )
        )
    }
}
