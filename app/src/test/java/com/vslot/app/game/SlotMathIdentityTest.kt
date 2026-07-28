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
                "e27f4ab51821de2a7098c23c947968638ad8d3aba29df461733edb60b65806d3",
            "roman_reels" to
                "a25c1e8b691aef3df8c01c338518692636e15780d55f398ecea8b4af2af0c29a",
            "neon_nights" to
                "b6e13b266d8f388e55ffbfd8e8b5d4c8c3af7bb71697eef9cb3d89525a1762de",
            "pharaoh_gold" to
                "19af9ae48b0c39b813bab1abede66390d08a05c204be67535606433c4cab87d1",
            "ocean_pearl" to
                "3ea2c0e96dd4a05798dce9ed79e8099bc34ade64113d099bbe64ff363f8b03ee"
        )
    }
}
