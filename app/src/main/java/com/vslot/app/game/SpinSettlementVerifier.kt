package com.vslot.app.game

import com.vslot.app.data.PendingSpinSettlement
import com.vslot.app.data.PlayerState
import com.vslot.app.data.isValid

class SpinSettlementVerifier(
    private val slotCatalog: SlotCatalog,
    private val slotEngine: SlotEngine
) {
    fun verify(settlement: PendingSpinSettlement): PendingSpinSettlement? {
        if (!settlement.isValid()) return null
        if (!SlotMathIdentity.supports(settlement.mathVersion)) return null
        val config = slotCatalog.getSlotExact(settlement.slotId) ?: return null
        if (SlotMathIdentity.fingerprint(config) != settlement.configFingerprint) return null

        val result = try {
            slotEngine.evaluateStops(
                config = config,
                stopIndexes = settlement.stopIndexes,
                bet = settlement.lineBet,
                lines = settlement.lines,
                isFreeSpin = settlement.isFreeSpin
            )
        } catch (_: Exception) {
            return null
        }
        val expectedXp = PlayerState.xpForSpin(
            totalBet = result.totalBet,
            isFreeSpin = settlement.isFreeSpin,
            winAmount = result.winAmount
        )
        if (
            settlement.totalBet != result.totalBet ||
            settlement.winAmount != result.winAmount ||
            settlement.freeSpinsAwarded != result.freeSpinsAwarded ||
            settlement.levelXpAwarded != expectedXp
        ) {
            return null
        }
        return settlement.copy(visualResult = result)
    }
}
