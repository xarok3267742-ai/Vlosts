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
            "Background cleanup must cancel reel, infinite overlay, carousel and delayed dialog work",
            fragment.contains("slotSoundPlayer?.stopAll()") &&
                fragment.indexOf("wasSpinning = false", fragment.indexOf("private fun stopBackgroundFeedback()")) >
                fragment.indexOf("slotSoundPlayer?.stopAll()", fragment.indexOf("private fun stopBackgroundFeedback()")) &&
                fragment.contains("stopSpinPreview()") &&
                fragment.contains("stopSpinReadyGlow(immediate = true)") &&
                fragment.contains("stopCabinetLights()") &&
                fragment.contains("stopThemeAmbientOverlay()") &&
                fragment.contains("winningPaylineCarouselJob?.cancel()") &&
                fragment.contains("autoSpinResultDismissJob?.cancel()")
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
}
