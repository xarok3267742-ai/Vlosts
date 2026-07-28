package com.vslot.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushOpenPayloadTest {
    @Test
    fun `extracts canonical campaign id and payload type`() {
        val payload = PushOpenPayload.fromExtras(
            mapOf(
                "campaign_id" to "daily_bonus_01",
                "payload_type" to "daily_bonus"
            )
        )

        assertEquals("daily_bonus_01", payload?.campaignId)
        assertEquals("daily_bonus", payload?.payloadType)
    }

    @Test
    fun `extracts appmetrica campaign id and defaults payload type`() {
        val payload = PushOpenPayload.fromExtras(
            mapOf("appmetrica_push_campaign_id" to "return_bonus")
        )

        assertEquals("return_bonus", payload?.campaignId)
        assertEquals("push", payload?.payloadType)
    }

    @Test
    fun `ignores payload without campaign id`() {
        assertNull(PushOpenPayload.fromExtras(mapOf("payload_type" to "daily_bonus")))
    }

    @Test
    fun `normalizes push payload values before analytics`() {
        val payload = PushOpenPayload.fromExtras(
            mapOf(
                "campaign_id" to "  return bonus/summer.open  ",
                "payload_type" to "daily bonus/open"
            )
        )

        assertEquals("return_bonus_summer.open", payload?.campaignId)
        assertEquals("daily_bonus_open", payload?.payloadType)
    }

    @Test
    fun `rejects push campaign ids that look like personal data or urls`() {
        assertNull(PushOpenPayload.fromExtras(mapOf("campaign_id" to "email@example.com")))
        assertNull(PushOpenPayload.fromExtras(mapOf("campaign_id" to "https://example.com/campaign?token=secret")))
        assertNull(PushOpenPayload.fromExtras(mapOf("campaign_id" to "return_bonus?token=secret")))
    }

    @Test
    fun `drops blank normalized campaign id`() {
        assertNull(PushOpenPayload.fromExtras(mapOf("campaign_id" to "  ///  ")))
    }
}
