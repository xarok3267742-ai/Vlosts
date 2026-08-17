package com.vslot.app.ui.slot

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinPresentationLifecycleContractTest {
    @Test
    fun `durable presentation is acknowledged only after a real draw`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val resultDialog = source("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt")

        assertTrue(slotFragment.contains("ViewTreeObserver.OnDrawListener"))
        assertTrue(slotFragment.contains("target.post"))
        assertTrue(slotFragment.contains("viewModel.onSpinPresentationRendered(presentationId)"))
        assertTrue(resultDialog.contains("ViewTreeObserver.OnDrawListener"))
        assertTrue(resultDialog.contains("root.post"))
        assertTrue(resultDialog.contains("PRESENTED_REQUEST_KEY"))
    }

    @Test
    fun `modal request remains replayable when fragment state is saved`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val viewModel = source("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt")
        val showDialogBlock = slotFragment
            .substringAfter("private fun showResultDialog(")
            .substringBefore("private fun showPendingResultDialogIfNeeded")

        assertTrue(slotFragment.contains("showPendingResultDialogIfNeeded(state)"))
        assertTrue(showDialogBlock.contains("parentFragmentManager.isStateSaved"))
        assertFalse(showDialogBlock.contains("onResultDialogDismissed"))
        assertTrue(viewModel.contains("deferredResultDialogPresentedId"))
        assertTrue(viewModel.contains("reconcileDeferredResultDialogPresentation()"))
    }

    @Test
    fun `configuration recreation restores result dialog auto dismiss`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue(slotFragment.contains("activity?.isChangingConfigurations != true"))
        assertTrue(slotFragment.contains("resumeRestoredResultDialogAutoDismissIfNeeded(state)"))
        assertTrue(slotFragment.contains("restoredDialog !is ResultDialogFragment"))
    }

    @Test
    fun `dynamic slot status uses semantic accessibility state`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue(slotFragment.contains("ViewCompat.setStateDescription"))
        assertFalse(slotFragment.contains(".announceForAccessibility("))
    }

    @Test
    fun `persistent settlement failure is visible and does not trap navigation`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val mainActivity = source("src/main/java/com/vslot/app/MainActivity.kt")
        val exitRequest = slotFragment
            .substringAfter("private fun handleSlotExitRequest")
            .substringBefore("private fun handleAutoSpinClick")

        assertTrue(exitRequest.contains("if (state.isSettlementRecoveryPending)"))
        assertTrue(exitRequest.contains("popFromSlot()"))
        assertTrue(slotFragment.contains("isPending = state.isSettlementRecoveryPending"))
        assertTrue(slotFragment.contains("isBlockedByMath = state.isSettlementRecoveryBlockedByMath"))
        assertTrue(slotFragment.contains("binding.settlementRecoveryNotice.isVisible = isPending"))
        assertTrue(mainActivity.contains("destination.id == R.id.homeFragment"))
        assertTrue(mainActivity.contains("recoverPendingSpinSettlement(HOME_SETTLEMENT_RECOVERY_DELAY_MS)"))
    }

    @Test
    fun `reel preview follows monotonic time instead of stretching under dropped frames`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val previewLoop = slotFragment
            .substringAfter("spinPreviewJob = viewLifecycleOwner.lifecycleScope.launch")
            .substringBefore("private fun reelSpinPreviewFrameDelayMs")
        val stripRenderer = slotFragment
            .substringAfter("private fun renderSpinStripColumn(")
            .substringBefore("private fun animateReelColumnSpin")
        val ambientRenderer = slotFragment
            .substringAfter("private fun updateThemeAmbientOverlay(")
            .substringBefore("private fun stopThemeAmbientOverlay")

        assertTrue(previewLoop.contains("val previewTimelineStartedAtMs = monotonicTimeMs() - elapsedMs"))
        assertTrue(previewLoop.contains("monotonicTimeMs() - previewTimelineStartedAtMs"))
        assertTrue(previewLoop.contains("remainingColumnWorkBudget = REEL_SPIN_COLUMNS_PER_TICK"))
        assertTrue(ambientRenderer.contains("if (isSpinning)"))
        assertTrue(ambientRenderer.contains("stopThemeAmbientOverlay()"))
        assertFalse(previewLoop.contains("elapsedMs += REEL_SPIN_TICK_MS"))
        assertFalse(stripRenderer.contains("imageView.animate().cancel()"))
    }

    @Test
    fun `dynamic reel view references are released with the fragment view`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val destroyView = slotFragment
            .substringAfter("override fun onDestroyView()")
            .substringBefore("private fun stopWinGlowOverlay")

        assertTrue(destroyView.contains("reelCellBackdrops.clear()"))
        assertTrue(destroyView.contains("reelLandingSparkViews.clear()"))
        assertTrue(destroyView.contains("reelLandingSparkAnimators.clear()"))
    }

    @Test
    fun `scatter anticipation is owned and cancelled before reel stop and teardown`() {
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val anticipation = slotFragment
            .substringAfter("private fun animateSpinStripColumnAnticipation")
            .substringBefore("private fun cancelReelScatterAnticipation")
        val cancelOne = slotFragment
            .substringAfter("private fun cancelReelScatterAnticipation")
            .substringBefore("private fun cancelAllReelScatterAnticipation")
        val cancelAll = slotFragment
            .substringAfter("private fun cancelAllReelScatterAnticipation")
            .substringBefore("private fun releaseReelScatterAnticipation")
        val release = slotFragment
            .substringAfter("private fun releaseReelScatterAnticipation")
            .substringBefore("private fun animateReelAnticipationKick")
        val stop = slotFragment
            .substringAfter("private fun animateSpinStripColumnStop")
            .substringBefore("private fun revealStoppedReelColumn")
        val stopPreview = slotFragment
            .substringAfter("private fun stopSpinPreview()")
            .substringBefore("private fun highlightedCellIndexes")

        assertTrue(
            slotFragment.contains(
                "private val reelScatterAnticipationAnimators = mutableMapOf<Int, AnimatorSet>()"
            )
        )
        assertTrue(anticipation.contains("cancelReelScatterAnticipation(column)"))
        assertTrue(
            anticipation.contains(
                "reelScatterAnticipationAnimators[column] = AnimatorSet().apply"
            )
        )
        assertTrue(cancelOne.contains("reelScatterAnticipationAnimators.remove(column)?.cancel()"))
        assertTrue(cancelOne.contains("strip.scaleX = 1f"))
        assertTrue(cancelOne.contains("strip.translationX = 0f"))
        assertTrue(cancelAll.contains("forEach(::cancelReelScatterAnticipation)"))
        assertTrue(release.contains("reelScatterAnticipationAnimators[column] === animation"))
        assertTrue(release.contains("reelScatterAnticipationAnimators.remove(column)"))
        val cancelBeforeStop = stop.indexOf("cancelReelScatterAnticipation(column)")
        val stopAnimator = stop.indexOf("reelSpinStopAnimators[column] = AnimatorSet().apply")
        assertTrue(cancelBeforeStop >= 0 && stopAnimator > cancelBeforeStop)
        assertTrue(stopPreview.contains("cancelAllReelScatterAnticipation()"))
    }

    private fun source(relativePath: String): String = Path.of(relativePath).readText()
}
