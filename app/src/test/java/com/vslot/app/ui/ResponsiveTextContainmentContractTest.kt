package com.vslot.app.ui

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class ResponsiveTextContainmentContractTest {
    @Test
    fun `activity backdrop fills system insets while navigation content remains safe`() {
        val activity = resource("layout/activity_main.xml")
        val background = activity.id("screenBackground")
        val navHost = activity.id("nav_host_fragment")
        val source = Path.of("src/main/java/com/vslot/app/MainActivity.kt").readText()

        assertEquals("FrameLayout", activity.documentElement.tagName)
        assertEquals("match_parent", background.attr("layout_width"))
        assertEquals("match_parent", background.attr("layout_height"))
        assertEquals("centerCrop", background.attr("scaleType"))
        assertEquals("match_parent", navHost.attr("layout_width"))
        assertEquals("match_parent", navHost.attr("layout_height"))
        assertTrue(source.contains("val navHostView = binding.navHostFragment"))
        assertTrue(source.contains("R.id.splashFragment -> R.drawable.splash_bg"))
        assertTrue(source.contains("R.id.homeFragment -> R.drawable.home_bg"))
        assertTrue(source.contains("else -> R.drawable.main_bg"))
    }

    @Test
    fun `every platform text view wraps and uses the shared Russian line breaking policy`() {
        layoutFiles().forEach { path ->
            val document = parse(path)
            document.elements("TextView").forEach { textView ->
                val id = textView.getAttribute("android:id").ifBlank { "anonymous TextView" }
                assertEquals("$path $id must grow vertically", "wrap_content", textView.attr("layout_height"))
                assertTrue(
                    "$path $id must inherit the scalable copy policy",
                    textView.getAttribute("style").startsWith("@style/VSlotAccessibleCopy")
                )
                assertFalse("$path $id must not ellipsize", textView.hasAttribute("android:ellipsize"))
                assertFalse("$path $id must not cap lines", textView.hasAttribute("android:maxLines"))
                assertFalse("$path $id must not force horizontal scrolling", textView.hasAttribute("android:scrollHorizontally"))
            }
        }

        val styles = Path.of("src/main/res/values/styles.xml").readText()
        assertTrue(styles.contains("<item name=\"android:breakStrategy\">high_quality</item>"))
        assertTrue(styles.contains("<item name=\"android:hyphenationFrequency\">full</item>"))
        assertTrue(styles.contains("<item name=\"android:breakStrategy\">balanced</item>"))
    }

    @Test
    fun `error states and wide settings keep growing text on reachable scroll axes`() {
        listOf(
            "layout/fragment_splash.xml",
            "layout-land/fragment_splash.xml",
            "layout-w600dp-land/fragment_splash.xml"
        ).forEach { relativePath ->
            val document = resource(relativePath)
            assertEquals("$relativePath storage failure must scroll", "ScrollView", document.id("splashStorageErrorGroup").tagName)
            assertTrue(
                "$relativePath storage message must stay reachable",
                document.id("splashStorageErrorGroup").contains(document.id("splashStorageErrorMessage"))
            )
        }

        listOf("layout/fragment_privacy.xml", "layout-land/fragment_privacy.xml").forEach { relativePath ->
            val document = resource(relativePath)
            assertEquals("$relativePath privacy failure must scroll", "ScrollView", document.id("errorGroup").tagName)
            assertTrue("$relativePath retry must stay reachable", document.id("errorGroup").contains(document.id("retryButton")))
        }

        val wideSettings = resource("layout-w600dp-land/fragment_settings.xml")
        assertEquals("wrap_content", wideSettings.id("settingsLegalActionsRow").attr("layout_height"))
        listOf("rulesButtonLargeText", "pushButtonLargeText", "pushStatusLargeText").forEach { id ->
            val parent = wideSettings.id(id).parentNode as Element
            assertEquals("Wide settings parent for $id must grow", "wrap_content", parent.attr("layout_height"))
        }
    }

    @Test
    fun `compact landscape hud and recovery notice are bounded by the available width`() {
        val home = resource("layout-land/fragment_home.xml")
        assertEquals("match_parent", home.id("homeBalancePanel").parentElement().attr("layout_width"))
        assertEquals("0dp", home.id("homeBalancePanel").attr("layout_width"))
        assertEquals("0dp", home.id("homeLevelPanel").attr("layout_width"))
        assertEquals("match_parent", home.id("homeXpTrack").attr("layout_width"))

        val slot = resource("layout-land/fragment_slot.xml")
        assertEquals("match_parent", (slot.id("slotMachineFrame").parentNode as Element).attr("layout_width"))
        assertEquals("match_parent", slot.id("settlementRecoveryNotice").attr("layout_width"))
    }

    private fun layoutFiles(): List<Path> = listOf("layout", "layout-land", "layout-w600dp-land")
        .flatMap { directory ->
            Files.list(Path.of("src/main/res/$directory")).use { files ->
                files.filter { it.fileName.toString().endsWith(".xml") }.toList()
            }
        }

    private fun resource(relativePath: String): Document = parse(Path.of("src/main/res/$relativePath"))

    private fun parse(path: Path): Document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(path.toFile())

    private fun Document.id(id: String): Element = elements("*")
        .single { it.getAttribute("android:id") == "@+id/$id" }

    private fun Document.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return List(nodes.length) { index -> nodes.item(index) as Element }
    }

    private fun Element.attr(localName: String): String = getAttribute("android:$localName")

    private fun Element.parentElement(): Element = parentNode as Element

    private fun Element.contains(descendant: Element): Boolean {
        var current: org.w3c.dom.Node? = descendant
        while (current != null) {
            if (current === this) return true
            current = current.parentNode
        }
        return false
    }
}
