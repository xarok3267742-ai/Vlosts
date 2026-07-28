package com.vslot.app.security

import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import kotlin.io.path.readText

class ReleaseSecurityGradleContractTest {
    private val rootBuild = Path.of("../build.gradle.kts").readText()
    private val appBuild = Path.of("build.gradle.kts").readText()
    private val dataSafetyTemplate = JSONObject(
        Path.of("../docs/store/DATA_SAFETY_EVIDENCE_TEMPLATE.json").readText()
    )

    @Test
    fun androidLintRemainsStrictForEveryReleaseCandidate() {
        assertTrue(appBuild.contains("abortOnError = true"))
        assertTrue(appBuild.contains("checkReleaseBuilds = true"))
        assertTrue(appBuild.contains("warningsAsErrors = true"))
    }

    @Test
    fun secretScanCoversHistoryIndexTrackedWorktreeAndUntrackedCandidates() {
        assertTrue(rootBuild.contains("gitBytes(\"ls-files\", \"--stage\", \"-z\")"))
        assertTrue(rootBuild.contains("ProcessBuilder(\"git\", \"cat-file\", \"--batch\")"))
        assertTrue(rootBuild.contains("\"--cached\","))
        assertTrue(rootBuild.contains("\"--others\","))
        assertTrue(rootBuild.contains("\"--exclude-standard\","))
        assertTrue(rootBuild.contains("scan(relativePath, \"index\", bytes)"))
        assertTrue(rootBuild.contains("scan(relativePath, \"worktree\", bytes)"))
        assertTrue(rootBuild.contains("\"--branches\",") && rootBuild.contains("\"--remotes\",") && rootBuild.contains("\"--tags\""))
        assertTrue(!rootBuild.contains("gitBytes(\"rev-list\", \"--objects\", \"--all\")"))
        assertTrue(rootBuild.contains("scan(historicalPath, \"history:\$objectId\", bytes)"))
        assertTrue(rootBuild.contains("history-blob-count="))
        assertTrue(rootBuild.contains("history-message-count="))
        assertTrue(rootBuild.contains("tag-message-count="))
        assertTrue(rootBuild.contains("--format=%H%x00%B%x00"))
        assertTrue(rootBuild.contains("--format=%(objectname)%00%(contents)%00"))
        assertTrue(rootBuild.contains("requires a conflict-free Git index"))
        assertTrue(rootBuild.contains("*.p8"))
        assertTrue(rootBuild.contains("*.der"))
        assertTrue(rootBuild.contains("Charsets.UTF_16LE"))
        assertTrue(rootBuild.contains("Charsets.UTF_16BE"))
        assertFalse(rootBuild.contains("if (bytes.any { it == 0.toByte() }) return"))
    }

    @Test
    fun scanUsesReviewedHighConfidenceDetectorFamiliesWithoutLoggingValues() {
        val detectorLabels = setOf(
            "AWS access key",
            "Google API key",
            "Google OAuth client secret",
            "GitHub token",
            "GitLab token",
            "Slack token",
            "Stripe live key",
            "OpenAI API key",
            "npm token",
            "PyPI token",
            "SendGrid API key",
            "Firebase server key",
            "remote root login block",
            "base64-encoded JKS keystore",
            "high-entropy credential assignment"
        )

        detectorLabels.forEach { label ->
            assertTrue("Missing detector family: $label", rootBuild.contains("\"$label\""))
        }
        assertTrue(rootBuild.contains("\$relativePath@\$origin"))
        assertFalse(rootBuild.contains("match.value"))
        assertFalse(rootBuild.contains("groupValues[1]}"))
    }

