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
        val homeSource = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()

        assertEquals("FrameLayout", activity.documentElement.tagName)
        assertEquals("match_parent", background.attr("layout_width"))
        assertEquals("match_parent", background.attr("layout_height"))
        assertEquals("centerCrop", background.attr("scaleType"))
        assertEquals("@color/deep_navy", background.attr("background"))
        assertEquals("", background.attr("src"))
        assertEquals("match_parent", navHost.attr("layout_width"))
        assertEquals("match_parent", navHost.attr("layout_height"))
        assertTrue(source.contains("val navHostView = binding.navHostFragment"))
        assertTrue(source.contains("R.id.splashFragment -> R.drawable.splash_bg"))
        assertTrue(source.contains("R.id.homeFragment -> R.drawable.home_bg"))
        assertTrue(source.contains("if (destinationId == R.id.slotFragment)"))
        assertTrue(source.contains("internal fun releaseScreenBackgroundForSlot()"))
        assertTrue(source.contains("internal fun prepareScreenBackgroundForSlot(slotId: String)"))
        assertTrue(source.contains("binding.screenBackground.setImageDrawable(null)"))
        assertTrue(homeSource.contains("prepareScreenBackgroundForSlot(slotId)"))
        assertTrue(source.contains("else -> R.drawable.main_bg"))
    }

    @Test
    fun `every platform text view wraps and uses the shared Russian line breaking policy`() {
        layoutFiles().forEach { path ->
            val document = parse(path)
            document.elements("TextView").forEach { textView ->
                val id = textView.getAttribute("android:id").ifBlank { "anonymous TextView" }
                val isAutosizedSingleLine =
                    textView.attr("maxLines") == "1" &&
                        textView.attr("singleLine") == "true" &&
                        textView.getAttribute("app:autoSizeTextType") == "uniform"
                if (isAutosizedSingleLine) {
                    assertTrue(
                        "$path $id must have a bounded single-line height",
                        textView.attr("layout_height") in setOf("wrap_content", "match_parent")
                    )
                } else {
                    assertEquals("$path $id must grow vertically", "wrap_content", textView.attr("layout_height"))
                }
                assertTrue(
                    "$path $id must inherit the scalable copy policy",
                    textView.getAttribute("style").startsWith("@style/VSlotAccessibleCopy")
                )
                assertFalse("$path $id must not ellipsize", textView.hasAttribute("android:ellipsize"))
                if (!isAutosizedSingleLine) {
                    assertFalse("$path $id must not cap lines", textView.hasAttribute("android:maxLines"))
                }
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
    fun `portrait disclaimer action fits the narrowest supported content column`() {
        val disclaimer = resource("layout/fragment_disclaimer.xml")
        val continueButton = disclaimer.id("continueButton")
        val actionContainer = continueButton.parentElement()

        assertEquals("200dp", actionContainer.attr("layout_width"))
        assertEquals("match_parent", continueButton.attr("layout_width"))
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
        listOf("betLabel", "linesLabel").forEach { id ->
            val meterColumn = slot.id(id).parentElement()
            assertEquals("$id must preserve compact label width", "4dp", meterColumn.attr("paddingStart"))
            assertEquals("$id must preserve compact label width", "4dp", meterColumn.attr("paddingEnd"))
        }

        val source = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        assertTrue(source.contains("private fun adaptCompactLandscapeLayout()"))
        assertTrue(source.contains("configuration.screenHeightDp >= COMPACT_LANDSCAPE_MAX_HEIGHT_DP"))
        assertTrue(source.contains("gameContent.orientation = LinearLayout.HORIZONTAL"))
        assertTrue(source.contains("calculateSlotMachineHeight(machineWidth, minimumHeightPx = 0)"))
        assertTrue(source.contains("binding.totalBetLabel.setText(R.string.spin_cost_compact)"))
    }

    @Test
    fun `theme-bound slot chrome is not decoded twice during inflation`() {
        val runtimeBoundIds = listOf(
            "slotMachineFrame",
            "slotCabinetLights",
            "slotCabinetChaseLights",
            "slotMarqueeGlass",
            "reelDepthDividers",
            "paylineMarkersOverlay",
            "reelWindowDepthMask",
            "reelApertureShadow",
            "activeLinesRailImage",
            "activeLinesRailLabel",
            "freeSpinsRailImage",
            "betPanelImage",
            "betPanelMeterGlow",
            "betMinusButton",
            "betPlusButton",
            "linesMinusButton",
            "linesPlusButton",
            "lastWinPanelImage",
            "lastWinPanelMeterGlow",
            "spinDeckGlow",
            "spinButtonReadyGlow",
            "paytableButtonDockGlow",
            "paytableButtonIcon",
            "paytableButtonLabel",
            "spinButton",
            "autoSpinButton",
            "maxLinesButtonIcon"
        )
        listOf(
            "layout/fragment_slot.xml",
            "layout-land/fragment_slot.xml",
            "layout-w600dp-land/fragment_slot.xml"
        ).forEach { relativePath ->
            val slot = resource(relativePath)
            runtimeBoundIds.forEach { id ->
                val view = slot.id(id)
                assertEquals("$relativePath $id must defer runtime bitmap loading", "", view.attr("src"))
                assertTrue("$relativePath $id must keep a design-time preview", view.getAttribute("tools:src").isNotBlank())
            }
        }
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
