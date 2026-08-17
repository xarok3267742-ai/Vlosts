package com.vslot.app.ui.dialog

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBonusDialogLifecycleContractTest {
    @Test
    fun `recreated dialog restores rendered state and then follows player state`() {
        val source = Path.of(
            "src/main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt"
        ).readText()

        assertTrue(source.contains("savedInstanceState.readClaimEnabledOrNull()"))
        assertTrue(source.contains("savedInstanceState.readLastDailyBonusTimestampOrNull()"))
        assertTrue(source.contains("savedInstanceState.readClaimedAmountOrNull()"))
        assertTrue(source.contains("override fun onSaveInstanceState(outState: Bundle)"))
        assertTrue(source.contains("outState.putBoolean(STATE_CLAIM_ENABLED"))
        assertTrue(source.contains("outState.putLong(STATE_LAST_DAILY_BONUS_TIMESTAMP"))
        assertTrue(source.contains("outState.putInt(STATE_CLAIMED_AMOUNT"))
        assertTrue(source.contains("AppGraph.playerRepository.playerState.collect { state ->"))
        assertTrue(source.contains("state.isDailyBonusAvailable()"))
        assertTrue(source.contains("activeLastDailyBonusTimestamp = state.lastDailyBonusTimestamp"))
        assertTrue(source.contains("renderClaimState(enabled = latestClaimEnabled)"))
        assertTrue(source.contains("renderClaimSuccess(result.amount)"))
        assertTrue(source.contains("R.string.bonus_claimed"))
        assertTrue(source.contains("R.drawable.label_continue_action"))
        assertTrue(source.contains("binding.bonusCooldownTimerRail.alpha = 1f"))
    }
}
