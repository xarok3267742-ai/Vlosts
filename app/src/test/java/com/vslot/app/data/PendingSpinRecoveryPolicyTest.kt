package com.vslot.app.data

import com.vslot.app.ProcessSession
import com.vslot.app.game.SlotMathIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSpinRecoveryPolicyTest {
    @Test
    fun `same-process recovery is deferred only while the concrete owner is alive`() {
        val settlement = settlement(ProcessSession.id)

        assertFalse(shouldDeferPendingSpinRecovery(settlement, ProcessSession.id))
        ProcessSession.registerSpinSettlement(settlement.id)
        try {
            assertTrue(shouldDeferPendingSpinRecovery(settlement, ProcessSession.id))
        } finally {
            ProcessSession.releaseSpinSettlement(settlement.id)
        }
        assertFalse(shouldDeferPendingSpinRecovery(settlement, ProcessSession.id))
    }

    @Test
    fun `view model scoped consumer cannot recover its process active settlement`() {
        val settlement = settlement(ProcessSession.id)
        val scopedConsumer = "${ProcessSession.id}:second-view-model"

        ProcessSession.registerSpinSettlement(settlement.id)
        try {
            assertTrue(shouldDeferPendingSpinRecovery(settlement, scopedConsumer))
        } finally {
            ProcessSession.releaseSpinSettlement(settlement.id)
        }
        assertFalse(shouldDeferPendingSpinRecovery(settlement, scopedConsumer))
    }

    @Test
    fun `similar process prefix is not treated as the settlement owner`() {
        val settlement = settlement(ProcessSession.id)
        ProcessSession.registerSpinSettlement(settlement.id)
        try {
            assertFalse(
                shouldDeferPendingSpinRecovery(
                    settlement,
                    "${ProcessSession.id}suffix:second-view-model"
                )
            )
        } finally {
            ProcessSession.releaseSpinSettlement(settlement.id)
        }
    }

    @Test
    fun `previous-process journal is always recoverable`() {
        val settlement = settlement("previous-process")
        ProcessSession.registerSpinSettlement(settlement.id)
        try {
            assertFalse(shouldDeferPendingSpinRecovery(settlement, ProcessSession.id))
        } finally {
            ProcessSession.releaseSpinSettlement(settlement.id)
        }
    }

    private fun settlement(processSessionId: String) = PendingSpinSettlement(
        id = "settlement-policy-test-$processSessionId",
        processSessionId = processSessionId,
        slotId = "violet_fortune",
        isFreeSpin = false,
        lineBet = 10,
        lines = 10,
        totalBet = 100,
        winAmount = 200,
        freeSpinsAwarded = 0,
        levelXpAwarded = 1,
        mathVersion = SlotMathIdentity.VERSION,
        configFingerprint = "0".repeat(64),
        stopIndexes = listOf(0, 0, 0, 0, 0)
    )
}
