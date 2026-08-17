package com.vslot.app.game

import com.vslot.app.data.PendingSpinSettlement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class ReleasedSlotMathV5Test {
    @Test
    fun `released asset has immutable bytes and remains the current V5 source`() {
        val releasedBytes = Path.of("src/main/assets", ReleasedSlotMathV5.ASSET_PATH).readBytes()
        val currentBytes = Path.of("src/main/assets/slots_config.json").readBytes()

        ReleasedSlotMathV5.verifyReleasedAsset(releasedBytes)
        if (SlotMathIdentity.VERSION == ReleasedSlotMathV5.VERSION) {
            assertArrayEquals(releasedBytes, currentBytes)
        }

        val tampered = releasedBytes.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleasedSlotMathV5.verifyReleasedAsset(tampered)
        }
    }

    @Test
    fun `released configs retain their V5 golden fingerprints`() {
        val actual = releasedConfigs().associate { config ->
            config.id to ReleasedSlotMathV5.fingerprint(config)
        }

        assertEquals(GOLDEN_FINGERPRINTS, actual)
    }

    @Test
    fun `released paylines award and wild tie break remain explicit`() {
        assertEquals(
            listOf(
                listOf(1, 1, 1, 1, 1),
                listOf(0, 0, 0, 0, 0),
                listOf(2, 2, 2, 2, 2),
                listOf(0, 1, 2, 1, 0),
                listOf(2, 1, 0, 1, 2),
                listOf(0, 0, 1, 2, 2),
                listOf(2, 2, 1, 0, 0),
                listOf(1, 0, 0, 0, 1),
                listOf(1, 2, 2, 2, 1),
                listOf(0, 1, 1, 1, 0)
            ),
            ReleasedSlotMathV5.paylineRows
        )
        assertEquals(5, ReleasedSlotMathV5.FREE_SPINS_BONUS_AWARD)

        val allWildStrips = List(5) { listOf("b", "wild", "a", "scatter") }
        val tiedConfig = fixtureConfig(allWildStrips).copy(
            payouts = linkedMapOf(
                "b" to mapOf(3 to 2, 4 to 4, 5 to 8),
                "a" to mapOf(3 to 2, 4 to 4, 5 to 8),
                "wild" to mapOf(3 to 2, 4 to 4, 5 to 8)
            )
        )
        val result = ReleasedSlotMathV5.evaluateStops(
            tiedConfig,
            stopIndexes = List(5) { 0 },
            bet = 10,
            lines = 1
        )

        assertEquals("wild", result.winningLines.single().symbol)
        assertEquals(80, result.winAmount)
    }

    @Test
    fun `released evaluator retains line scatter and free spin golden outcomes`() {
        val lineConfig = fixtureConfig(
            reelStrips = List(5) { listOf("b", "a", "b", "scatter", "wild") }
        )
        val lineWin = ReleasedSlotMathV5.evaluateStops(
            config = lineConfig,
            stopIndexes = List(5) { 0 },
            bet = 10,
            lines = 1,
            isFreeSpin = false
        )

        assertEquals(10, lineWin.totalBet)
        assertEquals(80, lineWin.winAmount)
        assertEquals(ResultType.Win, lineWin.resultType)
        assertEquals(1, lineWin.winningLines.size)
        assertEquals("a", lineWin.winningLines.single().symbol)
        assertEquals(5, lineWin.winningLines.single().count)
        assertEquals(0, lineWin.freeSpinsAwarded)

        val scatterStrips = listOf(
            listOf("scatter", "b", "a", "wild"),
            listOf("scatter", "a", "b", "wild"),
            listOf("scatter", "b", "a", "wild"),
            listOf("a", "a", "b", "scatter", "wild"),
            listOf("a", "b", "wild", "scatter")
        )
        val bonusConfig = fixtureConfig(reelStrips = scatterStrips)
        val bonus = ReleasedSlotMathV5.evaluateStops(
            config = bonusConfig,
            stopIndexes = List(5) { 0 },
            bet = 10,
            lines = 1,
            isFreeSpin = false
        )

        assertEquals(10, bonus.totalBet)
        assertEquals(50, bonus.winAmount)
        assertEquals(3, bonus.scatterCount)
        assertEquals(ResultType.Bonus, bonus.resultType)
        assertEquals(ReleasedSlotMathV5.FREE_SPINS_BONUS_AWARD, bonus.freeSpinsAwarded)

        val freeSpinConfig = lineConfig.copy(
            freeSpinReelStrips = List(5) { listOf("b", "b", "a", "scatter", "wild") }
        )
        val freeSpin = ReleasedSlotMathV5.evaluateStops(
            config = freeSpinConfig,
            stopIndexes = List(5) { 0 },
            bet = 10,
            lines = 1,
            isFreeSpin = true
        )
        assertEquals(40, freeSpin.winAmount)
        assertTrue(freeSpin.isFreeSpin)
    }

    @Test
    fun `released XP policy retains minimum regular and capped awards`() {
        assertEquals(9, ReleasedSlotMathV5.xpForSpin(totalBet = 0, isFreeSpin = false, winAmount = 0))
        assertEquals(5, ReleasedSlotMathV5.xpForSpin(totalBet = 0, isFreeSpin = true, winAmount = 0))
        assertEquals(14, ReleasedSlotMathV5.xpForSpin(totalBet = 250, isFreeSpin = false, winAmount = 400))
        assertEquals(10, ReleasedSlotMathV5.xpForSpin(totalBet = 250, isFreeSpin = true, winAmount = 400))
        assertEquals(68, ReleasedSlotMathV5.xpForSpin(Int.MAX_VALUE, false, Int.MAX_VALUE))
        assertEquals(64, ReleasedSlotMathV5.xpForSpin(Int.MAX_VALUE, true, Int.MAX_VALUE))
    }

    @Test
    fun `production V5 outcomes retain complete paid bonus and free spin digests`() {
        val configs = releasedConfigs().associateBy(SlotConfig::id)
        val actualDigests = PRODUCTION_FIXTURES.associate { fixture ->
            val config = checkNotNull(configs[fixture.slotId])
            val result = ReleasedSlotMathV5.evaluateStops(
                config = config,
                stopIndexes = fixture.stops,
                bet = 25,
                lines = 10,
                isFreeSpin = fixture.isFreeSpin
            )
            val xp = ReleasedSlotMathV5.xpForSpin(
                totalBet = result.totalBet,
                isFreeSpin = fixture.isFreeSpin,
                winAmount = result.winAmount
            )

            assertEquals(fixture.key, 250, result.totalBet)
            assertEquals(fixture.key, fixture.expectedWin, result.winAmount)
            assertEquals(fixture.key, fixture.expectedXp, xp)
            assertEquals(fixture.key, fixture.stops, result.stopIndexes)
            assertEquals(fixture.key, fixture.isFreeSpin, result.isFreeSpin)
            if (fixture.isScatterBonus) {
                assertEquals(fixture.key, 3, result.scatterCount)
                assertEquals(fixture.key, ResultType.Bonus, result.resultType)
                assertEquals(
                    fixture.key,
                    ReleasedSlotMathV5.FREE_SPINS_BONUS_AWARD,
                    result.freeSpinsAwarded
                )
            }
            fixture.key to completeResultDigest(result, xp)
        }

        assertEquals(PRODUCTION_OUTCOME_DIGESTS, actualDigests)
    }

    @Test
    fun `V5 journal survives a changed current catalog through released registry`() {
        val releasedConfig = releasedConfigs().first()
        val releasedCatalog = catalogOf(releasedConfig)
        val registryVerifier = SpinSettlementVerifier(
            ReleasedSlotMathRegistry.withV5Catalog(releasedCatalog)
        )
        val stops = listOf(0, 1, 2, 3, 4)
        val result = ReleasedSlotMathV5.evaluateStops(
            releasedConfig,
            stops,
            bet = releasedConfig.bets.first(),
            lines = releasedConfig.paylines
        )
        val settlement = PendingSpinSettlement(
            id = "upgrade-v5-spin",
            processSessionId = "old-process",
            slotId = releasedConfig.id,
            isFreeSpin = false,
            lineBet = result.bet,
            lines = result.lines,
            totalBet = result.totalBet,
            winAmount = result.winAmount,
            freeSpinsAwarded = result.freeSpinsAwarded,
            levelXpAwarded = ReleasedSlotMathV5.xpForSpin(
                result.totalBet,
                isFreeSpin = false,
                result.winAmount
            ),
            mathVersion = ReleasedSlotMathV5.VERSION,
            configFingerprint = ReleasedSlotMathV5.fingerprint(releasedConfig),
            stopIndexes = stops
        )
        val firstSymbol = releasedConfig.payouts.keys.first()
        val changedCurrentConfig = releasedConfig.copy(
            payouts = releasedConfig.payouts.toMutableMap().apply {
                this[firstSymbol] = getValue(firstSymbol).toMutableMap().apply {
                    this[3] = getValue(3) + 1
                }
            }
        )
        val currentCatalogVerifier = SpinSettlementVerifier(
            catalogOf(changedCurrentConfig),
            SlotEngine()
        )
        val v5Release = checkNotNull(
            ReleasedSlotMathRegistry.withV5Catalog(releasedCatalog)
                .release(ReleasedSlotMathV5.VERSION)
        )
        val v6Release = futureRelease(6, changedCurrentConfig)
        val v7Release = futureRelease(7, changedCurrentConfig)
        val v6Verifier = SpinSettlementVerifier(
            ReleasedSlotMathRegistry.withReleases(listOf(v5Release, v6Release), currentVersion = 6)
        )
        val v7Verifier = SpinSettlementVerifier(
            ReleasedSlotMathRegistry.withReleases(
                listOf(v5Release, v6Release, v7Release),
                currentVersion = 7
            )
        )

        assertNotEquals(
            settlement.configFingerprint,
            SlotMathIdentity.fingerprint(changedCurrentConfig)
        )
        assertNotNull(registryVerifier.verify(settlement))
        assertNotNull(v6Verifier.verify(settlement))
        assertNotNull(v7Verifier.verify(settlement))
        assertNull(currentCatalogVerifier.verify(settlement))
        assertNull(ReleasedSlotMathRegistry.withV5Catalog(releasedCatalog).release(6))
    }

    @Test
    fun `released implementation has no dependency on mutable runtime math classes`() {
        val source = Path.of("src/main/java/com/vslot/app/game/ReleasedSlotMathV5.kt").readText()

        assertTrue(!source.contains("SlotEngine"))
        assertTrue(!source.contains("SlotRules"))
        assertTrue(!source.contains("PlayerState"))
        assertTrue(!source.contains("SlotConfigParser"))
    }

    private fun releasedConfigs(): List<SlotConfig> {
        val bytes = Path.of("src/main/assets", ReleasedSlotMathV5.ASSET_PATH).readBytes()
        ReleasedSlotMathV5.verifyReleasedAsset(bytes)
        return ReleasedSlotMathV5ConfigParser().parse(bytes.toString(Charsets.UTF_8))
    }

    private fun catalogOf(config: SlotConfig): SlotCatalog = object : SlotCatalog {
        override fun getSlot(slotId: String): SlotConfig = config
        override fun getSlotExact(slotId: String): SlotConfig? = config.takeIf { it.id == slotId }
    }

    private fun futureRelease(version: Int, config: SlotConfig): ReleasedSlotMathRelease =
        ReleasedSlotMathRelease(
            version = version,
            slotCatalog = catalogOf(config),
            configs = listOf(config),
            fingerprintProvider = { error("Future fingerprint must not inspect a V5 journal.") },
            inputValidator = { _, _, _, _, _ ->
                error("Future input policy must not inspect a V5 journal.")
            },
            stopEvaluator = { _, _, _, _, _ ->
                error("Future evaluator must not settle a V5 journal.")
            },
            xpPolicy = { _, _, _ -> error("Future XP policy must not settle a V5 journal.") }
        )

    private fun fixtureConfig(reelStrips: List<List<String>>): SlotConfig = SlotConfig(
        id = "released-v5-fixture",
        name = "Fixture",
        theme = SlotTheme.Violet,
        reels = 5,
        rows = 3,
        paylines = 10,
        wild = "wild",
        scatter = "scatter",
        symbols = listOf("wild", "scatter", "a", "b"),
        bets = listOf(10),
        payouts = mapOf(
            "wild" to mapOf(3 to 5, 4 to 10, 5 to 20),
            "a" to mapOf(3 to 2, 4 to 4, 5 to 8),
            "b" to mapOf(3 to 1, 4 to 2, 5 to 4)
        ),
        scatterBonus = mapOf(3 to 5, 4 to 10, 5 to 25),
        reelStrips = reelStrips,
        freeSpinReelStrips = reelStrips
    )

    private fun completeResultDigest(result: SpinResult, xp: Int): String {
        val payload = CanonicalPayloadWriter().apply {
            writeInt(result.reels.size)
            result.reels.forEach(::writeStrings)
            writeInt(result.bet)
            writeInt(result.lines)
            writeInt(result.totalBet)
            writeInt(result.winAmount)
            writeString(result.resultType.name)
            writeInt(result.winningLines.size)
            result.winningLines.forEach { line ->
                writeInt(line.paylineIndex)
                writeString(line.symbol)
                writeInt(line.count)
                writeInt(line.amount)
                writeInt(line.positions.size)
                line.positions.forEach { position ->
                    writeInt(position.reel)
                    writeInt(position.row)
                }
            }
            writeInt(result.scatterCount)
            writeInt(result.scatterPositions.size)
            result.scatterPositions.forEach { position ->
                writeInt(position.reel)
                writeInt(position.row)
            }
            writeInt(result.freeSpinsAwarded)
            writeInts(result.stopIndexes)
            writeBoolean(result.isFreeSpin)
            writeInt(xp)
        }.toByteArray()
        return sha256Hex(payload)
    }

    private companion object {
        data class ProductionFixture(
            val slotId: String,
            val mode: String,
            val stops: List<Int>,
            val expectedWin: Int,
            val expectedXp: Int,
            val isFreeSpin: Boolean = false,
            val isScatterBonus: Boolean = false
        ) {
            val key: String
                get() = "$slotId:$mode"
        }

        val PRODUCTION_FIXTURES = listOf(
            ProductionFixture("violet_fortune", "paid-five-wild", listOf(5, 7, 11, 13, 22), 6_775, 33),
            ProductionFixture("violet_fortune", "paid-scatter-three", listOf(20, 21, 19, 0, 0), 1_250, 18, isScatterBonus = true),
            ProductionFixture("violet_fortune", "free-extra-wild", listOf(23, 3, 11, 0, 1), 3_075, 21, isFreeSpin = true),
            ProductionFixture("roman_reels", "paid-five-wild", listOf(0, 1, 15, 3, 12), 7_200, 33),
            ProductionFixture("roman_reels", "paid-scatter-three", listOf(8, 14, 22, 0, 0), 1_250, 18, isScatterBonus = true),
            ProductionFixture("roman_reels", "free-extra-wild", listOf(5, 5, 15, 1, 23), 4_125, 25, isFreeSpin = true),
            ProductionFixture("neon_nights", "paid-five-wild", listOf(10, 1, 23, 3, 19), 6_075, 33),
            ProductionFixture("neon_nights", "paid-scatter-three", listOf(21, 19, 10, 0, 3), 1_250, 18, isScatterBonus = true),
            ProductionFixture("neon_nights", "free-extra-wild", listOf(23, 1, 15, 9, 5), 2_450, 18, isFreeSpin = true),
            ProductionFixture("pharaoh_gold", "paid-five-wild", listOf(14, 3, 16, 10, 20), 26_775, 33),
            ProductionFixture("pharaoh_gold", "paid-scatter-three", listOf(22, 9, 4, 0, 0), 1_250, 18, isScatterBonus = true),
            ProductionFixture("pharaoh_gold", "free-extra-wild", listOf(7, 9, 17, 15, 7), 4_675, 27, isFreeSpin = true),
            ProductionFixture("ocean_pearl", "paid-five-wild", listOf(5, 17, 18, 5, 9), 12_825, 33),
            ProductionFixture("ocean_pearl", "paid-scatter-three", listOf(11, 13, 7, 1, 0), 1_250, 18, isScatterBonus = true),
            ProductionFixture("ocean_pearl", "free-extra-wild", listOf(8, 23, 19, 4, 0), 3_000, 21, isFreeSpin = true)
        )

        val PRODUCTION_OUTCOME_DIGESTS = mapOf(
            "violet_fortune:paid-five-wild" to "aade10759a721c595625423db918f5c5ffecee2e119982134a260bf80ad78569",
            "violet_fortune:paid-scatter-three" to "8c3a7e4e62833dff4528ce0e2dc1ebd1945f9f95994a4d21aa4cd849891c06ee",
            "violet_fortune:free-extra-wild" to "82b718f5824477f1793d72ee4c14d1fffc09d14de31fe4de0dcf0d630e95f4f4",
            "roman_reels:paid-five-wild" to "62bbf3306bd23c62902fee212e6507b22ca87dc54d3a50c93ed5ed6e8352c2f7",
            "roman_reels:paid-scatter-three" to "5e5de7114f82ca6879659da998995415e97ebd57fa3be6e54e30293ec6520eb0",
            "roman_reels:free-extra-wild" to "cc79855f9f8da8ce8349e8abf2eced1b5ee68edba16e5997c0e121bdbea83de3",
            "neon_nights:paid-five-wild" to "2c0c587aab53ad70ebb66c20664325558d1052b4f8ebf3b17187c8796da2acd2",
            "neon_nights:paid-scatter-three" to "22d64692a3f879229ebb826cbc6d5b0a106cb8cd0c6f02ac3e4441ea7891956c",
            "neon_nights:free-extra-wild" to "7952064bb12ac81d9f142f273162b11da4d5165ab768d2a47fade5ed104b3411",
            "pharaoh_gold:paid-five-wild" to "5417940cdc926978ed84ad6ac35675bba5baf5f9569481e58d634b2b33ecb71d",
            "pharaoh_gold:paid-scatter-three" to "5c74dfa4e1c02216c5fa8f53e500f51805ab2c27f489cbf1f65d770898935df9",
            "pharaoh_gold:free-extra-wild" to "a30b4c9305256d09987b221e0fa1580ec1bb993bd387d44873cabbe4a1ff350a",
            "ocean_pearl:paid-five-wild" to "f073f849a420455048f0323270a096373f57e2ced47f25f09cbe21c86bcfc54c",
            "ocean_pearl:paid-scatter-three" to "0bc2d3cb6e19942fb91c73a15257bd5c026897cbd70b31c2a4261a95cd1ebb9f",
            "ocean_pearl:free-extra-wild" to "d2459f9115408e1c3266d0aa550bd3539906ac6d0a11a8dd8c9bdf7758a776d2"
        )

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
