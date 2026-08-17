package com.vslot.app.ui.slot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class SlotBackgroundLifecycleContractTest {
    @Test
    fun `slot stops autospin audio and continuous visuals before entering background`() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val viewModel = Path.of("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt").readText()
        val soundPlayer = Path.of("src/main/java/com/vslot/app/ui/slot/SlotSoundPlayer.kt").readText()
        val backgroundCleanup = fragment
            .substringAfter("private fun stopBackgroundFeedback()")
            .substringBefore("override fun onDestroyView()")

        val onStop = fragment.indexOf("override fun onStop()")
        val pauseAutoSpin = fragment.indexOf("viewModel.pauseAutoSpin()", onStop)
        val stopFeedback = fragment.indexOf("stopBackgroundFeedback()", onStop)
        val superOnStop = fragment.indexOf("super.onStop()", onStop)
        assertTrue(
            "Slot must stop autospin and feedback before completing onStop",
            onStop >= 0 && pauseAutoSpin > onStop && stopFeedback > pauseAutoSpin && superOnStop > stopFeedback
        )
        val onStart = fragment.indexOf("override fun onStart()")
        assertTrue(
            "Slot must resume an unfinished persisted free-spin feature on foreground entry",
            onStart >= 0 && fragment.indexOf("viewModel.resumeFreeSpinsFeatureIfNeeded()", onStart) > onStart
        )
        assertTrue(
            "Slot must retry a durable pending presentation on foreground entry",
            onStart >= 0 && fragment.indexOf("viewModel.retryPendingPresentationRecovery()", onStart) > onStart
        )
        assertTrue(
            "Background cleanup must cancel reel, infinite overlay, carousel and delayed dialog work",
            backgroundCleanup.contains("slotSoundPlayer?.stopAll()") &&
                backgroundCleanup.indexOf("wasSpinning = false") >
                backgroundCleanup.indexOf("slotSoundPlayer?.stopAll()") &&
                backgroundCleanup.contains("stopSpinPreview()") &&
                backgroundCleanup.contains("stopSpinReadyGlow(immediate = true)") &&
                backgroundCleanup.contains("stopCabinetLights()") &&
                backgroundCleanup.contains("stopThemeAmbientOverlay()") &&
                backgroundCleanup.contains("winningPaylineCarouselJob?.cancel()") &&
                backgroundCleanup.contains("autoSpinResultDismissJob?.cancel()")
        )
        assertTrue(
            "Autospin stop must clear mode and cancel its delayed next-spin job",
            viewModel.contains("autoPlayState.value = AutoPlayState.Off") &&
                viewModel.contains("autoSpinJob?.cancel()") &&
                viewModel.contains("autoSpinJob = null") &&
                viewModel.contains("playerRepository.updateFreeSpinAutoPlay(config.id, enabled = false)") &&
                viewModel.contains("CoroutineStart.UNDISPATCHED") &&
                viewModel.contains("withContext(NonCancellable)")
        )
        assertTrue(
            "Sound player must retain bounded stream ids and stop each active stream",
            soundPlayer.contains("ConcurrentLinkedQueue<Int>()") &&
                soundPlayer.contains("MAX_TRACKED_STREAMS = 16") &&
                soundPlayer.contains("fun stopAll()") &&
                soundPlayer.contains("soundPool.stop(streamId)") &&
                soundPlayer.indexOf("stopAll()") < soundPlayer.indexOf("released = true")
        )
    }

    @Test
    fun `transient result animations are cancelled in background and view teardown`() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val backgroundCleanup = fragment
            .substringAfter("private fun stopBackgroundFeedback()")
            .substringBefore("override fun onDestroyView()")
        val destroyView = fragment
            .substringAfter("override fun onDestroyView()")
            .substringBefore("private fun stopWinGlowOverlay")
        val hideBanner = fragment
            .substringAfter("private fun hideBigWinBanner")
            .substringBefore("private fun startSpinBlurOverlay")

        assertTrue(backgroundCleanup.contains("hideSpinImpactFlash(immediate = true)"))
        assertTrue(backgroundCleanup.contains("hideBigWinBanner(immediate = true)"))
        assertTrue(backgroundCleanup.contains("hideThemeWinBurst(immediate = true)"))
        assertTrue(backgroundCleanup.contains("hideBonusEntryPortal(immediate = true)"))
        assertTrue(backgroundCleanup.contains("hideReelStopFlashLayer(immediate = true)"))
        assertTrue(backgroundCleanup.contains("hideWinningPaylineOverlay(immediate = true)"))
        assertTrue(destroyView.contains("hideBigWinBanner(immediate = true)"))
        assertTrue(hideBanner.contains("bigWinBannerAnimator?.cancel()"))
        assertTrue(hideBanner.contains("bigWinBannerAnimator = null"))
        assertTrue(hideBanner.contains("banner.animate().cancel()"))
    }
}