    @Test
    fun releaseSecurityProducesDeterministicContentAddressedEvidence() {
        assertTrue(rootBuild.contains("schema=workspace-secret-scan-v3"))
        assertTrue(rootBuild.contains("history-status="))
        assertTrue(rootBuild.contains("!hasReachableHistory -> \"INCOMPLETE\""))
        assertTrue(rootBuild.contains("requires a complete passing reachable-history secret scan"))
        assertTrue(rootBuild.contains("candidate-snapshot-sha256="))
        assertTrue(rootBuild.contains("outputs.upToDateWhen { false }"))
        assertTrue(rootBuild.contains("tasks.register(\"verifyReleaseSecurityEvidence\")"))
        assertTrue(rootBuild.contains("schema=v-slot-release-security-evidence-v1"))
        assertTrue(rootBuild.contains("\"release-dependency-inventory\" to"))
        assertTrue(rootBuild.contains("\"release-license-evidence\" to"))
        assertTrue(rootBuild.contains("\"release-osv-inventory\" to"))
        assertTrue(rootBuild.contains("\"workspace-secret-scan\" to"))
        assertTrue(rootBuild.contains("appendLine(\"\$name-sha256=\${sha256(file.readBytes())}\")"))
        assertFalse(rootBuild.contains("Instant.now"))
        assertTrue(appBuild.contains("rootProject.tasks.named(\"verifyReleaseSecurityEvidence\")"))
    }

    @Test
    fun releaseBundleRequiresSuccessfulCommitBoundOsvScanEvidence() {
        assertTrue(rootBuild.contains("tasks.register(\"verifyReleaseOsvScanEvidence\")"))
        assertTrue(rootBuild.contains("v-slot-osv-scan-evidence-v1"))
        assertTrue(rootBuild.contains("9a498708959aeaef5ef730655706c5a1df1edbc2"))
        assertTrue(rootBuild.contains("fields[\"result\"] != \"success\""))
        assertTrue(rootBuild.contains("fields[\"commit\"]?.lowercase() != head"))
        assertTrue(rootBuild.contains("fields[\"inventory-sha256\"]?.lowercase()"))
        assertTrue(rootBuild.contains("Release OSV scan evidence contains duplicate fields."))
        assertTrue(appBuild.contains("\":verifyReleaseOsvScanEvidence\""))
        assertTrue(appBuild.contains("rootProject.tasks.named(\"verifyReleaseOsvScanEvidence\")"))
        assertTrue(appBuild.contains("\"release-osv-scan\" to"))
    }

    @Test
    fun releaseProvenanceRejectsHiddenInputsAndBindsProductionConfiguration() {
        assertTrue(rootBuild.contains("gitOutput(\"ls-files\", \"-v\")"))
        assertTrue(rootBuild.contains("hidden index flags"))
        assertTrue(rootBuild.contains("--ignored"))
        assertTrue(rootBuild.contains("app/src/release/google-services.json"))
        assertTrue(rootBuild.contains("schema=v-slot-release-provenance-v6"))
        assertTrue(rootBuild.contains("android-platform-36-jar-sha256"))
        assertTrue(rootBuild.contains("android-build-tools-36.0.0-aapt2-sha256"))
        assertTrue(rootBuild.contains("android-build-tools-36.0.0-dexdump-sha256"))
        assertTrue(rootBuild.contains("release-google-services-sha256="))
        assertTrue(rootBuild.contains("release-configuration-sha256="))
        assertTrue(rootBuild.contains("V_SLOT_FIREBASE_PROJECT_ID"))
        assertTrue(rootBuild.contains("V_SLOT_FIREBASE_APP_ID"))
        assertTrue(rootBuild.contains("V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE"))
        assertTrue(rootBuild.contains("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256"))
        assertTrue(rootBuild.contains("V_SLOT_ANDROID_PLATFORM_36_JAR_SHA256"))
        assertTrue(rootBuild.contains("V_SLOT_ANDROID_BUILD_TOOLS_36_AAPT2_SHA256"))
        assertTrue(rootBuild.contains("V_SLOT_ANDROID_BUILD_TOOLS_36_DEXDUMP_SHA256"))
        assertTrue(rootBuild.contains("androidPlatformJarSha256 != expectedAndroidPlatformJarSha256"))
        assertTrue(rootBuild.contains("androidAapt2Sha256 != expectedAndroidAapt2Sha256"))
        assertTrue(rootBuild.contains("androidDexdumpSha256 != expectedAndroidDexdumpSha256"))
        assertTrue(rootBuild.contains("gradleProperty(\"android.aapt2FromMavenOverride\")"))
        assertTrue(rootBuild.contains("configuredAapt2Override.canonicalFile != androidAapt2.canonicalFile"))
        assertTrue(rootBuild.contains("android-aapt2-source"))
        assertTrue(rootBuild.contains("val requiredReleaseBundletoolVersion = \"1.18.3\""))
        assertTrue(rootBuild.contains("bundletool-sha256"))
        assertTrue(rootBuild.contains("bundletoolSha256 != requiredReleaseBundletoolSha256"))
    }

