package com.vslot.app.ui.slot

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotSoundPlayerTest {
    @Test
    fun `silent device policy blocks playback without requesting focus`() {
        val session = FakeSlotAudioSession(playbackAllowed = false)
        val controller = SlotAudioFocusController(session)
        var stopCalls = 0

        val prepared = controller.prepareForPlayback { stopCalls += 1 }

        assertFalse(prepared)
        assertEquals(1, stopCalls)
        assertEquals(0, session.focusRequestCalls)
        assertEquals(0, session.focusAbandonCalls)
    }

    @Test
    fun `active focus is reused and abandoned once when playback becomes idle`() {
        val session = FakeSlotAudioSession()
        val controller = SlotAudioFocusController(session)

        assertTrue(controller.prepareForPlayback())
        assertTrue(controller.prepareForPlayback())
        assertEquals(1, session.focusRequestCalls)

        controller.onPlaybackBecameIdle()
        controller.onPlaybackBecameIdle()

        assertEquals(1, session.focusAbandonCalls)
    }

    @Test
    fun `denied focus does not become active and can be retried`() {
        val session = FakeSlotAudioSession(focusGranted = false)
        val controller = SlotAudioFocusController(session)

        assertFalse(controller.prepareForPlayback())
        session.focusGranted = true
        assertTrue(controller.prepareForPlayback())

        assertEquals(2, session.focusRequestCalls)
        controller.release()
        assertEquals(1, session.focusAbandonCalls)
    }

    @Test
    fun `policy change after focus acquisition stops playback and abandons focus`() {
        val session = FakeSlotAudioSession()
        val controller = SlotAudioFocusController(session)
        var stopCalls = 0

        assertTrue(controller.prepareForPlayback())
        session.playbackAllowed = false

        assertFalse(controller.prepareForPlayback { stopCalls += 1 })
        assertEquals(1, stopCalls)
        assertEquals(1, session.focusAbandonCalls)
    }

    @Test
    fun `focus loss stops playback and abandons the active request`() {
        val session = FakeSlotAudioSession()
        val controller = SlotAudioFocusController(session)
        var stopCalls = 0

        assertTrue(controller.prepareForPlayback())
        controller.onFocusLost { stopCalls += 1 }

        assertEquals(1, stopCalls)
        assertEquals(1, session.focusAbandonCalls)
    }

    @Test
    fun `cue completion timing follows deterministic asset duration and playback rate`() {
        assertEquals(660L, SlotSoundTiming.playbackDurationMs(SlotSoundCue.SpinStart, 1f))
        assertEquals(1_460L, SlotSoundTiming.playbackDurationMs(SlotSoundCue.Bonus, 1f))
        assertTrue(
            SlotSoundTiming.playbackDurationMs(SlotSoundCue.ReelStop, 1.04f) <
                SlotSoundTiming.playbackDurationMs(SlotSoundCue.ReelStop, 0.94f)
        )
    }

    @Test
    fun `android audio interruption contract covers ringer focus loss and cleanup`() {
        val source = Path.of(
            "src/main/java/com/vslot/app/ui/slot/SlotSoundPlayer.kt"
        ).readText()

        assertTrue(source.contains("AudioManager.RINGER_MODE_NORMAL"))
        assertTrue(source.contains("AudioManager.RINGER_MODE_CHANGED_ACTION"))
        assertTrue(source.indexOf("audioSession.start()") > source.indexOf("soundIds.putAll("))
        assertTrue(source.contains("AudioFocusRequest.Builder("))
        assertTrue(source.contains("AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"))
        assertTrue(source.contains("audioManager.requestAudioFocus(audioFocusRequest)"))
        assertTrue(source.contains("audioManager.abandonAudioFocusRequest(audioFocusRequest)"))
        assertTrue(source.contains("AudioManager.AUDIOFOCUS_LOSS,"))
        assertTrue(source.contains("AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,"))
        assertTrue(source.contains("AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"))
        assertTrue(source.contains("audioFocusController.onFocusLost(::stopAll)"))
        assertTrue(source.contains("trackTransientStream("))
        assertTrue(source.contains("audioSession.release()"))
    }

    private class FakeSlotAudioSession(
        var playbackAllowed: Boolean = true,
        var focusGranted: Boolean = true
    ) : SlotAudioSession {
        var focusRequestCalls = 0
        var focusAbandonCalls = 0

        override fun start() = Unit

        override fun isPlaybackAllowed(): Boolean = playbackAllowed

        override fun requestAudioFocus(): Boolean {
            focusRequestCalls += 1
            return focusGranted
        }

        override fun abandonAudioFocus() {
            focusAbandonCalls += 1
        }

        override fun release() = Unit
    }
}
