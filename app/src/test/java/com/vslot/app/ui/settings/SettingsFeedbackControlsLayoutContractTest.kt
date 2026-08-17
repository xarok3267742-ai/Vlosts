package com.vslot.app.ui.settings

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class SettingsFeedbackControlsLayoutContractTest {
    @Test
    fun `feedback controls keep visible Russian labels and states in every layout`() {
        LAYOUTS.forEach { relativePath ->
            val document = layout(relativePath)
            val controls = document.id("settingsFeedbackControls")

            assertEquals("$relativePath must stack narrow-screen-safe rows", "vertical", controls.attr("orientation"))
            assertEquals("$relativePath controls must grow with font scale", "wrap_content", controls.attr("layout_height"))
            assertTrue("$relativePath controls must remain reachable by scrolling", document.scrollView().contains(controls))

            TOGGLES.forEach { toggle ->
                val button = document.id(toggle.buttonId)
                val label = document.id(toggle.labelId)
                val state = document.id(toggle.stateId)

                assertEquals("$relativePath ${toggle.buttonId} must fill the available width", "match_parent", button.attr("layout_width"))
                assertEquals("$relativePath ${toggle.buttonId} must grow vertically", "wrap_content", button.attr("layout_height"))
                assertTrue("$relativePath ${toggle.buttonId} must be at least 48dp", button.attr("minHeight").removeSuffix("dp").toInt() >= 48)
                assertEquals("true", button.attr("clickable"))
                assertEquals("true", button.attr("focusable"))
                assertEquals(toggle.labelString, label.attr("text"))
                assertEquals("@string/settings_state_off", state.attr("text"))
                assertTrue(button.contains(label))
                assertTrue(button.contains(state))
                listOf(label, state).forEach { text ->
                    assertEquals("wrap_content", text.attr("layout_height"))
                    assertFalse(text.hasAttribute("android:maxLines"))
                    assertFalse(text.hasAttribute("android:ellipsize"))
                    assertEquals("no", text.attr("importantForAccessibility"))
                }
            }
        }
    }

    @Test
    fun `unconfigured push UI is hidden as one complete block`() {
        LAYOUTS.forEach { relativePath ->
            val document = layout(relativePath)
            assertEquals("gone", document.id("pushActionStage").attr("visibility"))
            assertEquals("gone", document.id("pushStatusStage").attr("visibility"))
            assertFalse(relativePath, Path.of("src/main/res/$relativePath").readText().contains("@string/push_unconfigured_status"))
        }

        val fragment = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt").readText()
        assertTrue(fragment.contains("binding.pushActionStage.visibility = if (pushConfigured) View.VISIBLE else View.GONE"))
        assertTrue(fragment.contains("binding.pushStatusStage.visibility = if (pushConfigured) View.VISIBLE else View.GONE"))
        assertTrue(fragment.contains("if (!pushConfigured) {"))
        assertTrue(fragment.contains("binding.pushButton.isEnabled = false"))
    }

    @Test
    fun `settings footer uses only a factual social casino disclaimer`() {
        val strings = Path.of("src/main/res/values/strings.xml").readText()
        val disclaimer = Regex("<string name=\"settings_safety_panel\">([^<]+)</string>")
            .find(strings)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        assertFalse(disclaimer.contains("Честный режим", ignoreCase = true))
        listOf("18+", "виртуальные монеты", "без покупок", "ставок на реальные деньги", "выплат", "денежных призов")
            .forEach { required -> assertTrue("Missing factual disclaimer part: $required", disclaimer.contains(required, ignoreCase = true)) }

        LAYOUTS.forEach { relativePath ->
            val document = layout(relativePath)
            assertEquals("gone", document.id("settingsSafetyPanel").attr("visibility"))
            assertEquals("visible", document.id("settingsSafetyLargeText").attr("visibility"))
        }
    }

    private fun layout(relativePath: String): Document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(Path.of("src/main/res/$relativePath").toFile())

    private fun Document.id(id: String): Element = elements("*")
        .single { it.getAttribute("android:id") == "@+id/$id" }

    private fun Document.scrollView(): Element = elements("ScrollView").single()

    private fun Document.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return List(nodes.length) { index -> nodes.item(index) as Element }
    }

    private fun Element.attr(localName: String): String = getAttribute("android:$localName")

    private fun Element.contains(descendant: Node): Boolean {
        var current: Node? = descendant
        while (current != null) {
            if (current === this) return true
            current = current.parentNode
        }
        return false
    }

    private data class Toggle(
        val buttonId: String,
        val labelId: String,
        val stateId: String,
        val labelString: String
    )

    private companion object {
        val LAYOUTS = listOf(
            "layout/fragment_settings.xml",
            "layout-land/fragment_settings.xml",
            "layout-w600dp-land/fragment_settings.xml"
        )
        val TOGGLES = listOf(
            Toggle("soundToggleButton", "soundToggleLabel", "soundToggleState", "@string/settings_sound"),
            Toggle("analyticsToggleButton", "analyticsToggleLabel", "analyticsToggleState", "@string/settings_analytics"),
            Toggle("hapticsToggleButton", "hapticsToggleLabel", "hapticsToggleState", "@string/settings_haptics")
        )
    }
}
