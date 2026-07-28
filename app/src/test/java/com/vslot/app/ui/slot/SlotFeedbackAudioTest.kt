package com.vslot.app.ui.slot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotFeedbackAudioTest {
    @Test
    fun `slot feedback wav assets are valid mono pcm with intentional durations`() {
        val expectedDurations = mapOf(
            "slot_spin_start.wav" to 0.58,
            "slot_reel_spin_loop.wav" to 0.75,
            "slot_reel_stop.wav" to 0.16,
            "slot_payout.wav" to 0.38,
            "slot_win.wav" to 0.96,
            "slot_bonus.wav" to 1.38
        )

        expectedDurations.forEach { (fileName, expectedDuration) ->
            val bytes = Files.readAllBytes(Path.of("src/main/res/raw/$fileName"))
            assertTrue("$fileName is unexpectedly small", bytes.size > 10_000)
            assertEquals("RIFF", bytes.ascii(offset = 0, length = 4))
            assertEquals("WAVE", bytes.ascii(offset = 8, length = 4))
            assertEquals("fmt ", bytes.ascii(offset = 12, length = 4))
            assertEquals("data", bytes.ascii(offset = 36, length = 4))

            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("$fileName must use PCM", 1, header.getShort(20).toInt())
            assertEquals("$fileName must be mono", 1, header.getShort(22).toInt())
            assertEquals(44_100, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            val byteRate = header.getInt(28)
            val dataSize = header.getInt(40)
            val duration = dataSize.toDouble() / byteRate.toDouble()
            assertTrue(
                "$fileName duration $duration differs from $expectedDuration",
                kotlin.math.abs(duration - expectedDuration) < 0.015
            )
        }
    }

    @Test
    fun `audio assets have a reproducible generator`() {
        val generator = String(
            Files.readAllBytes(Path.of("../tools/generate_slot_feedback_audio.py")),
            Charsets.UTF_8
        )

        assertTrue(generator.contains("random.Random(7)"))
        assertTrue(generator.contains("random.Random(11)"))
        assertTrue(generator.contains("slot_spin_start.wav"))
        assertTrue(generator.contains("slot_reel_spin_loop.wav"))
        assertTrue(generator.contains("slot_reel_stop.wav"))
        assertTrue(generator.contains("slot_payout.wav"))
        assertTrue(generator.contains("slot_win.wav"))
        assertTrue(generator.contains("slot_bonus.wav"))
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }
}
