package com.vslot.app.ui.dialog

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ResponsiveDialogLayoutContractTest {
    @Test
    fun `all dialogs use the shared bounded window hook`() {
        val dialogFragments = listOf(
            "AnalyticsConsentDialogFragment.kt",
            "AutoSpinCountDialogFragment.kt",
            "DailyBonusDialogFragment.kt",
            "LowCoinsDialogFragment.kt",
            "PaytableDialogFragment.kt",
            "PushPermissionDialogFragment.kt",
            "ResultDialogFragment.kt",
            "SocialRulesDialogFragment.kt",
            "ThirdPartyNoticesDialogFragment.kt"
        )

        dialogFragments.forEach { fileName ->
            val source = Path.of("src/main/java/com/vslot/app/ui/dialog/$fileName").readText()
            assertTrue("$fileName must use the shared dialog window policy", source.contains("keepGameFullscreen()"))
        }

        val helper = Path.of("src/main/java/com/vslot/app/ui/dialog/DialogWindowPolish.kt").readText()
        assertTrue("Shared dialog policy must account for freeform caption and system bars", helper.contains("WindowInsetsCompat.Type.systemBars()"))
        assertTrue("Shared dialog policy must account for display cutouts", helper.contains("WindowInsetsCompat.Type.displayCutout()"))
        assertTrue("Shared dialog policy must retain whole-dialog scrolling for short windows", helper.contains("installBoundedScrollView"))

        val notices = Path.of(
            "src/main/java/com/vslot/app/ui/dialog/ThirdPartyNoticesDialogFragment.kt"
        ).readText()
        assertFalse("Notices must not overwrite the shared inset-safe size", notices.contains("window?.setLayout("))
    }

    @Test
    fun `compact landscape dialogs expose responsive roots with appropriate width preferences`() {
        listOf(
            "layout-land/dialog_auto_spin_count.xml",
            "layout-land/dialog_result.xml"
        ).forEach { relativePath ->
            val layout = resource(relativePath).readText()
            val root = layout.substringAfter("?>").substringBefore('>')

            assertTrue("$relativePath root must fill the bounded dialog window", root.contains("android:layout_width=\"match_parent\""))
            assertTrue("$relativePath must retain its roomy-window width preference", root.contains("android:tag=\"dialog_preferred_width_430dp\""))
            assertFalse("$relativePath must not force a 430dp root", root.contains("android:layout_width=\"430dp\""))
        }

        listOf(
            "layout-land/dialog_paytable.xml",
            "layout-w600dp-land/dialog_paytable.xml"
        ).forEach { relativePath ->
            val root = resource(relativePath).readText().substringAfter("?>").substringBefore('>')
            assertTrue("$relativePath must use the available landscape width", root.contains("android:tag=\"dialog_preferred_width_840dp\""))
        }
    }

    @Test
    fun `two column social rules dialog requests a wide bounded window`() {
        val layout = resource("layout-land/dialog_social_rules.xml").readText()
        val root = layout.substringAfter("?>").substringBefore('>')
        val helper = Path.of("src/main/java/com/vslot/app/ui/dialog/DialogWindowPolish.kt").readText()

        assertTrue(root.contains("android:tag=\"dialog_preferred_width_840dp\""))
        assertTrue(helper.contains("WIDE_DIALOG_WIDTH_TAG"))
        assertTrue(helper.contains("WIDE_DIALOG_PREFERRED_WIDTH_DP = 840"))
    }

    @Test
    fun `dialog image actions retain at least a 48dp touch height`() {
        val layouts = Files.list(Path.of("src/main/res/layout")).use { files ->
            files.filter { it.fileName.toString().startsWith("dialog_") && it.toString().endsWith(".xml") }
                .toList()
        } + Files.list(Path.of("src/main/res/layout-land")).use { files ->
            files.filter { it.fileName.toString().startsWith("dialog_") && it.toString().endsWith(".xml") }
                .toList()
        }

        layouts.forEach { layout ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout.toFile())
            val actions = document.getElementsByTagName("ImageButton")
            for (index in 0 until actions.length) {
                val action = actions.item(index) as Element
                val heightDp = action.effectiveDp("android:layout_height")
                assertTrue("$layout image action must resolve to at least 48dp, was $heightDp", heightDp != null && heightDp >= 48)
            }
        }
    }

    private fun Element.effectiveDp(attribute: String): Int? {
        var current: Element? = this
        while (current != null) {
            val value = current.getAttribute(attribute)
            DP_VALUE.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            current = current.parentNode as? Element
        }
        return null
    }

    private fun resource(relativePath: String): Path = Path.of("src/main/res/$relativePath")

    private fun Path.readText(): String = String(Files.readAllBytes(this), Charsets.UTF_8)

    private companion object {
        val DP_VALUE = Regex("(\\d+)dp")
    }
}
