package com.vslot.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotFrameMetricsQaContractTest {
    @Test
    fun `instrumentation test measures a deterministic spin and reports stable percentiles`() {
        val source = Path.of("src/androidTest/java/com/vslot/app/SlotFrameMetricsTest.kt").readText()

        assertTrue(source.contains("@SdkSuppress(minSdkVersion = 26)"))
        assertTrue(source.contains("seedQaScenario(SCENARIO_SLOT_MULTI_WIN)"))
        assertTrue(source.contains("QA_OPEN_SLOT_EXTRA"))
        assertTrue(source.contains("Window.OnFrameMetricsAvailableListener"))
        assertTrue(source.contains("FrameMetrics.TOTAL_DURATION"))
        assertTrue(source.contains("samples.percentile(50)"))
        assertTrue(source.contains("samples.percentile(95)"))
        assertTrue(source.contains("samples.percentile(99)"))
        assertTrue(source.contains("JANK_THRESHOLD_NANOS = 32L * NANOS_PER_MILLISECOND"))
        assertTrue(source.contains("assumeTrue("))
        assertTrue(source.contains("profile == EMULATOR_PROFILE || profile == PHYSICAL_SAMSUNG_PROFILE"))
        assertTrue(source.contains("EMULATOR_P95_LIMIT_NANOS = 250L * NANOS_PER_MILLISECOND"))
        assertTrue(source.contains("EMULATOR_MAX_FRAME_NANOS = 500L * NANOS_PER_MILLISECOND"))
        assertTrue(source.contains("PHYSICAL_SAMSUNG_P95_LIMIT_NANOS = 50L * NANOS_PER_MILLISECOND"))
        assertTrue(source.contains("PHYSICAL_SAMSUNG_P99_LIMIT_NANOS = 100L * NANOS_PER_MILLISECOND"))
        assertTrue(source.contains("PHYSICAL_SAMSUNG_MAX_FRAME_NANOS = 100L * NANOS_PER_MILLISECOND"))
        assertTrue(source.contains("PHYSICAL_SAMSUNG_MAX_JANK_RATE_PERCENT = 10.0"))
        assertTrue(source.contains("PHYSICAL_SAMSUNG_MAX_MISSED_DEADLINE_RATE_PERCENT = 20.0"))
        assertTrue(source.contains("profile == PHYSICAL_SAMSUNG_PROFILE"))
        assertTrue(source.contains("jankRatePercent <= PHYSICAL_SAMSUNG_MAX_JANK_RATE_PERCENT"))
        assertTrue(source.contains("missedDeadlineRatePercent <= PHYSICAL_SAMSUNG_MAX_MISSED_DEADLINE_RATE_PERCENT"))
        assertTrue(source.contains("MIN_FRAME_SAMPLES = 30"))
        assertTrue(source.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(source.contains("setAnimatorDurationScale(FRAME_METRICS_ANIMATOR_SCALE)"))
        assertTrue(source.contains("setAnimatorDurationScale(originalAnimatorScale)"))
        assertTrue(source.contains("SLOT_FRAME_METRICS samples=%d"))
        assertTrue(source.contains("samples.size >= MIN_FRAME_SAMPLES"))
        assertTrue(source.contains("p95Nanos <= EMULATOR_P95_LIMIT_NANOS"))
        assertTrue(source.contains("maxNanos <= EMULATOR_MAX_FRAME_NANOS"))
        assertTrue(source.contains("p99Nanos <= PHYSICAL_SAMSUNG_P99_LIMIT_NANOS"))
        assertTrue(!source.contains("EMULATOR_MAX_JANK_RATE_PERCENT"))
        assertTrue(!source.contains("EMULATOR_MAX_MISSED_DEADLINE_RATE_PERCENT"))
    }

    @Test
    fun `host script targets one serial runs only frame test and redacts evidence`() {
        val scriptPath = Path.of("../tools/qa_slot_frame_metrics.sh")
        val script = scriptPath.readText()
        val gitIgnore = Path.of("../.gitignore").readText()

        assertTrue("Frame metrics host script must be executable.", Files.isExecutable(scriptPath))
        assertTrue(script.contains("serial=\"\${1:-\${ANDROID_SERIAL:-}}\""))
        assertTrue(script.contains("model:SM_"))
        assertTrue(script.contains("manufacturer_lc"))
        assertTrue(script.contains("== \"samsung\""))
        assertTrue(script.contains("ro.kernel.qemu"))
        assertTrue(script.contains("frame_profile=\"physical_samsung\""))
        assertTrue(script.contains("frame_profile=\"emulator\""))
        assertTrue(script.contains("local p95_limit_ms=250.0"))
        assertTrue(script.contains("local max_limit_ms=500.0"))
        assertTrue(script.contains("local jank_rate_limit_pct=null"))
        assertTrue(script.contains("local missed_deadline_limit_pct=null"))
        assertTrue(script.contains("jank_rate_limit_pct=10.0"))
        assertTrue(script.contains("missed_deadline_limit_pct=20.0"))
        assertTrue(script.contains("slot_frame_profile=\$frame_profile"))
        assertTrue(script.contains("v-slot-samsung-qa-\${lock_serial}.lock"))
        assertTrue(script.contains("trap cleanup EXIT"))
        assertTrue(script.contains("rm -rf -- \"\$lock_dir\""))
        assertTrue(script.contains("\"\$ADB\" -s \"\$serial\""))
        assertTrue(script.contains("ANDROID_SERIAL=\"\$serial\" \"\$GRADLE\""))
        assertTrue(script.contains(":app:connectedQaAndroidTest"))
        assertTrue(script.contains("android.testInstrumentationRunnerArguments.class=\$TEST_CLASS"))
        assertTrue(script.contains("TEST_CLASS=\"com.vslot.app.SlotFrameMetricsTest\""))
        assertTrue(script.contains("ROOT/qa/frame-metrics"))
        assertTrue(script.contains("serial_sha256="))
        assertTrue(script.contains("build_fingerprint_sha256="))
        assertTrue(script.contains("\"schema_version\": 2"))
        assertTrue(script.contains("\\\"git_commit\\\""))
        assertTrue(script.contains("\\\"payload_sha256\\\""))
        assertTrue(script.contains("V_SLOT_APK_PAYLOAD_DIGEST"))
        assertTrue(script.contains("Frame metrics QA evidence:"))
        assertTrue(script.contains("qa_status=\"passed\""))
        assertTrue(script.contains("missed_deadline_rate_pct"))
        assertTrue(script.contains("QA_APK="))
        assertTrue(script.contains("<redacted-serial>"))
        assertTrue(script.contains("logcat -d -v brief -s SlotFrameMetrics:I"))
        assertTrue(!script.contains("connectedDebugAndroidTest"))
        assertTrue(gitIgnore.lineSequence().any { it.trim() == "qa/frame-metrics/" })
    }

    private fun Path.readText(): String = String(Files.readAllBytes(this), Charsets.UTF_8)
}