    @Test
    fun releaseProvenancePinsTheReviewedJavaAndGradleWrapper() {
        assertTrue(rootBuild.contains("val requiredReleaseJavaMajor = 17"))
        assertTrue(rootBuild.contains("val requiredReleaseGradleVersion = \"8.14.5\""))
        assertTrue(
            rootBuild.contains(
                "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854"
            )
        )
        assertTrue(rootBuild.contains("actualJavaMajor != requiredReleaseJavaMajor"))
        assertTrue(rootBuild.contains("gradle.gradleVersion != requiredReleaseGradleVersion"))
        assertTrue(rootBuild.contains("gradle/wrapper/gradle-wrapper.properties"))
        assertTrue(rootBuild.contains("wrapperDistributionUrl != expectedDistributionUrl"))
        assertTrue(rootBuild.contains("wrapperProperties.getProperty(\"validateDistributionUrl\") != \"true\""))
        assertTrue(rootBuild.contains("Production release requires Java"))
        assertTrue(rootBuild.contains("Production release requires Gradle Wrapper"))
    }

    @Test
    fun releaseProvenanceRecordsOnlySanitizedEnvironmentIdentity() {
        val evidenceKeys = listOf(
            "java-runtime-version",
            "java-vendor",
            "java-major",
            "gradle-version",
            "gradle-wrapper-distribution-sha256",
            "os-name",
            "os-version",
            "os-arch",
            "github-image-os",
            "github-image-version"
        )
        evidenceKeys.forEach { key ->
            assertTrue("Missing release provenance evidence: $key", rootBuild.contains("\"$key\""))
        }
        assertTrue(rootBuild.contains("fun safeProvenanceValue(name: String, rawValue: String?)"))
        assertTrue(rootBuild.contains("System.getenv(\"ImageOS\")"))
        assertTrue(rootBuild.contains("System.getenv(\"ImageVersion\")"))
        assertFalse(rootBuild.contains("System.getProperty(\"java.home\")"))
        assertFalse(rootBuild.contains("System.getProperty(\"user.dir\")"))
        assertFalse(rootBuild.contains("System.getenv().forEach"))

        val commit = rootBuild.indexOf("appendLine(\"commit=\${head.lowercase()}\")")
        val environment = rootBuild.indexOf("environmentEvidence.forEach", startIndex = commit)
        val runner = rootBuild.indexOf("githubRunnerEvidence.forEach", startIndex = environment)
        val configuration = rootBuild.indexOf(
            "appendLine(\"release-configuration-sha256=\$releaseConfigurationSha256\")",
            startIndex = runner
        )
        assertTrue(commit >= 0)
        assertTrue(environment > commit)
        assertTrue(runner > environment)
        assertTrue(configuration > runner)
    }

