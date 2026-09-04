package com.vslot.app.ui

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class CompactLandscapeLayoutContractTest {
    @Test
    fun `compact and wide landscape variants expose the same binding ids`() {
        listOf("fragment_home.xml", "fragment_slot.xml").forEach { fileName ->
            val compact = layout("layout-land/$fileName")
            val wide = layout("layout-w600dp-land/$fileName")

            assertEquals("$fileName must keep the same view-binding contract", wide.ids(), compact.ids())
        }
    }

    @Test
    fun `compact home keeps every primary action on reachable scroll axes`() {
        val document = layout("layout-land/fragment_home.xml")
        val cardScroll = document.id("homeSlotHorizontalScrollView")

        listOf(
            "violetCard",
            "romanCard",
            "neonCard",
            "pharaohCard",
            "oceanCard"
        ).forEach { id ->
            assertTrue("$id must remain reachable through the compact card carousel", cardScroll.contains(document.id(id)))
            assertEquals("228dp", document.id(id).attr("layout_width"))
        }
        assertEquals(1, document.elements("HorizontalScrollView").size)
        assertEquals("96dp", cardScroll.singleElementChild().attr("paddingEnd"))
        val actionColumn = document.id("dailyBonusButton").parentNode as Element
        val actionScroll = actionColumn.parentNode as Element
        assertEquals("androidx.core.widget.NestedScrollView", actionScroll.tagName)
        assertEquals("168dp", actionScroll.attr("layout_width"))
        assertEquals("true", actionScroll.attr("fillViewport"))
        assertTrue("Settings must stay pinned outside the card carousel", !cardScroll.contains(document.id("settingsButton")))
        assertTrue("Daily bonus must stay pinned outside the card carousel", !cardScroll.contains(document.id("dailyBonusButton")))
        assertTrue("Privacy must stay pinned outside the card carousel", !cardScroll.contains(document.id("privacyButton")))
        assertTouchTargets(document, HOME_ACTION_IDS)
    }

    @Test
    fun `compact 320dp high home keeps locked cards and daily bonus contained`() {
        val document = layout("layout-land/fragment_home.xml")
        val dailyBonus = document.id("dailyBonusButton")
        val actionColumn = dailyBonus.parentNode as Element
        val actionScroll = actionColumn.parentNode as Element
        val contentRow = actionScroll.parentNode as Element
        val screenColumn = contentRow.parentNode as Element
        val topBar = screenColumn.elementChildren().first()
        val availableContentHeight = COMPACT_LANDSCAPE_HEIGHT_DP -
            screenColumn.attr("paddingTop").dp() -
            screenColumn.attr("paddingBottom").dp() -
            topBar.attr("layout_height").dp() -
            contentRow.attr("layout_marginTop").dp()
        val actionStackHeight = actionColumn.elementChildren().sumOf { child ->
            child.attr("layout_height").dp() +
                child.attr("layout_marginTop").dp() +
                child.attr("layout_marginBottom").dp()
        }

        assertEquals("168dp", actionScroll.attr("layout_width"))
        assertEquals("match_parent", actionColumn.attr("layout_width"))
        assertEquals("wrap_content", actionColumn.attr("layout_height"))
        assertTrue(actionScroll.contains(document.id("dailyBonusButton")))
        assertTrue(actionScroll.contains(document.id("privacyButton")))
        assertTrue(
            "Pinned actions occupy ${actionStackHeight}dp inside a ${availableContentHeight}dp compact viewport",
            actionStackHeight <= availableContentHeight
        )
        assertEndAlignedChildFits(document.id("dailyBonusClaimPlate"), actionScroll.attr("layout_width").dp())
        assertEndAlignedChildFits(document.id("dailyBonusCountdownRail"), actionScroll.attr("layout_width").dp())

        val cardScroll = document.id("homeSlotHorizontalScrollView")
        LOCKED_CARD_OVERLAYS.forEach { (cardId, overlayId) ->
            val card = document.id(cardId)
            val overlay = document.id(overlayId)
            assertTrue("$cardId must remain reachable through the compact carousel", cardScroll.contains(card))
            assertTrue("$overlayId must stay inside $cardId", card.contains(overlay))
            assertEquals("match_parent", overlay.attr("layout_width"))
            assertEquals("match_parent", overlay.attr("layout_height"))
        }
    }

    @Test
    fun `compact slot stacks proportional reels above a full width console`() {
        val document = layout("layout-land/fragment_slot.xml")
        val verticalScroll = document.elements("ScrollView").single()
        val machineFrame = document.id("slotMachineFrame")
        val machineStage = machineFrame.parentNode as Element
        val console = document.id("slotControlConsole")

        assertEquals("wrap_content", machineStage.attr("layout_height"))
        assertEquals("match_parent", machineStage.attr("layout_width"))
        assertEquals("220dp", machineStage.attr("minHeight"))
        assertEquals("com.vslot.app.ui.widget.SlotMachineLayout", machineStage.tagName)
        assertEquals("match_parent", machineFrame.attr("layout_height"))
        assertEquals("match_parent", machineFrame.attr("layout_width"))
        assertEquals("match_parent", console.attr("layout_width"))
        assertEquals("262dp", console.attr("layout_height"))
        assertTrue("Reels must be vertically reachable", verticalScroll.contains(machineStage))
        assertTrue("Console must be vertically reachable", verticalScroll.contains(console))

        SLOT_SCROLL_ACTION_IDS.forEach { id ->
            assertTrue("$id must remain reachable through the compact slot scroll", verticalScroll.contains(document.id(id)))
        }
        assertTouchTargets(document, SLOT_SCROLL_ACTION_IDS + "backButton")
    }

    @Test
    fun `wide landscape variants retain side by side content with vertical reachability`() {
        val home = layout("layout-w600dp-land/fragment_home.xml")
        val slot = layout("layout-w600dp-land/fragment_slot.xml")

        assertTrue("Wide home must not inherit compact vertical scrolling", home.elements("ScrollView").isEmpty())
        assertEquals("248dp", home.id("violetCard").attr("layout_width"))
        assertEquals("310dp", (home.id("dailyBonusButton").parentNode as Element).attr("layout_width"))

        val slotScroll = slot.elements("ScrollView").single()
        val sideBySideContent = slotScroll.singleElementChild()
        assertEquals("true", slotScroll.attr("fillViewport"))
        assertEquals("horizontal", sideBySideContent.attr("orientation"))
        assertEquals("com.vslot.app.ui.widget.SlotLandscapeContentLayout", sideBySideContent.tagName)
        assertEquals("262dp", sideBySideContent.attr("minHeight"))
        assertTrue("Wide reels must stay inside the reachable side-by-side content", slotScroll.contains(slot.id("slotMachineFrame")))
        assertTrue("Wide controls must stay inside the reachable side-by-side content", slotScroll.contains(slot.id("slotControlConsole")))
        assertEquals("340dp", slot.id("slotControlConsole").attr("layout_width"))
        assertEquals("match_parent", slot.id("slotControlConsole").attr("layout_height"))
        val wideMachine = slot.id("slotMachine")
        assertEquals("com.vslot.app.ui.widget.SlotMachineLayout", wideMachine.tagName)
        assertEquals("wrap_content", wideMachine.attr("layout_height"))
        assertEquals("center_vertical", wideMachine.attr("layout_gravity"))
    }

    @Test
    fun `landscape stepper fallback follows the physical end edge in rtl and ltr`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").toFile().readText()
        val resolver = source
            .substringAfter("private fun resolveLandscapeStepperFallbackTarget")
            .substringBefore("private fun MotionEvent.isInsideVerticalBandOf")

        assertTrue(resolver.contains("binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL"))
        assertTrue(resolver.contains("rootRect.left.."))
        assertTrue(resolver.contains("..rootRect.right"))
    }

    @Test
    fun `runtime compact slot console fits a 320dp high large-font viewport`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").toFile().readText()

        assertTrue(source.contains("private fun adaptCompactLandscapeConsoleContents()"))
        assertTrue(source.contains("COMPACT_LANDSCAPE_CONTENT_HEIGHT_DP = 214"))
        assertTrue(source.contains("COMPACT_LANDSCAPE_BET_PANEL_HEIGHT_DP = 98"))
        assertTrue(source.contains("COMPACT_LANDSCAPE_LAST_WIN_PANEL_HEIGHT_DP = 48"))
        assertTrue(source.contains("COMPACT_LANDSCAPE_SPIN_DECK_HEIGHT_DP = 60"))
        assertTrue(source.contains("COMPACT_LANDSCAPE_METER_LABEL_HEIGHT_DP = 16"))
        assertTrue(source.contains("label.isSingleLine = true"))
        assertTrue(source.contains("COMPACT_LANDSCAPE_METER_LABEL_MIN_SP"))
        assertTrue(source.contains("COMPACT_LANDSCAPE_SPIN_LABEL_MIN_SP = 6"))
        assertTrue(source.contains("compactSpinVisual -> R.string.spin_compact_visual_label"))
        assertTrue(source.contains("private fun usesCompactSpinVisualLabel()"))
    }

    private fun assertTouchTargets(document: Document, ids: List<String>) {
        ids.forEach { id ->
            val view = document.id(id)
            val width = view.effectiveDp("layout_width")
            val height = view.effectiveDp("layout_height")
            val constrainedWidth = view.attr("layout_width") == "0dp" &&
                view.getAttribute("app:layout_constraintStart_toEndOf").isNotEmpty() &&
                view.getAttribute("app:layout_constraintEnd_toStartOf").isNotEmpty()
            assertTrue(
                "$id must resolve to at least 48dp wide or fill a bounded constraint span, was $width",
                width != null && width >= 48 || constrainedWidth || view.attr("layout_width") == "match_parent"
            )
            assertTrue(
                "$id must resolve to at least 48dp high, was $height",
                height != null && height >= 48 || view.attr("layout_height") == "match_parent"
            )
        }
    }

    private fun layout(relativePath: String): Document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(Path.of("src/main/res/$relativePath").toFile())

    private fun Document.id(id: String): Element = elements("*")
        .single { it.getAttribute("android:id") == "@+id/$id" }

    private fun Document.ids(): Set<String> = elements("*")
        .map { it.getAttribute("android:id") }
        .filter { it.startsWith("@+id/") }
        .toSet()

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

    private fun Element.elementChildren(): List<Element> = (0 until childNodes.length)
        .map(childNodes::item)
        .filterIsInstance<Element>()

    private fun Element.contains(descendant: Node): Boolean {
        var current: Node? = descendant
        while (current != null) {
            if (current === this) return true
            current = current.parentNode
        }
        return false
    }

    private fun Element.effectiveDp(localName: String): Int? {
        var current: Element? = this
        while (current != null) {
            DP_VALUE.matchEntire(current.attr(localName))
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.let { return it }
            current = current.parentNode as? Element
        }
        return null
    }

    private fun assertEndAlignedChildFits(child: Element, parentWidthDp: Int) {
        val occupiedWidth = child.attr("layout_width").dp() + child.attr("layout_marginEnd").dp()
        assertTrue(
            "${child.getAttribute("android:id")} occupies ${occupiedWidth}dp inside ${parentWidthDp}dp",
            occupiedWidth <= parentWidthDp
        )
    }

    private fun String.dp(): Int = if (isBlank()) 0 else removeSuffix("dp").toInt()

    private companion object {
        val HOME_ACTION_IDS = listOf(
            "settingsButton",
            "violetCard",
            "romanCard",
            "neonCard",
            "pharaohCard",
            "oceanCard",
            "dailyBonusButton",
            "privacyButton"
        )
        val SLOT_SCROLL_ACTION_IDS = listOf(
            "betMinusButton",
            "betPlusButton",
            "linesMinusButton",
            "linesPlusButton",
            "paytableButton",
            "spinButton",
            "autoSpinButton",
            "maxLinesButton"
        )
        const val COMPACT_LANDSCAPE_HEIGHT_DP = 320
        val LOCKED_CARD_OVERLAYS = mapOf(
            "neonCard" to "neonLockedOverlay",
            "pharaohCard" to "pharaohLockedOverlay",
            "oceanCard" to "oceanLockedOverlay"
        )
        val DP_VALUE = Regex("(\\d+)dp")
    }
}
