package com.vslot.app

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalableComplianceCopyContractTest {
    @Test
    fun `disclaimer exposes the same scalable legal copy in both orientations`() {
        val layouts = orientationLayouts("fragment_disclaimer.xml")

        layouts.forEach { (path, layout) ->
            assertEquals("$path must declare exactly two scalable TextViews", 2, Regex("<TextView").findAll(layout).count())
            assertTrue(layout.contains("@+id/disclaimerBodyLargeText"))
            assertTrue(layout.contains("android:text=\"@string/disclaimer_body\""))
            assertTrue(layout.contains("@+id/disclaimerCheckboxLargeText"))
            assertTrue(layout.contains("android:text=\"@string/disclaimer_checkbox\""))
            assertTrue(layout.contains("android:visibility=\"gone\""))
            assertTrue(layout.contains("android:layout_height=\"wrap_content\""))
        }

        val fragment = source("main/java/com/vslot/app/ui/disclaimer/DisclaimerFragment.kt")
        assertTrue(fragment.contains("resources.configuration.fontScale > DEFAULT_FONT_SCALE"))
        assertTrue(fragment.contains("DEFAULT_FONT_SCALE = 1.0f"))
        assertTrue(fragment.contains("binding.disclaimerBodyLargeText.visibility"))
        assertTrue(fragment.contains("binding.disclaimerCheckboxLargeText.visibility"))
    }

    @Test
    fun `settings exposes scalable copy for status and commands in both orientations`() {
        val layouts = orientationLayouts("fragment_settings.xml")

        layouts.forEach { (path, layout) ->
            assertEquals("$path must declare every scalable text peer", SCALABLE_SETTINGS_IDS.size, Regex("<TextView").findAll(layout).count())
            SCALABLE_SETTINGS_IDS.forEach { id -> assertTrue("$path is missing $id", layout.contains("@+id/$id")) }
            assertTrue(layout.contains("android:text=\"@string/social_disclaimer_short\""))
            assertTrue(layout.contains("android:text=\"@string/settings_safety_panel\""))
            SCALABLE_SETTINGS_STRINGS.forEach { stringName ->
                assertTrue("$path is missing text for $stringName", layout.contains("android:text=\"@string/$stringName\""))
            }
            assertTrue("$path scalable rows must grow", layout.contains("android:minHeight="))
            assertTrue("$path scalable copy must wrap", !layout.contains("android:maxLines="))
            assertTrue("$path scalable copy must not ellipsize", !layout.contains("android:ellipsize="))
        }

        val fragment = source("main/java/com/vslot/app/ui/settings/SettingsFragment.kt")
        assertTrue(fragment.contains("resources.configuration.fontScale > DEFAULT_FONT_SCALE"))
        assertTrue(fragment.contains("DEFAULT_FONT_SCALE = 1.0f"))
        assertTrue(fragment.contains("binding.versionLargeText.text = getString(R.string.version_format"))
        assertTrue(fragment.contains("binding.settingsSafetyLargeText.visibility"))
        assertTrue(fragment.contains("binding.privacyButtonLargeText.visibility"))
        assertTrue(fragment.contains("binding.noticesButtonLargeText.visibility"))
        assertTrue(fragment.contains("binding.rulesButtonLargeText.visibility"))
        assertTrue(fragment.contains("binding.pushButtonLargeText.text = pushButtonLabel"))
        assertTrue(fragment.contains("binding.pushStatusLargeText.text = pushStatus.first"))
        assertTrue(fragment.contains("Configuration.ORIENTATION_LANDSCAPE"))
    }

    @Test
    fun `scalable copy styles use sp without autosizing or truncation`() {
        val styles = source("main/res/values/styles.xml")

        assertTrue(styles.contains("name=\"VSlotAccessibleCopy\""))
        assertTrue(styles.contains("name=\"VSlotAccessibleCopy.Compact\""))
        assertTrue(styles.contains("<item name=\"android:textSize\">14sp</item>"))
        assertTrue(styles.contains("<item name=\"android:textSize\">13sp</item>"))
        assertTrue(!styles.contains("autoSizeTextType"))
        orientationLayouts("fragment_disclaimer.xml").forEach { (_, layout) ->
            assertTrue(!layout.contains("android:maxLines="))
            assertTrue(!layout.contains("android:ellipsize="))
        }
    }

    private fun orientationLayouts(fileName: String): Map<String, String> = listOf(
        "main/res/layout/$fileName",
        "main/res/layout-land/$fileName"
    ).associateWith(::source)

    private fun source(relativePath: String): String = Path.of("src/$relativePath").readText()

    private companion object {
        val SCALABLE_SETTINGS_IDS = listOf(
            "versionLargeText",
            "socialDisclaimerLargeText",
            "settingsSafetyLargeText",
            "privacyButtonLargeText",
            "noticesButtonLargeText",
            "rulesButtonLargeText",
            "pushButtonLargeText",
            "pushStatusLargeText"
        )
        val SCALABLE_SETTINGS_STRINGS = listOf(
            "privacy_policy",
            "third_party_notices_action",
            "social_casino_rules",
            "push_unconfigured_status"
        )
    }
}
