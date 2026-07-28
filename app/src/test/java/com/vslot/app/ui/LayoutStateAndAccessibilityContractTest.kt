package com.vslot.app.ui

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutStateAndAccessibilityContractTest {
    @Test
    fun `scroll containers keep stable ids in each layout variant`() {
        listOf(
            "main/res/layout/fragment_settings.xml",
            "main/res/layout-land/fragment_settings.xml"
        ).forEach { relativePath ->
            assertTrue(source(relativePath).contains("android:id=\"@+id/settingsScrollView\""))
        }
        listOf(
            "main/res/layout/fragment_slot.xml",
            "main/res/layout-land/fragment_slot.xml",
            "main/res/layout-w600dp-land/fragment_slot.xml"
        ).forEach { relativePath ->
            assertTrue(source(relativePath).contains("android:id=\"@+id/slotScrollView\""))
        }
    }

    @Test
    fun `aggregate level description hides duplicate number descendants`() {
        listOf(
            "main/res/layout/fragment_home.xml",
            "main/res/layout-land/fragment_home.xml",
            "main/res/layout-w600dp-land/fragment_home.xml"
        ).forEach { relativePath ->
            val layout = source(relativePath)
            val levelDigits = layout.substringAfter("android:id=\"@+id/homeLevelDigits\"")
                .substringBefore("/>")
            assertTrue(levelDigits.contains("android:importantForAccessibility=\"no\""))
        }
    }

    @Test
    fun `aggregate scatter description hides duplicate symbol and badge descendants`() {
        val source = source("main/java/com/vslot/app/ui/dialog/PaytableDialogFragment.kt")
        val bonusCell = source.substringAfter("private fun bonusSymbolCell()")
            .substringBefore("private fun lineMultiplierView")

        assertTrue(bonusCell.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(
            Regex("importantForAccessibility = View\\.IMPORTANT_FOR_ACCESSIBILITY_NO")
                .findAll(bonusCell)
                .count() >= 3
        )
        assertTrue(!bonusCell.contains("contentDescription = symbolLabel"))
        assertTrue(!bonusCell.contains("R.string.paytable_bonus_badge_accessibility"))
    }

    private fun source(relativePath: String): String = Path.of("src/$relativePath").readText()
}
