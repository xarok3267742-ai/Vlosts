package com.vslot.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SlotMathIdentityTest {
    @Test
    fun `bundled configs have stable golden fingerprints`() {
        val actual = bundledConfigs().associate { config ->
            config.id to SlotMathIdentity.fingerprint(config)
        }

        assertEquals(GOLDEN_FINGERPRINTS, actual)
    }

    @Test
    fun `fingerprint is deterministic for unordered payout maps`() {
        val config = bundledConfigs().first()
        val reordered = config.copy(
            payouts = config.payouts.entries.reversed().associate { (symbol, payouts) ->
                symbol to payouts.entries.reversed().associate { it.toPair() }
            },
            scatterBonus = config.scatterBonus.entries.reversed().associate { it.toPair() }
        )

        assertEquals(SlotMathIdentity.fingerprint(config), SlotMathIdentity.fingerprint(reordered))
    }

    @Test
    fun `every math config field changes fingerprint while display metadata does not`() {
        val config = bundledConfigs().first()
        val original = SlotMathIdentity.fingerprint(config)
        val mutations = listOf(
            config.copy(id = "changed-id"),
            config.copy(reels = config.reels + 1),
            config.copy(rows = config.rows + 1),
            config.copy(paylines = config.paylines - 1),
            config.copy(wild = config.symbols.first { it != config.wild }),
            config.copy(scatter = config.symbols.first { it != config.scatter }),
            config.copy(symbols = config.symbols.reversed()),
            config.copy(bets = config.bets.reversed()),
            config.copy(
                payouts = config.payouts.toMutableMap().apply {
                    val symbol = keys.first()
                    this[symbol] = getValue(symbol).toMutableMap().apply {
                        val count = keys.first()
                        this[count] = getValue(count) + 1
                    }
                }
            ),
            config.copy(
                scatterBonus = config.scatterBonus.toMutableMap().apply {
                    val count = keys.first()
                    this[count] = getValue(count) + 1
                }
            ),
            config.copy(
                reelStrips = config.reelStrips.toMutableList().apply {
                    this[0] = this[0].drop(1) + this[0].first()
                }
            ),
            config.copy(
                freeSpinReelStrips = config.freeSpinReelStrips.toMutableList().apply {
                    this[0] = this[0].drop(1) + this[0].first()
                }
            )
        )

        mutations.forEach { changed ->
            assertNotEquals(changed.toString(), original, SlotMathIdentity.fingerprint(changed))
        }
        assertEquals(original, SlotMathIdentity.fingerprint(config.copy(name = "Display only")))
        assertEquals(original, SlotMathIdentity.fingerprint(config.copy(theme = SlotTheme.Ocean)))
    }

    private fun bundledConfigs(): List<SlotConfig> {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("slots_config.json")) {
            "slots_config.json test resource missing"
        }.bufferedReader().use { it.readText() }
        return SlotConfigParser().parse(json)
    }

    private companion object {
        val GOLDEN_FINGERPRINTS = mapOf(
            "violet_fortune" to
                "9c58810a2ac85df677f7c0028aace90c8e6ae5547c1e9beae36d7c50f580ec55",
            "roman_reels" to
                "b104067b0c2e7718ea0123f548bf96eefa6ce033291d85deeee331d57e23c914",
            "neon_nights" to
                "7ca72d5f5d199ed4078ad11516d133b9078b0b33c349ec95a7fbed1f2ebd258e",
            "pharaoh_gold" to
                "dcc3c0a787f4e6c0b3981ea4596bf4cb9c161c924e35decb5f9c77fa5677235f",
            "ocean_pearl" to
                "2d06622d2f9a7c166852e216f255d7e608accc408fbb13688224b7834e87eac8"
        )
    }
}
