package com.vslot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeliveryStateTest {
    @Test
    fun noChannelsYetAllowsTheSdkToCreateItsFirstChannel() {
        assertTrue(notificationChannelsAllowDelivery(emptyList(), blockedImportance = 0))
    }

    @Test
    fun oneDeliverableChannelIsEnough() {
        assertTrue(notificationChannelsAllowDelivery(listOf(0, 3, 0), blockedImportance = 0))
    }

    @Test
    fun allBlockedChannelsDisableDelivery() {
        assertFalse(notificationChannelsAllowDelivery(listOf(0, 0), blockedImportance = 0))
    }
}
