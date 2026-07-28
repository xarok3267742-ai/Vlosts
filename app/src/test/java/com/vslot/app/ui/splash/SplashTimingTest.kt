package com.vslot.app.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Test

class SplashTimingTest {
    @Test
    fun `animated splash preserves branded presentation time`() {
        assertEquals(
            SplashTiming.ANIMATED_ROUTE_DELAY_MS,
            SplashTiming.routeDelayMs(animationsEnabled = true)
        )
    }

    @Test
    fun `disabled animations route without a static pause`() {
        assertEquals(0L, SplashTiming.routeDelayMs(animationsEnabled = false))
    }
}
