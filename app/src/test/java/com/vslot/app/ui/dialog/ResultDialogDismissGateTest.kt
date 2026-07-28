package com.vslot.app.ui.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultDialogDismissGateTest {
    @Test
    fun `lifecycle dismiss without a close request is ignored`() {
        val gate = ResultDialogDismissGate()

        assertFalse(gate.consumeDismissResult())
    }

    @Test
    fun `real close dispatches exactly once`() {
        val gate = ResultDialogDismissGate()

        gate.requestDismiss()

        assertTrue(gate.consumeDismissResult())
        assertFalse(gate.consumeDismissResult())
    }

    @Test
    fun `duplicate close requests still dispatch exactly once`() {
        val gate = ResultDialogDismissGate()

        gate.requestDismiss()
        assertTrue(gate.consumeDismissResult())

        gate.requestDismiss()
        assertFalse(gate.consumeDismissResult())
    }
}
