package com.vslot.app.game

import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSlotRngTest {
    @Test
    fun `secure reel stop rng always respects exclusive bound`() {
        val rng = SecureSlotRng()

        listOf(1, 2, 3, 17, 31, 64).forEach { bound ->
            repeat(1_000) {
                assertTrue(rng.nextInt(bound) in 0 until bound)
            }
        }
    }
}