    @Test
    fun productionReadinessRequiresCurrentVersionDataSafetyReview() {
        assertTrue(
            appBuild.contains(
                "val dataSafetyReviewedVersionCode = " +
                    "releaseConfigValue(\"V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE\")"
            )
        )
        assertTrue(appBuild.contains("dataSafetyReviewedVersionCode.isBlank()"))
        assertTrue(appBuild.contains("dataSafetyReviewedVersionCode != vSlotVersionCode.toString()"))
        assertTrue(appBuild.contains("current versionCode \$vSlotVersionCode required"))
        assertTrue(appBuild.contains("V_SLOT_DATA_SAFETY_EVIDENCE_FILE"))
        assertTrue(appBuild.contains("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256"))
        assertTrue(appBuild.contains("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_FILE"))
        assertTrue(appBuild.contains("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256"))
        assertTrue(appBuild.contains("fun dataSafetyEvidenceIssues"))
        assertTrue(appBuild.contains("fun dataSafetyRawEvidenceIssues"))
        assertTrue(appBuild.contains("manifests/data-safety.json"))
        assertTrue(appBuild.contains("raw/network-capture.pcapng"))
        assertTrue(appBuild.contains("raw/play-console-export.csv"))
        assertTrue(appBuild.contains("raw/privacy-policy-snapshot.html"))
        assertTrue(appBuild.contains("raw/evidence-archive.zip"))
        assertTrue(appBuild.contains("reviewed_commit must match release HEAD"))
        assertTrue(appBuild.contains("privacy_policy_url must match the production release URL"))
        assertTrue(appBuild.contains("check \$check must be true"))
        assertTrue(appBuild.contains("tasks.register(\"verifyDataSafetyEvidence\")"))
        assertTrue(appBuild.contains("verifyDataSafetyEvidenceValidatorContract"))
        assertTrue(appBuild.contains("Data Safety evidence validator accepted a mismatched release commit."))
        assertTrue(appBuild.contains("Data Safety evidence validator did not reject a mismatched checksum."))
        assertTrue(appBuild.contains("Data Safety raw evidence validator accepted tampered capture bytes."))
        assertTrue(appBuild.contains("Data Safety raw evidence validator accepted an incomplete archive."))
    }

    @Test
    fun physicalSamsungRawEvidenceRequiresExactTestcaseIds() {
        assertTrue(appBuild.contains("fun expectedPhysicalSamsungStageTestIds"))
        assertTrue(appBuild.contains("document.getElementsByTagName(\"testcase\")"))
        assertTrue(appBuild.contains("\"\$className#\$testName\""))
        assertTrue(appBuild.contains("actualTestIds != expectedTestIds"))
        assertTrue(appBuild.contains("allTestIds.size == 62"))
        assertTrue(appBuild.contains("legacyPrimarySchemasUpgradeInPlaceAndSurviveDurableRewrite"))
        assertTrue(
            appBuild.contains(
                "Physical Samsung evidence validator accepted XML counters without testcase IDs."
            )
        )
    }

    @Test
    fun assetRightsReviewIsBoundToTheCandidateInventoryAndCommit() {
        assertTrue(appBuild.contains("V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE"))
        assertTrue(appBuild.contains("V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE"))
        assertTrue(appBuild.contains("V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256"))
        assertTrue(appBuild.contains("fun assetProvenanceInventoryIssues"))
        assertTrue(appBuild.contains("fun assetRightsEvidenceIssues"))
        assertTrue(appBuild.contains("inventory_sha256 must match the checked-in provenance inventory"))
        assertTrue(appBuild.contains("check \$check must be true"))
        assertTrue(appBuild.contains("tasks.register(\"verifyAssetRightsEvidence\")"))
        assertTrue(appBuild.contains("verifyAssetRightsEvidenceValidatorContract"))
        assertTrue(appBuild.contains("Asset rights evidence validator accepted a mismatched release commit."))
        assertTrue(appBuild.contains("Asset rights evidence validator accepted the incomplete template."))
    }

