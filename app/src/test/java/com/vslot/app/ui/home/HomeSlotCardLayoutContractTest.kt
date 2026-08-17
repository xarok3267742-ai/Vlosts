package com.vslot.app.ui.home

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class HomeSlotCardLayoutContractTest {
    @Test
    fun `portrait cards preserve the source artwork aspect ratio at every width`() {
        val document = portraitLayout()
        val cards = document.getElementsByTagName("com.vslot.app.ui.widget.HomeSlotCardLayout")

        assertEquals(5, cards.length)
        for (index in 0 until cards.length) {
            val card = cards.item(index) as Element
            assertEquals("match_parent", card.getAttribute("android:layout_width"))
            assertEquals("wrap_content", card.getAttribute("android:layout_height"))
        }

        val source = Path.of("src/main/java/com/vslot/app/ui/widget/HomeSlotCardLayout.kt").readText()
        assertTrue(source.contains("980f / 620f"))
        assertTrue(source.contains("MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY)"))
    }

    @Test
    fun `portrait 320dp keeps locked cards and daily bonus inside the reachable flow`() {
        val document = portraitLayout()
        val scroll = document.id("homeSlotScrollView")
        val scrollContent = scroll.singleElementChild()
        val dailyBonus = document.id("dailyBonusButton")
        val contentWidthDp = COMPACT_PORTRAIT_WIDTH_DP - 16 - 16

        assertTrue("Daily bonus must remain reachable through portrait scrolling", scroll.contains(dailyBonus))
        assertEquals("match_parent", dailyBonus.attr("layout_width"))
        assertEndAlignedChildFits(document.id("dailyBonusClaimPlate"), contentWidthDp)
        assertEndAlignedChildFits(document.id("dailyBonusCountdownRail"), contentWidthDp)
        assertTrue(
            "Bottom padding must keep the final actions above the scroll veil",
            scrollContent.attr("paddingBottom").dp() >= document.id("homeScrollBottomVeil").attr("layout_height").dp()
        )

        LOCKED_CARD_OVERLAYS.forEach { (cardId, overlayId) ->
            val card = document.id(cardId)
            val overlay = document.id(overlayId)
            assertTrue("$cardId must remain reachable through portrait scrolling", scroll.contains(card))
            assertTrue("$overlayId must stay inside $cardId", card.contains(overlay))
            assertEquals("match_parent", overlay.attr("layout_width"))
            assertEquals("match_parent", overlay.attr("layout_height"))
            assertEquals("fitXY", overlay.attr("scaleType"))
        }
    }

    private fun portraitLayout(): Document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(Path.of("src/main/res/layout/fragment_home.xml").toFile())

    private fun assertEndAlignedChildFits(child: Element, parentWidthDp: Int) {
        val occupiedWidth = child.attr("layout_width").dp() + child.attr("layout_marginEnd").dp()
        assertTrue(
            "${child.getAttribute("android:id")} occupies ${occupiedWidth}dp inside ${parentWidthDp}dp",
            occupiedWidth <= parentWidthDp
        )
    }

    private fun Document.id(id: String): Element {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/$id" }
    }

    private fun Element.attr(localName: String): String = getAttribute("android:$localName")

    private fun Element.singleElementChild(): Element {
        return (0 until childNodes.length)
            .map(childNodes::item)
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

    private fun String.dp(): Int = removeSuffix("dp").toInt()

    private companion object {
        const val COMPACT_PORTRAIT_WIDTH_DP = 320
        val LOCKED_CARD_OVERLAYS = mapOf(
            "neonCard" to "neonLockedOverlay",
            "pharaohCard" to "pharaohLockedOverlay",
            "oceanCard" to "oceanLockedOverlay"
        )
    }
}
