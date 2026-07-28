package com.vslot.app.analytics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMetricaAnalyticsTrackerTest {
    @Test
    fun `reports sanitized event names and supported params only`() {
        val reporter = RecordingAnalyticsReporter()
        val tracker = AppMetricaAnalyticsTracker(reporter)

        tracker.track(
            " spin-result! ",
            mapOf(
                " slot-id " to " violet fortune/main ",
                "win_amount" to 250,
                "free_spin" to true,
                "tiny" to 2.toByte(),
                "full_url" to "https://example.com/?token=secret",
                "unsupported" to listOf("bad"),
                "blank" to " /// ",
                "null_value" to null
            )
        )

        assertEquals("spin_result", reporter.events.single().eventName)
        assertEquals(
            mapOf(
                "slot_id" to "violet_fortune_main",
                "win_amount_range" to "250_499",
                "free_spin" to true,
                "tiny" to 2
            ),
            reporter.events.single().params
        )
        assertTrue("win_amount" !in reporter.events.single().params)
    }

    @Test
    fun `coarsens virtual economy values before they reach the provider`() {
        val reporter = RecordingAnalyticsReporter()
        val tracker = AppMetricaAnalyticsTracker(reporter)

        tracker.track(
            AnalyticsEvents.SpinResult,
            mapOf(
                "amount" to 2_500,
                "balance" to 7_500L,
                "balance_before" to 12_345L,
                "balance_after" to 62_345L,
                "bet" to 250,
                "coins_balance" to 1_000L,
                "free_spins" to 3,
                "free_spins_before" to 4,
                "free_spins_after" to 10,
                "free_spins_awarded" to 12,
                "level" to 7,
                "level_xp_awarded" to 50,
                "line_bet" to 25,
                "total_bet" to 250,
                "win_amount" to 50_000,
                "lines" to 10
            )
        )

        val params = reporter.events.single().params
        assertEquals(
            setOf(
                "amount_range",
                "balance_range",
                "balance_before_range",
                "balance_after_range",
                "bet_range",
                "coins_balance_range",
                "free_spins_range",
                "free_spins_before_range",
                "free_spins_after_range",
                "free_spins_awarded_range",
                "level_range",
                "level_xp_awarded_range",
                "line_bet_range",
                "total_bet_range",
                "win_amount_range",
                "lines_range"
            ),
            params.keys
        )
        assertEquals("2500_4999", params["amount_range"])
        assertEquals("5000_9999", params["balance_range"])
        assertEquals("10000_49999", params["balance_before_range"])
        assertEquals("50000_plus", params["balance_after_range"])
        assertEquals("250_499", params["bet_range"])
        assertEquals("1000_2499", params["coins_balance_range"])
        assertEquals("1_9", params["free_spins_range"])
        assertEquals("1_9", params["free_spins_before_range"])
        assertEquals("10_49", params["free_spins_after_range"])
        assertEquals("10_49", params["free_spins_awarded_range"])
        assertEquals("1_9", params["level_range"])
        assertEquals("50_99", params["level_xp_awarded_range"])
        assertEquals("10_49", params["line_bet_range"])
        assertEquals("250_499", params["total_bet_range"])
        assertEquals("50000_plus", params["win_amount_range"])
        assertEquals("10_49", params["lines_range"])
        assertTrue(
            params.keys.none {
                it in setOf(
                    "amount",
                    "balance",
                    "balance_before",
                    "balance_after",
                    "bet",
                    "coins_balance",
                    "free_spins",
                    "free_spins_before",
                    "free_spins_after",
                    "free_spins_awarded",
                    "level",
                    "level_xp_awarded",
                    "line_bet",
                    "lines",
                    "total_bet",
                    "win_amount"
                )
            }
        )
    }

    @Test
    fun `drops blank sanitized event names`() {
        val reporter = RecordingAnalyticsReporter()
        val tracker = AppMetricaAnalyticsTracker(reporter)

        tracker.track(" !!! ", mapOf("slot_id" to "violet_fortune"))

        assertTrue(reporter.events.isEmpty())
    }

    @Test
    fun `swallows reporter exceptions so analytics cannot crash gameplay`() {
        val tracker = AppMetricaAnalyticsTracker(
            object : AnalyticsReporter {
                override fun reportEvent(eventName: String) {
                    error("sdk failed")
                }

                override fun reportEvent(eventName: String, params: Map<String, Any>) {
                    error("sdk failed")
                }
            }
        )

        tracker.track(AnalyticsEvents.SpinResult, mapOf("slot_id" to "violet_fortune"))
        tracker.track(AnalyticsEvents.PaytableOpen)
    }

    @Test
    fun `does not report before opt in and blocks immediately after opt out`() {
        val reporter = RecordingAnalyticsReporter()
        val tracker = AppMetricaAnalyticsTracker(
            reporter = reporter,
            dataSendingEnabled = false
        )

        tracker.track(AnalyticsEvents.AppOpen)
        tracker.setAnalyticsEnabled(true)
        tracker.track(AnalyticsEvents.AppOpen)
        tracker.setAnalyticsEnabled(false)
        tracker.track(AnalyticsEvents.PaytableOpen)

        assertEquals(listOf(true, false), reporter.dataSendingChanges)
        assertEquals(listOf(AnalyticsEvents.AppOpen), reporter.events.map { it.eventName })
    }

    @Test
    fun `failed opt out remains locally blocked and retries provider transition`() {
        var optOutAttempts = 0
        val reporter = object : AnalyticsReporter {
            val events = mutableListOf<String>()

            override fun reportEvent(eventName: String) {
                events += eventName
            }

            override fun reportEvent(eventName: String, params: Map<String, Any>) {
                events += eventName
            }

            override fun setDataSendingEnabled(enabled: Boolean) {
                if (!enabled && optOutAttempts++ == 0) error("first opt out failed")
            }
        }
        val tracker = AppMetricaAnalyticsTracker(reporter, dataSendingEnabled = true)

        assertTrue(!tracker.setAnalyticsEnabled(false))
        tracker.track(AnalyticsEvents.AppOpen)
        assertTrue(tracker.setAnalyticsEnabled(false))

        assertEquals(2, optOutAttempts)
        assertTrue(reporter.events.isEmpty())
    }

    @Test
    fun `concurrent opt out cannot be overwritten by an in flight opt in`() {
        val enableStarted = CountDownLatch(1)
        val releaseEnable = CountDownLatch(1)
        val changes = mutableListOf<Boolean>()
        val reporter = object : AnalyticsReporter {
            override fun reportEvent(eventName: String) = Unit

            override fun reportEvent(eventName: String, params: Map<String, Any>) = Unit

            override fun setDataSendingEnabled(enabled: Boolean) {
                if (enabled) {
                    enableStarted.countDown()
                    assertTrue(releaseEnable.await(2, TimeUnit.SECONDS))
                }
                synchronized(changes) { changes += enabled }
            }
        }
        val tracker = AppMetricaAnalyticsTracker(reporter, dataSendingEnabled = false)

        val enabling = thread { tracker.setAnalyticsEnabled(true) }
        assertTrue(enableStarted.await(2, TimeUnit.SECONDS))
        val disabling = thread { tracker.setAnalyticsEnabled(false) }
        releaseEnable.countDown()
        enabling.join(2_000)
        disabling.join(2_000)
        tracker.track(AnalyticsEvents.AppOpen)

        assertEquals(listOf(true, false), synchronized(changes) { changes.toList() })
    }

    @Test
    fun `report cannot finish after a completed opt out`() {
        val reportStarted = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val optOutCompleted = AtomicBoolean(false)
        val reportedAfterOptOut = AtomicBoolean(false)
        val reporter = object : AnalyticsReporter {
            override fun reportEvent(eventName: String) {
                reportStarted.countDown()
                assertTrue(releaseReport.await(2, TimeUnit.SECONDS))
                if (optOutCompleted.get()) reportedAfterOptOut.set(true)
            }

            override fun reportEvent(eventName: String, params: Map<String, Any>) {
                reportEvent(eventName)
            }

            override fun setDataSendingEnabled(enabled: Boolean) = Unit
        }
        val tracker = AppMetricaAnalyticsTracker(reporter, dataSendingEnabled = true)

        val reporting = thread { tracker.track(AnalyticsEvents.AppOpen) }
        assertTrue(reportStarted.await(2, TimeUnit.SECONDS))
        val disabling = thread {
            tracker.setAnalyticsEnabled(false)
            optOutCompleted.set(true)
        }
        releaseReport.countDown()

        reporting.join(2_000)
        disabling.join(2_000)
        tracker.track(AnalyticsEvents.AppOpen)

        assertFalse(reporting.isAlive)
        assertFalse(disabling.isAlive)
        assertFalse(reportedAfterOptOut.get())
    }

    private class RecordingAnalyticsReporter : AnalyticsReporter {
        val events = mutableListOf<ReportedEvent>()
        val dataSendingChanges = mutableListOf<Boolean>()

        override fun reportEvent(eventName: String) {
            events += ReportedEvent(eventName, emptyMap())
        }

        override fun reportEvent(eventName: String, params: Map<String, Any>) {
            events += ReportedEvent(eventName, params)
        }

        override fun setDataSendingEnabled(enabled: Boolean) {
            dataSendingChanges += enabled
        }
    }

    private data class ReportedEvent(
        val eventName: String,
        val params: Map<String, Any>
    )
}
