package com.vslot.app.ui.home

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class HomeSlotCardLayoutContractTest {
    @Test
    fun `portrait cards preserve the source artwork aspect ratio at every width`() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("src/main/res/layout/fragment_home.xml").toFile())
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
}
