package com.vslot.app.game

import android.content.Context
import com.vslot.app.data.PendingSpinSettlement
import com.vslot.app.data.isValid

sealed interface SpinSettlementVerification {
    data class Verified(val settlement: PendingSpinSettlement) : SpinSettlementVerification
    data class UnsupportedMath(val settlement: PendingSpinSettlement) : SpinSettlementVerification
    data object Corrupt : SpinSettlementVerification
}

class SpinSettlementVerifier internal constructor(
    private val releasedMathRegistry: ReleasedSlotMathRegistry
) {
    constructor(context: Context) : this(
        ReleasedSlotMathRegistry.fromAssets(context.applicationContext)
    )

    constructor(
        slotCatalog: SlotCatalog,
        slotEngine: SlotEngine,
        evaluateStops: (
            config: SlotConfig,
            stopIndexes: List<Int>,
            bet: Int,
            lines: Int,
            isFreeSpin: Boolean
        ) -> SpinResult = slotEngine::evaluateStops
    ) : this(ReleasedSlotMathRegistry.withV5Catalog(slotCatalog, evaluateStops))

    fun inspect(settlement: PendingSpinSettlement?): SpinSettlementVerification {
        if (settlement == null || !settlement.isValid()) {
            return SpinSettlementVerification.Corrupt
        }
        val releasedMath = releasedMathRegistry.release(settlement.mathVersion)
            ?: return SpinSettlementVerification.UnsupportedMath(settlement)
        val config = releasedMath.getSlotExact(settlement.slotId)
            ?: return SpinSettlementVerification.Corrupt
        if (releasedMath.fingerprint(config) != settlement.configFingerprint) {
            return SpinSettlementVerification.Corrupt
        }
        if (!releasedMath.supportsInput(
                config = config,
                stopIndexes = settlement.stopIndexes,
                bet = settlement.lineBet,
                lines = settlement.lines,
                isFreeSpin = settlement.isFreeSpin
            )
        ) {
            return SpinSettlementVerification.Corrupt
        }

        val result = try {
            releasedMath.evaluateStops(
                config,
                settlement.stopIndexes,
                settlement.lineBet,
                settlement.lines,
                settlement.isFreeSpin
            )
        } catch (_: Exception) {
            return SpinSettlementVerification.UnsupportedMath(settlement)
        }
        val expectedXp = releasedMath.xpForSpin(
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
            return SpinSettlementVerification.Corrupt
        }
        return SpinSettlementVerification.Verified(settlement.copy(visualResult = result))
    }

    fun verify(settlement: PendingSpinSettlement): PendingSpinSettlement? {
        return (inspect(settlement) as? SpinSettlementVerification.Verified)?.settlement
    }
}
