package com.vslot.app.ui.dialog

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultDialogLifecycleContractTest {
    @Test
    fun `only requested dismiss publishes the result`() {
        val source = resultDialogSource()
        val onDismiss = source.substringAfter("override fun onDismiss(dialog: DialogInterface)")
            .substringBefore("override fun onDestroyView()")

        assertTrue(source.contains("override fun dismiss()"))
        assertTrue(source.contains("override fun dismissNow()"))
        assertTrue(source.contains("override fun dismissAllowingStateLoss()"))
        assertTrue(source.contains("override fun onCancel(dialog: DialogInterface)"))
        assertTrue(onDismiss.contains("dismissGate.consumeDismissResult()"))
        assertTrue(onDismiss.contains("setFragmentResult("))
    }

    @Test
    fun `title uses pane semantics without stealing screen reader focus`() {
        val source = resultDialogSource()

        assertTrue(source.contains("ViewCompat.setAccessibilityPaneTitle(binding.root"))
        assertFalse(source.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertFalse(source.contains("announceForAccessibility"))
        assertFalse(source.contains("TYPE_VIEW_ACCESSIBILITY_FOCUSED"))
        assertFalse(source.contains("sendAccessibilityEvent("))
    }

    private fun resultDialogSource(): String =
        Path.of("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt").readText()
}
