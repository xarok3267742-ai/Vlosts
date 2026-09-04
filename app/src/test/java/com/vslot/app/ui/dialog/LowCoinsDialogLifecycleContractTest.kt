package com.vslot.app.ui.dialog

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class LowCoinsDialogLifecycleContractTest {
    @Test
    fun `completed async bonus claim dismisses safely after state save`() {
        val source = Path.of(
            "src/main/java/com/vslot/app/ui/dialog/LowCoinsDialogFragment.kt"
        ).readText()
        val safeDismiss = source
            .substringAfter("private fun dismissAfterAsyncClaim()")
            .substringBefore("companion object")

        assertTrue(source.contains("dismissAfterAsyncClaim()"))
        assertTrue(safeDismiss.contains("!dialogUiActive || !isAdded"))
        assertTrue(safeDismiss.contains("parentFragmentManager.isStateSaved"))
        assertTrue(safeDismiss.contains("dismissAllowingStateLoss()"))
        assertTrue(source.contains("restoredFromSavedState && initialBonusAvailable"))
        assertTrue(source.contains("isDailyBonusAvailable()"))
        assertTrue(source.contains("arguments?.putBoolean(ARG_BONUS_AVAILABLE, false)"))
    }
}
