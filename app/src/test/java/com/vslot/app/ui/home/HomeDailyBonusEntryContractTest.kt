package com.vslot.app.ui.home

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDailyBonusEntryContractTest {
    @Test
    fun `daily bonus opens only from its explicit home action`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()
        val beforeUserAction = source.substringBefore("private fun openDailyBonusFromUserAction")
        val userAction = source
            .substringAfter("private fun openDailyBonusFromUserAction")
            .substringBefore("private fun showDailyBonusDialog")

        assertTrue(beforeUserAction.contains("binding.dailyBonusButton.setOnClickListener"))
        assertTrue(beforeUserAction.contains("openDailyBonusFromUserAction()"))
        assertFalse("State collection must never open a modal", beforeUserAction.contains("showDailyBonusDialog("))
        assertTrue(userAction.contains("latestPlayerState.isDailyBonusAvailable()"))
        assertTrue(userAction.contains("showDailyBonusDialog("))
        assertTrue(userAction.contains("viewModel.onDailyBonusOpen(available)"))
        assertFalse(source.contains("bonusShown"))
        assertFalse(source.contains("KEY_BONUS_SHOWN"))
        assertEquals(
            "There must be one dialog invocation and one dialog function declaration",
            2,
            Regex("showDailyBonusDialog\\(").findAll(source).count()
        )
    }

    @Test
    fun `available daily bonus keeps a visible and accessible call to action`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()
        val readyState = source
            .substringAfter("private fun renderDailyBonusState")
            .substringBefore("private fun bindLevelState")
        val portrait = Path.of("src/main/res/layout/fragment_home.xml").readText()
        val landscape = Path.of("src/main/res/layout-land/fragment_home.xml").readText()

        assertTrue(readyState.contains("binding.dailyBonusStatusText.visibility"))
        assertTrue(readyState.contains("View.VISIBLE"))
        assertTrue(readyState.contains("R.drawable.label_claim_bonus"))
        assertTrue(readyState.contains("R.string.daily_bonus_ready_action"))
        listOf(portrait, landscape).forEach { layout ->
            val action = layout.substringAfter("@+id/dailyBonusButton").substringBefore("@+id/dailyBonusImage")
            assertTrue(action.contains("android:clickable=\"true\""))
            assertTrue(action.contains("android:importantForAccessibility=\"yes\""))
            assertTrue(layout.contains("@+id/dailyBonusClaimPlate"))
            assertTrue(layout.contains("@drawable/label_claim_bonus"))
        }
    }
}
