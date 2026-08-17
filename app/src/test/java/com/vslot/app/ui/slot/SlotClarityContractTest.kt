package com.vslot.app.ui.slot

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotClarityContractTest {
    private val resourceRoot = Path.of("src/main/res")

    @Test
    fun `every slot layout names line bet spin cost payout and locked free spin stake`() {
        listOf(
            "layout/fragment_slot.xml",
            "layout-land/fragment_slot.xml",
            "layout-w600dp-land/fragment_slot.xml"
        ).forEach { relativePath ->
            val layout = resourceRoot.resolve(relativePath).readText()
            assertTrue(relativePath, layout.contains("android:text=\"@string/line_bet_short\""))
            assertTrue(relativePath, layout.contains("android:text=\"@string/spin_cost\""))
            assertTrue(relativePath, layout.contains("android:text=\"@string/payout_short\""))
            assertTrue(relativePath, layout.contains("@+id/freeSpinsStakeLockLabel"))
            assertTrue(relativePath, layout.contains("android:text=\"@string/free_spins_stake_locked\""))
        }
    }

    @Test
    fun `every net outcome has a visible compact meter status without false win feedback`() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val strings = resourceRoot.resolve("values/slot_ui_strings.xml").readText()
        val returnFeedback = fragment
            .substringAfter("NetOutcome.PartialReturn,")
            .substringBefore("NetOutcome.NetWin,")
        val neutralAnimation = fragment
            .substringAfter("private fun animatePartialReturnOverlay()")
            .substringBefore("private fun showResultDialog")

        listOf(
            "slot_outcome_loss_visible",
            "slot_outcome_partial_return_visible",
            "slot_outcome_break_even_visible",
            "slot_outcome_net_win_visible",
            "slot_outcome_bonus_visible"
        ).forEach { stringName ->
            assertTrue(strings.contains("name=\"$stringName\""))
            assertTrue(fragment.contains("R.string.$stringName"))
        }
        assertFalse(returnFeedback.contains("SlotSoundCue.Payout"))
        assertTrue(returnFeedback.contains("animatePartialReturnOverlay()"))
        assertTrue(neutralAnimation.contains("binding.lastWinPanelMeterGlow"))
        assertFalse(neutralAnimation.contains("binding.winGlowOverlay"))

        listOf(
            "layout/fragment_slot.xml",
            "layout-land/fragment_slot.xml",
            "layout-w600dp-land/fragment_slot.xml"
        ).forEach { relativePath ->
            val layout = resourceRoot.resolve(relativePath).readText()
            listOf("betLabel", "linesLabel", "totalBetLabel", "lastWinLabel").forEach { id ->
                val label = layout.substringAfter("@+id/$id").substringBefore("/>")
                assertFalse("$relativePath $id", label.contains("android:maxLines="))
                assertTrue("$relativePath $id", label.contains("app:autoSizeTextType=\"uniform\""))
                assertTrue("$relativePath $id", label.contains("app:autoSizeMinTextSize=\"8dp\""))
                assertTrue("$relativePath $id", label.contains("app:autoSizeMaxTextSize=\"12dp\""))
            }
        }
    }

    @Test
    fun `idle payline geometry stays subordinate to reel symbols`() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        assertTrue(fragment.contains("PAYLINE_MARKER_REST_ALPHA = 0.06f"))
        assertTrue(fragment.contains("PAYLINE_MARKER_PULSE_ALPHA = 0.24f"))
        assertFalse(fragment.contains("binding.paylineMarkersOverlay.alpha = 1f"))
    }

    @Test
    fun `idle free spin rail is hidden and locked stake keeps its values visible`() {
        listOf(
            "layout/fragment_slot.xml",
            "layout-land/fragment_slot.xml",
            "layout-w600dp-land/fragment_slot.xml"
        ).forEach { relativePath ->
            val layout = resourceRoot.resolve(relativePath).readText()
            val rail = layout.substringAfter("@+id/freeSpinsRail").substringBefore("</FrameLayout>")
            assertTrue(relativePath, rail.contains("android:visibility=\"invisible\""))
        }

        val fragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        assertTrue(fragment.contains("binding.freeSpinsRail.visibility = if (freeSpinModeActive) View.VISIBLE else View.INVISIBLE"))
        assertTrue(fragment.contains("setStakeControlsVisible(true)"))
        assertTrue(fragment.contains("R.string.free_spins_stake_locked_accessibility"))
    }

    @Test
    fun `completed free spins show a total win summary`() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val viewModel = Path.of("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt").readText()
        val dialog = Path.of("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt").readText()
        val strings = resourceRoot.resolve("values/slot_ui_strings.xml").readText()

        assertTrue(viewModel.contains("pendingFreeSpinsTotalWin"))
        assertTrue(viewModel.contains("recordFreeSpinWin"))
        assertTrue(viewModel.contains("FREE_SPINS_TOTAL_WIN_KEY"))
        assertTrue(fragment.contains("ResultDialogFragment.newFreeSpinsSummary"))
        assertTrue(dialog.contains("R.string.free_spins_summary_title"))
        assertTrue(dialog.contains("R.string.free_spins_summary_body"))
        assertTrue(dialog.contains("R.string.free_spins_summary_amount_accessibility"))
        assertTrue(dialog.contains("showPlus = !isFreeSpinsSummary || winAmount > 0"))
        assertTrue(strings.contains("Фриспины завершены\\nИтог серии"))
    }

    @Test
    fun `free spin resume cannot queue paid spins and autospin stop remains visible`() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val viewModel = Path.of("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt").readText()
        val startAutoSpin = viewModel
            .substringAfter("fun startAutoSpin(count: Int)")
            .substringBefore("fun stopAutoSpin")

        assertTrue(startAutoSpin.contains("AutoPlayState.FreeSpins(suspendedPaidBatch = null)"))
        assertFalse(startAutoSpin.contains("suspendedPaidBatch = paidBatch"))
        assertTrue(viewModel.contains("autoSpinStopReason = MutableStateFlow<AutoSpinStopReason?>(null)"))
        assertTrue(fragment.contains("bindAutoSpinStopNotice(state.autoSpinStopReason"))
        listOf(
            "layout/fragment_slot.xml",
            "layout-land/fragment_slot.xml",
            "layout-w600dp-land/fragment_slot.xml"
        ).forEach { relativePath ->
            val layout = resourceRoot.resolve(relativePath).readText()
            assertTrue(relativePath, layout.contains("@+id/autoSpinStopNotice"))
            assertTrue(relativePath, layout.contains("android:accessibilityLiveRegion=\"assertive\""))
        }
    }

    @Test
    fun `paytable uses scalable values and one scatter symbol layer`() {
        val dialog = Path.of("src/main/java/com/vslot/app/ui/dialog/PaytableDialogFragment.kt").readText()
        listOf(
            "layout/dialog_paytable.xml",
            "layout-land/dialog_paytable.xml",
            "layout-w600dp-land/dialog_paytable.xml"
        ).forEach { relativePath ->
            val layout = resourceRoot.resolve(relativePath).readText()
            assertTrue(relativePath, layout.contains("style=\"@style/VSlotAccessibleCopy.PaytableValue\""))
            assertTrue(relativePath, layout.contains("android:visibility=\"visible\""))
        }
        assertTrue(dialog.contains("TextView(requireContext()).apply"))
        assertFalse(dialog.contains("R.drawable.symbol_bonus_scatter_halo"))
        assertFalse(dialog.contains("R.drawable.modal_badge_bonus"))
    }
}
