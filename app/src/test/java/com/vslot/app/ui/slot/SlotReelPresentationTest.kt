package com.vslot.app.ui.slot

import com.vslot.app.game.SlotConfigParser
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotReelPresentationTest {
    @Test
    fun `every initial reel is a physically possible configured strip window`() {
        val configs = SlotConfigParser().parse(
            Path.of("src/main/assets/slots_config.json").readText()
        )

        configs.forEach { config ->
            val initialReels = initialSlotReels(config)

            assertEquals(config.reels, initialReels.size)
            initialReels.forEachIndexed { reelIndex, symbols ->
                assertEquals(config.rows, symbols.size)
                val strip = config.reelStrips[reelIndex]
                assertTrue(
                    "${config.id} reel $reelIndex must show a contiguous cyclic strip window",
                    strip.indices.any { stopIndex ->
                        List(config.rows) { rowIndex ->
                            strip[(stopIndex + rowIndex) % strip.size]
                        } == symbols
                    }
                )
            }
        }
    }
}
