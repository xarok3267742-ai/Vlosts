package com.vslot.app

import android.content.Context
import androidx.core.content.edit
import com.vslot.app.analytics.AnalyticsTracker
import com.vslot.app.analytics.AnalyticsConsentController
import com.vslot.app.analytics.AnalyticsRevocationGuard
import com.vslot.app.analytics.InMemoryAnalyticsRevocationGuard
import com.vslot.app.analytics.NoOpAnalyticsTracker
import com.vslot.app.data.PlayerRepository
import com.vslot.app.game.SlotEngine
import com.vslot.app.game.SlotRng
import com.vslot.app.game.ReleasedSlotMathRegistry
import com.vslot.app.game.SlotRepository
import com.vslot.app.game.SpinSettlementVerifier

object AppGraph {
    private val noOpAnalytics = NoOpAnalyticsTracker()
    lateinit var playerRepository: PlayerRepository
        private set
    lateinit var slotRepository: SlotRepository
        private set
    lateinit var slotEngine: SlotEngine
        private set
    var analyticsTracker: AnalyticsTracker = noOpAnalytics
        private set
    var analyticsConsentController: AnalyticsConsentController = noOpAnalytics
        private set
    var analyticsRevocationGuard: AnalyticsRevocationGuard = InMemoryAnalyticsRevocationGuard()
        private set

    fun init(
        context: Context,
        analyticsTracker: AnalyticsTracker,
        analyticsConsentController: AnalyticsConsentController =
            analyticsTracker as? AnalyticsConsentController ?: noOpAnalytics,
        analyticsRevocationGuard: AnalyticsRevocationGuard = InMemoryAnalyticsRevocationGuard()
    ) {
        val appContext = context.applicationContext
        this.analyticsTracker = analyticsTracker
        this.analyticsConsentController = analyticsConsentController
        this.analyticsRevocationGuard = analyticsRevocationGuard
        val releasedMathRegistry = ReleasedSlotMathRegistry.fromAssets(appContext)
        slotRepository = SlotRepository(releasedMathRegistry.currentConfigs())
        slotEngine = debugSlotEngineOverride(appContext) ?: SlotEngine()
        playerRepository = PlayerRepository(
            appContext,
            SpinSettlementVerifier(releasedMathRegistry)
        )
    }

    fun replaceSlotEngineForDebug(engine: SlotEngine) {
        check(BuildConfig.QA_ENABLED) { "Slot engine replacement is only available in QA builds." }
        slotEngine = engine
    }

    fun resetSlotEngineForDebug() {
        if (BuildConfig.QA_ENABLED) {
            slotEngine = SlotEngine()
        }
    }

    fun persistSlotEngineOverrideForDebug(context: Context, stops: IntArray) {
        check(BuildConfig.QA_ENABLED) { "Slot engine override persistence is only available in QA builds." }
        context.applicationContext
            .getSharedPreferences(DEBUG_QA_PREFS, Context.MODE_PRIVATE)
            .edit { putString(DEBUG_SLOT_STOPS_KEY, stops.joinToString(DEBUG_STOP_SEPARATOR)) }
        replaceSlotEngineForDebug(SlotEngine(LoopingStopsRng(stops)))
    }

    fun clearSlotEngineOverrideForDebug(context: Context) {
        check(BuildConfig.QA_ENABLED) { "Slot engine override persistence is only available in QA builds." }
        context.applicationContext
            .getSharedPreferences(DEBUG_QA_PREFS, Context.MODE_PRIVATE)
            .edit { remove(DEBUG_SLOT_STOPS_KEY) }
        resetSlotEngineForDebug()
    }

    private fun debugSlotEngineOverride(context: Context): SlotEngine? {
        if (!BuildConfig.QA_ENABLED) return null
        val serializedStops = context
            .getSharedPreferences(DEBUG_QA_PREFS, Context.MODE_PRIVATE)
            .getString(DEBUG_SLOT_STOPS_KEY, null)
            ?: return null
        val stops = serializedStops
            .split(DEBUG_STOP_SEPARATOR)
            .mapNotNull { value -> value.toIntOrNull() }
            .takeIf { it.size == DEBUG_REEL_COUNT }
            ?.toIntArray()
            ?: return null
        return SlotEngine(LoopingStopsRng(stops))
    }

    private class LoopingStopsRng(private val stops: IntArray) : SlotRng {
        private var index = 0

        override fun nextInt(bound: Int): Int {
            val value = stops[index % stops.size] % bound
            index += 1
            return value
        }
    }

    private const val DEBUG_QA_PREFS = "v_slot_debug_qa"
    private const val DEBUG_SLOT_STOPS_KEY = "slotEngineStops"
    private const val DEBUG_STOP_SEPARATOR = ","
    private const val DEBUG_REEL_COUNT = 5
}
