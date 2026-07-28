package com.vslot.app

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSessionTest {
    @Test
    fun `settlement ownership is active only between register and release`() {
        val settlementId = UUID.randomUUID().toString()

        assertFalse(ProcessSession.isSpinSettlementActive(settlementId))
        ProcessSession.registerSpinSettlement(settlementId)
        assertTrue(ProcessSession.isSpinSettlementActive(settlementId))
        ProcessSession.releaseSpinSettlement(settlementId)
        assertFalse(ProcessSession.isSpinSettlementActive(settlementId))
    }
}
