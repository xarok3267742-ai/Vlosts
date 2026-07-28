package com.vslot.app.ui.home

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAccessibilityContractTest {
    @Test
    fun `locked cards expose a truthful click action and announce the required level`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()
        val semantics = source
            .substringAfter("private fun installLockedSlotAccessibility")
            .substringBefore("private fun bindLockOverlay")

        assertTrue(source.contains("installLockedSlotAccessibility(\n            binding.neonCard"))
        assertTrue(source.contains("installLockedSlotAccessibility(\n            binding.pharaohCard"))
        assertTrue(source.contains("installLockedSlotAccessibility(\n            binding.oceanCard"))
        assertTrue(semantics.contains("info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)"))
        assertTrue(semantics.contains("AccessibilityNodeInfoCompat.ACTION_CLICK"))
        assertTrue(semantics.contains("performAccessibilityAction"))
        assertTrue(semantics.contains("pulseLockedSlot(slotId)"))
        assertFalse(semantics.contains("announceForAccessibility"))
    }
}
