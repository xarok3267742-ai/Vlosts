package com.vslot.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.annotation.IdRes
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.vslot.app.game.SlotEngine
import com.vslot.app.ui.widget.BitmapNumberView
import java.io.FileInputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class SlotFrameMetricsTest {
    @Test
    fun deterministicSpinHasNoCatastrophicP95FrameTime() {
        val profile = InstrumentationRegistry.getArguments().getString(PROFILE_ARGUMENT).orEmpty()
        assumeTrue(
            "Frame metrics require an explicit emulator or physical Samsung QA profile.",
            profile == EMULATOR_PROFILE || profile == PHYSICAL_SAMSUNG_PROFILE
        )
        val originalAnimatorScale = Settings.Global.getString(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE
        )
        setAnimatorDurationScale(FRAME_METRICS_ANIMATOR_SCALE)
        try {
            waitUntil(ANIMATOR_SCALE_TIMEOUT_MS) {
                assertTrue(
                    "Animator duration scale did not reach 1x.",
                    ValueAnimator.getDurationScale() >= FRAME_METRICS_ANIMATOR_SCALE.toFloat()
                )
            }
            seedQaScenario(SCENARIO_SLOT_MULTI_WIN)
            val expectedWin = expectedMultiWinAmount()
            assertTrue("The deterministic QA spin must produce a win.", expectedWin > 0)

            launchQaSlot().use { scenario ->
                waitUntil {
                    assertTrue(scenario.viewState(R.id.spinButton).isEffectivelyDisplayed)
                    assertTrue(scenario.viewState(R.id.spinButton).isEnabled)
                }

            val frameSamples = Collections.synchronizedList(mutableListOf<FrameSample>())
            val droppedCallbacks = AtomicInteger(0)
            val collecting = AtomicBoolean(true)
            val spinStartedAtNanos = AtomicLong(0L)
            val metricsThread = HandlerThread("slot-frame-metrics").apply { start() }
            val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                if (!collecting.get()) return@OnFrameMetricsAvailableListener
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration > 0L) {
                    val spinStartedAt = spinStartedAtNanos.get()
                    val elapsedMs = if (spinStartedAt > 0L) {
                        (SystemClock.elapsedRealtimeNanos() - spinStartedAt) / NANOS_PER_MILLISECOND
                    } else {
                        -1L
                    }
                    frameSamples += FrameSample(
                        durationNanos = duration,
                        elapsedMs = elapsedMs,
                        drawNanos = metrics.getMetric(FrameMetrics.DRAW_DURATION),
                        syncNanos = metrics.getMetric(FrameMetrics.SYNC_DURATION),
                        commandIssueNanos = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                        swapBuffersNanos = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                        gpuNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            metrics.getMetric(FrameMetrics.GPU_DURATION)
                        } else {
                            0L
                        }
                    )
                }
                droppedCallbacks.addAndGet(dropped)
            }

            scenario.onActivity { activity ->
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper))
            }
            try {
                spinStartedAtNanos.set(SystemClock.elapsedRealtimeNanos())
                tapView(scenario, R.id.spinButton)
                waitUntil(SPIN_COMPLETION_TIMEOUT_MS) {
                    val spinButton = scenario.viewState(R.id.spinButton)
                    val lastWin = scenario.bitmapNumberState(R.id.lastWinDigits)
                    assertTrue(spinButton.isEnabled)
                    assertEquals(expectedWin, lastWin)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                SystemClock.sleep(FINAL_FRAME_DRAIN_MS)
            } finally {
                collecting.set(false)
                scenario.onActivity { activity ->
                    activity.window.removeOnFrameMetricsAvailableListener(listener)
                }
                metricsThread.quitSafely()
                metricsThread.join(METRICS_THREAD_JOIN_TIMEOUT_MS)
            }

            val capturedSamples = synchronized(frameSamples) { frameSamples.toList() }
            val samples = capturedSamples.map(FrameSample::durationNanos).sorted()
            assertTrue(
                "Too few frame metrics were captured around the deterministic spin: ${samples.size}.",
                samples.size >= MIN_FRAME_SAMPLES
            )

            val p50Nanos = samples.percentile(50)
            val p95Nanos = samples.percentile(95)
            val p99Nanos = samples.percentile(99)
            val maxNanos = samples.last()
            val jankFrames = samples.count { it > JANK_THRESHOLD_NANOS }
            val jankRatePercent = jankFrames * 100.0 / samples.size
            val refreshRate = scenario.refreshRate().coerceAtLeast(MIN_VALID_REFRESH_RATE)
            val missedDeadlineThresholdNanos = maxOf(
                MISSED_DEADLINE_FLOOR_NANOS,
                (NANOS_PER_SECOND / refreshRate * MISSED_DEADLINE_BUDGET_MULTIPLIER).toLong()
            )
            val missedDeadlineFrames = samples.count { it > missedDeadlineThresholdNanos }
            val missedDeadlineRatePercent = missedDeadlineFrames * 100.0 / samples.size
            val summary = String.format(
                Locale.US,
                "SLOT_FRAME_METRICS samples=%d p50_ms=%.2f p95_ms=%.2f p99_ms=%.2f max_ms=%.2f " +
                    "jank_gt_32ms=%d jank_rate_pct=%.2f refresh_hz=%.1f " +
                    "missed_deadline_gt_ms=%.2f missed_deadline_frames=%d " +
                    "missed_deadline_rate_pct=%.2f dropped_callbacks=%d",
                samples.size,
                p50Nanos.toMilliseconds(),
                p95Nanos.toMilliseconds(),
                p99Nanos.toMilliseconds(),
                maxNanos.toMilliseconds(),
                jankFrames,
                jankRatePercent,
                refreshRate,
                missedDeadlineThresholdNanos.toMilliseconds(),
                missedDeadlineFrames,
                missedDeadlineRatePercent,
                droppedCallbacks.get()
            )
            Log.i(LOG_TAG, summary)
            println(summary)
            val phaseSummary = capturedSamples
                .filter { it.elapsedMs >= 0L }
                .groupBy { it.elapsedMs / PHASE_BUCKET_MS }
                .toSortedMap()
                .entries
                .joinToString(separator = " ") { (bucket, bucketSamples) ->
                    val bucketDurations = bucketSamples.map(FrameSample::durationNanos).sorted()
                    val bucketJank = bucketDurations.count { it > JANK_THRESHOLD_NANOS }
                    String.format(
                        Locale.US,
                        "%d-%dms:n=%d,p50=%.2f,p95=%.2f,max=%.2f,jank=%.1f%%",
                        bucket * PHASE_BUCKET_MS,
                        (bucket + 1L) * PHASE_BUCKET_MS,
                        bucketDurations.size,
                        bucketDurations.percentile(50).toMilliseconds(),
                        bucketDurations.percentile(95).toMilliseconds(),
                        bucketDurations.last().toMilliseconds(),
                        bucketJank * 100.0 / bucketDurations.size
                    )
                }
            Log.i(LOG_TAG, "SLOT_FRAME_PHASES $phaseSummary")
            println("SLOT_FRAME_PHASES $phaseSummary")
            val componentSummary = listOf(
                "draw" to capturedSamples.map(FrameSample::drawNanos),
                "sync" to capturedSamples.map(FrameSample::syncNanos),
                "command" to capturedSamples.map(FrameSample::commandIssueNanos),
                "swap" to capturedSamples.map(FrameSample::swapBuffersNanos),
                "gpu" to capturedSamples.map(FrameSample::gpuNanos)
            ).joinToString(separator = " ") { (name, componentSamples) ->
                val sorted = componentSamples.filter { it > 0L }.sorted()
                if (sorted.isEmpty()) {
                    "$name=n/a"
                } else {
                    String.format(
                        Locale.US,
                        "%s:p50=%.2f,p95=%.2f,max=%.2f",
                        name,
                        sorted.percentile(50).toMilliseconds(),
                        sorted.percentile(95).toMilliseconds(),
                        sorted.last().toMilliseconds()
                    )
                }
            }
            Log.i(LOG_TAG, "SLOT_FRAME_COMPONENTS $componentSummary")
            println("SLOT_FRAME_COMPONENTS $componentSummary")

                if (profile == PHYSICAL_SAMSUNG_PROFILE) {
                    assertTrue(
                        "$profile slot p95 exceeded its QA target: $summary",
                        p95Nanos <= PHYSICAL_SAMSUNG_P95_LIMIT_NANOS
                    )
                    assertTrue(
                        "$profile slot p99 exceeded its QA target: $summary",
                        p99Nanos <= PHYSICAL_SAMSUNG_P99_LIMIT_NANOS
                    )
                    assertTrue(
                        "$profile slot maximum frame exceeded its QA target: $summary",
                        maxNanos <= PHYSICAL_SAMSUNG_MAX_FRAME_NANOS
                    )
                    assertTrue(
                        "$profile slot jank rate exceeded its QA target: $summary",
                        jankRatePercent <= PHYSICAL_SAMSUNG_MAX_JANK_RATE_PERCENT
                    )
                    assertTrue(
                        "$profile missed-deadline rate exceeded its QA target: $summary",
                        missedDeadlineRatePercent <= PHYSICAL_SAMSUNG_MAX_MISSED_DEADLINE_RATE_PERCENT
                    )
                } else {
                    assertTrue(
                        "$profile slot p95 exceeded its catastrophic-regression ceiling: $summary",
                        p95Nanos <= EMULATOR_P95_LIMIT_NANOS
                    )
                    assertTrue(
                        "$profile slot maximum frame exceeded its catastrophic-regression ceiling: $summary",
                        maxNanos <= EMULATOR_MAX_FRAME_NANOS
                    )
                }
            }
        } finally {
            setAnimatorDurationScale(originalAnimatorScale)
        }
    }

    private fun setAnimatorDurationScale(value: String?) {
        val command = if (value == null) {
            "settings delete global animator_duration_scale"
        } else {
            "settings put global animator_duration_scale $value"
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream -> stream.readBytes() }
            }
    }

    private fun seedQaScenario(scenario: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val completed = CountDownLatch(1)
        val intent = Intent(QA_STATE_ACTION)
            .setClassName(context.packageName, QA_STATE_RECEIVER)
            .putExtra(QA_SCENARIO_EXTRA, scenario)
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    completed.countDown()
                }
            },
            null,
            Activity.RESULT_OK,
            null,
            null
        )
        assertTrue(
            "Timed out seeding QA scenario $scenario.",
            completed.await(QA_STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )
    }

    private fun launchQaSlot(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(QA_OPEN_SLOT_EXTRA, SLOT_ID)
        return ActivityScenario.launch(intent)
    }

    private fun expectedMultiWinAmount(): Int {
        val config = AppGraph.slotRepository.getSlot(SLOT_ID)
        return SlotEngine().evaluate(
            config = config,
            reels = config.reelStrips.mapIndexed { reelIndex, strip ->
                List(config.rows) { row ->
                    strip[(MULTI_WIN_STOPS[reelIndex] + row) % strip.size]
                }
            },
            bet = QA_LINE_BET,
            lines = QA_LINES,
            stopIndexes = MULTI_WIN_STOPS.toList()
        ).winAmount
    }

    private fun tapView(scenario: ActivityScenario<MainActivity>, @IdRes viewId: Int) {
        val bounds = scenario.value { activity ->
            val view = activity.findViewById<View>(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            assertTrue("View $viewId is not displayed.", view.isShown && view.width > 0 && view.height > 0)
            assertTrue("View $viewId is disabled.", view.isEnabled)
            val localBounds = android.graphics.Rect()
            assertTrue("View $viewId has no visible bounds.", view.getLocalVisibleRect(localBounds))
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            android.graphics.Rect(
                location[0] + localBounds.left,
                location[1] + localBounds.top,
                location[0] + localBounds.right,
                location[1] + localBounds.bottom
            )
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).useTouchEvent {
            source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(this)
        }
        SystemClock.sleep(POINTER_TAP_DURATION_MS)
        val upTime = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0).useTouchEvent {
            source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(this)
        }
        instrumentation.waitForIdleSync()
    }

    private fun ActivityScenario<MainActivity>.viewState(@IdRes viewId: Int): ViewState {
        return value { activity ->
            val view = activity.findViewById<View>(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            ViewState(
                isEffectivelyDisplayed = view.isShown && view.width > 0 && view.height > 0,
                isEnabled = view.isEnabled
            )
        }
    }

    private fun ActivityScenario<MainActivity>.refreshRate(): Float {
        var refreshRate = DEFAULT_REFRESH_RATE
        onActivity { activity ->
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            refreshRate = display.refreshRate
        }
        return refreshRate
    }

    private fun ActivityScenario<MainActivity>.bitmapNumberState(@IdRes viewId: Int): Int {
        return value { activity ->
            val view = activity.findViewById<BitmapNumberView>(viewId)
                ?: throw AssertionError("Bitmap number $viewId is missing.")
            view.displayedCharacters.filter(Char::isDigit).toIntOrNull() ?: 0
        }
    }

    private fun <T> ActivityScenario<MainActivity>.value(block: (MainActivity) -> T): T {
        var result: Any? = null
        onActivity { activity -> result = block(activity) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(timeoutMs: Long = VIEW_WAIT_TIMEOUT_MS, assertion: () -> Unit) {
        val timeoutAt = SystemClock.elapsedRealtime() + timeoutMs
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < timeoutAt) {
            try {
                assertion()
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                SystemClock.sleep(VIEW_WAIT_STEP_MS)
            }
        }
        throw AssertionError("Timed out waiting for slot UI state.", lastFailure)
    }

    private fun List<Long>.percentile(percentile: Int): Long {
        val index = (ceil(percentile / 100.0 * size).toInt() - 1).coerceIn(indices)
        return this[index]
    }

    private fun Long.toMilliseconds(): Double = this / NANOS_PER_MILLISECOND.toDouble()

    private inline fun MotionEvent.useTouchEvent(block: MotionEvent.() -> Unit) {
        try {
            block()
        } finally {
            recycle()
        }
    }

    private data class ViewState(
        val isEffectivelyDisplayed: Boolean,
        val isEnabled: Boolean
    )

    private data class FrameSample(
        val durationNanos: Long,
        val elapsedMs: Long,
        val drawNanos: Long,
        val syncNanos: Long,
        val commandIssueNanos: Long,
        val swapBuffersNanos: Long,
        val gpuNanos: Long
    )

    private companion object {
        const val LOG_TAG = "SlotFrameMetrics"
        const val PHASE_BUCKET_MS = 500L
        const val PROFILE_ARGUMENT = "slot_frame_profile"
        const val EMULATOR_PROFILE = "emulator"
        const val PHYSICAL_SAMSUNG_PROFILE = "physical_samsung"
        const val QA_STATE_ACTION = "com.vslot.app.debug.QA_STATE"
        const val QA_STATE_RECEIVER = "com.vslot.app.debug.QaStateReceiver"
        const val QA_SCENARIO_EXTRA = "scenario"
        const val QA_OPEN_SLOT_EXTRA = "qa_open_slot"
        const val SCENARIO_SLOT_MULTI_WIN = "slot_multi_win"
        const val SLOT_ID = "violet_fortune"
        const val QA_LINE_BET = 25
        const val QA_LINES = 10
        const val QA_STATE_TIMEOUT_MS = 5_000L
        const val VIEW_WAIT_TIMEOUT_MS = 7_000L
        const val ANIMATOR_SCALE_TIMEOUT_MS = 3_000L
        const val SPIN_COMPLETION_TIMEOUT_MS = 14_000L
        const val VIEW_WAIT_STEP_MS = 80L
        const val POINTER_TAP_DURATION_MS = 50L
        const val FINAL_FRAME_DRAIN_MS = 250L
        const val METRICS_THREAD_JOIN_TIMEOUT_MS = 2_000L
        const val MIN_FRAME_SAMPLES = 30
        const val FRAME_METRICS_ANIMATOR_SCALE = "1.0"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val JANK_THRESHOLD_NANOS = 32L * NANOS_PER_MILLISECOND
        const val MISSED_DEADLINE_FLOOR_NANOS = 20L * NANOS_PER_MILLISECOND
        const val MISSED_DEADLINE_BUDGET_MULTIPLIER = 1.25
        const val DEFAULT_REFRESH_RATE = 60f
        const val MIN_VALID_REFRESH_RATE = 30f
        const val EMULATOR_P95_LIMIT_NANOS = 250L * NANOS_PER_MILLISECOND
        const val EMULATOR_MAX_FRAME_NANOS = 500L * NANOS_PER_MILLISECOND
        const val PHYSICAL_SAMSUNG_P95_LIMIT_NANOS = 50L * NANOS_PER_MILLISECOND
        const val PHYSICAL_SAMSUNG_P99_LIMIT_NANOS = 100L * NANOS_PER_MILLISECOND
        const val PHYSICAL_SAMSUNG_MAX_FRAME_NANOS = 100L * NANOS_PER_MILLISECOND
        const val PHYSICAL_SAMSUNG_MAX_JANK_RATE_PERCENT = 10.0
        const val PHYSICAL_SAMSUNG_MAX_MISSED_DEADLINE_RATE_PERCENT = 20.0
        val MULTI_WIN_STOPS = intArrayOf(0, 5, 11, 1, 0)
    }
}