    @Test
    fun dataSafetyEvidenceTemplateCoversEveryReleaseReviewBoundary() {
        assertTrue(dataSafetyTemplate.getInt("schema_version") == 1)
        assertTrue(dataSafetyTemplate.getString("application_id") == "com.vslot.app")
        val checks = dataSafetyTemplate.getJSONObject("checks")
        val requiredChecks = setOf(
            "fresh_install_pre_consent_no_telemetry",
            "analytics_decline_no_telemetry",
            "analytics_opt_in_expected_telemetry_only",
            "analytics_revoke_no_future_telemetry",
            "push_opt_in_expected_registration_only",
            "notification_denied_no_registration",
            "notification_disabled_no_registration",
            "cleartext_absent",
            "advertising_id_absent",
            "location_absent",
            "unexpected_pii_absent",
            "production_dashboard_reviewed",
            "privacy_policy_matches",
            "play_console_preview_reviewed"
        )
        assertTrue(checks.keys().asSequence().toSet() == requiredChecks)
        requiredChecks.forEach { check -> assertFalse(checks.getBoolean(check)) }
        listOf(
            "network_capture_sha256",
            "play_console_export_sha256",
            "privacy_policy_snapshot_sha256",
            "evidence_archive_sha256"
        ).forEach { field -> assertTrue(dataSafetyTemplate.has(field)) }
    }

    @Test
    fun productionReleaseRequiresCommitAndApkBoundPhysicalSamsungEvidence() {
        listOf(
            "V_SLOT_SAMSUNG_QA_EVIDENCE_FILE",
            "V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256",
            "V_SLOT_PROCESS_DEATH_EVIDENCE_FILE",
            "V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256",
            "V_SLOT_FRAME_METRICS_EVIDENCE_FILE",
            "V_SLOT_FRAME_METRICS_EVIDENCE_SHA256",
            "V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_FILE",
            "V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256"
        ).forEach { input -> assertTrue(appBuild.contains(input)) }
        assertTrue(appBuild.contains("fun physicalSamsungEvidenceIssues"))
        assertTrue(appBuild.contains("source.git_commit must match release HEAD"))
        assertTrue(appBuild.contains("apk.payload_sha256 must match the release-gated QA APK payload"))
        assertTrue(appBuild.contains("qa_profile physical_samsung required"))
        assertTrue(appBuild.contains("three distinct positive process IDs required"))
        assertTrue(appBuild.contains("must run all 62 tests"))
        assertTrue(appBuild.contains("physical Samsung frame metrics exceed release limits"))
        assertTrue(appBuild.contains("raw connected stage"))
        assertTrue(appBuild.contains("process-death raw log missing"))
        assertTrue(appBuild.contains("tasks.register(\"verifyPhysicalSamsungEvidence\")"))
        assertTrue(appBuild.contains("verifyPhysicalSamsungEvidenceValidatorContract"))
        assertTrue(rootBuild.contains("V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256"))
        assertTrue(rootBuild.contains("V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256"))
        assertTrue(rootBuild.contains("V_SLOT_FRAME_METRICS_EVIDENCE_SHA256"))
        assertTrue(rootBuild.contains("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256"))
    }

