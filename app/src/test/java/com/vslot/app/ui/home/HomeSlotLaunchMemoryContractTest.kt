package com.vslot.app.ui.home

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSlotLaunchMemoryContractTest {
    @Test
    fun `slot navigation releases home images before the slot view is inflated`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()
        val openSlot = source
            .substringAfter("private fun openSlot(slotId: String, slotName: String)")
            .substringBefore("private fun releaseHomeImageResources")
        val releaseImages = source
            .substringAfter("private fun releaseHomeImageResources")
            .substringBefore("private fun navigateFromHome")
        val navigateFromHome = source
            .substringAfter("private fun navigateFromHome")
            .substringBefore("private fun openDailyBonusFromUserAction")

        assertTrue(openSlot.contains("if (opened)"))
        assertTrue(openSlot.contains("beforeNavigation = ::releaseHomeImageResources"))
        assertTrue(releaseImages.contains("clearImageResourcesRecursively()"))
        assertTrue(
            navigateFromHome.indexOf("beforeNavigation()") <
                navigateFromHome.indexOf("navController.navigate(actionId, args)")
        )
        assertFalse(source.contains("System.gc()"))
    }
}
