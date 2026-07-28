package com.vslot.app.ui.settings

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class SettingsCompactPortraitLayoutContractTest {
    @Test
    fun `compact portrait keeps controls scrollable above a fixed safety footer`() {
        val document = layout("layout/fragment_settings.xml")
        val scroll = document.elements("ScrollView").single()
        val safetyStage = document.id("settingsSafetyStage")
        val safetyPanel = document.id("settingsSafetyPanel")

        assertEquals("0dp", scroll.attr("layout_height"))
        assertEquals("1", scroll.attr("layout_weight"))
        assertEquals("false", scroll.attr("fillViewport"))
        assertEquals("none", scroll.attr("scrollbars"))
        assertEquals("wrap_content", scroll.singleElementChild().attr("layout_height"))
        assertSame("Scrollable content and footer must share a vertical parent", scroll.parentNode, safetyStage.parentNode)
        assertEquals("vertical", (scroll.parentNode as Element).attr("orientation"))
        assertTrue("Scrollable content must be laid out before the footer", scroll.siblingIndex() < safetyStage.siblingIndex())
        assertFalse("The fixed safety footer must stay outside scrolling content", scroll.contains(safetyStage))
        assertEquals("wrap_content", safetyStage.attr("layout_height"))
        assertEquals("118dp", safetyStage.attr("minHeight"))
        assertTrue("The readable safety panel must stay inside its fixed stage", safetyStage.contains(safetyPanel))

        SCROLLABLE_CONTROL_IDS.forEach { id ->
            assertTrue("$id must remain reachable through portrait scrolling", scroll.contains(document.id(id)))
        }
    }

    @Test
    fun `compact portrait preserves bitmap and accessibility contracts`() {
        val document = layout("layout/fragment_settings.xml")
        val safetyAnchor = document.id("settingsSafetyAnchor")
        val safetyPanel = document.id("settingsSafetyPanel")
        val scalableCopy = SCALABLE_COPY_IDS.map { document.id(it) }

        assertEquals("Every bitmap-only command needs scalable copy", SCALABLE_COPY_IDS.toSet(), scalableCopy.map { it.attr("id").removePrefix("@+id/") }.toSet())
        assertEquals(SCALABLE_COPY_IDS.size, document.elements("TextView").size)
        scalableCopy.forEach { copy ->
            assertEquals("gone", copy.attr("visibility"))
            assertTrue(copy.getAttribute("style").startsWith("@style/VSlotAccessibleCopy"))
        }
        assertEquals("@drawable/settings_safety_anchor", safetyAnchor.attr("src"))
        assertEquals("@null", safetyAnchor.attr("contentDescription"))
        assertEquals("no", safetyAnchor.attr("importantForAccessibility"))
        assertEquals("@drawable/settings_safety_panel", safetyPanel.attr("src"))
        assertEquals("@string/settings_safety_panel", safetyPanel.attr("contentDescription"))

        TOGGLE_IDS.forEach { id ->
            val toggle = document.id(id)
            assertEquals("true", toggle.attr("clickable"))
            assertEquals("true", toggle.attr("focusable"))
            assertTrue("$id must retain a spoken state", toggle.attr("contentDescription").startsWith("@string/settings_"))
        }
    }

    @Test
    fun `landscape keeps growing copy in a vertically scrollable viewport`() {
        val document = layout("layout-land/fragment_settings.xml")
        val scroll = document.elements("ScrollView").single()
        val pushStatusStage = document.id("pushStatusStage")

        assertEquals("match_parent", scroll.attr("layout_height"))
        assertEquals("true", scroll.attr("fillViewport"))
        assertEquals("wrap_content", scroll.singleElementChild().attr("layout_height"))
        assertEquals(
            "The compact status console must grow with 200% Russian copy",
            "wrap_content",
            pushStatusStage.attr("layout_height")
        )
        assertEquals("32dp", pushStatusStage.attr("minHeight"))
        SCALABLE_COPY_IDS.forEach { id ->
            val copy = document.id(id)
            assertTrue("$id must remain reachable in landscape", scroll.contains(copy))
            assertEquals("wrap_content", copy.attr("layout_height"))
            assertFalse("$id must not cap lines", copy.hasAttribute("android:maxLines"))
            assertFalse("$id must not ellipsize", copy.hasAttribute("android:ellipsize"))
        }
    }

    private fun layout(relativePath: String): Document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(Path.of("src/main/res/$relativePath").toFile())

    private fun Document.id(id: String): Element = elements("*")
        .single { it.getAttribute("android:id") == "@+id/$id" }

    private fun Document.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return List(nodes.length) { index -> nodes.item(index) as Element }
    }

    private fun Element.attr(localName: String): String = getAttribute("android:$localName")

    private fun Element.singleElementChild(): Element {
        val children = childNodes
        return (0 until children.length)
            .map(children::item)
            .filterIsInstance<Element>()
            .single()
    }

    private fun Element.contains(descendant: Node): Boolean {
        var current: Node? = descendant
        while (current != null) {
            if (current === this) return true
            current = current.parentNode
        }
        return false
    }

    private fun Element.siblingIndex(): Int {
        val siblings = parentNode.childNodes
        return (0 until siblings.length).first { siblings.item(it) === this }
    }

    private companion object {
        val SCROLLABLE_CONTROL_IDS = listOf(
            "versionImage",
            "soundToggleButton",
            "analyticsToggleButton",
            "hapticsToggleButton",
            "privacyButton",
            "noticesButton",
            "rulesButton",
            "pushButton",
            "pushStatusStage"
        )
        val TOGGLE_IDS = listOf(
            "soundToggleButton",
            "analyticsToggleButton",
            "hapticsToggleButton"
        )
        val SCALABLE_COPY_IDS = listOf(
            "versionLargeText",
            "socialDisclaimerLargeText",
            "settingsSafetyLargeText",
            "privacyButtonLargeText",
            "noticesButtonLargeText",
            "rulesButtonLargeText",
            "pushButtonLargeText",
            "pushStatusLargeText"
        )
    }
}
