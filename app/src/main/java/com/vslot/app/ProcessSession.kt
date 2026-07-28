package com.vslot.app

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ProcessSession {
    val id: String = UUID.randomUUID().toString()

    private val activeSpinSettlements = ConcurrentHashMap.newKeySet<String>()
    private val activePresentationConsumers = ConcurrentHashMap.newKeySet<String>()

    internal fun registerSpinSettlement(settlementId: String) {
        require(settlementId.isNotBlank()) { "Settlement id is required." }
        activeSpinSettlements += settlementId
    }

    internal fun releaseSpinSettlement(settlementId: String) {
        activeSpinSettlements -= settlementId
    }

    internal fun isSpinSettlementActive(settlementId: String): Boolean {
        return settlementId in activeSpinSettlements
    }

    internal fun registerPresentationConsumer(consumerId: String) {
        require(consumerId.isNotBlank()) { "Presentation consumer id is required." }
        activePresentationConsumers += consumerId
    }

    internal fun releasePresentationConsumer(consumerId: String) {
        activePresentationConsumers -= consumerId
    }

    internal fun isPresentationConsumerActive(consumerId: String): Boolean {
        return consumerId in activePresentationConsumers
    }
}
