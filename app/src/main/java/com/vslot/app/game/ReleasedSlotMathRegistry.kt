package com.vslot.app.game

import android.content.Context
import java.nio.charset.StandardCharsets

internal class ReleasedSlotMathRegistry private constructor(
    private val releases: Map<Int, ReleasedSlotMathRelease>,
    private val currentVersion: Int
) {
    fun release(version: Int): ReleasedSlotMathRelease? = releases[version]

    fun currentConfigs(): List<SlotConfig> =
        checkNotNull(release(currentVersion)).allConfigs()

    companion object {
        fun withReleases(
            releases: List<ReleasedSlotMathRelease>,
            currentVersion: Int
        ): ReleasedSlotMathRegistry {
            require(releases.map(ReleasedSlotMathRelease::version).distinct().size == releases.size) {
                "Released slot math versions must be unique."
            }
            require(releases.any { it.version == currentVersion }) {
                "Current slot math version must be registered."
            }
            return ReleasedSlotMathRegistry(
                releases = releases.associateBy(ReleasedSlotMathRelease::version),
                currentVersion = currentVersion
            )
        }

        fun fromAssets(context: Context): ReleasedSlotMathRegistry {
            val bytes = context.assets.open(ReleasedSlotMathV5.ASSET_PATH).use { it.readBytes() }
            ReleasedSlotMathV5.verifyReleasedAsset(bytes)
            val configs = ReleasedSlotMathV5ConfigParser().parse(
                bytes.toString(StandardCharsets.UTF_8)
            )
            return withV5Configs(configs)
        }

        private fun withV5Configs(configs: List<SlotConfig>): ReleasedSlotMathRegistry =
            createV5Registry(FixedSlotCatalog(configs), configs)

        fun withV5Catalog(
            slotCatalog: SlotCatalog,
            evaluateStops: (
                config: SlotConfig,
                stopIndexes: List<Int>,
                bet: Int,
                lines: Int,
                isFreeSpin: Boolean
            ) -> SpinResult = ReleasedSlotMathV5::evaluateStops
        ): ReleasedSlotMathRegistry = createV5Registry(slotCatalog, null, evaluateStops)

        private fun createV5Registry(
            slotCatalog: SlotCatalog,
            configs: List<SlotConfig>?,
            evaluateStops: (
                config: SlotConfig,
                stopIndexes: List<Int>,
                bet: Int,
                lines: Int,
                isFreeSpin: Boolean
            ) -> SpinResult = ReleasedSlotMathV5::evaluateStops
        ): ReleasedSlotMathRegistry {
            val release = ReleasedSlotMathRelease(
                version = ReleasedSlotMathV5.VERSION,
                slotCatalog = slotCatalog,
                configs = configs,
                fingerprintProvider = ReleasedSlotMathV5::fingerprint,
                inputValidator = ReleasedSlotMathV5::supportsInput,
                stopEvaluator = evaluateStops,
                xpPolicy = ReleasedSlotMathV5::xpForSpin
            )
            return ReleasedSlotMathRegistry(
                releases = mapOf(release.version to release),
                currentVersion = release.version
            )
        }
    }
}

internal class ReleasedSlotMathRelease(
    val version: Int,
    private val slotCatalog: SlotCatalog,
    private val configs: List<SlotConfig>?,
    private val fingerprintProvider: (SlotConfig) -> String,
    private val inputValidator: (
        config: SlotConfig,
        stopIndexes: List<Int>,
        bet: Int,
        lines: Int,
        isFreeSpin: Boolean
    ) -> Boolean,
    private val stopEvaluator: (
        config: SlotConfig,
        stopIndexes: List<Int>,
        bet: Int,
        lines: Int,
        isFreeSpin: Boolean
    ) -> SpinResult,
    private val xpPolicy: (totalBet: Int, isFreeSpin: Boolean, winAmount: Int) -> Int
) {
    fun allConfigs(): List<SlotConfig> =
        checkNotNull(configs) { "This released math registry does not expose a current catalog." }

    fun getSlotExact(slotId: String): SlotConfig? = slotCatalog.getSlotExact(slotId)

    fun fingerprint(config: SlotConfig): String = fingerprintProvider(config)

    fun supportsInput(
        config: SlotConfig,
        stopIndexes: List<Int>,
        bet: Int,
        lines: Int,
        isFreeSpin: Boolean
    ): Boolean = inputValidator(config, stopIndexes, bet, lines, isFreeSpin)

    fun evaluateStops(
        config: SlotConfig,
        stopIndexes: List<Int>,
        bet: Int,
        lines: Int,
        isFreeSpin: Boolean
    ): SpinResult = stopEvaluator(config, stopIndexes, bet, lines, isFreeSpin)

    fun xpForSpin(totalBet: Int, isFreeSpin: Boolean, winAmount: Int): Int =
        xpPolicy(totalBet, isFreeSpin, winAmount)
}

private class FixedSlotCatalog(configs: List<SlotConfig>) : SlotCatalog {
    private val slotsById = configs.associateBy(SlotConfig::id)

    override fun getSlot(slotId: String): SlotConfig =
        getSlotExact(slotId) ?: error("Unknown released slot math slot: $slotId")

    override fun getSlotExact(slotId: String): SlotConfig? = slotsById[slotId]
}
