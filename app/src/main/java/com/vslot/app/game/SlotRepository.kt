package com.vslot.app.game

import android.content.Context

interface SlotCatalog {
    fun getSlot(slotId: String): SlotConfig

    fun getSlotExact(slotId: String): SlotConfig? = getSlot(slotId).takeIf { it.id == slotId }
}

class SlotRepository(
    context: Context,
    parser: SlotConfigParser = SlotConfigParser()
) : SlotCatalog {
    val slots: List<SlotConfig> by lazy {
        val json = context.assets.open("slots_config.json")
            .bufferedReader()
            .use { it.readText() }
        parser.parse(json)
    }

    override fun getSlot(slotId: String): SlotConfig {
        return getSlotExact(slotId) ?: slots.first()
    }

    override fun getSlotExact(slotId: String): SlotConfig? {
        return slots.firstOrNull { it.id == slotId }
    }
}
