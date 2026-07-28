package com.vslot.app.ui.home

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIdleAnimationContractTest {
    @Test
    fun `home shine and aura are finite lifecycle effects`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()
        val shine = source
            .substringAfter("private fun startHomeShineAnimations")
            .substringBefore("private fun startHomeAuraAnimations")
        val aura = source
            .substringAfter("private fun startHomeAuraAnimations")
            .substringBefore("private fun homeAuraViews")

        assertFalse(shine.contains("while (true)"))
        assertFalse(aura.contains("while (true)"))
        assertTrue(source.contains("stopHomeShineAnimations()"))
        assertTrue(source.contains("stopHomeAuraAnimations()"))
    }
}
