package com.vslot.app.ui.slot

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class SlotResponsiveLayoutContractTest {
    @Test
    fun `short portrait host scrolls without collapsing reels or clipping console`() {
        val document = layout("layout/fragment_slot.xml")
        val slotContent = document.id("slotContent")
        val topHud = document.id("slotTopHudRow").parentNode as Element
        val scroll = document.elements("ScrollView").single()
        val scrollContent = scroll.singleElementChild()
        val machineStage = document.id("slotMachineFrame").parentNode as Element
        val console = document.id("slotControlConsole")

        assertEquals("0dp", scroll.attr("layout_height"))
        assertEquals("1", scroll.attr("layout_weight"))
        assertEquals("true", scroll.attr("fillViewport"))
        assertEquals("wrap_content", scrollContent.attr("layout_height"))
        assertEquals("center_vertical", scrollContent.attr("gravity"))
        assertEquals("com.vslot.app.ui.widget.SlotMachineLayout", machineStage.tagName)
        assertEquals("wrap_content", machineStage.attr("layout_height"))
        assertEquals("240dp", machineStage.attr("minHeight"))
        assertEquals("238dp", console.attr("layout_height"))
        assertTrue("The reels must be reachable through the portrait scroll host", scroll.contains(machineStage))
        assertTrue("The full console must be reachable through the portrait scroll host", scroll.contains(console))

        val viewportHeight = SHORT_PORTRAIT_HEIGHT_DP -
            slotContent.dp("paddingTop") -
            slotContent.dp("paddingBottom") -
            topHud.dp("layout_height") -
            scroll.dp("layout_marginTop")
        val minimumContentHeight = machineStage.dp("minHeight") +
            console.dp("layout_marginTop") +
            console.dp("layout_height")

        assertEquals(358, viewportHeight)
        assertEquals(492, minimumContentHeight)
        assertTrue("Short portrait content must overflow instead of shrinking the reels", minimumContentHeight > viewportHeight)
        assertTrue("The viewport must be tall enough to expose the complete console at the scroll end", viewportHeight >= console.dp("layout_height"))
    }

    @Test
    fun `wide 320dp tall landscape host can scroll to every control`() {
        val document = layout("layout-w600dp-land/fragment_slot.xml")
        val slotContent = document.id("slotContent")
        val topHud = document.id("slotTopHudRow").parentNode as Element
        val scroll = document.elements("ScrollView").single()
        val sideBySideContent = scroll.singleElementChild()
        val console = document.id("slotControlConsole")
        val consoleColumn = console.elementChildren().single { it.tagName == "LinearLayout" }

        val viewportHeight = SHORT_LANDSCAPE_HEIGHT_DP -
            slotContent.dp("paddingTop") -
            slotContent.dp("paddingBottom") -
            topHud.dp("layout_height") -
            scroll.dp("layout_marginTop")
        val consoleRequiredHeight = consoleColumn.dp("paddingTop") +
            consoleColumn.dp("paddingBottom") +
            consoleColumn.elementChildren().sumOf { child ->
                child.dp("layout_marginTop") + child.dp("layout_height") + child.dp("layout_marginBottom")
            }

        assertEquals("true", scroll.attr("fillViewport"))
        assertEquals("com.vslot.app.ui.widget.SlotLandscapeContentLayout", sideBySideContent.tagName)
        assertEquals("262dp", sideBySideContent.attr("minHeight"))
        assertEquals(236, viewportHeight)
        assertEquals(262, consoleRequiredHeight)
        assertTrue("A 320dp host must scroll rather than clip the console", sideBySideContent.dp("minHeight") > viewportHeight)
        assertEquals("The scroll range must expose the exact console overflow", 26, consoleRequiredHeight - viewportHeight)

        SLOT_ACTION_IDS.forEach { id ->
            assertTrue("$id must remain reachable through the wide landscape scroll host", scroll.contains(document.id(id)))
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

    private fun Element.dp(localName: String): Int {
        return DP_VALUE.matchEntire(attr(localName))?.groupValues?.get(1)?.toInt()
            ?: if (attr(localName).isEmpty()) 0 else error("$tagName android:$localName must be a dp value")
    }

    private fun Element.singleElementChild(): Element = elementChildren().single()

    private fun Element.elementChildren(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map(nodes::item).filterIsInstance<Element>()
    }

    private fun Element.contains(descendant: Node): Boolean {
        var current: Node? = descendant
        while (current != null) {
            if (current === this) return true
            current = current.parentNode
        }
        return false
    }

    private companion object {
        const val SHORT_PORTRAIT_HEIGHT_DP = 480
        const val SHORT_LANDSCAPE_HEIGHT_DP = 320
        val SLOT_ACTION_IDS = listOf(
            "betMinusButton",
            "betPlusButton",
            "linesMinusButton",
            "linesPlusButton",
            "paytableButton",
            "spinButton",
            "autoSpinButton",
            "maxLinesButton"
        )
        val DP_VALUE = Regex("(\\d+)dp")
    }
}