    @Test
    fun postBuildEvidenceBindsTheSignedBundleToVerificationOutputs() {
        assertTrue(appBuild.contains("tasks.register(\"generateReleaseArtifactEvidence\")"))
        assertTrue(appBuild.contains("schema=v-slot-release-artifact-evidence-v7"))
        assertTrue(appBuild.contains("outputs/bundle/release/app-release.aab"))
        assertTrue(appBuild.contains("tasks.register(\"verifyReleaseBundleWithBundletool\")"))
        assertTrue(appBuild.contains("runBundletool(\"validate\""))
        assertTrue(appBuild.contains("\"dump\","))
        assertTrue(appBuild.contains("\":app:verifyReleaseBundleWithBundletool\""))
        assertTrue(appBuild.contains("\"bundletool-validation\" to"))
        assertTrue(appBuild.contains("\"bundletool-base-manifest\" to"))
        assertTrue(appBuild.contains("upload-certificate-sha256="))
        assertTrue(appBuild.contains("JarFile(bundle, true)"))
        assertTrue(appBuild.contains("certificate.encoded"))
        assertTrue(appBuild.contains("digests += checkNotNull(normalizedSha256(digest))"))
        assertTrue(appBuild.contains("Certificate SHA-256 normalization failed."))
        val entryRead = appBuild.indexOf("jar.getInputStream(entry).use")
        val certificateRead = appBuild.indexOf(
            "val entryCertificates = entry.certificates.orEmpty()",
            startIndex = entryRead
        )
        assertTrue(entryRead >= 0)
        assertTrue(appBuild.indexOf("while (input.read(buffer) != -1)", entryRead) in entryRead until certificateRead)
        assertTrue(certificateRead > entryRead)
        assertTrue(appBuild.contains("if (entryCertificates.isEmpty())"))
        assertTrue(appBuild.contains("unsignedBundleEntries += entry.name"))
        assertTrue(appBuild.contains("Signed release bundle contains unsigned non-metadata entries"))
        assertTrue(appBuild.contains("requiredTaskPaths"))
        assertTrue(appBuild.contains("releaseArtifactEvidenceFile.get().asFile.delete()"))
        assertTrue(appBuild.contains("!state.executed"))
        assertTrue(appBuild.contains("state.failure != null"))
        assertTrue(appBuild.contains("test-results/testReleaseUnitTest"))
        assertTrue(appBuild.contains("lint-results-release.xml"))
        assertTrue(appBuild.contains("processReleaseManifest/AndroidManifest.xml"))
        assertTrue(appBuild.contains("release-security-evidence.txt"))
        assertTrue(appBuild.contains("\"osv-release-runtime-inventory\" to reviewedReleaseOsvInventoryFile"))
        assertTrue(appBuild.contains("\"asset-rights-evidence\" to archivedAssetRightsEvidenceFile"))
        assertTrue(appBuild.contains("\"data-safety-evidence\" to archivedDataSafetyEvidenceFile"))
        assertTrue(appBuild.contains("\"physical-samsung\" to archivedPhysicalSamsungEvidenceDirectory"))
        assertTrue(appBuild.contains("\"store-screenshot-qa-apk\" to storeScreenshotQaApkValidationReportFile"))
        assertTrue(appBuild.contains("\"release-app-set-id-dex\" to releaseAppSetIdDexReportFile"))
        assertTrue(appBuild.contains("\":app:verifyDataSafetyEvidence\""))
        assertTrue(appBuild.contains("\":app:verifyAssetRightsEvidence\""))
        assertTrue(appBuild.contains("\":app:verifyPhysicalSamsungEvidence\""))
        assertTrue(appBuild.contains("finalizedBy(generateReleaseArtifactEvidence)"))
    }

    @Test
    fun optimizedReleaseDexKeepsAppSetIdFailClosed() {
        assertTrue(appBuild.contains("verifyAppSetIdCompatValidatorContract"))
        assertTrue(appBuild.contains("tasks.register<org.gradle.api.tasks.Exec>(\n    \"verifyReleaseAppSetIdDisabled\""))
        assertTrue(appBuild.contains("tools/verify_appsetid_compat.py"))
        assertTrue(appBuild.contains("dependsOn(\"minifyReleaseWithR8\", verifyAppSetIdCompatValidatorContract)"))
        assertTrue(appBuild.contains("build-tools/\$vSlotBuildToolsVersion/\$dexdumpName"))
        assertTrue(appBuild.contains("release-app-set-id-dex-validation.txt"))
        assertTrue(appBuild.contains("\":app:verifyReleaseAppSetIdDisabled\""))
    }

    @Test
    fun deliveredBundleManifestPinsSdkAndVersionIdentity() {
        assertTrue(appBuild.contains("val vSlotMinSdk = 26"))
        assertTrue(appBuild.contains("getAttributeNS(androidNamespace, \"versionCode\")"))
        assertTrue(appBuild.contains("getAttributeNS(androidNamespace, \"versionName\")"))
        assertTrue(appBuild.contains("getAttributeNS(androidNamespace, \"minSdkVersion\")"))
        assertTrue(appBuild.contains("getAttributeNS(androidNamespace, \"targetSdkVersion\")"))
        assertTrue(appBuild.contains("deliveredVersionCode != vSlotVersionCode"))
        assertTrue(appBuild.contains("deliveredVersionName != vSlotVersionName"))
        assertTrue(appBuild.contains("deliveredMinSdk != vSlotMinSdk"))
        assertTrue(appBuild.contains("deliveredTargetSdk != vSlotStoreSdk"))
        assertTrue(appBuild.contains("schema=v-slot-bundletool-validation-v2"))
    }

