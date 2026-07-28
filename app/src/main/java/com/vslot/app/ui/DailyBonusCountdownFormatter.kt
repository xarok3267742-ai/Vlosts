package com.vslot.app.ui

import com.vslot.app.data.PlayerState

object DailyBonusCountdownFormatter {
    const val ONE_SECOND_MS = 1_000L

    private const val SECONDS_IN_MINUTE = 60
    private const val MINUTES_IN_HOUR = 60
    private const val SECONDS_IN_HOUR = SECONDS_IN_MINUTE * MINUTES_IN_HOUR
    private const val MAX_DISPLAY_HOURS = 99
    private const val MAX_DISPLAY_SECONDS =
        MAX_DISPLAY_HOURS * SECONDS_IN_HOUR + 59 * SECONDS_IN_MINUTE + 59

    fun format(
        lastDailyBonusTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ): DailyBonusCountdownDisplay {
        val remainingMs = PlayerState.dailyBonusRemainingMs(lastDailyBonusTimestamp, now)
        val uncappedSeconds = remainingMs / ONE_SECOND_MS +
            if (remainingMs % ONE_SECOND_MS == 0L) 0L else 1L
        val totalSeconds = uncappedSeconds.coerceIn(0L, MAX_DISPLAY_SECONDS.toLong())
        val hours = (totalSeconds / SECONDS_IN_HOUR).toInt()
        val minutes = ((totalSeconds / SECONDS_IN_MINUTE) % MINUTES_IN_HOUR).toInt()
        val seconds = (totalSeconds % SECONDS_IN_MINUTE).toInt()
        return DailyBonusCountdownDisplay(
            hours = hours,
            minutes = minutes,
            seconds = seconds,
            isReady = remainingMs == 0L,
            digits = "${hours.twoDigits()}:${minutes.twoDigits()}:${seconds.twoDigits()}"
        )
    }

    fun accessibility(
        hours: Int,
        minutes: Int,
        seconds: Int
    ): DailyBonusCountdownAccessibility {
        val totalMinutes = hours * MINUTES_IN_HOUR + minutes
        return if (totalMinutes == 0) {
            DailyBonusCountdownAccessibility(
                bucket = -seconds,
                usesSeconds = true,
                hours = hours,
                minutes = minutes,
                seconds = seconds
            )
        } else {
            DailyBonusCountdownAccessibility(
                bucket = totalMinutes,
                usesSeconds = false,
                hours = hours,
                minutes = minutes,
                seconds = seconds
            )
        }
    }

    private fun Int.twoDigits(): String = toString().padStart(2, '0')
}

data class DailyBonusCountdownDisplay(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val isReady: Boolean,
    val digits: String
)

data class DailyBonusCountdownAccessibility(
    val bucket: Int,
    val usesSeconds: Boolean,
    val hours: Int,
    val minutes: Int,
    val seconds: Int
)
