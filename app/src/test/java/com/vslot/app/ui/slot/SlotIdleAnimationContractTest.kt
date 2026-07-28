package com.vslot.app.ui.slot

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotIdleAnimationContractTest {
    @Test
    fun `idle cabinet effects settle instead of running infinite property animators`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val spinReadyGlow = source
            .substringAfter("private fun startSpinReadyGlow()")
            .substringBefore("private fun stopSpinReadyGlow")
        val ambientOverlay = source
            .substringAfter("private fun updateThemeAmbientOverlay")
            .substringBefore("private fun stopThemeAmbientOverlay")
        val cabinetLights = source
            .substringAfter("private fun updateCabinetLights")
            .substringBefore("private fun stopCabinetLights")
        val freeSpinsRail = source
            .substringAfter("private fun startFreeSpinsRailCharge")
            .substringBefore("private fun stopFreeSpinsRailCharge")
        val freeSpinsMode = source
            .substringAfter("private fun startFreeSpinsModeOverlay")
            .substringBefore("private fun stopFreeSpinsModeOverlay")

        assertFalse(spinReadyGlow.contains("repeatCount = ValueAnimator.INFINITE"))
        assertFalse(ambientOverlay.contains("repeatCount = ValueAnimator.INFINITE"))
        assertTrue(spinReadyGlow.contains("!shouldUseRichSpinEffects()"))
        assertTrue(ambientOverlay.contains("!shouldUseRichSpinEffects()"))
        assertTrue(cabinetLights.contains("mode == CabinetLightMode.Idle"))
        assertTrue(cabinetLights.contains("CABINET_SPIN_CHASE_ALPHA"))
        assertFalse(cabinetLights.contains("repeatCount = ValueAnimator.INFINITE"))
        assertFalse(freeSpinsRail.contains("repeatCount = ValueAnimator.INFINITE"))
        assertFalse(freeSpinsMode.contains("repeatCount = ValueAnimator.INFINITE"))
        assertTrue(source.contains("winGlowAnimator?.cancel()"))
        assertTrue(source.contains("stopWinGlowOverlay()"))
    }
}
