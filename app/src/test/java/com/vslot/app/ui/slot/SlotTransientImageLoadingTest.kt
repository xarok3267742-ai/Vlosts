package com.vslot.app.ui.slot

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SlotTransientImageLoadingTest {
    @Test
    fun `heavy transient slot images are preview only until their effect starts`() {
        SLOT_LAYOUTS.forEach { layoutPath ->
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(Path.of(layoutPath).toFile())
            val imageViews = document.getElementsByTagName("ImageView")
            val imagesById = (0 until imageViews.length)
                .map { imageViews.item(it) as Element }
                .associateBy { it.attribute(ANDROID_NAMESPACE, "id").substringAfterLast('/') }

            TRANSIENT_IMAGE_IDS.forEach { imageId ->
                val image = imagesById[imageId]
                assertTrue("$layoutPath is missing transient image $imageId", image != null)
                assertEquals(
                    "$imageId must not decode a runtime bitmap while hidden in $layoutPath",
                    "",
                    image?.attribute(ANDROID_NAMESPACE, "src")
                )
                assertTrue(
                    "$imageId must keep a layout preview asset in $layoutPath",
                    image?.attribute(TOOLS_NAMESPACE, "src").orEmpty().startsWith("@drawable/")
                )
            }
        }
    }

    private fun Element.attribute(namespace: String, name: String): String {
        return getAttributeNS(namespace, name)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
        val SLOT_LAYOUTS = listOf(
            "src/main/res/layout/fragment_slot.xml",
            "src/main/res/layout-land/fragment_slot.xml"
        )
        val TRANSIENT_IMAGE_IDS = setOf(
            "winGlowOverlay",
            "themeSpinOverlay",
            "freeSpinsModeOverlay",
            "freeSpinsRailCharge",
            "spinEnergyOverlay",
            "winningPaylineOverlay",
            "spinBlurOverlay",
            "coinBurstOverlay",
            "bonusEntryPortalOverlay",
            "bigWinBannerOverlay",
            "totalBetLinkPulse",
            "freeSpinsStakeLockOverlay",
            "slamStopCue",
            "spinButtonImpactFlash",
            "autoSpinActiveHalo"
        )
    }
}
