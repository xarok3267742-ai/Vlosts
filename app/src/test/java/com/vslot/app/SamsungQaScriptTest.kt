package com.vslot.app

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SamsungQaScriptTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `successful qa run executes every required stage and restores exact display state`() {
        val fixture = createFixture(gradleExitCode = 0)

        val result = runQaScript(fixture)

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("Samsung SM-G975F"))
        assertEquals(7, result.log.lineSequence().count { it.startsWith("gradle ") })
        assertEquals(
            2,
            result.log.lineSequence().count {
                it.startsWith("gradle serial=$SAMSUNG_SERIAL args=connectedQaAndroidTest") &&
                    it.contains("android.testInstrumentationRunnerArguments.notClass=com.vslot.app.SlotFrameMetricsTest")
            }
        )
        assertTrue(Path.of("../tools/qa_samsung_connected_tests.sh").readText().contains("FULL_SUITE_EXPECTED_TESTS=65"))
        assertTrue(Path.of("../tools/qa_samsung_connected_tests.sh").readText().contains("FULL_SUITE_EXPECTED_SKIPPED=5"))
        assertTrue(
            result.log,
            result.log.contains("android.testInstrumentationRunnerArguments.class=$PORTRAIT_SMOKE_TEST")
        )
        assertTrue(
            result.log,
            result.log.contains("android.testInstrumentationRunnerArguments.class=$LARGE_FONT_TESTS")
        )
        assertTrue(
            result.log,
            result.log.contains("android.testInstrumentationRunnerArguments.class=$COMPACT_SETTINGS_TEST")
        )
        assertEquals(
            2,
            result.log.lineSequence().count {
                it.contains("android.testInstrumentationRunnerArguments.class=$COMPACT_LANDSCAPE_TEST")
            }
        )
        assertTrue(result.log, result.log.contains("shell settings put system font_scale 2.0"))
        assertTrue(result.log, result.log.contains("shell wm size 720x1280"))
        assertTrue(result.log, result.log.contains("shell wm size 720x1080"))
        assertTrue(result.log, result.log.contains("shell wm density 320"))
        assertTrue(result.log, result.log.contains("shell settings put system font_scale 1.0"))
        assertTrue(result.log, result.log.contains("shell settings put system user_rotation 0"))
        assertTrue(result.log, result.log.contains("shell wm user-rotation lock 0"))
        assertTrue(result.log, result.log.contains("shell wm user-rotation lock 1"))
        assertTrue(result.log, result.log.contains("shell wm user-rotation lock 3"))
        assertEquals(
            4,
            result.log.lineSequence().count { it.contains("shell wm user-rotation lock 1") }
        )
        assertEquals(
            4,
            result.log.lineSequence().count { it.contains("shell wm user-rotation lock 3") }
        )
        assertEquals(
            8,
            result.log.lineSequence().count {
                it.contains("shell am start -W -n com.vslot.app.qa/com.vslot.app.MainActivity")
            }
        )
        assertEquals(
            8,
            result.log.lineSequence().count { it.contains("shell dumpsys activity activities") }
        )
        val firstLandscapeProbe = result.log.indexOf(
            "shell am start -W -n com.vslot.app.qa/com.vslot.app.MainActivity"
        )
        assertTrue(result.log, firstLandscapeProbe >= 0)
        assertTrue(
            result.log,
            firstLandscapeProbe < result.log.indexOf("shell wm user-rotation lock 1")
        )
        assertTrue(result.log, result.log.contains("shell wm size 1000x2000"))
        assertTrue(result.log, result.log.contains("shell wm density 480"))
        assertTrue(result.log, result.log.contains("shell settings put system font_scale 1.15"))
        assertTrue(result.log, result.log.contains("shell settings put system accelerometer_rotation 1"))
        assertTrue(
            result.log,
            result.log.contains("-s $SAMSUNG_SERIAL shell settings put global stay_on_while_plugged_in 3")
        )
        assertEquals("1000x2000", fixture.wmSizeState.readText().trim())
        assertEquals("480", fixture.wmDensityState.readText().trim())
        assertEquals("1.15", fixture.fontScaleState.readText().trim())
        assertEquals("0", fixture.rotationState.readText().trim())
        assertEquals("1", fixture.accelerometerRotationState.readText().trim())
        assertEquals("3", fixture.stayAwakeState.readText().trim())
        assertTrue(result.log, result.log.contains("shell dumpsys window displays"))
        assertTrue(result.log, result.log.contains("install -r -t -d"))
        assertTrue(
            result.log,
            result.log.contains(
                "shell am start -W -n com.vslot.app.qa/com.vslot.app.MainActivity"
            )
        )
        assertTrue(result.log, result.log.contains("uninstall com.vslot.app.qa"))

        val evidence = result.evidence.single()
        assertTrue(evidence, evidence.contains("\"schema_version\": 6"))
        assertTrue(evidence, evidence.contains("\"git_commit\""))
        assertTrue(evidence, evidence.contains("\"serial_sha256\": \"${sha256(SAMSUNG_SERIAL)}\""))
        assertTrue(evidence, !evidence.contains(SAMSUNG_SERIAL))
        assertTrue(evidence, evidence.contains("\"one_ui_version\": \"60100\""))
        assertTrue(evidence, evidence.contains("\"sha256\": \"${sha256(QA_APK_CONTENT)}\""))
        assertTrue(evidence, evidence.contains("\"payload_sha256\": \"${sha256(QA_APK_CONTENT)}\""))
        assertStageStatus(evidence, "portrait_smoke", "passed")
        assertStageStatus(evidence, "font_scale_2_0_first_launch_legal_notices", "passed")
        assertStageStatus(evidence, "compact_portrait_settings", "passed")
        assertStageStatus(evidence, "compact_landscape_rotation_1", "passed")
        assertStageStatus(evidence, "compact_landscape_rotation_3", "passed")
        assertStageStatus(evidence, "landscape_rotation_1", "passed")
        assertStageStatus(evidence, "landscape_rotation_3", "passed")
        assertTrue(evidence, evidence.contains("\"landscape_rotation_1\": {\"status\": \"passed\", \"user_rotation\": 1, \"display_profile\": \"captured\", \"tests\": 65, \"skipped\": 5"))
        assertTrue(evidence, evidence.contains("\"landscape_rotation_3\": {\"status\": \"passed\", \"user_rotation\": 3, \"display_profile\": \"captured\", \"tests\": 65, \"skipped\": 5"))
        assertTrue(evidence, evidence.contains("\"user_rotation\": 1"))
        assertTrue(evidence, evidence.contains("\"user_rotation\": 3"))
        assertTrue(evidence, evidence.contains("\"verified_landscape\": true"))
        assertTrue(evidence, evidence.contains("\"status\": \"passed\""))
    }

    @Test
    fun `failed Gradle stage preserves absent overrides and exit code`() {
        val fixture = createFixture(
            gradleExitCode = GRADLE_FAILURE_EXIT_CODE,
            initialWmSizeOverride = null,
            initialWmDensityOverride = null
        )

        val result = runQaScript(fixture)

        assertEquals(result.output, GRADLE_FAILURE_EXIT_CODE, result.exitCode)
        assertEquals(1, result.log.lineSequence().count { it.startsWith("gradle ") })
        assertTrue(result.log, !result.log.contains("shell wm size reset"))
        assertTrue(result.log, !result.log.contains("shell wm density reset"))
        assertTrue(result.log, result.log.contains("shell settings put system font_scale 1.15"))
        assertTrue(result.log, result.log.contains("shell settings put system user_rotation 0"))
        assertTrue(result.log, result.log.contains("shell settings put system accelerometer_rotation 1"))
        assertTrue(fixture.wmSizeState.readText().isBlank())
        assertTrue(fixture.wmDensityState.readText().isBlank())
        val evidence = result.evidence.single()
        assertStageStatus(evidence, "portrait_smoke", "failed")
        assertStageStatus(evidence, "font_scale_2_0_first_launch_legal_notices", "not_run")
        assertStageStatus(evidence, "compact_portrait_settings", "not_run")
        assertTrue(evidence, evidence.contains("\"test_status\": \"failed\""))
        assertTrue(evidence, evidence.contains("\"status\": \"failed\""))
    }

    @Test
    fun `mandatory portrait stage fails before Gradle when portrait cannot be verified`() {
        val fixture = createFixture(gradleExitCode = 0, forceLandscape = true)

        val result = runQaScript(fixture)

        assertEquals(result.output, 1, result.exitCode)
        assertTrue(result.output, result.output.contains("was not verified as portrait"))
        assertTrue(result.log, result.log.lineSequence().none { it.startsWith("gradle ") })
        assertTrue(result.log, result.log.contains("shell settings put system user_rotation 0"))
        assertTrue(result.log, result.log.contains("shell settings put system accelerometer_rotation 1"))
        val evidence = result.evidence.single()
        assertStageStatus(evidence, "portrait_smoke", "configuration_failed")
        assertStageStatus(evidence, "font_scale_2_0_first_launch_legal_notices", "not_run")
        assertTrue(evidence, evidence.contains("\"status\": \"failed\""))
    }

    @Test
    fun `rotation 90 and 270 are verified from Samsung display rotation`() {
        val fixture = createFixture(gradleExitCode = 0, swapLandscapeRotations = true)

        val result = runQaScript(fixture)

        assertEquals(result.output, 1, result.exitCode)
        assertTrue(result.output, result.output.contains("display_rotation=3, user_rotation=1"))
        assertEquals(3, result.log.lineSequence().count { it.startsWith("gradle ") })
        val evidence = result.evidence.single()
        assertStageStatus(evidence, "compact_landscape_rotation_1", "configuration_failed")
        assertStageStatus(evidence, "compact_landscape_rotation_3", "not_run")
    }

    @Test
    fun `landscape stage fails when rotation probe never becomes foreground`() {
        val fixture = createFixture(gradleExitCode = 0, probeForegroundFails = true)

        val result = runQaScript(fixture)

        assertEquals(result.output, 1, result.exitCode)
        assertTrue(result.output, result.output.contains("did not become the resumed activity"))
        assertEquals(3, result.log.lineSequence().count { it.startsWith("gradle ") })
        val evidence = result.evidence.single()
        assertStageStatus(evidence, "compact_landscape_rotation_1", "configuration_failed")
        assertStageStatus(evidence, "compact_landscape_rotation_3", "not_run")
    }

    @Test
    fun `stage fails when connected tests change its rotation postcondition`() {
        val fixture = createFixture(gradleExitCode = 0, rotationDriftsAfterGradle = true)

        val result = runQaScript(fixture)

        assertEquals(result.output, 1, result.exitCode)
        assertEquals(1, result.log.lineSequence().count { it.startsWith("gradle ") })
        val evidence = result.evidence.single()
        assertStageStatus(evidence, "portrait_smoke", "postcondition_failed")
        assertStageStatus(evidence, "font_scale_2_0_first_launch_legal_notices", "not_run")
    }

    @Test
    fun `emulator advertising a Samsung model is rejected before device mutation`() {
        val fixture = createFixture(gradleExitCode = 0, isQemu = true)

        val result = runQaScript(fixture)

        assertEquals(result.output, 1, result.exitCode)
        assertTrue(result.output, result.output.contains("not a physical Samsung"))
        assertTrue(result.log, result.log.lineSequence().none { it.startsWith("gradle ") })
        assertTrue(result.log, !result.log.contains("shell settings put"))
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `filtered stage fails closed when instrumentation report is skipped`() {
        val fixture = createFixture(gradleExitCode = 0, filteredStageSkipped = true)

        val result = runQaScript(fixture)

        assertEquals(result.output, 1, result.exitCode)
        assertTrue(result.output, result.output.contains("skipped=1"))
        val evidence = result.evidence.single()
        assertStageStatus(evidence, "portrait_smoke", "failed")
        assertTrue(evidence, evidence.contains("\"tests\": 1, \"skipped\": 1"))
    }

    @Test
    fun `full landscape stage fails closed when instrumentation report is skipped`() {
        val fixture = createFixture(gradleExitCode = 0, fullStageSkipped = true)

        val result = runQaScript(fixture)

        assertEquals(result.output, 1, result.exitCode)
        assertTrue(result.output, result.output.contains("landscape_rotation_1 report mismatch"))
        assertTrue(result.output, result.output.contains("skipped=6"))
        val evidence = result.evidence.single()
        assertStageStatus(evidence, "landscape_rotation_1", "failed")
        assertStageStatus(evidence, "landscape_rotation_3", "not_run")
        assertTrue(evidence, evidence.contains("\"tests\": 65, \"skipped\": 6"))
    }

    private fun assertStageStatus(evidence: String, stage: String, status: String) {
        assertTrue(
            evidence,
            evidence.contains("\"$stage\": {\"status\": \"$status\"")
        )
    }

    private fun createFixture(
        gradleExitCode: Int,
        forceLandscape: Boolean = false,
        swapLandscapeRotations: Boolean = false,
        rotationDriftsAfterGradle: Boolean = false,
        isQemu: Boolean = false,
        filteredStageSkipped: Boolean = false,
        fullStageSkipped: Boolean = false,
        probeForegroundFails: Boolean = false,
        initialWmSizeOverride: String? = "1000x2000",
        initialWmDensityOverride: String? = "480"
    ): Fixture {
        val root = temporaryFolder.newFolder().toPath()
        val log = root.resolve("commands.log")
        val rotationState = root.resolve("rotation-state")
        val accelerometerRotationState = root.resolve("accelerometer-rotation-state")
        val stayAwakeState = root.resolve("stay-awake-state")
        val wmSizeState = root.resolve("wm-size-state")
        val wmDensityState = root.resolve("wm-density-state")
        val fontScaleState = root.resolve("font-scale-state")
        val displayRotationState = root.resolve("display-rotation-state")
        val foregroundState = root.resolve("foreground-state")
        val evidenceDir = root.resolve("evidence")
        val connectedResultsDir = root.resolve("connected-results")
        val qaApk = root.resolve("app-qa.apk")
        val tempDir = Files.createDirectory(root.resolve("tmp"))
        rotationState.writeText("0\n")
        accelerometerRotationState.writeText("1\n")
        stayAwakeState.writeText("3\n")
        wmSizeState.writeText(initialWmSizeOverride.orEmpty())
        wmDensityState.writeText(initialWmDensityOverride.orEmpty())
        fontScaleState.writeText("1.15\n")
        displayRotationState.writeText("0\n")
        foregroundState.writeText("launcher\n")
        qaApk.writeText(QA_APK_CONTENT)
        val apkPayloadDigest = executable(
            root.resolve("apk-payload-digest"),
            """
                #!/bin/bash
                shasum -a 256 "${'$'}1" | awk '{print ${'$'}1}'
            """.trimIndent()
        )
        val adb = executable(
            root.resolve("adb"),
            """
                #!/bin/bash
                set -eu
                printf 'adb %s\n' "${'$'}*" >> "${'$'}QA_TEST_LOG"

                if [[ "${'$'}{1:-}" == "devices" ]]; then
                  printf '$SAMSUNG_SERIAL device product:beyond2lte model:SM_G975F transport_id:1\n'
                  exit 0
                fi

                if [[ "${'$'}{1:-}" != "-s" || "${'$'}{2:-}" != "$SAMSUNG_SERIAL" ]]; then
                  exit 64
                fi
                shift 2

                if [[ "${'$'}{1:-}" == "get-state" ]]; then
                  printf 'device\n'
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "getprop" ]]; then
                  case "${'$'}{3:-}" in
                    ro.product.manufacturer) printf 'samsung\n' ;;
                    ro.product.model) printf 'SM-G975F\n' ;;
                    ro.build.version.release) printf '12\n' ;;
                    ro.build.fingerprint) printf 'samsung/beyond2lte/beyond2:12/SP1A/test:user/release-keys\n' ;;
                    ro.build.version.oneui) printf '60100\n' ;;
                    ro.kernel.qemu) printf '%s\n' "${'$'}QA_IS_QEMU" ;;
                    persist.sys.locale) printf 'ru-RU\n' ;;
                    ro.product.locale) printf 'ru-RU\n' ;;
                    *) exit 65 ;;
                  esac
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "dumpsys" && "${'$'}{3:-}" == "window" && "${'$'}{4:-}" == "displays" ]]; then
                  rotation="${'$'}(tr -d '\r\n' < "${'$'}QA_DISPLAY_ROTATION_STATE")"
                  size="${'$'}(tr -d '\r\n' < "${'$'}QA_WM_SIZE_STATE")"
                  [[ -n "${'$'}size" ]] || size="1080x2280"
                  width="${'$'}{size%x*}"
                  height="${'$'}{size#*x}"
                  if [[ "${'$'}QA_FORCE_LANDSCAPE" == "1" ]]; then
                    reported_rotation=1
                    current_width="${'$'}height"
                    current_height="${'$'}width"
                  else
                    reported_rotation="${'$'}rotation"
                    if [[ "${'$'}rotation" == "0" || "${'$'}rotation" == "2" ]]; then
                      current_width="${'$'}width"
                      current_height="${'$'}height"
                    else
                      current_width="${'$'}height"
                      current_height="${'$'}width"
                    fi
                  fi
                  if [[ "${'$'}QA_SWAP_LANDSCAPE_ROTATIONS" == "1" ]]; then
                    case "${'$'}reported_rotation" in
                      1) reported_rotation=3 ;;
                      3) reported_rotation=1 ;;
                    esac
                  fi
                  case "${'$'}reported_rotation" in
                    0) rotation_name=ROTATION_0 ;;
                    1) rotation_name=ROTATION_90 ;;
                    2) rotation_name=ROTATION_180 ;;
                    3) rotation_name=ROTATION_270 ;;
                  esac
                  printf 'Display: mDisplayId=0\n  init=%sx%s cur=%sx%s app=%sx%s\n  DisplayRotation: mRotation=%s\n' \
                    "${'$'}width" "${'$'}height" "${'$'}current_width" "${'$'}current_height" \
                    "${'$'}current_width" "${'$'}current_height" "${'$'}rotation_name"
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "dumpsys" && "${'$'}{3:-}" == "window" ]]; then
                  printf 'mDreamingLockscreen=false mShowingLockscreen=false\n'
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "dumpsys" && "${'$'}{3:-}" == "activity" && "${'$'}{4:-}" == "activities" ]]; then
                  foreground="${'$'}(tr -d '\r\n' < "${'$'}QA_FOREGROUND_STATE")"
                  if [[ "${'$'}foreground" == "qa" ]]; then
                    printf 'mResumedActivity: ActivityRecord{fixture u0 com.vslot.app.qa/com.vslot.app.MainActivity t1}\n'
                  else
                    printf 'mResumedActivity: ActivityRecord{fixture u0 com.sec.android.app.launcher/.Launcher t1}\n'
                  fi
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "dumpsys" && "${'$'}{3:-}" == "display" ]]; then
                  rotation="${'$'}(tr -d '\r\n' < "${'$'}QA_DISPLAY_ROTATION_STATE")"
                  size="${'$'}(tr -d '\r\n' < "${'$'}QA_WM_SIZE_STATE")"
                  [[ -n "${'$'}size" ]] || size="1080x2280"
                  width="${'$'}{size%x*}"
                  height="${'$'}{size#*x}"
                  if [[ "${'$'}QA_FORCE_LANDSCAPE" == "1" || "${'$'}rotation" == "1" || "${'$'}rotation" == "3" ]]; then
                    printf 'logicalWidth=%s, logicalHeight=%s\n' "${'$'}height" "${'$'}width"
                  else
                    printf 'logicalWidth=%s, logicalHeight=%s\n' "${'$'}width" "${'$'}height"
                  fi
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "settings" && "${'$'}{3:-}" == "get" ]]; then
                  case "${'$'}{4:-}/${'$'}{5:-}" in
                    global/stay_on_while_plugged_in) cat "${'$'}QA_STAY_AWAKE_STATE" ;;
                    system/accelerometer_rotation) cat "${'$'}QA_ACCELEROMETER_ROTATION_STATE" ;;
                    system/user_rotation) cat "${'$'}QA_ROTATION_STATE" ;;
                    system/font_scale) cat "${'$'}QA_FONT_SCALE_STATE" ;;
                    *) printf 'null\n' ;;
                  esac
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "settings" && "${'$'}{3:-}" == "put" ]]; then
                  case "${'$'}{4:-}/${'$'}{5:-}" in
                    global/stay_on_while_plugged_in) printf '%s\n' "${'$'}{6:-}" > "${'$'}QA_STAY_AWAKE_STATE" ;;
                    system/accelerometer_rotation) printf '%s\n' "${'$'}{6:-}" > "${'$'}QA_ACCELEROMETER_ROTATION_STATE" ;;
                    system/user_rotation) printf '%s\n' "${'$'}{6:-}" > "${'$'}QA_ROTATION_STATE" ;;
                    system/font_scale) printf '%s\n' "${'$'}{6:-}" > "${'$'}QA_FONT_SCALE_STATE" ;;
                  esac
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "settings" && "${'$'}{3:-}" == "delete" ]]; then
                  case "${'$'}{4:-}/${'$'}{5:-}" in
                    global/stay_on_while_plugged_in) : > "${'$'}QA_STAY_AWAKE_STATE" ;;
                    system/font_scale) : > "${'$'}QA_FONT_SCALE_STATE" ;;
                  esac
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "svc" && "${'$'}{3:-}" == "power" && "${'$'}{4:-}" == "stayon" ]]; then
                  printf '7\n' > "${'$'}QA_STAY_AWAKE_STATE"
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "wm" && "${'$'}{3:-}" == "user-rotation" && "${'$'}{4:-}" == "lock" ]]; then
                  printf '%s\n' "${'$'}{5:-}" > "${'$'}QA_ROTATION_STATE"
                  printf '0\n' > "${'$'}QA_ACCELEROMETER_ROTATION_STATE"
                  if [[ "${'$'}(tr -d '\r\n' < "${'$'}QA_FOREGROUND_STATE")" == "qa" || "${'$'}{5:-}" == "0" ]]; then
                    printf '%s\n' "${'$'}{5:-}" > "${'$'}QA_DISPLAY_ROTATION_STATE"
                  fi
                elif [[ "${'$'}{1:-}" == "install" ]]; then
                  :
                elif [[ "${'$'}{1:-}" == "uninstall" ]]; then
                  printf 'launcher\n' > "${'$'}QA_FOREGROUND_STATE"
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "am" && "${'$'}{3:-}" == "start" ]]; then
                  if [[ "${'$'}QA_PROBE_FOREGROUND_FAILS" == "1" ]]; then
                    printf 'launcher\n' > "${'$'}QA_FOREGROUND_STATE"
                  else
                    printf 'qa\n' > "${'$'}QA_FOREGROUND_STATE"
                  fi
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "am" && "${'$'}{3:-}" == "force-stop" ]]; then
                  printf 'launcher\n' > "${'$'}QA_FOREGROUND_STATE"
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "wm" && "${'$'}{3:-}" == "size" ]]; then
                  if [[ -z "${'$'}{4:-}" ]]; then
                    printf 'Physical size: 1080x2280\n'
                    override="${'$'}(tr -d '\r\n' < "${'$'}QA_WM_SIZE_STATE")"
                    [[ -z "${'$'}override" ]] || printf 'Override size: %s\n' "${'$'}override"
                  elif [[ "${'$'}4" == "reset" ]]; then
                    : > "${'$'}QA_WM_SIZE_STATE"
                  else
                    printf '%s\n' "${'$'}4" > "${'$'}QA_WM_SIZE_STATE"
                  fi
                elif [[ "${'$'}{1:-}" == "shell" && "${'$'}{2:-}" == "wm" && "${'$'}{3:-}" == "density" ]]; then
                  if [[ -z "${'$'}{4:-}" ]]; then
                    printf 'Physical density: 420\n'
                    override="${'$'}(tr -d '\r\n' < "${'$'}QA_WM_DENSITY_STATE")"
                    [[ -z "${'$'}override" ]] || printf 'Override density: %s\n' "${'$'}override"
                  elif [[ "${'$'}4" == "reset" ]]; then
                    : > "${'$'}QA_WM_DENSITY_STATE"
                  else
                    printf '%s\n' "${'$'}4" > "${'$'}QA_WM_DENSITY_STATE"
                  fi
                fi
            """.trimIndent()
        )
        val gradle = executable(
            root.resolve("gradle"),
            """
                #!/bin/bash
                printf 'gradle serial=%s args=%s\n' "${'$'}{ANDROID_SERIAL:-}" "${'$'}*" >> "${'$'}QA_TEST_LOG"
                test_filter=""
                for argument in "${'$'}@"; do
                  case "${'$'}argument" in
                    -Pandroid.testInstrumentationRunnerArguments.class=*)
                      test_filter="${'$'}{argument#*=}"
                      ;;
                  esac
                done
                if [[ -n "${'$'}test_filter" ]]; then
                  test_count="${'$'}(printf '%s' "${'$'}test_filter" | awk -F, '{print NF}')"
                  skipped="${'$'}QA_FILTERED_STAGE_SKIPPED"
                else
                  test_count=65
                  skipped="${'$'}((5 + QA_FULL_STAGE_SKIPPED))"
                fi
                mkdir -p "${'$'}QA_CONNECTED_RESULTS_DIR"
                printf '<testsuite tests="%s" failures="0" errors="0" skipped="%s"></testsuite>\n' \
                  "${'$'}test_count" "${'$'}skipped" \
                  > "${'$'}QA_CONNECTED_RESULTS_DIR/TEST-fixture.xml"
                printf 'launcher\n' > "${'$'}QA_FOREGROUND_STATE"
                if [[ $gradleExitCode == 0 && "${'$'}QA_ROTATION_DRIFT_AFTER_GRADLE" == "1" ]]; then
                  printf '1\n' > "${'$'}QA_DISPLAY_ROTATION_STATE"
                else
                  printf '0\n' > "${'$'}QA_DISPLAY_ROTATION_STATE"
                fi
                exit $gradleExitCode
            """.trimIndent()
        )
        return Fixture(
            adb = adb,
            gradle = gradle,
            log = log,
            rotationState = rotationState,
            accelerometerRotationState = accelerometerRotationState,
            stayAwakeState = stayAwakeState,
            wmSizeState = wmSizeState,
            wmDensityState = wmDensityState,
            fontScaleState = fontScaleState,
            displayRotationState = displayRotationState,
            foregroundState = foregroundState,
            evidenceDir = evidenceDir,
            connectedResultsDir = connectedResultsDir,
            qaApk = qaApk,
            apkPayloadDigest = apkPayloadDigest,
            tempDir = tempDir,
            forceLandscape = forceLandscape,
            swapLandscapeRotations = swapLandscapeRotations,
            rotationDriftsAfterGradle = rotationDriftsAfterGradle,
            isQemu = isQemu,
            filteredStageSkipped = filteredStageSkipped,
            fullStageSkipped = fullStageSkipped,
            probeForegroundFails = probeForegroundFails
        )
    }

    private fun runQaScript(fixture: Fixture): ScriptResult {
        val script = Path.of("../tools/qa_samsung_connected_tests.sh").toAbsolutePath().normalize()
        val outputFile = fixture.tempDir.resolve("qa-script-output.txt")
        val process = ProcessBuilder("/bin/bash", script.toString())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .apply {
                environment().remove("ANDROID_SERIAL")
                environment()["TMPDIR"] = fixture.tempDir.toString()
                environment()["V_SLOT_ADB"] = fixture.adb.toString()
                environment()["V_SLOT_GRADLE"] = fixture.gradle.toString()
                environment()["V_SLOT_SKIP_CONFLICT_STOP"] = "1"
                environment()["V_SLOT_SKIP_REPORT_CLEANUP"] = "1"
                environment()["V_SLOT_STOP_EXTERNAL_QA"] = "0"
                environment()["QA_TEST_LOG"] = fixture.log.toString()
                environment()["QA_ROTATION_STATE"] = fixture.rotationState.toString()
                environment()["QA_ACCELEROMETER_ROTATION_STATE"] = fixture.accelerometerRotationState.toString()
                environment()["QA_STAY_AWAKE_STATE"] = fixture.stayAwakeState.toString()
                environment()["QA_WM_SIZE_STATE"] = fixture.wmSizeState.toString()
                environment()["QA_WM_DENSITY_STATE"] = fixture.wmDensityState.toString()
                environment()["QA_FONT_SCALE_STATE"] = fixture.fontScaleState.toString()
                environment()["QA_DISPLAY_ROTATION_STATE"] = fixture.displayRotationState.toString()
                environment()["QA_FOREGROUND_STATE"] = fixture.foregroundState.toString()
                environment()["QA_FORCE_LANDSCAPE"] = if (fixture.forceLandscape) "1" else "0"
                environment()["QA_SWAP_LANDSCAPE_ROTATIONS"] = if (fixture.swapLandscapeRotations) "1" else "0"
                environment()["QA_ROTATION_DRIFT_AFTER_GRADLE"] = if (fixture.rotationDriftsAfterGradle) "1" else "0"
                environment()["QA_IS_QEMU"] = if (fixture.isQemu) "1" else "0"
                environment()["QA_FILTERED_STAGE_SKIPPED"] = if (fixture.filteredStageSkipped) "1" else "0"
                environment()["QA_FULL_STAGE_SKIPPED"] = if (fixture.fullStageSkipped) "1" else "0"
                environment()["QA_PROBE_FOREGROUND_FAILS"] = if (fixture.probeForegroundFails) "1" else "0"
                environment()["V_SLOT_ROTATION_VERIFY_ATTEMPTS"] = "1"
                environment()["V_SLOT_ROTATION_SETTLE_SECONDS"] = "0"
                environment()["V_SLOT_ROTATION_APPLY_ATTEMPTS"] = "1"
                environment()["V_SLOT_FOREGROUND_VERIFY_ATTEMPTS"] = "1"
                environment()["V_SLOT_FOREGROUND_SETTLE_SECONDS"] = "0"
                environment()["V_SLOT_QA_EVIDENCE_DIR"] = fixture.evidenceDir.toString()
                environment()["V_SLOT_QA_APK"] = fixture.qaApk.toString()
                environment()["V_SLOT_APK_PAYLOAD_DIGEST"] = fixture.apkPayloadDigest.toString()
                environment()["V_SLOT_CONNECTED_RESULTS_DIR"] = fixture.connectedResultsDir.toString()
                environment()["QA_CONNECTED_RESULTS_DIR"] = fixture.connectedResultsDir.toString()
            }
            .start()

        val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val output = if (Files.exists(outputFile)) outputFile.readText() else ""
        assertTrue("Samsung QA script timed out. Output:\n$output", completed)
        val log = if (Files.exists(fixture.log)) fixture.log.readText() else ""
        val evidence = mutableListOf<String>()
        if (Files.isDirectory(fixture.evidenceDir)) {
            Files.newDirectoryStream(fixture.evidenceDir, "*.json").use { paths ->
                paths.forEach { path -> evidence += path.readText() }
            }
        }
        return ScriptResult(exitCode = process.exitValue(), output = output, log = log, evidence = evidence)
    }

    private fun executable(path: Path, content: String): Path {
        path.writeText("$content\n")
        assertTrue("Could not make test fixture executable: $path", path.toFile().setExecutable(true))
        return path
    }

    private fun Path.readText(): String = String(Files.readAllBytes(this), Charsets.UTF_8)

    private fun Path.writeText(value: String) {
        Files.write(this, value.toByteArray(Charsets.UTF_8))
    }

    private data class Fixture(
        val adb: Path,
        val gradle: Path,
        val log: Path,
        val rotationState: Path,
        val accelerometerRotationState: Path,
        val stayAwakeState: Path,
        val wmSizeState: Path,
        val wmDensityState: Path,
        val fontScaleState: Path,
        val displayRotationState: Path,
        val foregroundState: Path,
        val evidenceDir: Path,
        val connectedResultsDir: Path,
        val qaApk: Path,
        val apkPayloadDigest: Path,
        val tempDir: Path,
        val forceLandscape: Boolean,
        val swapLandscapeRotations: Boolean,
        val rotationDriftsAfterGradle: Boolean,
        val isQemu: Boolean,
        val filteredStageSkipped: Boolean,
        val fullStageSkipped: Boolean,
        val probeForegroundFails: Boolean
    )

    private data class ScriptResult(
        val exitCode: Int,
        val output: String,
        val log: String,
        val evidence: List<String>
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val SAMSUNG_SERIAL = "SAMSUNG_TEST_SERIAL_0001"
        const val QA_APK_CONTENT = "qa-apk-fixture"
        const val GRADLE_FAILURE_EXIT_CODE = 9
        const val PROCESS_TIMEOUT_SECONDS = 60L
        const val PORTRAIT_SMOKE_TEST =
            "com.vslot.app.MainActivitySmokeTest#homeNavigationOpensSlotPaytableSettingsAndPrivacyFallback"
        const val LARGE_FONT_TESTS =
            "com.vslot.app.MainActivitySmokeTest#largeFontLegalCopyWrapsAndKeepsActionsReachable," +
                "com.vslot.app.MainActivitySmokeTest#largeFontDialogCopyWrapsAndKeepsActionsReachable," +
                "com.vslot.app.ThirdPartyNoticesTest#settingsOpensThirdPartyNoticesWithBundledNoticeText"
        const val COMPACT_SETTINGS_TEST =
            "com.vslot.app.MainActivitySmokeTest#settingsCompactPortraitKeepsScrollableControlsAboveSafetyFooter"
        const val COMPACT_LANDSCAPE_TEST =
            "com.vslot.app.MainActivitySmokeTest#compactLandscapeKeepsHomeAndSlotActionsReachable"
    }
}
