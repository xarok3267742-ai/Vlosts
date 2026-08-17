package com.vslot.app.ui.disclaimer

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NarrativeCopyContractTest {
    @Test
    fun `first run copy is concise truthful and free of internal instructions`() {
        val strings = narrativeStrings()
        val disclaimer = strings.getValue("disclaimer_body")
        val privacyError = strings.getValue("privacy_not_configured")

        assertEquals(
            "V Slot — игра с виртуальными монетами. Здесь нет ставок на реальные деньги, " +
                "денежных призов и вывода средств. Виртуальные монеты не имеют денежной стоимости.",
            disclaimer
        )
        assertFalse(disclaimer.lowercase().contains("только для развлечения"))
        assertEquals("Политика конфиденциальности сейчас недоступна.", privacyError)
        strings.values.forEach { copy ->
            assertFalse(copy.lowercase().contains("перед релизом"))
            assertFalse(copy.lowercase().contains("добавьте"))
        }
    }

    @Test
    fun `disclaimer renders narrative text directly in every layout`() {
        listOf("layout", "layout-land", "layout-w600dp-land").forEach { directory ->
            val layout = Path.of("src/main/res/$directory/fragment_disclaimer.xml").toFile().readText()
            val body = layout
                .substringAfter("@+id/disclaimerBodyLargeText")
                .substringBefore("/>")

            assertTrue("$directory must render the narrative resource", body.contains("@string/disclaimer_body"))
            assertFalse("$directory must not hide the primary narrative", body.contains("android:visibility=\"gone\""))
            assertFalse("$directory must not render stale bitmap copy", layout.contains("@drawable/body_disclaimer"))
        }
    }

    private fun narrativeStrings(): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("src/main/res/values/narrative_strings.xml").toFile())
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }
}
