package com.vslot.app.security

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.readText

class CiWorkflowContractTest {
    private val androidCi = Path.of("../.github/workflows/android-ci.yml").readText()
    private val productionRelease = Path.of("../.github/workflows/production-release.yml").readText()
    private val dependabot = Path.of("../.github/dependabot.yml").readText()

    @Test
    fun thirdPartyActionsArePinnedToImmutableCommits() {
        listOf(androidCi, productionRelease).forEach { workflow ->
            val actionLines = workflow.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("uses:") }
                .toList()

            assertTrue(actionLines.isNotEmpty())
            actionLines.forEach { line ->
                val revision = line.substringAfter('@').substringBefore(' ')
                assertTrue("Action is not pinned to a commit: $line", revision.matches(Regex("[0-9a-f]{40}")))
            }
        }
    }

    @Test
    fun productionReleaseAttestsBundleAndRawEvidence() {
        assertTrue(productionRelease.contains("id-token: write"))
        assertTrue(productionRelease.contains("attestations: write"))
        assertTrue(
            productionRelease.contains(
                "uses: actions/attest@f7c74d28b9d84cb8768d0b8ca14a4bac6ef463e6"
            )
        )
        assertTrue(productionRelease.contains("app-release.aab"))
        assertTrue(productionRelease.contains("release-artifact-evidence.txt"))
        assertTrue(productionRelease.contains("bundletool-validation.txt"))
        assertTrue(productionRelease.contains("bundletool-base-manifest.xml"))
        assertTrue(productionRelease.contains("release-16k-page-size.txt"))
        assertTrue(productionRelease.contains("released-slot-math-v5-release-aab.txt"))
        assertTrue(productionRelease.contains("released-slot-math-v5-release-universal-apk.txt"))
        assertTrue(productionRelease.contains("release-app-set-id-dex-validation.txt"))
        assertTrue(productionRelease.contains("data-safety-raw-evidence.zip"))
        assertTrue(productionRelease.contains("physical-samsung/raw-evidence.zip"))
        assertTrue(productionRelease.contains("release-provenance.txt"))
    }

    @Test
    fun pullRequestsCannotAccessTheProductionReleaseJob() {
        assertTrue(androidCi.contains("pull_request:"))
        assertFalse(androidCi.contains("pull_request_target:"))
        assertFalse(productionRelease.contains("pull_request:"))
        assertTrue(productionRelease.contains("environment: production"))
        assertTrue(productionRelease.contains("test \"\$GITHUB_REF\" = 'refs/heads/main'"))
        assertTrue(productionRelease.contains("test \"\$GITHUB_REF_PROTECTED\" = 'true'"))
        assertTrue(productionRelease.contains("test \"\$(git rev-parse --verify HEAD)\" = \"\$GITHUB_SHA\""))
    }

    @Test
    fun ciRunsEveryNonProductionReleaseGate() {
        val requiredTasks = setOf(
            ":app:testDebugUnitTest",
            ":app:testQaUnitTest",
            ":app:testReleaseUnitTest",
            ":app:lintDebug",
            ":app:lintQa",
            ":app:lintRelease",
            ":app:assembleDebug",
            ":app:assembleQa",
            ":app:minifyReleaseWithR8",
            ":verifyReleaseSecurityEvidence"
        )

        assertEquals(emptySet<String>(), requiredTasks.filterNot(androidCi::contains).toSet())
        assertTrue(androidCi.contains("build-tools/36.0.0/aapt2"))
        assertTrue(androidCi.contains("Pkg.Revision=20.0"))
        assertTrue(androidCi.contains("Pkg.Path=cmdline-tools;20.0"))
        assertTrue(androidCi.contains("-Pandroid.aapt2FromMavenOverride="))
        assertTrue(productionRelease.contains(":app:bundleRelease"))
        assertTrue(productionRelease.contains("build-tools/36.0.0/aapt2"))
        assertTrue(productionRelease.contains("Pkg.Revision=20.0"))
        assertTrue(productionRelease.contains("Pkg.Path=cmdline-tools;20.0"))
        assertTrue(productionRelease.contains("-Pandroid.aapt2FromMavenOverride="))
        assertTrue(productionRelease.contains("sha256sum \"\$ANDROID_HOME/platforms/android-36/android.jar\""))
        assertTrue(productionRelease.contains("sha256sum \"\$ANDROID_HOME/build-tools/36.0.0/aapt2\""))
        assertTrue(productionRelease.contains("bundletool-all-1.18.3.jar"))
        assertTrue(productionRelease.contains("ndk;\$NDK_VERSION"))
        assertTrue(productionRelease.contains("NDK_VERSION='27.0.12077973'"))
        assertTrue(productionRelease.contains("V_SLOT_LLVM_READELF="))
        assertTrue(androidCi.contains(":app:ciPixel2Api35QaAndroidTest"))
        assertTrue(androidCi.contains("managed-device-smoke:"))
        assertTrue(androidCi.contains("managed-device-sdk-boundaries:"))
        assertTrue(androidCi.contains("managed-device-full:"))
        assertTrue(androidCi.contains("notClass=com.vslot.app.SlotFrameMetricsTest"))
        assertTrue(
            productionRelease.contains(
                "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
            )
        )
        assertTrue(productionRelease.contains("curl --proto '=https' --tlsv1.2 --fail"))
    }

    @Test
    fun runtimeInstrumentationCoversSdkBoundariesAndKeepsApi35Qa() {
        val api35Smoke = androidCi
            .substringAfter("  managed-device-smoke:")
            .substringBefore("\n  managed-device-sdk-boundaries:")
        val sdkBoundaries = androidCi
            .substringAfter("  managed-device-sdk-boundaries:")
            .substringBefore("\n  managed-device-full:")
        val api35Full = androidCi.substringAfter("  managed-device-full:")

        assertTrue(api35Smoke.contains("github.event_name == 'pull_request' || github.event_name == 'push'"))
        assertTrue(api35Smoke.contains("apiLevel = 35"))
        assertTrue(api35Smoke.contains(":app:ciPixel2Api35QaAndroidTest"))
        assertTrue(api35Smoke.contains("testInstrumentationRunnerArguments.class="))

        assertTrue(sdkBoundaries.contains("github.event_name == 'push'"))
        assertTrue(sdkBoundaries.contains("api: [26, 36]"))
        assertTrue(sdkBoundaries.contains("apiLevel = \${{ matrix.api }}"))
        assertTrue(sdkBoundaries.contains(":app:ciPixel2Api\${{ matrix.api }}QaAndroidTest"))
        assertTrue(sdkBoundaries.contains("testInstrumentationRunnerArguments.class="))

        assertTrue(api35Full.contains("github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'"))
        assertTrue(api35Full.contains("apiLevel = 35"))
        assertTrue(api35Full.contains(":app:ciPixel2Api35QaAndroidTest"))
        assertTrue(api35Full.contains("notClass=com.vslot.app.SlotFrameMetricsTest"))
    }

    @Test
    fun productionReleaseRequiresSuccessfulManagedDeviceJobsForExactCommit() {
        val gate = productionRelease
            .substringAfter("  managed-device-gate:")
            .substringBefore("\n  bundle:")
        val bundleHeader = productionRelease
            .substringAfter("  bundle:")
            .substringBefore("\n    runs-on:")

        assertTrue(gate.contains("needs: vulnerability-scan"))
        assertTrue(gate.contains("actions: read"))
        assertTrue(gate.contains("actions/workflows/android-ci.yml/runs"))
        assertTrue(gate.contains("-f head_sha=\"\$GITHUB_SHA\""))
        assertTrue(gate.contains("-f branch=main"))
        assertTrue(gate.contains("-f event=push"))
        assertTrue(gate.contains(".head_sha == \$sha"))
        assertTrue(gate.contains(".conclusion == \"success\""))
        assertTrue(gate.contains("actions/runs/\$run_id/jobs"))
        assertTrue(gate.contains("passed(\"Critical QA smoke (managed device)\")"))
        assertTrue(gate.contains("passed(\"Runtime SDK boundary smoke (API 26)\")"))
        assertTrue(gate.contains("passed(\"Runtime SDK boundary smoke (API 36)\")"))
        assertTrue(bundleHeader.contains("- vulnerability-scan"))
        assertTrue(bundleHeader.contains("- managed-device-gate"))
    }

    @Test
    fun knownVulnerabilitiesBlockCiAndProductionRelease() {
        listOf(androidCi, productionRelease).forEach { workflow ->
            val scanJob = workflow
                .substringAfter("  vulnerability-scan:")
                .substringBefore("\n\n  ")
            assertTrue(
                workflow.contains(
                    "uses: google/osv-scanner-action/.github/workflows/" +
                        "osv-scanner-reusable.yml@9a498708959aeaef5ef730655706c5a1df1edbc2"
                )
            )
            assertTrue(
                workflow.contains("--lockfile=osv-scanner:osv-scanner-custom.json")
            )
            assertFalse(workflow.contains("--recursive"))
            assertTrue(workflow.contains("upload-sarif: false"))
            assertTrue(workflow.contains("fail-on-vuln: true"))
            assertTrue(workflow.contains("needs: vulnerability-scan"))
            assertTrue(scanJob.contains("actions: read"))
            assertTrue(scanJob.contains("contents: read"))
            assertTrue(scanJob.contains("security-events: write"))
        }
        assertTrue(androidCi.contains("schedule:"))
        assertTrue(androidCi.contains("cron: '17 4 * * *'"))
        assertTrue(productionRelease.contains("V_SLOT_OSV_SCAN_JOB_RESULT: \${{ needs.vulnerability-scan.result }}"))
        assertTrue(productionRelease.contains("test \"\$V_SLOT_OSV_SCAN_JOB_RESULT\" = success"))
        assertTrue(productionRelease.contains("schema=v-slot-osv-scan-evidence-v1"))
        assertTrue(productionRelease.contains("inventory_sha256=\"\$(sha256sum osv-scanner-custom.json"))
    }

    @Test
    fun dependencyUpdatesCoverBuildAndWorkflowDependencies() {
        assertTrue(dependabot.contains("package-ecosystem: gradle"))
        assertTrue(dependabot.contains("package-ecosystem: github-actions"))
        assertEquals(2, Regex("interval: weekly").findAll(dependabot).count())
        assertEquals(2, Regex("target-branch: main").findAll(dependabot).count())
    }

    @Test
    fun releaseSecretsAreOnlyMaterializedInsideTheProtectedJob() {
        val jobEnvironment = productionRelease.substringAfter("    env:").substringBefore("\n    steps:")
        assertFalse(jobEnvironment.contains("secrets."))
        assertFalse(jobEnvironment.contains("runner.temp"))
        assertTrue(jobEnvironment.contains("\${{ github.workspace }}/.release-evidence/ci"))
        assertTrue(productionRelease.contains("mkdir -p \"\$V_SLOT_PROTECTED_DIR\""))
        assertTrue(productionRelease.contains("secrets.V_SLOT_GOOGLE_SERVICES_JSON_BASE64"))
        assertTrue(productionRelease.contains("secrets.V_SLOT_RELEASE_KEYSTORE_BASE64"))
        assertTrue(productionRelease.contains("secrets.V_SLOT_DATA_SAFETY_EVIDENCE_JSON_BASE64"))
        assertTrue(productionRelease.contains("vars.V_SLOT_DATA_SAFETY_EVIDENCE_SHA256"))
        assertTrue(productionRelease.contains("secrets.V_SLOT_DATA_SAFETY_RAW_EVIDENCE_ZIP_BASE64"))
        assertTrue(productionRelease.contains("vars.V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256"))
        assertTrue(productionRelease.contains("secrets.V_SLOT_SAMSUNG_QA_EVIDENCE_JSON_BASE64"))
        assertTrue(productionRelease.contains("vars.V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256"))
        assertTrue(productionRelease.contains("secrets.V_SLOT_PROCESS_DEATH_EVIDENCE_JSON_BASE64"))
        assertTrue(productionRelease.contains("vars.V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256"))
        assertTrue(productionRelease.contains("secrets.V_SLOT_FRAME_METRICS_EVIDENCE_JSON_BASE64"))
        assertTrue(productionRelease.contains("vars.V_SLOT_FRAME_METRICS_EVIDENCE_SHA256"))
        assertTrue(productionRelease.contains("secrets.V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_ZIP_BASE64"))
        assertTrue(productionRelease.contains("vars.V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256"))
        assertTrue(productionRelease.contains("chmod 600"))
        assertTrue(productionRelease.contains("umask 077"))
        assertTrue(productionRelease.contains("rm -f app/src/release/google-services.json"))
        assertTrue(productionRelease.contains("\"\$V_SLOT_DATA_SAFETY_EVIDENCE_FILE\""))
        assertTrue(productionRelease.contains("\"\$V_SLOT_SAMSUNG_QA_EVIDENCE_FILE\""))
        assertTrue(productionRelease.contains("\"\$V_SLOT_PROCESS_DEATH_EVIDENCE_FILE\""))
        assertTrue(productionRelease.contains("\"\$V_SLOT_FRAME_METRICS_EVIDENCE_FILE\""))
        assertTrue(productionRelease.contains("\"\$V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_FILE\""))
        val cleanup = productionRelease.indexOf("Remove protected release files before external actions")
        val attestation = productionRelease.indexOf("Attest release bundle and security evidence")
        val upload = productionRelease.indexOf("Upload immutable release evidence")
        assertTrue(cleanup >= 0 && attestation > cleanup && upload > attestation)
        assertTrue(productionRelease.contains("V_SLOT_ANDROID_PLATFORM_36_JAR_SHA256"))
        assertTrue(productionRelease.contains("V_SLOT_ANDROID_BUILD_TOOLS_36_AAPT2_SHA256"))
        assertTrue(productionRelease.contains("V_SLOT_ANDROID_BUILD_TOOLS_36_DEXDUMP_SHA256"))
        assertFalse(androidCi.contains("secrets."))
    }
}