    @Test
    fun storeScreenshotsAreBoundToTheReproducibleQaAppAndInstrumentationPayloads() {
        assertTrue(appBuild.contains("fun storeScreenshotQaArtifactIssues"))
        assertTrue(appBuild.contains("tasks.register(\n    \"verifyStoreScreenshotQaApkValidatorContract\""))
        assertTrue(appBuild.contains("tasks.register(\n    \"verifyStoreScreenshotsAgainstQaApk\""))
        assertTrue(appBuild.contains("Play screenshots must be recaptured from the exact QA APK payload"))
        assertTrue(appBuild.contains("Play screenshots must be recaptured from the exact QA instrumentation APK payload"))
        assertTrue(appBuild.contains("\"assembleQaAndroidTest\""))
        assertTrue(appBuild.contains("store-screenshot-qa-apk-validation.txt"))
        assertTrue(appBuild.contains("\":app:verifyStoreScreenshotsAgainstQaApk\""))
    }

    @Test
    fun release16kPageSizeGateValidatesTheDeliveredUniversalApkFailClosed() {
        assertTrue(appBuild.contains("tasks.register(\"verifyRelease16kPageSize\")"))
        assertTrue(appBuild.contains("tasks.register(\n    \"verifyRelease16kPageSizeValidatorContract\""))
        assertTrue(appBuild.contains("\"build-apks\","))
        assertTrue(appBuild.contains("\"--mode=universal\""))
        assertTrue(appBuild.contains("entry.name == \"universal.apk\""))
        assertTrue(
            appBuild.contains(
                "listOf(zipalign.absolutePath, \"-c\", \"-P\", \"16\", \"-v\", \"4\", " +
                    "universalApk.absolutePath)"
            )
        )
        assertTrue(appBuild.contains("entry.name.endsWith(\".so\")"))
        assertTrue(appBuild.contains("findAvailableLlvmReadelf()"))
        assertTrue(appBuild.contains("\"--program-headers\", \"--wide\""))
        assertTrue(appBuild.contains("alignment < requiredAndroidPageSizeBytes"))
        assertTrue(appBuild.contains("native-so-status=\${nativeSoPresenceStatus(nativeEntries.size)}"))
        assertTrue(appBuild.contains("llvm-readelf=NOT_REQUIRED"))
        assertTrue(appBuild.contains("16 KB validator accepted a 4 KB-aligned LOAD segment."))
        assertTrue(appBuild.contains("16 KB validator accepted llvm-readelf output without LOAD segments."))
        assertTrue(appBuild.contains("\":app:verifyRelease16kPageSize\""))
        assertTrue(appBuild.contains("\"release-16k-page-size\" to release16kPageSizeReportFile"))
    }

    @Test
    fun releaseArtifactGraphRejectsExcludedMandatoryGates() {
        assertTrue(appBuild.contains("val requiredStoreReleaseGatePaths = setOf("))
        assertTrue(appBuild.contains("val requiredReleaseBundlePostBuildGatePaths = setOf("))
        assertTrue(appBuild.contains("\":app:verifyReleaseBundleWithBundletool\""))
        assertTrue(appBuild.contains("\":app:generateReleaseArtifactEvidence\""))
        assertTrue(appBuild.contains("fun missingStoreReleaseGatePaths"))
        assertTrue(appBuild.contains("verifyStoreReleaseGraphValidatorContract"))
        assertTrue(appBuild.contains("missingStoreReleaseGatePaths(requiredStoreReleaseGatePaths - excluded)"))
        assertTrue(appBuild.contains("gradle.startParameter.excludedTaskNames"))
        assertTrue(appBuild.contains("Release artifact graphs forbid -x task exclusions"))
        assertTrue(appBuild.contains("-x exclusions are forbidden"))
        assertTrue(appBuild.contains("val taskPaths = allTasks.mapTo(mutableSetOf())"))
    }
}
