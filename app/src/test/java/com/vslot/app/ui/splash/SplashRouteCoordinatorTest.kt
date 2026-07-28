package com.vslot.app.ui.splash

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashRouteCoordinatorTest {
    @Test
    fun `fragment retries pending route from resumed state only`() {
        val source = Path.of(
            "src/main/java/com/vslot/app/ui/splash/SplashFragment.kt"
        ).readText()

        assertTrue(source.contains("override fun onResume()"))
        assertTrue(source.contains("dispatchPendingRoute()"))
        assertTrue(source.contains("Lifecycle.State.RESUMED"))
        assertTrue(source.contains("!parentFragmentManager.isStateSaved"))
        assertTrue(source.contains("routeCoordinator.submit(route)"))
        assertTrue(source.contains("routeCoordinator.dispatch(canNavigate)"))
        assertTrue(source.contains("recoverPendingSpinSettlement(ProcessSession.id)"))
        assertTrue(source.contains("pendingSpinPresentationSlotId()"))
        assertTrue(source.contains("SplashRoute.PendingPresentation"))
    }

    @Test
    fun `route skipped in background is dispatched exactly once after resume`() {
        val coordinator = SplashRouteCoordinator<String>()
        var navigationCount = 0
        coordinator.submit("home")

        assertFalse(
            coordinator.dispatch(canNavigate = false) {
                navigationCount += 1
                true
            }
        )
        assertTrue(
            coordinator.dispatch(canNavigate = true) { route ->
                assertEquals("home", route)
                navigationCount += 1
                true
            }
        )
        assertFalse(
            coordinator.dispatch(canNavigate = true) {
                navigationCount += 1
                true
            }
        )
        assertEquals(1, navigationCount)
    }

    @Test
    fun `failed safe navigation remains pending for the next resume`() {
        val coordinator = SplashRouteCoordinator<String>()
        var attempts = 0
        coordinator.submit("disclaimer")

        assertFalse(
            coordinator.dispatch(canNavigate = true) {
                attempts += 1
                false
            }
        )
        assertTrue(
            coordinator.dispatch(canNavigate = true) { route ->
                assertEquals("disclaimer", route)
                attempts += 1
                true
            }
        )
        assertEquals(2, attempts)
    }
}
