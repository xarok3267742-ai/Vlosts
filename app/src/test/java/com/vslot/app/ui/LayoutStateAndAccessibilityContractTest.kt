package com.vslot.app.ui

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutStateAndAccessibilityContractTest {
    @Test
    fun `graphic back actions fit their artwork inside the touch target`() {
        listOf(
            "main/res/layout/fragment_settings.xml",
            "main/res/layout-land/fragment_settings.xml",
            "main/res/layout-w600dp-land/fragment_settings.xml",
            "main/res/layout/fragment_privacy.xml",
            "main/res/layout-land/fragment_privacy.xml",
            "main/res/layout/fragment_slot.xml",
            "main/res/layout-land/fragment_slot.xml",
            "main/res/layout-w600dp-land/fragment_slot.xml"
        ).forEach { relativePath ->
            val backButton = source(relativePath)
                .substringAfter("android:id=\"@+id/backButton\"")
                .substringBefore("/>")
            assertTrue(
                "$relativePath must scale the back artwork instead of clipping its intrinsic bitmap",
                backButton.contains("android:scaleType=\"fitCenter\"")
            )
        }
    }

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
    fun `aggregate scatter description hides the decorative symbol descendant`() {
        val source = source("main/java/com/vslot/app/ui/dialog/PaytableDialogFragment.kt")
        val bonusCell = source.substringAfter("private fun bonusSymbolCell()")
            .substringBefore("private fun lineMultiplierView")

        assertTrue(bonusCell.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(
            Regex("importantForAccessibility = View\\.IMPORTANT_FOR_ACCESSIBILITY_NO")
                .findAll(bonusCell)
                .count() == 1
        )
        assertTrue(!bonusCell.contains("contentDescription = symbolLabel"))
        assertTrue(!bonusCell.contains("R.string.paytable_bonus_badge_accessibility"))
    }

    @Test
    fun `graphic page titles remain named accessibility headings`() {
        val settingsFragment = source("main/java/com/vslot/app/ui/settings/SettingsFragment.kt")
        val privacyFragment = source("main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt")
        listOf(
            "main/res/layout/fragment_settings.xml",
            "main/res/layout-land/fragment_settings.xml",
            "main/res/layout-w600dp-land/fragment_settings.xml"
        ).forEach { relativePath ->
            val title = source(relativePath)
                .substringAfter("android:id=\"@+id/settingsTitleImage\"")
                .substringBefore("/>")
            assertTrue(title.contains("android:contentDescription=\"@string/settings\""))
        }
        listOf(
            "main/res/layout/fragment_privacy.xml",
            "main/res/layout-land/fragment_privacy.xml"
        ).forEach { relativePath ->
            val title = source(relativePath)
                .substringAfter("android:id=\"@+id/privacyTitle\"")
                .substringBefore("/>")
            assertTrue(title.contains("android:contentDescription=\"@string/privacy_policy\""))
        }
        assertTrue(settingsFragment.contains("ViewCompat.setAccessibilityHeading(binding.settingsTitleImage, true)"))
        assertTrue(privacyFragment.contains("ViewCompat.setAccessibilityHeading(binding.privacyTitle, true)"))
    }

    @Test
    fun `compact landscape disclaimer confirmation meets minimum touch target`() {
        val layout = source("main/res/layout-land/fragment_disclaimer.xml")
        val checkRow = layout.substringAfter("android:id=\"@+id/disclaimerCheckRow\"")
            .substringBefore("android:id=\"@+id/disclaimerCheckButton\"")
        val checkButton = layout.substringAfter("android:id=\"@+id/disclaimerCheckButton\"")
            .substringBefore("/>")

        assertTrue(checkRow.contains("android:minHeight=\"48dp\""))
        assertTrue(checkButton.contains("android:layout_width=\"48dp\""))
        assertTrue(checkButton.contains("android:layout_height=\"48dp\""))
    }

    private fun source(relativePath: String): String = Path.of("src/$relativePath").readText()
}
