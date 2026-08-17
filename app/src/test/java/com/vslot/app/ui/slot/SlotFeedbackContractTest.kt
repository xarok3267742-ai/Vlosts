package com.vslot.app.ui.slot

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotFeedbackContractTest {
    @Test
    fun `slot feedback follows persisted settings and releases audio resources`() {
        val playerState = source("src/main/java/com/vslot/app/data/PlayerState.kt")
        val playerRepository = source("src/main/java/com/vslot/app/data/PlayerRepository.kt")
        val settingsViewModel = source("src/main/java/com/vslot/app/ui/settings/SettingsViewModel.kt")
        val settingsFragment = source("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt")
        val slotFragment = source("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val soundPlayer = source("src/main/java/com/vslot/app/ui/slot/SlotSoundPlayer.kt")
        val portraitSettings = source("src/main/res/layout/fragment_settings.xml")
        val landscapeSettings = source("src/main/res/layout-land/fragment_settings.xml")

        assertTrue(playerState.contains("soundEnabled: Boolean = true"))
        assertTrue(playerState.contains("hapticsEnabled: Boolean = true"))
        assertTrue(playerRepository.contains("Keys.SoundEnabled") && playerRepository.contains("Keys.HapticsEnabled"))
        assertTrue(settingsViewModel.contains("updateSoundEnabled(enabled)"))
        assertTrue(settingsViewModel.contains("updateHapticsEnabled(enabled)"))
        assertTrue(settingsFragment.contains("renderFeedbackState(state)"))
        assertTrue(portraitSettings.contains("@+id/soundToggleButton") && portraitSettings.contains("@+id/hapticsToggleButton"))
        assertTrue(landscapeSettings.contains("@+id/soundToggleButton") && landscapeSettings.contains("@+id/hapticsToggleButton"))

        assertTrue(soundPlayer.contains("AudioAttributes.USAGE_GAME"))
        assertTrue(soundPlayer.contains("SoundPool.Builder()"))
        assertTrue(soundPlayer.contains("SlotSoundMix.stereoVolumes"))
        assertTrue(soundPlayer.contains("fadeOutLoop(streamId)") && soundPlayer.contains("soundPool.setVolume"))
        assertTrue(slotFragment.contains("SlotSoundCue.SpinStart"))
        assertTrue(slotFragment.contains("startReelSpinLoop()"))
        assertTrue(slotFragment.contains("stopReelSpinLoop()"))
        assertTrue(slotFragment.contains("SlotSoundCue.ReelStop"))
        assertFalse(slotFragment.contains("SlotSoundCue.Payout"))
        assertTrue(slotFragment.contains("SlotSoundCue.Win"))
        assertTrue(slotFragment.contains("SlotSoundCue.Bonus"))
        assertTrue(slotFragment.contains("slotSoundPlayer?.release()"))
        assertTrue(slotFragment.contains("if (hapticsEnabled)"))
        assertTrue(slotFragment.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(slotFragment.contains("AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED"))
        assertTrue(slotFragment.contains("binding.reelsGrid.sendAccessibilityEvent"))
    }

    @Test
    fun `reel stop mix follows the physical left to right reel position`() {
        val volume = 0.42f
        val leftReel = SlotSoundMix.stereoVolumes(SlotSoundCue.ReelStop, reelIndex = 0, volume)
        val centerReel = SlotSoundMix.stereoVolumes(SlotSoundCue.ReelStop, reelIndex = 2, volume)
        val rightReel = SlotSoundMix.stereoVolumes(SlotSoundCue.ReelStop, reelIndex = 4, volume)

        assertTrue(leftReel.first > leftReel.second)
        assertEquals(centerReel.first, centerReel.second, 0.0001f)
        assertTrue(rightReel.first < rightReel.second)
        assertEquals(leftReel.first, rightReel.second, 0.0001f)
        assertEquals(leftReel.second, rightReel.first, 0.0001f)

        val win = SlotSoundMix.stereoVolumes(SlotSoundCue.Win, reelIndex = 4, volume)
        assertEquals(volume, win.first, 0.0001f)
        assertEquals(volume, win.second, 0.0001f)
    }

    private fun source(relativePath: String): String {
        return String(Files.readAllBytes(Path.of(relativePath)), Charsets.UTF_8)
    }
}
