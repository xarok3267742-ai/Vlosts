package com.vslot.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotConfigParserTest {
    @Test
    fun `parses bundled slot config shape`() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }

        val slots = SlotConfigParser().parse(json)

        assertEquals(5, slots.size)
        assertEquals(
            listOf("violet_fortune", "roman_reels", "neon_nights", "pharaoh_gold", "ocean_pearl"),
            slots.map { it.id }
        )
        assertEquals(listOf(10, 25, 50, 100, 250), slots[0].bets)
        assertTrue(slots.all { it.reels == 5 && it.rows == 3 && it.paylines == 10 })
        slots.forEach { slot ->
            assertEquals("${slot.id} must define one strip per reel", slot.reels, slot.reelStrips.size)
            assertEquals(
                "${slot.id} must define one free-spin strip per reel",
                slot.reels,
                slot.freeSpinReelStrips.size
            )
            assertTrue("${slot.id} free-spin strips must have a distinct order", slot.reelStrips != slot.freeSpinReelStrips)
            slot.reelStrips.forEach { strip ->
                assertTrue("${slot.id} reel strip must support visible rows", strip.size >= slot.rows)
                assertTrue("${slot.id} reel strip contains unknown symbols", strip.all { it in slot.symbols })
                assertEquals("${slot.id} scatter must stay rare on each strip", 1, strip.count { it == slot.scatter })
            }
            slot.reelStrips.zip(slot.freeSpinReelStrips).forEach { (paid, free) ->
                assertEquals(paid.groupingBy { it }.eachCount(), free.groupingBy { it }.eachCount())
            }
        }
    }

    @Test
    fun `bundled themes use distinct reel rhythms without adjacent duplicate symbols`() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }
        val slots = SlotConfigParser().parse(json)

        val signatures = slots.map { slot ->
            val symbolIndexes = slot.symbols.withIndex().associate { (index, symbol) -> symbol to index }
            slot.reelStrips.map { strip -> strip.map(symbolIndexes::getValue) }
        }

        assertEquals("Every theme must have its own physical reel rhythm", slots.size, signatures.distinct().size)
        slots.forEach { slot ->
            slot.reelStrips.forEachIndexed { reelIndex, strip ->
                assertTrue(
                    "${slot.id} reel $reelIndex contains an adjacent duplicate symbol",
                    strip.indices.none { index -> strip[index] == strip[(index + 1) % strip.size] }
                )
            }
        }
    }

    @Test
    fun `all line paytable symbols have visible three four and five symbol payouts`() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }

        val slots = SlotConfigParser().parse(json)

        slots.forEach { slot ->
            slot.symbols.filterNot { it == slot.scatter }.forEach { symbol ->
                val payouts = slot.payouts[symbol].orEmpty()
                assertTrue("${slot.id} $symbol missing 3x payout", payouts.containsKey(3))
                assertTrue("${slot.id} $symbol missing 4x payout", payouts.containsKey(4))
                assertTrue("${slot.id} $symbol missing 5x payout", payouts.containsKey(5))
            }
            assertTrue("${slot.id} scatter should use bonus payouts instead of line payouts", slot.payouts[slot.scatter].isNullOrEmpty())
            assertTrue("${slot.id} missing 3 scatter bonus", slot.scatterBonus.containsKey(3))
            assertTrue("${slot.id} missing 4 scatter bonus", slot.scatterBonus.containsKey(4))
            assertTrue("${slot.id} missing 5 scatter bonus", slot.scatterBonus.containsKey(5))
        }
    }

    @Test
    fun `rejects unknown slot theme instead of silently falling back`() {
        val result = runCatching {
            SlotConfigParser().parse(validConfigJson(theme = "unknown_theme"))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Unsupported slot theme"))
    }

    @Test
    fun `rejects reel strips without one scatter per reel`() {
        val result = runCatching {
            SlotConfigParser().parse(validConfigJson(scatterOnFirstReel = false))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("exactly one scatter"))
    }

    @Test
    fun `rejects payable symbols missing from a reel strip`() {
        val json = validConfigJson().replaceFirst(
            "[\"a\",\"scatter\",\"b\",\"wild\"]",
            "[\"a\",\"scatter\",\"b\",\"a\"]"
        )

        val result = runCatching { SlotConfigParser().parse(json) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("every configured symbol"))
    }

    @Test
    fun `rejects line payouts that do not increase with match length`() {
        val result = runCatching {
            SlotConfigParser().parse(validConfigJson(wildPayouts = """"3": 20, "4": 10, "5": 30"""))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("payouts must increase"))
    }

    @Test
    fun `rejects scatter bonuses that do not increase with scatter count`() {
        val result = runCatching {
            SlotConfigParser().parse(validConfigJson(scatterBonus = """"3": 5, "4": 4, "5": 25"""))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("scatter bonuses must increase"))
    }

    @Test
    fun `rejects free spin strips that change reviewed symbol weights`() {
        val result = runCatching {
            SlotConfigParser().parse(
                validConfigJson().replaceFirst(
                    "[\"wild\",\"b\",\"a\",\"scatter\"]",
                    "[\"wild\",\"b\",\"b\",\"scatter\"]"
                )
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("must contain every configured symbol"))
    }

    @Test
    fun `rejects total bet that can overflow gameplay arithmetic`() {
        val result = runCatching {
            SlotConfigParser().parse(validConfigJson(bets = "10, 2147483647"))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("maximum total bet"))
    }

    @Test
    fun `rejects line payout that can overflow gameplay arithmetic`() {
        val result = runCatching {
            SlotConfigParser().parse(
                validConfigJson(
                    wildPayouts = """"3": 2147483640, "4": 2147483641, "5": 2147483642"""
                )
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("maximum line payout"))
    }

    private fun validConfigJson(
        theme: String = "violet",
        scatterOnFirstReel: Boolean = true,
        bets: String = "10, 25",
        wildPayouts: String = """"3": 5, "4": 10, "5": 20""",
        scatterBonus: String = """"3": 5, "4": 10, "5": 25"""
    ): String {
        val firstReel = if (scatterOnFirstReel) {
            """["a","scatter","b","wild"]"""
        } else {
            """["a","wild","b","a"]"""
        }
        return """
            {
              "slots": [
                {
                  "id": "test_slot",
                  "name": "Test Slot",
                  "theme": "$theme",
                  "reels": 5,
                  "rows": 3,
                  "paylines": 10,
                  "wild": "wild",
                  "scatter": "scatter",
                  "symbols": ["wild", "scatter", "a", "b"],
                  "bets": [$bets],
                  "payouts": {
                    "wild": {$wildPayouts},
                    "a": {"3": 4, "4": 8, "5": 16},
                    "b": {"3": 2, "4": 4, "5": 8}
                  },
                  "scatterBonus": {$scatterBonus},
                  "reelStrips": [
                    $firstReel,
                    ["a","scatter","b","wild"],
                    ["a","scatter","b","wild"],
                    ["a","scatter","b","wild"],
                    ["a","scatter","b","wild"]
                  ],
                  "freeSpinReelStrips": [
                    ["wild","b","a","scatter"],
                    ["wild","b","a","scatter"],
                    ["wild","b","a","scatter"],
                    ["wild","b","a","scatter"],
                    ["wild","b","a","scatter"]
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
