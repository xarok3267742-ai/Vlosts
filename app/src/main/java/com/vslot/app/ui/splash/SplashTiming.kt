package com.vslot.app.ui.splash

object SplashTiming {
    const val ANIMATED_ROUTE_DELAY_MS = 1_700L

    fun routeDelayMs(animationsEnabled: Boolean): Long {
        return if (animationsEnabled) ANIMATED_ROUTE_DELAY_MS else 0L
    }
}
