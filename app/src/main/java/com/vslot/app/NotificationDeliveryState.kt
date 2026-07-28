package com.vslot.app

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

internal fun Context.areNotificationsDeliverable(): Boolean {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false

    val channels = getSystemService(NotificationManager::class.java)
        ?.notificationChannels
        .orEmpty()
    return notificationChannelsAllowDelivery(
        channelImportances = channels.map { channel -> channel.importance },
        blockedImportance = NotificationManager.IMPORTANCE_NONE
    )
}

internal fun notificationChannelsAllowDelivery(
    channelImportances: List<Int>,
    blockedImportance: Int
): Boolean {
    return channelImportances.isEmpty() || channelImportances.any { importance ->
        importance != blockedImportance
    }
}
