package com.vslot.app.ui.slot

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotExitMemoryContractTest {
    @Test
    fun `slot releases image resources before returning home`() {
        val slotSource = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val imageResourceSource = Path.of(
            "src/main/java/com/vslot/app/ui/widget/ImageViewResources.kt"
        ).readText()
        val popFromSlot = slotSource
            .substringAfter("private fun popFromSlot")
            .substringBefore("private fun handleSlotExitRequest")

        assertTrue(
            popFromSlot.indexOf("releaseSlotImageResources()") <
                popFromSlot.indexOf("navController.popBackStack()")
        )
        assertTrue(imageResourceSource.contains("fun View.clearImageResourcesRecursively()"))
        assertTrue(imageResourceSource.contains("if (this is ImageView)"))
        assertTrue(imageResourceSource.contains("if (this is ViewGroup)"))
        assertTrue(imageResourceSource.contains("getChildAt(index).clearImageResourcesRecursively()"))
    }
}
