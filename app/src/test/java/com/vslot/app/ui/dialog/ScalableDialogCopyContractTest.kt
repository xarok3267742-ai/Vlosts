package com.vslot.app.ui.dialog

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ScalableDialogCopyContractTest {
    @Test
    fun `body copy has a hidden scalable text peer in both orientations`() {
        COPY_SPECS.forEach { spec ->
            orientationLayouts(spec.layout).forEach { path ->
                val document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(path.toFile())
                val bitmapCopy = document.element("ImageView", spec.bitmapId)
                val scalableCopy = document.element("TextView", spec.textId)

                assertTrue("$path must keep image-first body copy", bitmapCopy.getAttribute("android:src").startsWith("@drawable/"))
                assertTrue(
                    "$path bitmap body must retain its image-first design sizing",
                    bitmapCopy.getAttribute("android:layout_height").matches(DP_VALUE) ||
                        bitmapCopy.getAttribute("android:layout_height") == "match_parent"
                )
                assertEquals("@string/${spec.stringName}", scalableCopy.getAttribute("android:text"))
                assertEquals("wrap_content", scalableCopy.getAttribute("android:layout_height"))
                assertEquals("gone", scalableCopy.getAttribute("android:visibility"))
                assertEquals("no", scalableCopy.getAttribute("android:importantForAccessibility"))
                assertTrue(scalableCopy.getAttribute("style").startsWith("@style/VSlotAccessibleCopy"))
                assertFalse("$path scalable copy must not ellipsize", scalableCopy.hasAttribute("android:ellipsize"))
                assertFalse("$path scalable copy must not cap lines", scalableCopy.hasAttribute("android:maxLines"))
            }
        }
    }

    @Test
    fun `large copy can grow the dialog while preserving its normal minimum height`() {
        DIALOG_LAYOUTS.forEach { layout ->
            orientationLayouts(layout).forEach { path ->
                val source = path.readText()
                val wideLandscapeSource = Path.of(
                    "src/main/res/layout-w600dp-land/${path.fileName}"
                ).takeIf { path.parent.fileName.toString() == "layout-land" && it.toFile().exists() }
                    ?.readText()
                assertTrue(
                    "$path needs a standard-size floor in either compact or wide landscape",
                    source.contains("android:minHeight=") ||
                        wideLandscapeSource?.contains("android:minHeight=") == true
                )
                assertTrue("$path decorative panel must follow growing content", source.contains("android:layout_height=\"match_parent\""))
            }
        }

        val helper = source("main/java/com/vslot/app/ui/dialog/DialogWindowPolish.kt")
        assertTrue(helper.contains("installBoundedScrollView(content)"))
        assertTrue(helper.contains("naturalHeightPx = content.measuredHeight"))
    }

    @Test
    fun `dialog fragments use readable scalable copy through the shared policy`() {
        FRAGMENT_COPY_IDS.forEach { (fragment, ids) ->
            val source = source("main/java/com/vslot/app/ui/dialog/$fragment")
            assertTrue("$fragment must use the shared scalable-copy policy", source.contains("bindScalableDialogCopy("))
            ids.forEach { id ->
                assertTrue("$fragment must bind $id", source.contains("binding.$id"))
            }
        }

        val helper = source("main/java/com/vslot/app/ui/dialog/DialogWindowPolish.kt")
        assertTrue(helper.contains("bitmapCopy.visibility = View.GONE"))
        assertTrue(helper.contains("scalableCopy.visibility = View.VISIBLE"))
        assertTrue(helper.contains("bitmapCopy.importantForAccessibility = accessibilityImportance(false)"))
        assertTrue(helper.contains("scalableCopy.importantForAccessibility = accessibilityImportance(true)"))
    }

    @Test
    fun `stateful dialog copy updates text and bitmap from the same resource`() {
        val bonus = source("main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt")
        assertTrue(bonus.contains("binding.bonusBody.contentDescription = bonusBody"))
        assertTrue(bonus.contains("binding.bonusBodyLargeText.text = bonusBody"))

        val lowCoins = source("main/java/com/vslot/app/ui/dialog/LowCoinsDialogFragment.kt")
        assertTrue(lowCoins.contains("binding.lowCoinsBody.contentDescription = getString(bodyText)"))
        assertTrue(lowCoins.contains("binding.lowCoinsBodyLargeText.setText(bodyText)"))

        val result = source("main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt")
        assertTrue(result.contains("binding.resultBody.contentDescription = getString(bodyText)"))
        assertTrue(result.contains("binding.resultBodyLargeText.setText(bodyText)"))

        val paytable = source("main/java/com/vslot/app/ui/dialog/PaytableDialogFragment.kt")
        assertTrue(paytable.contains("binding.paytableFooter.contentDescription = getString(footerText)"))
        assertTrue(paytable.contains("binding.paytableFooterLargeText.setText(footerText)"))
    }

    @Test
    fun `analytics consent describes diagnostics without crash wording`() {
        val stringsDocument = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("src/main/res/values/strings.xml").toFile())
        val strings = stringsDocument.getElementsByTagName("string")
        val consent = (0 until strings.length)
            .map { strings.item(it) as Element }
            .first { it.getAttribute("name") == "analytics_consent_body" }
            .textContent
        val generator = Path.of("../tools/generate_analytics_consent_assets.py").readText()

        assertTrue(consent.contains("данные диагностики и производительности"))
        assertFalse(consent.lowercase().contains("сбо"))
        assertFalse(consent.lowercase().contains("crash"))
        assertTrue(generator.contains("данные диагностики и производительности"))
        assertFalse(generator.lowercase().contains("сбо"))
        assertFalse(generator.lowercase().contains("crash"))
    }

    private fun org.w3c.dom.Document.element(tag: String, id: String): Element {
        val nodes = getElementsByTagName(tag)
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            if (element.getAttribute("android:id") == "@+id/$id") return element
        }
        throw AssertionError("Missing $tag @$id")
    }

    private fun orientationLayouts(layout: String): List<Path> = listOf(
        Path.of("src/main/res/layout/$layout"),
        Path.of("src/main/res/layout-land/$layout")
    )

    private fun source(relativePath: String): String = Path.of("src/$relativePath").readText()

    private data class CopySpec(
        val layout: String,
        val bitmapId: String,
        val textId: String,
        val stringName: String
    )

    private companion object {
        val DP_VALUE = Regex("\\d+dp")
        val DIALOG_LAYOUTS = listOf(
            "dialog_analytics_consent.xml",
            "dialog_push_permission.xml",
            "dialog_social_rules.xml",
            "dialog_bonus.xml",
            "dialog_low_coins.xml",
            "dialog_result.xml",
            "dialog_paytable.xml"
        )
        val COPY_SPECS = listOf(
            CopySpec("dialog_analytics_consent.xml", "analyticsConsentBody", "analyticsConsentBodyLargeText", "analytics_consent_body"),
            CopySpec("dialog_push_permission.xml", "pushPromptBody", "pushPromptBodyLargeText", "push_prompt_body"),
            CopySpec("dialog_social_rules.xml", "socialRulesBody", "socialRulesBodyLargeText", "social_casino_rules_body"),
            CopySpec("dialog_social_rules.xml", "socialRulesFooter", "socialRulesFooterLargeText", "social_casino_rules_footer"),
            CopySpec("dialog_bonus.xml", "bonusBody", "bonusBodyLargeText", "bonus_ready"),
            CopySpec("dialog_low_coins.xml", "lowCoinsBody", "lowCoinsBodyLargeText", "low_coins_bonus_body"),
            CopySpec("dialog_result.xml", "resultBody", "resultBodyLargeText", "result_win_body"),
            CopySpec("dialog_paytable.xml", "paytablePaylineGuide", "paytablePaylineGuideLargeText", "paytable_payline_guide"),
            CopySpec("dialog_paytable.xml", "paytableBetExplanation", "paytableBetExplanationLargeText", "paytable_bet_explanation"),
            CopySpec("dialog_paytable.xml", "paytableFooter", "paytableFooterLargeText", "paytable_footer_violet")
        )
        val FRAGMENT_COPY_IDS = mapOf(
            "AnalyticsConsentDialogFragment.kt" to listOf("analyticsConsentBody", "analyticsConsentBodyLargeText"),
            "SocialRulesDialogFragment.kt" to listOf(
                "socialRulesBody",
                "socialRulesBodyLargeText",
                "socialRulesFooter",
                "socialRulesFooterLargeText"
            ),
            "DailyBonusDialogFragment.kt" to listOf("bonusBody", "bonusBodyLargeText"),
            "LowCoinsDialogFragment.kt" to listOf("lowCoinsBody", "lowCoinsBodyLargeText"),
            "ResultDialogFragment.kt" to listOf("resultBody", "resultBodyLargeText"),
            "PaytableDialogFragment.kt" to listOf(
                "paytablePaylineGuide",
                "paytablePaylineGuideLargeText",
                "paytableBetExplanation",
                "paytableBetExplanationLargeText",
                "paytableFooter",
                "paytableFooterLargeText"
            )
        )
    }
}
