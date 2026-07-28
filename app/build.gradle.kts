import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.net.URI
import java.util.jar.JarFile
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun configValue(name: String): String {
    return providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: ""
}

fun releaseConfigValue(name: String): String {
    if (providers.gradleProperty(name).isPresent || localProperties.containsKey(name)) {
        throw GradleException(
            "$name must be supplied only through an environment variable for a reproducible release."
        )
    }
    return providers.environmentVariable(name).orNull.orEmpty()
}

fun signingSecret(name: String): String {
    if (providers.gradleProperty(name).isPresent || localProperties.containsKey(name)) {
        throw GradleException("$name must be supplied only through an environment variable, never -P or local.properties.")
    }
    return providers.environmentVariable(name).orNull.orEmpty()
}

fun String.asBuildConfigString(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

fun isHttpsUrl(value: String): Boolean {
    return runCatching {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }.getOrDefault(false)
}

fun isPlaceholderReleaseValue(value: String): Boolean {
    val normalized = value.trim().lowercase()
    if (normalized.isBlank()) return false
    return normalized.startsWith("<") ||
        normalized.endsWith(">") ||
        normalized.contains("example.") ||
        normalized.contains("placeholder") ||
        normalized.contains("dummy") ||
        normalized.contains("fake") ||
        normalized.contains("your-") ||
        normalized.contains("real-appmetrica-key")
}

val vSlotApplicationId = "com.vslot.app"
val vSlotVersionCode = 1
val vSlotVersionName = "1.0.0"
val vSlotMinSdk = 26
val vSlotStoreSdk = 36
val vSlotBuildToolsVersion = "36.0.0"
val googleServicesFiles = mapOf(
    "debug" to file("src/debug/google-services.json"),
    "qa" to file("src/qa/google-services.json"),
    "release" to file("src/release/google-services.json")
)
val googleServicesConfigured = googleServicesFiles.mapValues { (_, configFile) -> configFile.isFile }
if (googleServicesConfigured.values.any { it }) {
    apply(plugin = "com.google.gms.google-services")
}

val releasePrivacyPolicyUrl = releaseConfigValue("V_SLOT_PRIVACY_POLICY_URL")
val releaseSupportEmail = releaseConfigValue("V_SLOT_SUPPORT_EMAIL")
val releaseDeveloperLegalName = releaseConfigValue("V_SLOT_DEVELOPER_LEGAL_NAME")
val releaseAppMetricaApiKey = releaseConfigValue("V_SLOT_APPMETRICA_API_KEY")
val expectedAppMetricaApiKeySha256 = releaseConfigValue("V_SLOT_APPMETRICA_API_KEY_SHA256")
val expectedFirebaseProjectId = releaseConfigValue("V_SLOT_FIREBASE_PROJECT_ID")
val expectedFirebaseAppId = releaseConfigValue("V_SLOT_FIREBASE_APP_ID")
val releaseStoreFilePath = releaseConfigValue("V_SLOT_RELEASE_STORE_FILE")
val releaseKeyAlias = releaseConfigValue("V_SLOT_RELEASE_KEY_ALIAS")
val expectedReleaseCertificateSha256 = releaseConfigValue("V_SLOT_RELEASE_CERT_SHA256")
val dataSafetyReviewedVersionCode = releaseConfigValue("V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE")
val dataSafetyEvidenceFilePath = releaseConfigValue("V_SLOT_DATA_SAFETY_EVIDENCE_FILE")
val expectedDataSafetyEvidenceSha256 = releaseConfigValue("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256")
val dataSafetyRawEvidenceFilePath = releaseConfigValue("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_FILE")
val expectedDataSafetyRawEvidenceSha256 = releaseConfigValue("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256")
val assetRightsReviewedVersionCode = releaseConfigValue("V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE")
val assetRightsEvidenceFilePath = releaseConfigValue("V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE")
val expectedAssetRightsEvidenceSha256 = releaseConfigValue("V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256")
val samsungQaEvidenceFilePath = releaseConfigValue("V_SLOT_SAMSUNG_QA_EVIDENCE_FILE")
val expectedSamsungQaEvidenceSha256 = releaseConfigValue("V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256")
val processDeathEvidenceFilePath = releaseConfigValue("V_SLOT_PROCESS_DEATH_EVIDENCE_FILE")
val expectedProcessDeathEvidenceSha256 = releaseConfigValue("V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256")
val frameMetricsEvidenceFilePath = releaseConfigValue("V_SLOT_FRAME_METRICS_EVIDENCE_FILE")
val expectedFrameMetricsEvidenceSha256 = releaseConfigValue("V_SLOT_FRAME_METRICS_EVIDENCE_SHA256")
val physicalSamsungRawEvidenceFilePath = releaseConfigValue("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_FILE")
val expectedPhysicalSamsungRawEvidenceSha256 =
    releaseConfigValue("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256")
val releaseBundletoolJarPath = releaseConfigValue("V_SLOT_BUNDLETOOL_JAR")
val requiredReleaseBundletoolVersion = rootProject.extra["vSlotBundletoolVersion"] as String
val requiredReleaseBundletoolSha256 = rootProject.extra["vSlotBundletoolSha256"] as String
val qaAppMetricaApiKey = configValue("V_SLOT_QA_APPMETRICA_API_KEY")
val debugAppMetricaApiKey = configValue("V_SLOT_DEBUG_APPMETRICA_API_KEY")
val releaseStorePassword = signingSecret("V_SLOT_RELEASE_STORE_PASSWORD")
val releaseKeyPassword = signingSecret("V_SLOT_RELEASE_KEY_PASSWORD")

fun googleServicesReadinessIssues(
    googleServicesFile: File,
    expectedPackageName: String,
    expectedProjectId: String,
    expectedAppId: String
): List<String> {
    val displayPath = googleServicesFile.relativeTo(rootProject.projectDir).path
    if (!googleServicesFile.isFile) return listOf(displayPath)

    val root = runCatching {
        JsonSlurper().parse(googleServicesFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$displayPath(valid JSON required)")

    val issues = mutableListOf<String>()
    val projectInfo = root["project_info"] as? Map<*, *>
    val projectId = projectInfo?.get("project_id")?.toString().orEmpty()
    val projectNumber = projectInfo?.get("project_number")?.toString().orEmpty()
    if (projectId.isBlank()) {
        issues += "$displayPath(project_id required)"
    }
    if (projectNumber.isBlank() || projectNumber.any { !it.isDigit() }) {
        issues += "$displayPath(project_number required)"
    }
    if (expectedProjectId.isNotBlank() && projectId != expectedProjectId) {
        issues += "$displayPath(expected Firebase project mismatch)"
    }

    val clients = root["client"] as? List<*> ?: emptyList<Any?>()
    val matchingClient = clients
        .mapNotNull { it as? Map<*, *> }
        .firstOrNull { client ->
            val clientInfo = client["client_info"] as? Map<*, *> ?: return@firstOrNull false
            val androidInfo = clientInfo["android_client_info"] as? Map<*, *> ?: return@firstOrNull false
            val packageName = androidInfo["package_name"]?.toString().orEmpty()
            packageName == expectedPackageName
        }
    if (matchingClient == null) {
        issues += "$displayPath(client package_name $expectedPackageName required)"
    } else {
        val clientInfo = matchingClient["client_info"] as? Map<*, *>
        val mobileSdkAppId = clientInfo?.get("mobilesdk_app_id")?.toString().orEmpty()
        if (mobileSdkAppId.isBlank()) {
            issues += "$displayPath(mobilesdk_app_id required)"
        }
        if (expectedAppId.isNotBlank() && mobileSdkAppId != expectedAppId) {
            issues += "$displayPath(expected Firebase app mismatch)"
        }
        val hasValidApiKey = (matchingClient["api_key"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { apiKey -> apiKey["current_key"]?.toString().orEmpty() }
            .any { currentKey -> currentKey.matches(Regex("AIza[0-9A-Za-z_-]{35}")) }
        if (!hasValidApiKey) {
            issues += "$displayPath(api_key.current_key required)"
        }
    }

    return issues
}

fun normalizedSha256(value: String): String? {
    val normalized = value
        .trim()
        .replace(Regex("[:\\s]"), "")
        .uppercase()
    return normalized.takeIf { it.length == 64 && it.all { character -> character in '0'..'9' || character in 'A'..'F' } }
}

val assetProvenanceInventoryFile = rootProject.file(
    "docs/legal/ASSET_PROVENANCE_INVENTORY.json"
)

fun appLogoExportIssues(): List<String> {
    val label = "qa/source/vslot_app_logo_mark_export.json"
    val manifestFile = rootProject.file(label)
    if (!manifestFile.isFile) return listOf("$label(file not found)")
    val manifest = runCatching {
        JsonSlurper().parse(manifestFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$label(valid JSON object required)")
    val issues = mutableListOf<String>()
    val expectedKeys = setOf(
        "schema_version",
        "exporter",
        "exporter_sha256",
        "source",
        "source_sha256",
        "output",
        "output_sha256",
        "sampled_key_rgb",
        "transparent_threshold",
        "opaque_threshold",
        "despill"
    )
    if (manifest.keys.map { it.toString() }.toSet() != expectedKeys) {
        issues += "$label(exact schema-v1 fields required)"
    }
    if (manifest["schema_version"]?.toString()?.toIntOrNull() != 1) {
        issues += "$label(schema_version 1 required)"
    }
    val expectedPaths = mapOf(
        "exporter" to "tools/export_app_logo_mark.py",
        "source" to "qa/source/vslot_app_logo_mark_chroma_imagegen.png",
        "output" to "app/src/main/res/drawable-nodpi/app_logo_mark_v2.png"
    )
    expectedPaths.forEach { (key, expectedPath) ->
        if (manifest[key]?.toString() != expectedPath) {
            issues += "$label($key path mismatch)"
        }
    }
    listOf(
        Triple("exporter", "exporter_sha256", expectedPaths.getValue("exporter")),
        Triple("source", "source_sha256", expectedPaths.getValue("source")),
        Triple("output", "output_sha256", expectedPaths.getValue("output"))
    ).forEach { (pathKey, hashKey, expectedPath) ->
        val path = rootProject.file(expectedPath)
        if (!path.isFile || normalizedSha256(manifest[hashKey]?.toString().orEmpty()) != normalizedSha256(sha256Hex(path))) {
            issues += "$label($pathKey SHA-256 mismatch)"
        }
    }
    val sampledKey = (manifest["sampled_key_rgb"] as? List<*>)
        ?.map { value -> value?.toString()?.toIntOrNull() }
    if (
        sampledKey != listOf(12, 234, 20) ||
        manifest["transparent_threshold"]?.toString()?.toDoubleOrNull() != 12.0 ||
        manifest["opaque_threshold"]?.toString()?.toDoubleOrNull() != 220.0 ||
        manifest["despill"] != true
    ) {
        issues += "$label(chroma export parameters mismatch)"
    }
    return issues.distinct()
}

fun rasterDerivationManifestIssues(): List<String> {
    val label = "docs/legal/RASTER_DERIVATION_MANIFEST.json"
    val manifestFile = rootProject.file(label)
    if (!manifestFile.isFile) return listOf("$label(file not found)")
    val manifest = runCatching {
        JsonSlurper().parse(manifestFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$label(valid JSON object required)")
    val issues = mutableListOf<String>()
    if (manifest.keys.map { it.toString() }.toSet() != setOf(
            "schema_version",
            "status",
            "generated_by",
            "toolchain",
            "font",
            "entries"
        )
    ) {
        issues += "$label(exact schema-v1 fields required)"
    }
    if (manifest["schema_version"]?.toString()?.toIntOrNull() != 1) {
        issues += "$label(schema_version 1 required)"
    }
    if (manifest["status"] != "derivation_integrity_not_legal_clearance") {
        issues += "$label(must not claim legal clearance)"
    }

    fun validatePathRecord(
        record: Map<*, *>?,
        recordLabel: String,
        expectedType: String? = null
    ): String? {
        if (record == null) {
            issues += "$label($recordLabel object required)"
            return null
        }
        val expectedKeys = if (expectedType == null) setOf("path", "sha256") else setOf("path", "sha256", "type")
        if (record.keys.map { it.toString() }.toSet() != expectedKeys) {
            issues += "$label($recordLabel exact fields required)"
        }
        if (expectedType != null && record["type"]?.toString() != expectedType) {
            issues += "$label($recordLabel type mismatch)"
        }
        val path = record["path"]?.toString().orEmpty()
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
            issues += "$label($recordLabel repository-relative path required)"
            return null
        }
        val file = rootProject.file(path)
        if (!file.isFile || normalizedSha256(record["sha256"]?.toString().orEmpty()) != normalizedSha256(sha256Hex(file))) {
            issues += "$label($recordLabel SHA-256 mismatch)"
        }
        return path
    }

    val generatedBy = manifest["generated_by"] as? Map<*, *>
    val generatorPath = validatePathRecord(generatedBy, "generated_by")
    if (generatorPath != "tools/generate_raster_derivation_manifest.py") {
        issues += "$label(generated_by path mismatch)"
    }
    val expectedToolchain = mapOf(
        "python" to "3.9.6",
        "pillow" to "11.3.0",
        "freetype" to "2.13.3",
        "libwebp" to "1.5.0"
    )
    val toolchain = manifest["toolchain"] as? Map<*, *>
    if (toolchain?.mapKeys { entry -> entry.key.toString() }?.mapValues { entry -> entry.value?.toString().orEmpty() } != expectedToolchain) {
        issues += "$label(exact raster toolchain required)"
    }
    val font = manifest["font"] as? Map<*, *>
    val expectedFontKeys = setOf("path", "sha256", "license", "upstream_commit")
    if (font == null || font.keys.map { it.toString() }.toSet() != expectedFontKeys) {
        issues += "$label(exact pinned font fields required)"
    } else {
        val fontPath = font["path"]?.toString().orEmpty()
        val fontFile = rootProject.file(fontPath)
        if (
            fontPath != "tools/fonts/noto-sans/NotoSans[wdth,wght].ttf" ||
            !fontFile.isFile ||
            normalizedSha256(font["sha256"]?.toString().orEmpty()) != normalizedSha256(sha256Hex(fontFile)) ||
            font["license"] != "OFL-1.1" ||
            font["upstream_commit"] != "389b770410cc0b7c21c85673bfa2077420fe7f65"
        ) {
            issues += "$label(pinned Noto Sans identity mismatch)"
        }
    }

    val expectedProducerCounts = mapOf(
        "tools/generate_analytics_consent_assets.py" to 6,
        "tools/generate_auto_spin_dialog_assets.py" to 3,
        "tools/generate_free_spins_charge_assets.py" to 5,
        "tools/generate_home_scroll_cue_assets.py" to 2,
        "tools/generate_level_assets.py" to 7,
        "tools/generate_new_slot_assets.py" to 30,
        "tools/generate_paytable_bonus_lane_assets.py" to 5,
        "tools/generate_paytable_copy_asset.py" to 2,
        "tools/generate_paytable_footer_assets.py" to 10,
        "tools/generate_reel_aperture_assets.py" to 5,
        "tools/generate_reel_brake_assets.py" to 5,
        "tools/generate_reel_motion_streak_assets.py" to 5,
        "tools/generate_settings_feedback_icons.py" to 4,
        "tools/generate_slot_backgrounds.py" to 3,
        "tools/generate_slot_symbol_spin_blur_assets.py" to 36,
        "tools/generate_spin_impact_flash_assets.py" to 5,
        "tools/generate_theme_paytable_assets.py" to 9,
        "tools/generate_theme_paylines.py" to 60,
        "tools/generate_theme_result_assets.py" to 12,
        "tools/generate_theme_slot_buttons.py" to 69,
        "tools/generate_theme_slot_chrome.py" to 48,
        "tools/generate_theme_slot_labels.py" to 12,
        "tools/generate_third_party_notices_assets.py" to 1,
        "tools/generate_total_bet_link_assets.py" to 5,
        "tools/slice_imagegen_theme_result_banners.py" to 10
    )
    val fontProducers = setOf(
        "tools/generate_analytics_consent_assets.py",
        "tools/generate_auto_spin_dialog_assets.py",
        "tools/generate_level_assets.py",
        "tools/generate_new_slot_assets.py",
        "tools/generate_paytable_copy_asset.py",
        "tools/generate_paytable_footer_assets.py",
        "tools/generate_third_party_notices_assets.py",
        "tools/slice_imagegen_theme_result_banners.py"
    )
    val drawableSourceProducers = setOf(
        "tools/generate_theme_paylines.py",
        "tools/generate_theme_slot_buttons.py",
        "tools/generate_theme_slot_chrome.py",
        "tools/generate_theme_slot_labels.py",
        "tools/generate_slot_symbol_spin_blur_assets.py",
        "tools/generate_theme_paytable_assets.py",
        "tools/generate_theme_result_assets.py"
    )
    val expectedEntryKeys = setOf(
        "path",
        "bytes",
        "sha256",
        "media",
        "origin",
        "build_inputs",
        "reproduction",
        "rights_review"
    )
    val fontBuildInputTypes = setOf(
        "font",
        "font_license",
        "font_metadata",
        "font_loader"
    )
    val toolchainBuildInputTypes = setOf(
        "toolchain_gate",
        "python_requirement"
    )
    val entries = manifest["entries"] as? List<*>
    val parsedEntries = entries.orEmpty().mapNotNull { entry -> entry as? Map<*, *> }
    if (entries == null || parsedEntries.size != expectedProducerCounts.values.sum()) {
        issues += "$label(exact 359 derivation entries required)"
    }
    val paths = parsedEntries.map { entry -> entry["path"]?.toString().orEmpty() }
    if (paths.size != paths.toSet().size) issues += "$label(duplicate output paths are forbidden)"
    val producerCounts = mutableMapOf<String, Int>()
    parsedEntries.forEachIndexed { index, entry ->
        val path = entry["path"]?.toString().orEmpty()
        val entryLabel = "entry[$index] $path"
        if (entry.keys.map { it.toString() }.toSet() != expectedEntryKeys) {
            issues += "$label($entryLabel exact fields required)"
            return@forEachIndexed
        }
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
            issues += "$label($entryLabel repository-relative output required)"
            return@forEachIndexed
        }
        val output = rootProject.file(path)
        val outputSha256 = normalizedSha256(entry["sha256"]?.toString().orEmpty())
        if (
            !output.isFile ||
            entry["bytes"]?.toString()?.toLongOrNull() != output.length() ||
            outputSha256 != normalizedSha256(sha256Hex(output))
        ) {
            issues += "$label($entryLabel output identity mismatch)"
        }
        val media = entry["media"] as? Map<*, *>
        if (
            media == null ||
            media.keys.map { it.toString() }.toSet() != setOf("type", "width", "height") ||
            media["type"] != "image/webp" ||
            (media["width"] as? Number)?.toInt()?.let { it > 0 } != true ||
            (media["height"] as? Number)?.toInt()?.let { it > 0 } != true
        ) {
            issues += "$label($entryLabel valid WebP media fields required)"
        }
        val origin = entry["origin"] as? Map<*, *>
        var producerPath: String? = null
        if (origin == null || origin.keys.map { it.toString() }.toSet() != setOf("class", "sources", "producer")) {
            issues += "$label($entryLabel exact origin fields required)"
        } else {
            if (origin["class"] !in setOf("procedural", "derived_imagegen")) {
                issues += "$label($entryLabel unsupported origin class)"
            }
            val sources = origin["sources"] as? List<*>
            if (sources == null) {
                issues += "$label($entryLabel sources list required)"
            }
            val sourcePaths = sources.orEmpty().mapIndexedNotNull { sourceIndex, source ->
                validatePathRecord(source as? Map<*, *>, "$entryLabel source[$sourceIndex]")
            }
            if (origin["class"] == "derived_imagegen" && sources.orEmpty().size != 1) {
                issues += "$label($entryLabel derived imagegen source required)"
            }
            val producer = origin["producer"] as? Map<*, *>
            producerPath = validatePathRecord(
                producer?.filterKeys { key -> key.toString() != "command" },
                "$entryLabel producer"
            )
            val command = (producer?.get("command") as? List<*>)?.map { item -> item?.toString().orEmpty() }
            val producerText = producerPath?.let { rootProject.file(it).takeIf(File::isFile)?.readText(Charsets.UTF_8) }.orEmpty()
            if (
                producer?.keys?.map { it.toString() }?.toSet() != setOf("path", "sha256", "command") ||
                command != listOf("python3", producerPath) ||
                !producerText.contains("verify_asset_toolchain()")
            ) {
                issues += "$label($entryLabel canonical toolchain-gated producer required)"
            }
            if (origin["class"] == "procedural") {
                val expectsDrawableSource = producerPath in drawableSourceProducers
                if (
                    expectsDrawableSource &&
                    (sourcePaths.size != 1 || sourcePaths.singleOrNull()?.startsWith("app/src/main/res/drawable-nodpi/") != true)
                ) {
                    issues += "$label($entryLabel exact drawable source required)"
                }
                if (!expectsDrawableSource && sourcePaths.isNotEmpty()) {
                    issues += "$label($entryLabel pure procedural sources must be empty)"
                }
            }
            if (producerPath != null) producerCounts[producerPath] = producerCounts.getOrDefault(producerPath, 0) + 1
        }
        val buildInputs = entry["build_inputs"] as? List<*>
        val inputMaps = buildInputs.orEmpty().mapNotNull { input -> input as? Map<*, *> }
        val inputTypes = inputMaps.map { input -> input["type"]?.toString().orEmpty() }.toSet()
        val expectedBuildInputTypes = toolchainBuildInputTypes +
            if (producerPath in fontProducers) fontBuildInputTypes else emptySet()
        if (inputMaps.size != expectedBuildInputTypes.size || inputTypes != expectedBuildInputTypes) {
            issues += "$label($entryLabel exact conditional build inputs required)"
        }
        inputMaps.forEach { input ->
            val type = input["type"]?.toString().orEmpty()
            validatePathRecord(input, "$entryLabel build input $type", type)
        }
        val reproduction = entry["reproduction"] as? Map<*, *>
        if (
            reproduction == null ||
            reproduction.keys.map { it.toString() }.toSet() != setOf("mode", "expected_sha256") ||
            reproduction["mode"] != "byte_exact" ||
            normalizedSha256(reproduction["expected_sha256"]?.toString().orEmpty()) != outputSha256
        ) {
            issues += "$label($entryLabel byte-exact reproduction binding required)"
        }
        val rightsReview = entry["rights_review"] as? Map<*, *>
        if (
            rightsReview == null ||
            rightsReview.keys.map { it.toString() }.toSet() != setOf("state", "evidence_refs") ||
            rightsReview["state"] != "review_required" ||
            (rightsReview["evidence_refs"] as? List<*>)?.map { it?.toString().orEmpty() } != listOf("release_asset_rights_signoff_v1")
        ) {
            issues += "$label($entryLabel rights review must remain external and required)"
        }
    }
    if (producerCounts != expectedProducerCounts) {
        issues += "$label(canonical producer/output counts mismatch)"
    }
    return issues.distinct()
}

fun noncanonicalImagegenSlicerIssues(): List<String> {
    val scripts = listOf(
        "slice_imagegen_free_spins_stake_lock_overlay.py",
        "slice_imagegen_home_locked_slot_pulse.py",
        "slice_imagegen_home_slot_unlock_burst.py",
        "slice_imagegen_level_hud_assets.py",
        "slice_imagegen_privacy_web_panel.py",
        "slice_imagegen_push_permission_modal_panel.py",
        "slice_imagegen_reel_landing_spark.py",
        "slice_imagegen_settings_modal_panel.py",
        "slice_imagegen_settings_safety_anchor.py",
        "slice_imagegen_slam_stop_cue.py",
        "slice_imagegen_splash_ignition_overlay.py",
        "slice_imagegen_theme_reel_landing_sparks.py",
        "slice_imagegen_theme_slam_stop_cues.py",
        "slice_imagegen_top_bar.py"
    )
    val issues = mutableListOf<String>()
    scripts.forEach { scriptName ->
        val path = rootProject.file("tools/$scriptName")
        val text = path.takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        if (
            text.isBlank() ||
            !text.contains("NONCANONICAL_HISTORICAL_SLICER") ||
            text.contains("if __name__ == \"__main__\":\n    main()")
        ) {
            issues += "tools/$scriptName(must remain fail-closed historical provenance code)"
        }
    }
    return issues
}

fun imagegenDerivationManifestIssues(): List<String> {
    val label = "docs/legal/IMAGEGEN_DERIVATION_MANIFEST.json"
    val manifestFile = rootProject.file(label)
    if (!manifestFile.isFile) return listOf("$label(file not found)")
    val manifest = runCatching {
        JsonSlurper().parse(manifestFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$label(valid JSON object required)")
    val issues = mutableListOf<String>()
    if (manifest.keys.map { it.toString() }.toSet() != setOf("schema_version", "status", "generated_by", "toolchain", "entries")) {
        issues += "$label(exact schema-v1 fields required)"
    }
    if (manifest["schema_version"]?.toString()?.toIntOrNull() != 1) {
        issues += "$label(schema_version 1 required)"
    }
    if (manifest["status"] != "derivation_integrity_not_legal_clearance") {
        issues += "$label(must not claim legal clearance)"
    }

    fun validatePathRecord(record: Map<*, *>?, recordLabel: String, expectedType: String? = null): String? {
        if (record == null) {
            issues += "$label($recordLabel object required)"
            return null
        }
        val expectedKeys = if (expectedType == null) setOf("path", "sha256") else setOf("path", "sha256", "type")
        if (record.keys.map { it.toString() }.toSet() != expectedKeys) {
            issues += "$label($recordLabel exact fields required)"
        }
        if (expectedType != null && record["type"]?.toString() != expectedType) {
            issues += "$label($recordLabel type mismatch)"
        }
        val path = record["path"]?.toString().orEmpty()
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
            issues += "$label($recordLabel repository-relative path required)"
            return null
        }
        val file = rootProject.file(path)
        if (!file.isFile || normalizedSha256(record["sha256"]?.toString().orEmpty()) != normalizedSha256(sha256Hex(file))) {
            issues += "$label($recordLabel SHA-256 mismatch)"
        }
        return path
    }

    val generatedByPath = validatePathRecord(manifest["generated_by"] as? Map<*, *>, "generated_by")
    if (generatedByPath != "tools/generate_imagegen_derivation_manifest.py") {
        issues += "$label(generated_by path mismatch)"
    }
    val expectedToolchain = mapOf(
        "python" to "3.9.6",
        "pillow" to "11.3.0",
        "freetype" to "2.13.3",
        "libwebp" to "1.5.0"
    )
    val toolchain = manifest["toolchain"] as? Map<*, *>
    if (toolchain?.mapKeys { it.key.toString() }?.mapValues { it.value?.toString().orEmpty() } != expectedToolchain) {
        issues += "$label(exact imagegen raster toolchain required)"
    }
    val expectedProducerCounts = mapOf(
        "tools/slice_imagegen_bonus_entry_portals.py" to 5,
        "tools/slice_imagegen_daily_bonus_countdown_charge.py" to 1,
        "tools/slice_imagegen_daily_bonus_home_buttons.py" to 2,
        "tools/slice_imagegen_daily_bonus_modal_panel.py" to 1,
        "tools/slice_imagegen_low_coins_modal_panel.py" to 1,
        "tools/slice_imagegen_paytable_modal_panels.py" to 5,
        "tools/slice_imagegen_privacy_command_buttons.py" to 2,
        "tools/slice_imagegen_privacy_loading_overlay.py" to 2,
        "tools/slice_imagegen_privacy_loading_sweep.py" to 1,
        "tools/slice_imagegen_reel_anticipation_beams.py" to 5,
        "tools/slice_imagegen_result_modal_panels.py" to 5,
        "tools/slice_imagegen_settings_push_status_console.py" to 2,
        "tools/slice_imagegen_slot_level_meter.py" to 1,
        "tools/slice_imagegen_theme_ambient_overlays.py" to 5,
        "tools/slice_imagegen_theme_reel_glass_overlays.py" to 5,
        "tools/slice_imagegen_theme_reel_spin_blur.py" to 5,
        "tools/slice_imagegen_theme_reel_stop_flashes.py" to 5,
        "tools/slice_imagegen_theme_spin_energy_rims.py" to 5,
        "tools/slice_imagegen_theme_spin_overlays.py" to 5,
        "tools/slice_imagegen_theme_symbol_halos.py" to 10,
        "tools/slice_imagegen_theme_win_bursts.py" to 5,
        "tools/slice_imagegen_theme_win_glow_sprites.py" to 5
    )
    val expectedEntryKeys = setOf("path", "bytes", "sha256", "media", "origin", "build_inputs", "reproduction", "rights_review")
    val expectedBuildInputs = setOf("toolchain_gate", "python_requirement")
    val entries = manifest["entries"] as? List<*>
    val parsedEntries = entries.orEmpty().mapNotNull { it as? Map<*, *> }
    if (entries == null || parsedEntries.size != 83) {
        issues += "$label(exact 83 derivation entries required)"
    }
    val outputPaths = parsedEntries.map { it["path"]?.toString().orEmpty() }
    if (outputPaths.size != outputPaths.toSet().size) issues += "$label(duplicate output paths are forbidden)"
    val producerCounts = mutableMapOf<String, Int>()
    parsedEntries.forEachIndexed { index, entry ->
        val path = entry["path"]?.toString().orEmpty()
        val entryLabel = "entry[$index] $path"
        if (entry.keys.map { it.toString() }.toSet() != expectedEntryKeys) {
            issues += "$label($entryLabel exact fields required)"
            return@forEachIndexed
        }
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
            issues += "$label($entryLabel repository-relative output required)"
            return@forEachIndexed
        }
        val output = rootProject.file(path)
        val outputSha256 = normalizedSha256(entry["sha256"]?.toString().orEmpty())
        if (!output.isFile || entry["bytes"]?.toString()?.toLongOrNull() != output.length() || outputSha256 != normalizedSha256(sha256Hex(output))) {
            issues += "$label($entryLabel output identity mismatch)"
        }
        val media = entry["media"] as? Map<*, *>
        if (
            media == null ||
            media.keys.map { it.toString() }.toSet() != setOf("type", "width", "height") ||
            media["type"] != "image/webp" ||
            (media["width"] as? Number)?.toInt()?.let { it > 0 } != true ||
            (media["height"] as? Number)?.toInt()?.let { it > 0 } != true
        ) {
            issues += "$label($entryLabel valid WebP media fields required)"
        }
        val origin = entry["origin"] as? Map<*, *>
        if (origin == null || origin.keys.map { it.toString() }.toSet() != setOf("class", "sources", "producer") || origin["class"] != "derived_imagegen") {
            issues += "$label($entryLabel exact derived imagegen origin required)"
        } else {
            val sources = origin["sources"] as? List<*>
            if (sources.isNullOrEmpty()) issues += "$label($entryLabel imagegen source required)"
            sources.orEmpty().forEachIndexed { sourceIndex, source ->
                val sourcePath = validatePathRecord(source as? Map<*, *>, "$entryLabel source[$sourceIndex]")
                if (sourcePath?.startsWith("qa/source/") != true) issues += "$label($entryLabel source must be retained under qa/source)"
            }
            val producer = origin["producer"] as? Map<*, *>
            val producerPath = validatePathRecord(producer?.filterKeys { it.toString() != "command" }, "$entryLabel producer")
            val command = (producer?.get("command") as? List<*>)?.map { it?.toString().orEmpty() }
            val producerText = producerPath?.let { rootProject.file(it).takeIf(File::isFile)?.readText(Charsets.UTF_8) }.orEmpty()
            if (
                producer?.keys?.map { it.toString() }?.toSet() != setOf("path", "sha256", "command") ||
                command != listOf("python3", producerPath) ||
                producerPath !in expectedProducerCounts ||
                !producerText.contains("verify_asset_toolchain()") ||
                producerText.contains("NONCANONICAL_HISTORICAL_SLICER")
            ) {
                issues += "$label($entryLabel canonical toolchain-gated producer required)"
            }
            if (producerPath != null) producerCounts[producerPath] = producerCounts.getOrDefault(producerPath, 0) + 1
        }
        val buildInputs = entry["build_inputs"] as? List<*>
        val inputMaps = buildInputs.orEmpty().mapNotNull { it as? Map<*, *> }
        val inputTypes = inputMaps.map { it["type"]?.toString().orEmpty() }.toSet()
        if (inputMaps.size != expectedBuildInputs.size || inputTypes != expectedBuildInputs) {
            issues += "$label($entryLabel exact pinned build inputs required)"
        }
        inputMaps.forEach { input ->
            val type = input["type"]?.toString().orEmpty()
            validatePathRecord(input, "$entryLabel build input $type", type)
        }
        val reproduction = entry["reproduction"] as? Map<*, *>
        if (
            reproduction == null ||
            reproduction.keys.map { it.toString() }.toSet() != setOf("mode", "expected_sha256") ||
            reproduction["mode"] != "byte_exact" ||
            normalizedSha256(reproduction["expected_sha256"]?.toString().orEmpty()) != outputSha256
        ) {
            issues += "$label($entryLabel byte-exact reproduction binding required)"
        }
        val rightsReview = entry["rights_review"] as? Map<*, *>
        if (
            rightsReview == null ||
            rightsReview.keys.map { it.toString() }.toSet() != setOf("state", "evidence_refs") ||
            rightsReview["state"] != "review_required" ||
            (rightsReview["evidence_refs"] as? List<*>)?.map { it?.toString().orEmpty() } != listOf("release_asset_rights_signoff_v1")
        ) {
            issues += "$label($entryLabel rights review must remain external and required)"
        }
    }
    if (producerCounts != expectedProducerCounts) {
        issues += "$label(canonical imagegen producer/output counts mismatch)"
    }
    return issues.distinct()
}

fun assetProvenanceInventoryIssues(): List<String> {
    val label = "docs/legal/ASSET_PROVENANCE_INVENTORY.json"
    if (!assetProvenanceInventoryFile.isFile) return listOf("$label(file not found)")
    val root = runCatching {
        JsonSlurper().parse(assetProvenanceInventoryFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$label(valid JSON object required)")
    val issues = (
        appLogoExportIssues() +
            rasterDerivationManifestIssues() +
            imagegenDerivationManifestIssues() +
            noncanonicalImagegenSlicerIssues()
        ).toMutableList()
    val expectedRootKeys = setOf("schema_version", "status", "generated_by", "entries")
    if (root.keys.map { it.toString() }.toSet() != expectedRootKeys) {
        issues += "$label(exact schema-v1 fields required)"
    }
    if (root["schema_version"]?.toString()?.toIntOrNull() != 1) {
        issues += "$label(schema_version 1 required)"
    }
    if (root["status"] != "inventory_only_not_legal_clearance") {
        issues += "$label(must not claim legal clearance)"
    }
    if (root["generated_by"] != "tools/generate_asset_provenance_inventory.py") {
        issues += "$label(generated_by mismatch)"
    }
    val mediaExtensions = setOf("png", "webp", "wav")
    val expectedFiles = listOf(
        file("src/main/res"),
        rootProject.file("qa/source"),
        rootProject.file("docs/store/assets")
    ).flatMap { directory ->
        directory.walkTopDown().filter { candidate ->
            candidate.isFile && candidate.extension.lowercase() in mediaExtensions
        }.toList()
    }.sortedBy { candidate ->
        candidate.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    }
    val entries = root["entries"] as? List<*>
    if (entries == null) return issues + "$label(entries array required)"
    val parsedEntries = entries.mapNotNull { entry -> entry as? Map<*, *> }
    if (parsedEntries.size != entries.size) {
        issues += "$label(every entry must be an object)"
    }
    val expectedPaths = expectedFiles.mapTo(sortedSetOf()) { candidate ->
        candidate.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    }
    val actualPaths = parsedEntries.map { entry -> entry["path"]?.toString().orEmpty() }
    if (actualPaths.size != actualPaths.toSet().size) {
        issues += "$label(duplicate paths are forbidden)"
    }
    if (actualPaths.toSortedSet() != expectedPaths) {
        issues += "$label(must contain exactly every packaged/source/store media file)"
    }
    val expectedEntryKeys = setOf(
        "path",
        "bytes",
        "sha256",
        "media_role",
        "source_paths",
        "producer",
        "reproducibility",
        "rights_basis",
        "evidence_id"
    )
    val allowedMediaRoles = setOf(
        "retained_imagegen_source_master",
        "packaged_audio",
        "store_screenshot",
        "store_source_master",
        "store_icon_export",
        "store_feature_graphic_export",
        "packaged_visual"
    )
    val allowedReproducibility = setOf(
        "authoritative_master",
        "deterministic_generator",
        "instrumentation_capture",
        "retained_source_transform_review_required"
    )
    val allowedRightsBasis = setOf(
        "generation_terms_and_owner_attestation_required",
        "project_procedural_source_and_owner_attestation_required",
        "application_ui_and_owner_attestation_required",
        "owner_attestation_and_per_asset_review_required"
    )
    parsedEntries.forEach { entry ->
        val path = entry["path"]?.toString().orEmpty()
        if (entry.keys.map { it.toString() }.toSet() != expectedEntryKeys) {
            issues += "$label($path exact entry fields required)"
            return@forEach
        }
        val source = rootProject.file(path)
        if (!source.isFile) return@forEach
        if (entry["bytes"]?.toString()?.toLongOrNull() != source.length()) {
            issues += "$label($path byte length mismatch)"
        }
        if (normalizedSha256(entry["sha256"]?.toString().orEmpty()) != normalizedSha256(sha256Hex(source))) {
            issues += "$label($path SHA-256 mismatch)"
        }
        val mediaRole = entry["media_role"]?.toString().orEmpty()
        if (mediaRole !in allowedMediaRoles) {
            issues += "$label($path unsupported media_role)"
        }
        val sourcePaths = (entry["source_paths"] as? List<*>)
            ?.map { sourcePath -> sourcePath?.toString().orEmpty() }
            .orEmpty()
        if (sourcePaths.isEmpty() || sourcePaths.any { sourcePath ->
                sourcePath.isBlank() || sourcePath.startsWith("/") ||
                    sourcePath.contains("..") || !rootProject.file(sourcePath).isFile
            }
        ) {
            issues += "$label($path source_paths must identify checked-in files)"
        }
        val producer = entry["producer"]?.toString().orEmpty()
        if (producer.isBlank()) issues += "$label($path producer required)"
        val reproducibility = entry["reproducibility"]?.toString().orEmpty()
        if (reproducibility !in allowedReproducibility) {
            issues += "$label($path unsupported reproducibility status)"
        }
        if (entry["rights_basis"]?.toString() !in allowedRightsBasis) {
            issues += "$label($path unsupported rights_basis)"
        }
        if (entry["evidence_id"]?.toString() != "release_asset_rights_signoff_v1") {
            issues += "$label($path evidence_id mismatch)"
        }
        if (producer == "authoritative_checked_in_master" && sourcePaths != listOf(path)) {
            issues += "$label($path authoritative master must identify itself as source)"
        }
        if (path.startsWith("app/src/main/res/raw/") && (
                mediaRole != "packaged_audio" ||
                    producer != "tools/generate_slot_feedback_audio.py" ||
                    reproducibility != "deterministic_generator"
                )
        ) {
            issues += "$label($path packaged audio provenance mismatch)"
        }
        if (path.startsWith("docs/store/assets/screenshots/") && (
                mediaRole != "store_screenshot" ||
                    producer != "tools/capture_play_store_screenshots.sh" ||
                    reproducibility != "instrumentation_capture"
                )
        ) {
            issues += "$label($path store screenshot provenance mismatch)"
        }
        if (path.startsWith("qa/source/") && (
                mediaRole != "retained_imagegen_source_master" ||
                    producer != "openai_image_generation" ||
                    sourcePaths != listOf(path)
                )
        ) {
            issues += "$label($path retained imagegen source provenance mismatch)"
        }
    }
    return issues.distinct()
}

fun assetRightsEvidenceIssues(
    evidenceFile: File,
    expectedSha256: String,
    expectedCommit: String? = null
): List<String> {
    val label = "V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE"
    if (!evidenceFile.isFile) return listOf("$label(file not found)")
    if (evidenceFile.length() !in 1L..65_536L) return listOf("$label(size must be 1..65536 bytes)")
    val normalizedExpectedSha256 = normalizedSha256(expectedSha256)
        ?: return listOf("V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256(valid SHA-256 required)")
    if (normalizedSha256(sha256Hex(evidenceFile)) != normalizedExpectedSha256) {
        return listOf("V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256(evidence mismatch)")
    }
    val root = runCatching {
        JsonSlurper().parse(evidenceFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$label(valid JSON object required)")
    val issues = mutableListOf<String>()
    val expectedRootKeys = setOf(
        "schema_version",
        "application_id",
        "version_code",
        "reviewed_commit",
        "reviewed_at_utc",
        "reviewer",
        "inventory_sha256",
        "checks",
        "notes"
    )
    if (root.keys.map { it.toString() }.toSet() != expectedRootKeys) {
        issues += "$label(exact schema-v1 fields required)"
    }
    if (root["schema_version"]?.toString()?.toIntOrNull() != 1) {
        issues += "$label(schema_version 1 required)"
    }
    if (root["application_id"]?.toString() != vSlotApplicationId) {
        issues += "$label(application_id $vSlotApplicationId required)"
    }
    if (root["version_code"]?.toString()?.toIntOrNull() != vSlotVersionCode) {
        issues += "$label(version_code $vSlotVersionCode required)"
    }
    val reviewedCommit = root["reviewed_commit"]?.toString().orEmpty()
    if (!reviewedCommit.matches(Regex("[0-9a-fA-F]{40,64}"))) {
        issues += "$label(valid reviewed_commit required)"
    } else if (expectedCommit != null && !reviewedCommit.equals(expectedCommit, ignoreCase = true)) {
        issues += "$label(reviewed_commit must match release HEAD)"
    }
    if (runCatching { Instant.parse(root["reviewed_at_utc"]?.toString().orEmpty()) }.isFailure) {
        issues += "$label(reviewed_at_utc must be an ISO-8601 instant)"
    }
    val reviewer = root["reviewer"]?.toString().orEmpty()
    if (reviewer.length < 3 || isPlaceholderReleaseValue(reviewer)) {
        issues += "$label(real reviewer identity required)"
    }
    val inventorySha256 = normalizedSha256(root["inventory_sha256"]?.toString().orEmpty())
    if (!assetProvenanceInventoryFile.isFile || inventorySha256 != normalizedSha256(sha256Hex(assetProvenanceInventoryFile))) {
        issues += "$label(inventory_sha256 must match the checked-in provenance inventory)"
    }
    val expectedChecks = setOf(
        "generation_terms_and_owner_attestation_confirmed",
        "every_inventory_entry_reviewed",
        "procedural_audio_confirmed",
        "store_assets_cleared",
        "font_license_and_output_rights_confirmed",
        "names_and_trademarks_cleared",
        "third_party_content_and_notices_cleared"
    )
    val checks = root["checks"] as? Map<*, *>
    if (checks == null || checks.keys.map { it.toString() }.toSet() != expectedChecks) {
        issues += "$label(exact rights checks required)"
    } else {
        expectedChecks.forEach { check ->
            if (checks[check] != true) issues += "$label(check $check must be true)"
        }
    }
    if (root["notes"]?.toString().orEmpty().isBlank()) {
        issues += "$label(review notes required)"
    }
    return issues.distinct()
}

fun dataSafetyEvidenceIssues(
    evidenceFile: File,
    expectedSha256: String,
    expectedCommit: String? = null,
    expectedPrivacyPolicyUrl: String = releasePrivacyPolicyUrl
): List<String> {
    val label = "V_SLOT_DATA_SAFETY_EVIDENCE_FILE"
    if (!evidenceFile.isFile) return listOf("$label(file not found)")
    if (evidenceFile.length() !in 1L..65_536L) return listOf("$label(size must be 1..65536 bytes)")

    val normalizedExpectedSha256 = normalizedSha256(expectedSha256)
        ?: return listOf("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256(valid SHA-256 required)")
    val actualSha256 = normalizedSha256(sha256Hex(evidenceFile)).orEmpty()
    if (actualSha256 != normalizedExpectedSha256) {
        return listOf("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256(evidence mismatch)")
    }

    val root = runCatching {
        JsonSlurper().parse(evidenceFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$label(valid JSON object required)")
    val issues = mutableListOf<String>()
    val expectedRootKeys = setOf(
        "schema_version",
        "application_id",
        "version_code",
        "reviewed_commit",
        "reviewed_at_utc",
        "privacy_policy_url",
        "network_capture_sha256",
        "play_console_export_sha256",
        "privacy_policy_snapshot_sha256",
        "evidence_archive_sha256",
        "checks"
    )
    if (root.keys.map { key -> key.toString() }.toSet() != expectedRootKeys) {
        issues += "$label(exact schema-v1 fields required)"
    }
    if (root["schema_version"]?.toString()?.toIntOrNull() != 1) {
        issues += "$label(schema_version 1 required)"
    }
    if (root["application_id"]?.toString() != vSlotApplicationId) {
        issues += "$label(application_id $vSlotApplicationId required)"
    }
    if (root["version_code"]?.toString()?.toIntOrNull() != vSlotVersionCode) {
        issues += "$label(version_code $vSlotVersionCode required)"
    }
    val reviewedCommit = root["reviewed_commit"]?.toString().orEmpty()
    if (!reviewedCommit.matches(Regex("[0-9a-fA-F]{40,64}"))) {
        issues += "$label(valid reviewed_commit required)"
    } else if (expectedCommit != null && !reviewedCommit.equals(expectedCommit, ignoreCase = true)) {
        issues += "$label(reviewed_commit must match release HEAD)"
    }
    if (runCatching { Instant.parse(root["reviewed_at_utc"]?.toString().orEmpty()) }.isFailure) {
        issues += "$label(reviewed_at_utc must be an ISO-8601 instant)"
    }
    val reviewedPrivacyUrl = root["privacy_policy_url"]?.toString().orEmpty()
    if (!isHttpsUrl(reviewedPrivacyUrl) || reviewedPrivacyUrl != expectedPrivacyPolicyUrl) {
        issues += "$label(privacy_policy_url must match the production release URL)"
    }
    listOf(
        "network_capture_sha256",
        "play_console_export_sha256",
        "privacy_policy_snapshot_sha256",
        "evidence_archive_sha256"
    ).forEach { field ->
        val digest = normalizedSha256(root[field]?.toString().orEmpty())
        if (digest == null || digest.toSet().size == 1) {
            issues += "$label($field must be a non-placeholder SHA-256)"
        }
    }

    val expectedChecks = setOf(
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
    val checks = root["checks"] as? Map<*, *>
    if (checks == null || checks.keys.map { key -> key.toString() }.toSet() != expectedChecks) {
        issues += "$label(exact checks schema required)"
    } else {
        expectedChecks.forEach { check ->
            if (checks[check] != true) issues += "$label(check $check must be true)"
        }
    }
    return issues.distinct()
}

fun dataSafetyRawEvidenceIssues(
    archiveFile: File,
    expectedArchiveSha256: String,
    evidenceFile: File
): List<String> {
    val label = "V_SLOT_DATA_SAFETY_RAW_EVIDENCE_FILE"
    val shaLabel = "V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256"
    val issues = mutableListOf<String>()
    if (!archiveFile.isFile) return listOf("$label(file not found)")
    if (archiveFile.length() !in 1L..50_000_000L) {
        return listOf("$label(size must be 1..50000000 bytes)")
    }
    val expectedSha = normalizedSha256(expectedArchiveSha256)
        ?: return listOf("$shaLabel(valid SHA-256 required)")
    if (normalizedSha256(sha256Hex(archiveFile)) != expectedSha) {
        return listOf("$shaLabel(evidence mismatch)")
    }
    if (!evidenceFile.isFile) return listOf("V_SLOT_DATA_SAFETY_EVIDENCE_FILE(file not found)")

    fun boundedZipContents(bytes: ByteArray, archiveName: String): Map<String, ByteArray>? {
        val contents = linkedMapOf<String, ByteArray>()
        return runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                var totalBytes = 0L
                var entryCount = 0
                while (true) {
                    val entry = input.nextEntry ?: break
                    entryCount += 1
                    require(entryCount <= 20)
                    if (entry.isDirectory) continue
                    val name = entry.name
                    require(
                        !name.startsWith("/") && !name.contains("\\") &&
                            name.split('/').none { segment ->
                                segment.isBlank() || segment == "." || segment == ".."
                            }
                    )
                    require(!contents.containsKey(name))
                    val entryBytes = input.readNBytes(25_000_001)
                    require(entryBytes.size <= 25_000_000)
                    totalBytes += entryBytes.size
                    require(totalBytes <= 50_000_000L)
                    contents[name] = entryBytes
                    input.closeEntry()
                }
            }
            contents
        }.onFailure {
            issues += "$label($archiveName must be a valid bounded ZIP archive)"
        }.getOrNull()
    }

    val outer = boundedZipContents(archiveFile.readBytes(), "raw evidence") ?: return issues.distinct()
    val requiredOuterEntries = setOf(
        "manifests/data-safety.json",
        "raw/network-capture.pcapng",
        "raw/play-console-export.csv",
        "raw/privacy-policy-snapshot.html",
        "raw/evidence-archive.zip"
    )
    if (outer.keys != requiredOuterEntries) {
        issues += "$label(exact raw evidence entry set required)"
        return issues.distinct()
    }
    if (!MessageDigest.isEqual(outer.getValue("manifests/data-safety.json"), evidenceFile.readBytes())) {
        issues += "$label(manifests/data-safety.json must exactly match its pinned manifest)"
    }
    val root = runCatching {
        JsonSlurper().parse(evidenceFile) as? Map<*, *>
    }.getOrNull()
    if (root == null) {
        issues += "V_SLOT_DATA_SAFETY_EVIDENCE_FILE(valid JSON object required)"
        return issues.distinct()
    }
    mapOf(
        "network_capture_sha256" to "raw/network-capture.pcapng",
        "play_console_export_sha256" to "raw/play-console-export.csv",
        "privacy_policy_snapshot_sha256" to "raw/privacy-policy-snapshot.html",
        "evidence_archive_sha256" to "raw/evidence-archive.zip"
    ).forEach { (manifestField, entryName) ->
        val expectedEntrySha = normalizedSha256(root[manifestField]?.toString().orEmpty())
        val actualEntrySha = normalizedSha256(sha256Hex(outer.getValue(entryName)))
        if (expectedEntrySha == null || actualEntrySha != expectedEntrySha) {
            issues += "$label($entryName must match $manifestField)"
        }
    }
    fun validPcapng(bytes: ByteArray): Boolean = runCatching {
        require(bytes.size in 96..25_000_000)
        require(bytes.copyOfRange(0, 4).contentEquals(
            byteArrayOf(0x0A.toByte(), 0x0D.toByte(), 0x0D.toByte(), 0x0A.toByte())
        ))
        val byteOrder = when {
            bytes.copyOfRange(8, 12).contentEquals(
                byteArrayOf(0x4D, 0x3C, 0x2B, 0x1A)
            ) -> ByteOrder.LITTLE_ENDIAN
            bytes.copyOfRange(8, 12).contentEquals(
                byteArrayOf(0x1A, 0x2B, 0x3C, 0x4D)
            ) -> ByteOrder.BIG_ENDIAN
            else -> error("pcapng byte-order magic")
        }
        fun intAt(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).order(byteOrder).int
        var offset = 0
        var interfaceCount = 0
        var packetCount = 0
        var capturedBytes = 0L
        var minimumTimestamp = Long.MAX_VALUE
        var maximumTimestamp = Long.MIN_VALUE
        while (offset < bytes.size) {
            require(bytes.size - offset >= 12)
            val blockType = intAt(offset)
            val blockLength = intAt(offset + 4)
            require(blockLength >= 12 && blockLength % 4 == 0 && offset + blockLength <= bytes.size)
            require(intAt(offset + blockLength - 4) == blockLength)
            when (blockType) {
                0x0A0D0D0A -> require(offset == 0 && blockLength >= 28)
                0x00000001 -> {
                    require(blockLength >= 20)
                    interfaceCount += 1
                }
                0x00000006 -> {
                    require(interfaceCount > 0 && blockLength >= 32)
                    val interfaceId = intAt(offset + 8)
                    val timestampHigh = intAt(offset + 12).toLong() and 0xFFFF_FFFFL
                    val timestampLow = intAt(offset + 16).toLong() and 0xFFFF_FFFFL
                    val capturedLength = intAt(offset + 20)
                    val originalLength = intAt(offset + 24)
                    val paddedCapturedLength = (capturedLength + 3) and -4
                    require(interfaceId in 0 until interfaceCount)
                    require(capturedLength > 0 && originalLength >= capturedLength)
                    require(blockLength >= 32 + paddedCapturedLength)
                    val timestamp = (timestampHigh shl 32) or timestampLow
                    minimumTimestamp = minOf(minimumTimestamp, timestamp)
                    maximumTimestamp = maxOf(maximumTimestamp, timestamp)
                    capturedBytes += capturedLength.toLong()
                    packetCount += 1
                }
            }
            offset += blockLength
        }
        require(offset == bytes.size)
        require(interfaceCount > 0 && packetCount >= 2 && capturedBytes > 0L)
        require(maximumTimestamp > minimumTimestamp)
        true
    }.getOrDefault(false)

    fun parseCsv(input: String): List<List<String>>? = runCatching {
        require(input.isNotEmpty() && '\u0000' !in input)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() {
            row += field.toString()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            rows += row
            row = mutableListOf()
        }
        while (index < input.length) {
            val character = input[index]
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < input.length && input[index + 1] == '"') {
                        field.append('"')
                        index += 1
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(character)
                }
            } else {
                when (character) {
                    '"' -> {
                        require(field.isEmpty())
                        quoted = true
                    }
                    ',' -> finishField()
                    '\n' -> finishRow()
                    '\r' -> {
                        require(index + 1 < input.length && input[index + 1] == '\n')
                        finishRow()
                        index += 1
                    }
                    else -> field.append(character)
                }
            }
            index += 1
        }
        require(!quoted)
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        rows
    }.getOrNull()

    val networkCapture = outer.getValue("raw/network-capture.pcapng")
    if (!validPcapng(networkCapture)) {
        issues += "$label(network capture must be a structured pcapng with interface and timestamped packet blocks)"
    }
    val playConsoleExport = outer.getValue("raw/play-console-export.csv").toString(Charsets.UTF_8)
    val playConsoleRows = parseCsv(playConsoleExport)
    if (playConsoleRows == null || playConsoleRows.size < 3 ||
        playConsoleRows.first() != listOf("question", "answer") ||
        playConsoleRows.drop(1).any { row ->
            row.size != 2 || row.any { value -> value.isBlank() || value.length > 4_096 }
        } ||
        playConsoleRows.drop(1).map { row -> row[0] }.toSet().size != playConsoleRows.size - 1
    ) {
        issues += "$label(Play Console export must use the exact question,answer CSV schema with unique reviewed rows)"
    }
    val privacySnapshot = outer.getValue("raw/privacy-policy-snapshot.html").toString(Charsets.UTF_8)
    val normalizedPrivacySnapshot = privacySnapshot.lowercase()
    if (privacySnapshot.toByteArray(Charsets.UTF_8).size < 512 ||
        !normalizedPrivacySnapshot.contains("<!doctype html") ||
        !normalizedPrivacySnapshot.contains("<html") ||
        !normalizedPrivacySnapshot.contains("<head") ||
        !normalizedPrivacySnapshot.contains("<title") ||
        !normalizedPrivacySnapshot.contains("<body") ||
        !normalizedPrivacySnapshot.contains("</html>")
    ) {
        issues += "$label(privacy policy snapshot must be a complete HTML document)"
    }
    val inner = boundedZipContents(outer.getValue("raw/evidence-archive.zip"), "inner evidence archive")
    val requiredInnerEntries = setOf(
        "network-capture.pcapng",
        "play-console-export.csv",
        "privacy-policy-snapshot.html"
    )
    if (inner == null || inner.keys != requiredInnerEntries) {
        issues += "$label(inner evidence archive must contain the exact raw evidence set)"
    } else {
        requiredInnerEntries.forEach { entryName ->
            if (!MessageDigest.isEqual(inner.getValue(entryName), outer.getValue("raw/$entryName"))) {
                issues += "$label(inner evidence archive entry $entryName must match outer raw evidence)"
            }
        }
    }
    return issues.distinct()
}

fun pinnedJsonEvidenceRoot(
    evidenceFile: File,
    expectedSha256: String,
    fileVariable: String,
    shaVariable: String,
    issues: MutableList<String>
): Map<*, *>? {
    if (!evidenceFile.isFile) {
        issues += "$fileVariable(file not found)"
        return null
    }
    if (evidenceFile.length() !in 1L..131_072L) {
        issues += "$fileVariable(size must be 1..131072 bytes)"
        return null
    }
    val expected = normalizedSha256(expectedSha256)
    if (expected == null) {
        issues += "$shaVariable(valid SHA-256 required)"
        return null
    }
    if (normalizedSha256(sha256Hex(evidenceFile)) != expected) {
        issues += "$shaVariable(evidence mismatch)"
        return null
    }
    return runCatching {
        JsonSlurper().parse(evidenceFile) as? Map<*, *>
    }.getOrNull().also { root ->
        if (root == null) issues += "$fileVariable(valid JSON object required)"
    }
}

fun expectedPhysicalSamsungStageTestIds(): Map<String, Set<String>> {
    val androidTestRoot = file("src/androidTest/java")
    val allTestIds = androidTestRoot.walkTopDown()
        .filter { source -> source.isFile && source.extension == "kt" }
        .flatMap { source ->
            val text = source.readText(Charsets.UTF_8)
            val packageName = Regex("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*$")
                .find(text)?.groupValues?.get(1).orEmpty()
            val className = Regex("(?m)^class\\s+([A-Za-z0-9_]+)")
                .find(text)?.groupValues?.get(1).orEmpty()
            if (packageName.isBlank() || className.isBlank()) return@flatMap emptySequence()
            Regex(
                "@Test\\s+(?:@[A-Za-z0-9_.]+(?:\\([^\\n]*\\))?\\s+)*" +
                    "fun\\s+([A-Za-z0-9_]+)\\s*\\("
            )
                .findAll(text)
                .map { match -> "$packageName.$className#${match.groupValues[1]}" }
        }
        .filterNot { testId -> testId.startsWith("com.vslot.app.SlotFrameMetricsTest#") }
        .toSortedSet()
    require(allTestIds.size == 62) {
        "Physical Samsung evidence expects exactly 62 non-frame-metrics androidTests; found ${allTestIds.size}."
    }
    val legacyPrimarySchemaUpgradeTestId =
        "com.vslot.app.data.TransactionalPlayerStateStoreAndroidTest#" +
            "legacyPrimarySchemasUpgradeInPlaceAndSurviveDurableRewrite"
    require(legacyPrimarySchemaUpgradeTestId in allTestIds) {
        "Physical Samsung evidence must include $legacyPrimarySchemaUpgradeTestId."
    }
    val filtered = mapOf(
        "portrait_smoke" to setOf(
            "com.vslot.app.MainActivitySmokeTest#homeNavigationOpensSlotPaytableSettingsAndPrivacyFallback"
        ),
        "font_scale_2_0_first_launch_legal_notices" to setOf(
            "com.vslot.app.MainActivitySmokeTest#largeFontLegalCopyWrapsAndKeepsActionsReachable",
            "com.vslot.app.MainActivitySmokeTest#largeFontDialogCopyWrapsAndKeepsActionsReachable",
            "com.vslot.app.ThirdPartyNoticesTest#settingsOpensThirdPartyNoticesWithBundledNoticeText"
        ),
        "compact_portrait_settings" to setOf(
            "com.vslot.app.MainActivitySmokeTest#settingsCompactPortraitKeepsScrollableControlsAboveSafetyFooter"
        ),
        "compact_landscape_rotation_1" to setOf(
            "com.vslot.app.MainActivitySmokeTest#compactLandscapeKeepsHomeAndSlotActionsReachable"
        ),
        "compact_landscape_rotation_3" to setOf(
            "com.vslot.app.MainActivitySmokeTest#compactLandscapeKeepsHomeAndSlotActionsReachable"
        )
    )
    return filtered + mapOf(
        "landscape_rotation_1" to allTestIds,
        "landscape_rotation_3" to allTestIds
    )
}

fun expectedPhysicalSamsungSkippedTestIds(): Map<String, Set<String>> {
    val profileSpecificTests = setOf(
        "com.vslot.app.MainActivitySmokeTest#settingsCompactPortraitKeepsScrollableControlsAboveSafetyFooter",
        "com.vslot.app.MainActivitySmokeTest#compactLandscapeKeepsHomeAndSlotActionsReachable",
        "com.vslot.app.MainActivitySmokeTest#largeFontLegalCopyWrapsAndKeepsActionsReachable",
        "com.vslot.app.MainActivitySmokeTest#largeFontDialogCopyWrapsAndKeepsActionsReachable"
    )
    return mapOf(
        "landscape_rotation_1" to profileSpecificTests,
        "landscape_rotation_3" to profileSpecificTests
    )
}

fun physicalSamsungRawEvidenceIssues(
    archiveFile: File,
    expectedArchiveSha256: String,
    samsungEvidenceFile: File,
    processDeathEvidenceFile: File,
    frameMetricsEvidenceFile: File,
    expectedCommit: String,
    expectedQaApkPayloadSha256: String
): List<String> {
    val label = "V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_FILE"
    val shaLabel = "V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256"
    val issues = mutableListOf<String>()
    if (!archiveFile.isFile) return listOf("$label(file not found)")
    if (archiveFile.length() !in 1L..25_000_000L) {
        return listOf("$label(size must be 1..25000000 bytes)")
    }
    val expectedSha = normalizedSha256(expectedArchiveSha256)
        ?: return listOf("$shaLabel(valid SHA-256 required)")
    if (normalizedSha256(sha256Hex(archiveFile)) != expectedSha) {
        return listOf("$shaLabel(evidence mismatch)")
    }

    val contents = linkedMapOf<String, ByteArray>()
    runCatching {
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.size !in 1..500) error("entry count")
            var totalBytes = 0L
            entries.forEach { entry ->
                val name = entry.name
                if (entry.isDirectory) return@forEach
                if (name.startsWith("/") || name.contains("\\") ||
                    name.split('/').any { segment -> segment.isBlank() || segment == "." || segment == ".." }
                ) {
                    error("unsafe entry")
                }
                if (contents.containsKey(name) || entry.size !in 0L..5_000_000L) error("invalid entry")
                val bytes = zip.getInputStream(entry).use { input -> input.readNBytes(5_000_001) }
                if (bytes.size > 5_000_000 || (entry.size >= 0L && bytes.size.toLong() != entry.size)) {
                    error("entry size")
                }
                totalBytes += bytes.size
                if (totalBytes > 25_000_000L) error("expanded size")
                contents[name] = bytes
            }
        }
    }.onFailure {
        issues += "$label(valid bounded ZIP archive required)"
    }
    if (issues.isNotEmpty()) return issues

    mapOf(
        "manifests/connected-tests.json" to samsungEvidenceFile,
        "manifests/process-death.json" to processDeathEvidenceFile,
        "manifests/frame-metrics.json" to frameMetricsEvidenceFile
    ).forEach { (entryName, sourceFile) ->
        val archived = contents[entryName]
        if (archived == null || !MessageDigest.isEqual(archived, sourceFile.readBytes())) {
            issues += "$label($entryName must exactly match its pinned manifest)"
        }
    }

    data class XmlSuiteEvidence(
        val counts: LongArray,
        val testIds: Set<String>,
        val skippedTestIds: Set<String>
    )
    fun xmlSuiteEvidence(bytes: ByteArray): XmlSuiteEvidence? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val suites = document.getElementsByTagName("testsuite")
        require(suites.length > 0)
        val totals = LongArray(4)
        for (index in 0 until suites.length) {
            val attributes = suites.item(index).attributes
            listOf("tests", "failures", "errors", "skipped").forEachIndexed { fieldIndex, field ->
                val value = attributes.getNamedItem(field)?.nodeValue?.toLongOrNull()
                require(value != null && value >= 0L)
                totals[fieldIndex] += value
            }
        }
        val testCases = document.getElementsByTagName("testcase")
        val skippedTestIds = sortedSetOf<String>()
        val testIds = buildSet {
            for (index in 0 until testCases.length) {
                val testCase = testCases.item(index)
                val attributes = testCase.attributes
                val className = attributes.getNamedItem("classname")?.nodeValue.orEmpty()
                val testName = attributes.getNamedItem("name")?.nodeValue.orEmpty()
                require(className.isNotBlank() && testName.isNotBlank())
                val testId = "$className#$testName"
                var skipped = false
                val childNodes = testCase.childNodes
                for (childIndex in 0 until childNodes.length) {
                    val childName = childNodes.item(childIndex).nodeName.lowercase()
                    require(childName !in setOf("failure", "error"))
                    if (childName == "skipped") skipped = true
                }
                require(add(testId))
                if (skipped) skippedTestIds += testId
            }
        }
        require(testIds.size.toLong() == totals[0])
        require(skippedTestIds.size.toLong() == totals[3])
        XmlSuiteEvidence(totals, testIds, skippedTestIds)
    }.getOrNull()

    expectedPhysicalSamsungStageTestIds().forEach { (stage, expectedTestIds) ->
        val expectedSkippedTestIds = expectedPhysicalSamsungSkippedTestIds()[stage].orEmpty()
        val reports = contents.filterKeys { name ->
            name.startsWith("raw/connected/$stage/") && name.endsWith(".xml")
        }.values
        val parsedReports = reports.mapNotNull(::xmlSuiteEvidence)
        val totals = LongArray(4)
        val actualTestIds = sortedSetOf<String>()
        val actualSkippedTestIds = sortedSetOf<String>()
        parsedReports.forEach { report ->
            report.counts.indices.forEach { index -> totals[index] += report.counts[index] }
            actualTestIds += report.testIds
            actualSkippedTestIds += report.skippedTestIds
        }
        if (reports.isEmpty() || parsedReports.size != reports.size ||
            totals[0] != expectedTestIds.size.toLong() || totals[1] != 0L ||
            totals[2] != 0L || totals[3] != expectedSkippedTestIds.size.toLong() ||
            actualTestIds != expectedTestIds || actualSkippedTestIds != expectedSkippedTestIds
        ) {
            issues += "$label(raw connected stage $stage must prove the exact ${expectedTestIds.size} testcase IDs and expected profile-specific skips)"
        }
    }

    val normalizedCommit = expectedCommit.lowercase()
    val normalizedPayload = normalizedSha256(expectedQaApkPayloadSha256)?.lowercase().orEmpty()
    val processLog = contents["raw/process-death/process-death.log"]
        ?.toString(Charsets.UTF_8).orEmpty()
    listOf(
        "V_SLOT_PROCESS_DEATH_QA",
        "git_commit=$normalizedCommit",
        "apk_payload_sha256=$normalizedPayload",
        "presentation_observed=true",
        "first_draw_observed=true",
        "pending_journal_cleared=true",
        "second_restart_unchanged=true",
        "result_status=passed"
    ).forEach { marker ->
        if (!processLog.contains(marker, ignoreCase = marker.contains("sha256=") || marker.startsWith("git_commit="))) {
            issues += "$label(process-death raw log missing $marker)"
        }
    }
    val frameLog = contents["raw/frame-metrics/frame-metrics.log"]
        ?.toString(Charsets.UTF_8).orEmpty()
    listOf(
        "V_SLOT_FRAME_METRICS_QA",
        "git_commit=$normalizedCommit",
        "apk_payload_sha256=$normalizedPayload",
        "frame_profile=physical_samsung",
        "SLOT_FRAME_METRICS samples=",
        "BUILD SUCCESSFUL"
    ).forEach { marker ->
        if (!frameLog.contains(marker, ignoreCase = marker.contains("sha256=") || marker.startsWith("git_commit="))) {
            issues += "$label(frame-metrics raw log missing $marker)"
        }
    }
    return issues.distinct()
}

fun physicalSamsungEvidenceIssues(
    samsungEvidenceFile: File,
    expectedSamsungSha256: String,
    processDeathEvidenceFile: File,
    expectedProcessDeathSha256: String,
    frameMetricsEvidenceFile: File,
    expectedFrameMetricsSha256: String,
    rawEvidenceArchiveFile: File,
    expectedRawEvidenceArchiveSha256: String,
    expectedCommit: String,
    expectedQaApkPayloadSha256: String
): List<String> {
    val issues = mutableListOf<String>()
    val samsungLabel = "V_SLOT_SAMSUNG_QA_EVIDENCE_FILE"
    val processLabel = "V_SLOT_PROCESS_DEATH_EVIDENCE_FILE"
    val frameLabel = "V_SLOT_FRAME_METRICS_EVIDENCE_FILE"
    val samsung = pinnedJsonEvidenceRoot(
        samsungEvidenceFile,
        expectedSamsungSha256,
        samsungLabel,
        "V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256",
        issues
    )
    val processDeath = pinnedJsonEvidenceRoot(
        processDeathEvidenceFile,
        expectedProcessDeathSha256,
        processLabel,
        "V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256",
        issues
    )
    val frameMetrics = pinnedJsonEvidenceRoot(
        frameMetricsEvidenceFile,
        expectedFrameMetricsSha256,
        frameLabel,
        "V_SLOT_FRAME_METRICS_EVIDENCE_SHA256",
        issues
    )
    issues += physicalSamsungRawEvidenceIssues(
        archiveFile = rawEvidenceArchiveFile,
        expectedArchiveSha256 = expectedRawEvidenceArchiveSha256,
        samsungEvidenceFile = samsungEvidenceFile,
        processDeathEvidenceFile = processDeathEvidenceFile,
        frameMetricsEvidenceFile = frameMetricsEvidenceFile,
        expectedCommit = expectedCommit,
        expectedQaApkPayloadSha256 = expectedQaApkPayloadSha256
    )
    if (samsung == null || processDeath == null || frameMetrics == null) return issues.distinct()

    fun Map<*, *>.keysAsStrings(): Set<String> = keys.map(Any?::toString).toSet()
    fun exactKeys(value: Any?, expected: Set<String>, label: String): Map<*, *>? {
        val map = value as? Map<*, *>
        if (map == null || map.keysAsStrings() != expected) {
            issues += "$label(exact fields required)"
            return null
        }
        return map
    }
    fun longValue(value: Any?): Long? = (value as? Number)?.toLong()
        ?: value?.toString()?.toLongOrNull()
    fun doubleValue(value: Any?): Double? = (value as? Number)?.toDouble()
        ?: value?.toString()?.toDoubleOrNull()
    fun requireInstant(root: Map<*, *>, label: String) {
        if (runCatching { Instant.parse(root["generated_at_utc"]?.toString().orEmpty()) }.isFailure) {
            issues += "$label(generated_at_utc must be an ISO-8601 instant)"
        }
    }
    fun requireSource(root: Map<*, *>, label: String): String {
        val source = exactKeys(root["source"], setOf("git_commit"), "$label.source")
        val commit = source?.get("git_commit")?.toString().orEmpty()
        if (!commit.matches(Regex("[0-9a-fA-F]{40,64}"))) {
            issues += "$label(source.git_commit must be a committed revision)"
        } else if (!commit.equals(expectedCommit, ignoreCase = true)) {
            issues += "$label(source.git_commit must match release HEAD)"
        }
        return commit.lowercase()
    }
    fun requireApk(root: Map<*, *>, label: String): String {
        val apk = exactKeys(root["apk"], setOf("file_name", "sha256", "payload_sha256"), "$label.apk")
        val fileName = apk?.get("file_name")?.toString().orEmpty()
        val digest = normalizedSha256(apk?.get("sha256")?.toString().orEmpty())
        val payloadDigest = normalizedSha256(apk?.get("payload_sha256")?.toString().orEmpty())
        if (fileName.isBlank() || fileName == "unavailable") issues += "$label(apk.file_name required)"
        if (digest == null || digest.toSet().size == 1) {
            issues += "$label(apk.sha256 must be a non-placeholder SHA-256)"
        }
        if (payloadDigest == null || payloadDigest.toSet().size == 1) {
            issues += "$label(apk.payload_sha256 must be a non-placeholder SHA-256)"
        } else if (payloadDigest != normalizedSha256(expectedQaApkPayloadSha256)) {
            issues += "$label(apk.payload_sha256 must match the release-gated QA APK payload)"
        }
        return payloadDigest.orEmpty()
    }
    fun requirePhysicalSamsungDevice(root: Map<*, *>, label: String, expectedKeys: Set<String>): String {
        val device = exactKeys(root["device"], expectedKeys, "$label.device")
        val serialDigest = normalizedSha256(device?.get("serial_sha256")?.toString().orEmpty())
        if (serialDigest == null || serialDigest.toSet().size == 1) {
            issues += "$label(device.serial_sha256 must be a non-placeholder SHA-256)"
        }
        if (!device?.get("manufacturer")?.toString().orEmpty().equals("samsung", ignoreCase = true)) {
            issues += "$label(physical Samsung manufacturer required)"
        }
        if (!device?.get("model")?.toString().orEmpty().matches(Regex("SM[-_A-Za-z0-9]+"))) {
            issues += "$label(valid Samsung SM model required)"
        }
        listOf("android_version", "one_ui_version").filter(expectedKeys::contains).forEach { field ->
            val value = device?.get(field)?.toString().orEmpty()
            if (value.isBlank() || value.equals("unknown", ignoreCase = true)) {
                issues += "$label(device.$field required)"
            }
        }
        return serialDigest.orEmpty()
    }
    fun requirePassedResult(root: Map<*, *>, label: String, cleanupRequired: Boolean) {
        val expectedKeys = if (cleanupRequired) {
            setOf("status", "test_status", "cleanup_status", "exit_code")
        } else {
            setOf("status", "test_status", "exit_code")
        }
        val result = exactKeys(root["result"], expectedKeys, "$label.result")
        if (result?.get("status")?.toString() != "passed" ||
            result["test_status"]?.toString() != "passed" ||
            longValue(result["exit_code"]) != 0L ||
            (cleanupRequired && result["cleanup_status"]?.toString() != "passed")
        ) {
            issues += "$label(successful test${if (cleanupRequired) " and cleanup" else ""} required)"
        }
    }

    val samsungRootKeys = setOf(
        "schema_version", "generated_at_utc", "source", "device", "apk", "orientations", "stages", "result"
    )
    if (samsung.keysAsStrings() != samsungRootKeys) issues += "$samsungLabel(exact schema-v6 fields required)"
    if (longValue(samsung["schema_version"]) != 6L) issues += "$samsungLabel(schema_version 6 required)"
    requireInstant(samsung, samsungLabel)
    val samsungCommit = requireSource(samsung, samsungLabel)
    val samsungSerial = requirePhysicalSamsungDevice(
        samsung,
        samsungLabel,
        setOf(
            "serial_sha256", "manufacturer", "model", "android_version", "build_fingerprint",
            "one_ui_version", "locale", "size", "density", "font_scale"
        )
    )
    val samsungDevice = samsung["device"] as? Map<*, *>
    if (samsungDevice?.get("build_fingerprint")?.toString().orEmpty().let { it.isBlank() || it == "unknown" }) {
        issues += "$samsungLabel(device.build_fingerprint required)"
    }
    val samsungApk = requireApk(samsung, samsungLabel)
    val orientations = samsung["orientations"] as? List<*>
    if (orientations?.size != 2) {
        issues += "$samsungLabel(exact rotations 1 and 3 required)"
    } else {
        orientations.forEachIndexed { index, value ->
            val orientation = exactKeys(
                value,
                setOf(
                    "user_rotation", "observed_orientation", "logical_width", "logical_height",
                    "verified_landscape", "test_status"
                ),
                "$samsungLabel.orientations[$index]"
            )
            val expectedRotation = if (index == 0) 1L else 3L
            val width = longValue(orientation?.get("logical_width"))
            val height = longValue(orientation?.get("logical_height"))
            if (longValue(orientation?.get("user_rotation")) != expectedRotation ||
                longValue(orientation?.get("observed_orientation")) != expectedRotation ||
                width == null || height == null || width <= height ||
                orientation?.get("verified_landscape") != true ||
                orientation["test_status"]?.toString() != "passed"
            ) {
                issues += "$samsungLabel(rotation $expectedRotation must be verified landscape and passed)"
            }
        }
    }
    val expectedStages = linkedMapOf(
        "portrait_smoke" to 1L,
        "font_scale_2_0_first_launch_legal_notices" to 3L,
        "compact_portrait_settings" to 1L,
        "compact_landscape_rotation_1" to 1L,
        "compact_landscape_rotation_3" to 1L
    )
    val allStageNames = expectedStages.keys + setOf("landscape_rotation_1", "landscape_rotation_3")
    val stages = exactKeys(samsung["stages"], allStageNames, "$samsungLabel.stages")
    val filteredStageFields = mapOf(
        "portrait_smoke" to setOf("status", "user_rotation", "display_profile", "tests", "skipped"),
        "font_scale_2_0_first_launch_legal_notices" to
            setOf("status", "user_rotation", "font_scale", "tests", "skipped"),
        "compact_portrait_settings" to
            setOf("status", "user_rotation", "wm_size", "wm_density", "font_scale", "tests", "skipped"),
        "compact_landscape_rotation_1" to
            setOf("status", "user_rotation", "wm_size", "wm_density", "tests", "skipped"),
        "compact_landscape_rotation_3" to
            setOf("status", "user_rotation", "wm_size", "wm_density", "tests", "skipped")
    )
    expectedStages.forEach { (stageName, expectedTests) ->
        val stage = exactKeys(
            stages?.get(stageName),
            filteredStageFields.getValue(stageName),
            "$samsungLabel.stages.$stageName"
        )
        if (stage == null || stage["status"]?.toString() != "passed" ||
            longValue(stage["tests"]) != expectedTests || longValue(stage["skipped"]) != 0L
        ) {
            issues += "$samsungLabel(stage $stageName must pass $expectedTests tests with no skips)"
        }
    }
    listOf("landscape_rotation_1" to 1L, "landscape_rotation_3" to 3L).forEach { (stageName, rotation) ->
        val stage = exactKeys(
            stages?.get(stageName),
            setOf("status", "user_rotation", "display_profile", "tests", "skipped"),
            "$samsungLabel.stages.$stageName"
        )
        if (stage == null || stage["status"]?.toString() != "passed" ||
            longValue(stage["user_rotation"]) != rotation || stage["display_profile"]?.toString() != "captured" ||
            longValue(stage["tests"]) != 62L || longValue(stage["skipped"]) != 4L
        ) {
            issues += "$samsungLabel(stage $stageName must run all 62 tests with exactly 4 profile-specific skips on captured rotation $rotation)"
        }
    }
    requirePassedResult(samsung, samsungLabel, cleanupRequired = true)

    val processRootKeys = setOf(
        "schema_version", "generated_at_utc", "qa_profile", "source", "device", "apk", "fixture",
        "processes", "verification", "result"
    )
    if (processDeath.keysAsStrings() != processRootKeys) issues += "$processLabel(exact schema-v5 fields required)"
    if (longValue(processDeath["schema_version"]) != 5L) issues += "$processLabel(schema_version 5 required)"
    if (processDeath["qa_profile"]?.toString() != "physical_samsung") {
        issues += "$processLabel(qa_profile physical_samsung required)"
    }
    requireInstant(processDeath, processLabel)
    val processCommit = requireSource(processDeath, processLabel)
    val processSerial = requirePhysicalSamsungDevice(
        processDeath,
        processLabel,
        setOf("serial_sha256", "manufacturer", "model", "android_version", "one_ui_version")
    )
    val processApk = requireApk(processDeath, processLabel)
    val fixture = exactKeys(
        processDeath["fixture"],
        setOf(
            "settlement_id", "initial_balance", "reserved_balance", "expected_balance",
            "expected_level_xp", "expected_free_spins", "expected_win"
        ),
        "$processLabel.fixture"
    )
    val settlementId = fixture?.get("settlement_id")?.toString().orEmpty()
    val initialBalance = longValue(fixture?.get("initial_balance"))
    val reservedBalance = longValue(fixture?.get("reserved_balance"))
    val expectedBalance = longValue(fixture?.get("expected_balance"))
    val expectedWin = longValue(fixture?.get("expected_win"))
    val expectedLevelXp = longValue(fixture?.get("expected_level_xp"))
    val expectedFreeSpins = longValue(fixture?.get("expected_free_spins"))
    if (settlementId.isBlank() || settlementId == "unavailable" || initialBalance == null ||
        reservedBalance == null || expectedBalance == null || expectedWin == null ||
        expectedLevelXp == null || expectedLevelXp < 0L || expectedFreeSpins == null || expectedFreeSpins < 0L ||
        reservedBalance >= initialBalance || expectedBalance != reservedBalance + expectedWin
    ) {
        issues += "$processLabel(valid paid-spin fixture arithmetic required)"
    }
    val processes = exactKeys(
        processDeath["processes"],
        setOf("prepare_pid", "first_restart_pid", "second_restart_pid"),
        "$processLabel.processes"
    )
    val pids = listOf("prepare_pid", "first_restart_pid", "second_restart_pid")
        .map { field -> longValue(processes?.get(field)) }
    if (pids.any { it == null || it <= 0L } || pids.filterNotNull().toSet().size != 3) {
        issues += "$processLabel(three distinct positive process IDs required)"
    }
    val verification = exactKeys(
        processDeath["verification"],
        setOf(
            "presentation_observed", "first_draw_observed", "pending_journal_cleared",
            "second_restart_unchanged"
        ),
        "$processLabel.verification"
    )
    if (verification == null || verification.values.any { value -> value != true }) {
        issues += "$processLabel(all exactly-once verification checks must be true)"
    }
    requirePassedResult(processDeath, processLabel, cleanupRequired = true)

    val frameRootKeys = setOf(
        "schema_version", "generated_at_utc", "qa_profile", "source", "device", "apk", "metrics", "limits", "result"
    )
    if (frameMetrics.keysAsStrings() != frameRootKeys) issues += "$frameLabel(exact schema-v2 fields required)"
    if (longValue(frameMetrics["schema_version"]) != 2L) issues += "$frameLabel(schema_version 2 required)"
    if (frameMetrics["qa_profile"]?.toString() != "physical_samsung") {
        issues += "$frameLabel(qa_profile physical_samsung required)"
    }
    requireInstant(frameMetrics, frameLabel)
    val frameCommit = requireSource(frameMetrics, frameLabel)
    val frameSerial = requirePhysicalSamsungDevice(
        frameMetrics,
        frameLabel,
        setOf("serial_sha256", "manufacturer", "model", "android_version", "build_fingerprint_sha256")
    )
    val frameDevice = frameMetrics["device"] as? Map<*, *>
    val frameFingerprint = normalizedSha256(frameDevice?.get("build_fingerprint_sha256")?.toString().orEmpty())
    if (frameFingerprint == null || frameFingerprint.toSet().size == 1) {
        issues += "$frameLabel(device.build_fingerprint_sha256 must be a non-placeholder SHA-256)"
    }
    val frameApk = requireApk(frameMetrics, frameLabel)
    val metrics = exactKeys(
        frameMetrics["metrics"],
        setOf(
            "samples", "p50_ms", "p95_ms", "max_ms", "jank_rate_pct", "refresh_hz",
            "missed_deadline_threshold_ms", "missed_deadline_rate_pct", "dropped_callbacks"
        ),
        "$frameLabel.metrics"
    )
    val samples = longValue(metrics?.get("samples"))
    val p50 = doubleValue(metrics?.get("p50_ms"))
    val p95 = doubleValue(metrics?.get("p95_ms"))
    val maximum = doubleValue(metrics?.get("max_ms"))
    val jankRate = doubleValue(metrics?.get("jank_rate_pct"))
    val refreshRate = doubleValue(metrics?.get("refresh_hz"))
    val missedThreshold = doubleValue(metrics?.get("missed_deadline_threshold_ms"))
    val missedRate = doubleValue(metrics?.get("missed_deadline_rate_pct"))
    val droppedCallbacks = longValue(metrics?.get("dropped_callbacks"))
    val expectedMissedThreshold = refreshRate?.takeIf { it >= 24.0 }?.let { maxOf(20.0, 1_250.0 / it) }
    if (samples == null || samples < 30L || p50 == null || p95 == null || maximum == null ||
        p50 < 0.0 || p50 > p95 || p95 > maximum || p95 > 50.0 || maximum > 100.0 ||
        jankRate == null || jankRate !in 0.0..10.0 || refreshRate == null || refreshRate < 24.0 ||
        missedThreshold == null || expectedMissedThreshold == null ||
        abs(missedThreshold - expectedMissedThreshold) > 0.25 ||
        missedRate == null || missedRate !in 0.0..20.0 || droppedCallbacks != 0L
    ) {
        issues += "$frameLabel(physical Samsung frame metrics exceed release limits or are incomplete)"
    }
    val limits = exactKeys(
        frameMetrics["limits"],
        setOf("p95_ms", "max_ms", "jank_rate_pct", "missed_deadline_rate_pct"),
        "$frameLabel.limits"
    )
    if (doubleValue(limits?.get("p95_ms")) != 50.0 || doubleValue(limits?.get("max_ms")) != 100.0 ||
        doubleValue(limits?.get("jank_rate_pct")) != 10.0 ||
        doubleValue(limits?.get("missed_deadline_rate_pct")) != 20.0
    ) {
        issues += "$frameLabel(exact physical Samsung limits required)"
    }
    requirePassedResult(frameMetrics, frameLabel, cleanupRequired = false)

    if (setOf(samsungCommit, processCommit, frameCommit).size != 1) {
        issues += "Physical Samsung evidence must share one Git commit."
    }
    if (setOf(samsungSerial, processSerial, frameSerial).size != 1) {
        issues += "Physical Samsung evidence must share one device serial hash."
    }
    if (setOf(samsungApk, processApk, frameApk).size != 1) {
        issues += "Physical Samsung evidence must share one canonical QA APK payload SHA-256."
    }
    return issues.distinct()
}

fun uploadCertificateReadinessIssues(
    keystoreFile: File,
    keyAlias: String,
    storePassword: String,
    expectedSha256: String
): List<String> {
    val expected = normalizedSha256(expectedSha256)
        ?: return listOf("V_SLOT_RELEASE_CERT_SHA256(valid SHA-256 required)")
    val keytool = File(System.getProperty("java.home"), "bin/keytool")
    if (!keytool.isFile) return listOf("V_SLOT_RELEASE_CERT_SHA256(keytool unavailable)")

    val process = runCatching {
        ProcessBuilder(
            keytool.absolutePath,
            "-exportcert",
            "-keystore",
            keystoreFile.absolutePath,
            "-alias",
            keyAlias,
            "-storepass:env",
            KEYTOOL_STORE_PASSWORD_ENV
        )
            .directory(projectDir)
            .apply {
                environment()[KEYTOOL_STORE_PASSWORD_ENV] = storePassword
            }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.getOrElse {
        return listOf("V_SLOT_RELEASE_CERT_SHA256(certificate export failed)")
    }
    val certificateBytes = process.inputStream.readBytes()
    val exitCode = process.waitFor()
    if (exitCode != 0 || certificateBytes.isEmpty()) {
        return listOf("V_SLOT_RELEASE_CERT_SHA256(certificate export failed)")
    }

    val actual = MessageDigest.getInstance("SHA-256")
        .digest(certificateBytes)
        .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    return if (actual == expected) {
        emptyList()
    } else {
        listOf("V_SLOT_RELEASE_CERT_SHA256(upload certificate mismatch)")
    }
}

val KEYTOOL_STORE_PASSWORD_ENV = "V_SLOT_KEYTOOL_STORE_PASSWORD"

data class StorePngInfo(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val colorType: Int
)

fun readStorePngInfo(file: File): StorePngInfo? = runCatching {
    val header = file.inputStream().use { input -> input.readNBytes(26) }
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    check(header.size == 26 && header.copyOfRange(0, 8).contentEquals(signature))
    check(String(header, 12, 4, Charsets.US_ASCII) == "IHDR")
    StorePngInfo(
        width = ByteBuffer.wrap(header, 16, 4).order(ByteOrder.BIG_ENDIAN).int,
        height = ByteBuffer.wrap(header, 20, 4).order(ByteOrder.BIG_ENDIAN).int,
        bitDepth = header[24].toInt() and 0xFF,
        colorType = header[25].toInt() and 0xFF
    )
}.getOrNull()

fun storeGraphicsExportIssues(): List<String> {
    val label = "docs/store/assets/store-graphics-export-manifest.json"
    val manifestFile = rootProject.file(label)
    if (!manifestFile.isFile) return listOf("$label(file not found)")
    val manifest = runCatching {
        JsonSlurper().parse(manifestFile) as? Map<*, *>
    }.getOrNull() ?: return listOf("$label(valid JSON object required)")
    val issues = mutableListOf<String>()
    if (manifest.keys.map { it.toString() }.toSet() != setOf(
            "schema_version",
            "exporter",
            "exporter_sha256",
            "resampling",
            "entries"
        )
    ) {
        issues += "$label(exact schema-v1 fields required)"
    }
    if (manifest["schema_version"]?.toString()?.toIntOrNull() != 1) {
        issues += "$label(schema_version 1 required)"
    }
    val exporterPath = manifest["exporter"]?.toString().orEmpty()
    val exporter = rootProject.file(exporterPath)
    if (exporterPath != "tools/export_store_graphics.py" || !exporter.isFile) {
        issues += "$label(expected exporter required)"
    } else if (normalizedSha256(manifest["exporter_sha256"]?.toString().orEmpty()) != normalizedSha256(sha256Hex(exporter))) {
        issues += "$label(exporter SHA-256 mismatch)"
    }
    if (manifest["resampling"] != "Pillow LANCZOS") {
        issues += "$label(resampling declaration mismatch)"
    }
    val expectedOutputs = setOf(
        "app/src/main/res/drawable-nodpi/app_icon_art_v2.png",
        "docs/store/assets/v-slot-feature-graphic-1024x500-v1.png",
        "docs/store/assets/v-slot-icon-512-v2.png"
    )
    val expectedEntryKeys = setOf(
        "kind",
        "source",
        "source_sha256",
        "output",
        "output_sha256"
    )
    val entries = manifest["entries"] as? List<*>
    val parsedEntries = entries.orEmpty().mapNotNull { entry -> entry as? Map<*, *> }
    val actualOutputs = parsedEntries.map { entry -> entry["output"]?.toString().orEmpty() }
    if (entries == null || parsedEntries.size != 3 || actualOutputs.toSet() != expectedOutputs) {
        issues += "$label(exact three exported assets required)"
    }
    parsedEntries.forEach { entry ->
        val outputPath = entry["output"]?.toString().orEmpty()
        val sourcePath = entry["source"]?.toString().orEmpty()
        if (entry.keys.map { it.toString() }.toSet() != expectedEntryKeys) {
            issues += "$label($outputPath exact entry fields required)"
            return@forEach
        }
        if (outputPath.startsWith("/") || outputPath.contains("..") || sourcePath.startsWith("/") || sourcePath.contains("..")) {
            issues += "$label(repository-relative paths required)"
            return@forEach
        }
        val source = rootProject.file(sourcePath)
        val output = rootProject.file(outputPath)
        if (!source.isFile || normalizedSha256(entry["source_sha256"]?.toString().orEmpty()) != normalizedSha256(sha256Hex(source))) {
            issues += "$label($sourcePath source SHA-256 mismatch)"
        }
        if (!output.isFile || normalizedSha256(entry["output_sha256"]?.toString().orEmpty()) != normalizedSha256(sha256Hex(output))) {
            issues += "$label($outputPath output SHA-256 mismatch)"
        }
    }
    return issues.distinct()
}

fun storeListingAssetIssues(): List<String> = buildList {
    addAll(storeGraphicsExportIssues())
    val storeRoot = rootProject.file("docs/store")
    val assetRoot = File(storeRoot, "assets")
    val listingFile = File(storeRoot, "store-listing-ru.json")
    val iconFile = File(assetRoot, "v-slot-icon-512-v2.png")
    val featureGraphicFile = File(assetRoot, "v-slot-feature-graphic-1024x500-v1.png")
    val screenshotRoot = File(assetRoot, "screenshots")
    val screenshotMetadataFile = File(screenshotRoot, "capture-metadata.json")
    val expectedScreenshots = setOf(
        "01-home.png",
        "02-violet-slot.png",
        "03-paytable.png",
        "04-settings.png",
        "05-free-spins.png"
    )

    val listing = if (!listingFile.isFile) {
        add("docs/store/store-listing-ru.json(file not found)")
        null
    } else {
        runCatching { JsonSlurper().parse(listingFile) as? Map<*, *> }.getOrNull().also {
            if (it == null) add("docs/store/store-listing-ru.json(valid JSON object required)")
        }
    }
    if (listing != null) {
        fun listingText(key: String): String = listing[key] as? String ?: ""
        val boundedFields = mapOf(
            "title" to 30,
            "short_description" to 80,
            "full_description" to 4_000,
            "release_notes" to 500,
            "feature_graphic_alt_text" to 140
        )
        if (listingText("locale") != "ru-RU") add("store listing locale must be ru-RU")
        boundedFields.forEach { (key, maxLength) ->
            val value = listingText(key)
            if (value.isBlank() || value.length > maxLength) {
                add("store listing $key must contain 1..$maxLength characters")
            }
        }
        val screenshotAltTexts = listing["screenshot_alt_texts"] as? Map<*, *>
        if (screenshotAltTexts == null || screenshotAltTexts.keys.map(Any?::toString).toSet() != expectedScreenshots) {
            add("store listing must provide alt text for exactly five prepared screenshots")
        } else {
            expectedScreenshots.forEach { fileName ->
                val altText = screenshotAltTexts[fileName] as? String ?: ""
                if (altText.isBlank() || altText.length > 140) {
                    add("store screenshot alt text must contain 1..140 characters: $fileName")
                }
            }
        }
    }

    val iconInfo = iconFile.takeIf(File::isFile)?.let(::readStorePngInfo)
    if (iconInfo != StorePngInfo(512, 512, 8, 6) || iconFile.length() !in 1..1_048_576L) {
        add("Play icon must be a 512x512 32-bit RGBA PNG no larger than 1024 KiB")
    }
    val featureGraphicInfo = featureGraphicFile.takeIf(File::isFile)?.let(::readStorePngInfo)
    if (featureGraphicInfo != StorePngInfo(1_024, 500, 8, 2) || featureGraphicFile.length() < 100_000L) {
        add("Play feature graphic must be a detailed 1024x500 24-bit RGB PNG without alpha")
    }

    val actualScreenshotNames = screenshotRoot.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
        .map(File::getName)
        .toSet()
    if (actualScreenshotNames != expectedScreenshots) {
        add("Play phone screenshot set must contain exactly the five reviewed PNG files")
    }
    expectedScreenshots.forEach { fileName ->
        val screenshot = File(screenshotRoot, fileName)
        val info = screenshot.takeIf(File::isFile)?.let(::readStorePngInfo)
        if (info != StorePngInfo(1_080, 1_920, 8, 2) || screenshot.length() < 100_000L) {
            add("Play phone screenshot must be a detailed 1080x1920 24-bit RGB PNG: $fileName")
        }
    }
    val screenshotMetadata = if (!screenshotMetadataFile.isFile) {
        add("Play screenshot capture metadata is missing")
        null
    } else {
        runCatching { JsonSlurper().parse(screenshotMetadataFile) as? Map<*, *> }.getOrNull().also {
            if (it == null) add("Play screenshot capture metadata must be a valid JSON object")
        }
    }
    if (screenshotMetadata != null) {
        val capture = screenshotMetadata["capture"] as? Map<*, *>
        val device = screenshotMetadata["device"] as? Map<*, *>
        val screenshotHashes = screenshotMetadata["screenshot_sha256"] as? Map<*, *>
        val captureWidth = (capture?.get("width") as? Number)?.toInt()
        val captureHeight = (capture?.get("height") as? Number)?.toInt()
        val captureDensity = (capture?.get("density_dpi") as? Number)?.toInt()
        val captureFontScale = (capture?.get("font_scale") as? Number)?.toDouble()
        val capturedAtUtc = screenshotMetadata["captured_at_utc"]?.toString().orEmpty()
        val capturedAtValid = runCatching { Instant.parse(capturedAtUtc) }.isSuccess
        if (
            (screenshotMetadata["schema_version"] as? Number)?.toInt() != 2 ||
            screenshotMetadata.keys.map { it.toString() }.toSet() != setOf(
                "schema_version",
                "captured_at_utc",
                "build_variant",
                "package_name",
                "version_code",
                "version_name",
                "qa_apk_sha256",
                "qa_apk_payload_sha256",
                "qa_test_apk_sha256",
                "qa_test_apk_payload_sha256",
                "device",
                "capture",
                "screenshot_sha256"
            ) ||
            !capturedAtValid ||
            screenshotMetadata["build_variant"] != "qa" ||
            screenshotMetadata["package_name"] != "com.vslot.app.qa" ||
            (screenshotMetadata["version_code"] as? Number)?.toInt() != vSlotVersionCode ||
            screenshotMetadata["version_name"] != "$vSlotVersionName-qa" ||
            normalizedSha256(screenshotMetadata["qa_apk_sha256"] as? String ?: "") == null ||
            normalizedSha256(screenshotMetadata["qa_apk_payload_sha256"] as? String ?: "") == null ||
            normalizedSha256(screenshotMetadata["qa_test_apk_sha256"] as? String ?: "") == null ||
            normalizedSha256(screenshotMetadata["qa_test_apk_payload_sha256"] as? String ?: "") == null ||
            device?.keys?.map { it.toString() }?.toSet() != setOf(
                "avd_name",
                "api_level",
                "locale",
                "physical_size",
                "physical_density_dpi"
            ) ||
            device.get("avd_name")?.toString().isNullOrBlank() ||
            (device.get("api_level") as? Number)?.toInt() != vSlotStoreSdk ||
            device.get("locale") != "ru-RU" ||
            device.get("physical_size") != "1080x2400" ||
            (device.get("physical_density_dpi") as? Number)?.toInt() != 420 ||
            capture?.keys?.map { it.toString() }?.toSet() != setOf(
                "width",
                "height",
                "density_dpi",
                "font_scale"
            ) ||
            captureWidth != 1_080 ||
            captureHeight != 1_920 ||
            captureDensity != 360 ||
            captureFontScale != 1.0
        ) {
            add("Play screenshot capture metadata must identify the current minified QA build and exact API 36 capture profile")
        }
        if (screenshotHashes == null || screenshotHashes.keys.map { it.toString() }.toSet() != expectedScreenshots) {
            add("Play screenshot capture metadata must hash exactly five reviewed screenshots")
        } else {
            expectedScreenshots.forEach { fileName ->
                val expectedHash = normalizedSha256(screenshotHashes[fileName] as? String ?: "")
                val screenshot = File(screenshotRoot, fileName)
                if (expectedHash == null || !screenshot.isFile || sha256Hex(screenshot).uppercase() != expectedHash) {
                    add("Play screenshot hash mismatch: $fileName")
                }
            }
        }
    }
}

fun storeScreenshotReleaseIssues(): List<String> {
    fun gitCheck(vararg arguments: String): Boolean {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        return process.waitFor() == 0
    }

    val gitProcess = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val head = gitProcess.inputStream.bufferedReader().use { it.readText() }.trim()
    if (gitProcess.waitFor() != 0 || !head.matches(Regex("[0-9a-fA-F]{40,64}"))) {
        return listOf("Play screenshot release binding requires a committed Git HEAD")
    }
    val screenshotPaths = listOf(
        "docs/store/assets/screenshots/capture-metadata.json",
        "docs/store/assets/screenshots/01-home.png",
        "docs/store/assets/screenshots/02-violet-slot.png",
        "docs/store/assets/screenshots/03-paytable.png",
        "docs/store/assets/screenshots/04-settings.png",
        "docs/store/assets/screenshots/05-free-spins.png"
    )
    return buildList {
        screenshotPaths.forEach { path ->
            if (!gitCheck("cat-file", "-e", "$head:$path")) {
                add("Play screenshot assets must be committed in the release HEAD: $path")
            } else if (!gitCheck("diff", "--quiet", head, "--", path)) {
                add("Play screenshot assets must match the release HEAD: $path")
            }
        }
    }
}

fun storeScreenshotQaArtifactIssues(
    metadataFile: File,
    qaApk: File,
    qaTestApk: File
): List<String> = buildList {
    if (!metadataFile.isFile) {
        add("Play screenshot capture metadata is missing")
        return@buildList
    }
    val metadata = runCatching {
        JsonSlurper().parse(metadataFile) as? Map<*, *>
    }.getOrNull()
    if (metadata == null) {
        add("Play screenshot capture metadata must be valid JSON")
        return@buildList
    }
    val expectedQaApkPayloadSha256 = normalizedSha256(
        metadata["qa_apk_payload_sha256"]?.toString().orEmpty()
    )
    val expectedQaTestApkPayloadSha256 = normalizedSha256(
        metadata["qa_test_apk_payload_sha256"]?.toString().orEmpty()
    )
    if (expectedQaApkPayloadSha256 == null) {
        add("Play screenshot capture metadata must contain a valid QA APK payload SHA-256")
    }
    if (expectedQaTestApkPayloadSha256 == null) {
        add("Play screenshot capture metadata must contain a valid QA test APK payload SHA-256")
    }
    if (!qaApk.isFile || qaApk.length() == 0L) {
        add("Play screenshot validation requires the assembled QA APK")
    }
    if (!qaTestApk.isFile || qaTestApk.length() == 0L) {
        add("Play screenshot validation requires the assembled QA instrumentation APK")
    }
    if (
        qaApk.isFile &&
        expectedQaApkPayloadSha256 != null &&
        normalizedSha256(apkPayloadSha256(qaApk)) != expectedQaApkPayloadSha256
    ) {
        add("Play screenshots must be recaptured from the exact QA APK payload")
    }
    if (
        qaTestApk.isFile &&
        expectedQaTestApkPayloadSha256 != null &&
        normalizedSha256(apkPayloadSha256(qaTestApk)) != expectedQaTestApkPayloadSha256
    ) {
        add("Play screenshots must be recaptured from the exact QA instrumentation APK payload")
    }
}

fun storeReadinessIssues(): List<String> {
    return buildList {
        addAll(storeListingAssetIssues())
        addAll(assetProvenanceInventoryIssues())
        addAll(storeScreenshotReleaseIssues())
        val privacyPolicyUrl = releasePrivacyPolicyUrl
        val releaseStoreFile = releaseStoreFilePath
        if (privacyPolicyUrl.isBlank()) add("V_SLOT_PRIVACY_POLICY_URL")
        if (privacyPolicyUrl.isNotBlank() && !isHttpsUrl(privacyPolicyUrl)) add("V_SLOT_PRIVACY_POLICY_URL(https URL required)")
        if (privacyPolicyUrl.isNotBlank() && isPlaceholderReleaseValue(privacyPolicyUrl)) add("V_SLOT_PRIVACY_POLICY_URL(real production URL required)")
        if (releaseSupportEmail.isBlank()) {
            add("V_SLOT_SUPPORT_EMAIL")
        } else if (
            isPlaceholderReleaseValue(releaseSupportEmail) ||
            !releaseSupportEmail.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
        ) {
            add("V_SLOT_SUPPORT_EMAIL(valid production email required)")
        }
        if (releaseDeveloperLegalName.isBlank()) {
            add("V_SLOT_DEVELOPER_LEGAL_NAME")
        } else if (releaseDeveloperLegalName.length < 2 || isPlaceholderReleaseValue(releaseDeveloperLegalName)) {
            add("V_SLOT_DEVELOPER_LEGAL_NAME(real legal name required)")
        }
        val appMetricaApiKey = releaseAppMetricaApiKey
        if (appMetricaApiKey.isBlank()) add("V_SLOT_APPMETRICA_API_KEY")
        if (appMetricaApiKey.isNotBlank() && isPlaceholderReleaseValue(appMetricaApiKey)) add("V_SLOT_APPMETRICA_API_KEY(real production key required)")
        val normalizedAppMetricaSha256 = normalizedSha256(expectedAppMetricaApiKeySha256)
        if (expectedAppMetricaApiKeySha256.isBlank()) add("V_SLOT_APPMETRICA_API_KEY_SHA256")
        if (expectedAppMetricaApiKeySha256.isNotBlank() && normalizedAppMetricaSha256 == null) {
            add("V_SLOT_APPMETRICA_API_KEY_SHA256(valid SHA-256 required)")
        }
        if (
            appMetricaApiKey.isNotBlank() &&
            normalizedAppMetricaSha256 != null &&
            sha256Hex(appMetricaApiKey.toByteArray(Charsets.UTF_8)).uppercase() != normalizedAppMetricaSha256
        ) {
            add("V_SLOT_APPMETRICA_API_KEY_SHA256(AppMetrica key mismatch)")
        }
        if (expectedFirebaseProjectId.isBlank()) add("V_SLOT_FIREBASE_PROJECT_ID")
        if (expectedFirebaseAppId.isBlank()) add("V_SLOT_FIREBASE_APP_ID")
        if (dataSafetyReviewedVersionCode.isBlank()) {
            add("V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE")
        } else if (dataSafetyReviewedVersionCode != vSlotVersionCode.toString()) {
            add("V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE(current versionCode $vSlotVersionCode required)")
        }
        if (dataSafetyEvidenceFilePath.isBlank()) add("V_SLOT_DATA_SAFETY_EVIDENCE_FILE")
        if (expectedDataSafetyEvidenceSha256.isBlank()) add("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256")
        if (dataSafetyRawEvidenceFilePath.isBlank()) add("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_FILE")
        if (expectedDataSafetyRawEvidenceSha256.isBlank()) add("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256")
        if (
            expectedDataSafetyEvidenceSha256.isNotBlank() &&
            normalizedSha256(expectedDataSafetyEvidenceSha256) == null
        ) {
            add("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256(valid SHA-256 required)")
        }
        val dataSafetyEvidenceFile = dataSafetyEvidenceFilePath
            .takeIf(String::isNotBlank)
            ?.let(::file)
        if (dataSafetyEvidenceFile != null && !dataSafetyEvidenceFile.isFile) {
            add("V_SLOT_DATA_SAFETY_EVIDENCE_FILE(file not found)")
        }
        if (
            dataSafetyEvidenceFile?.isFile == true &&
            normalizedSha256(expectedDataSafetyEvidenceSha256) != null &&
            releasePrivacyPolicyUrl.isNotBlank()
        ) {
            addAll(
                dataSafetyEvidenceIssues(
                    evidenceFile = dataSafetyEvidenceFile,
                    expectedSha256 = expectedDataSafetyEvidenceSha256
                )
            )
        }
        val normalizedDataSafetyRawEvidenceSha256 = normalizedSha256(
            expectedDataSafetyRawEvidenceSha256
        )
        if (
            expectedDataSafetyRawEvidenceSha256.isNotBlank() &&
            normalizedDataSafetyRawEvidenceSha256 == null
        ) {
            add("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_SHA256(valid SHA-256 required)")
        }
        val dataSafetyRawEvidenceFile = dataSafetyRawEvidenceFilePath
            .takeIf(String::isNotBlank)
            ?.let(::file)
        if (dataSafetyRawEvidenceFile != null && !dataSafetyRawEvidenceFile.isFile) {
            add("V_SLOT_DATA_SAFETY_RAW_EVIDENCE_FILE(file not found)")
        }
        if (
            dataSafetyEvidenceFile?.isFile == true &&
            dataSafetyRawEvidenceFile?.isFile == true &&
            normalizedDataSafetyRawEvidenceSha256 != null
        ) {
            addAll(
                dataSafetyRawEvidenceIssues(
                    archiveFile = dataSafetyRawEvidenceFile,
                    expectedArchiveSha256 = expectedDataSafetyRawEvidenceSha256,
                    evidenceFile = dataSafetyEvidenceFile
                )
            )
        }
        if (assetRightsReviewedVersionCode.isBlank()) {
            add("V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE")
        } else if (assetRightsReviewedVersionCode != vSlotVersionCode.toString()) {
            add("V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE(current versionCode $vSlotVersionCode required)")
        }
        if (assetRightsEvidenceFilePath.isBlank()) add("V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE")
        if (expectedAssetRightsEvidenceSha256.isBlank()) add("V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256")
        if (
            expectedAssetRightsEvidenceSha256.isNotBlank() &&
            normalizedSha256(expectedAssetRightsEvidenceSha256) == null
        ) {
            add("V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256(valid SHA-256 required)")
        }
        val assetRightsEvidenceFile = assetRightsEvidenceFilePath
            .takeIf(String::isNotBlank)
            ?.let(::file)
        if (assetRightsEvidenceFile != null && !assetRightsEvidenceFile.isFile) {
            add("V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE(file not found)")
        }
        if (
            assetRightsEvidenceFile?.isFile == true &&
            normalizedSha256(expectedAssetRightsEvidenceSha256) != null
        ) {
            addAll(
                assetRightsEvidenceIssues(
                    evidenceFile = assetRightsEvidenceFile,
                    expectedSha256 = expectedAssetRightsEvidenceSha256
                )
            )
        }
        listOf(
            Triple(
                "V_SLOT_SAMSUNG_QA_EVIDENCE_FILE" to samsungQaEvidenceFilePath,
                "V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256" to expectedSamsungQaEvidenceSha256,
                "Samsung connected"
            ),
            Triple(
                "V_SLOT_PROCESS_DEATH_EVIDENCE_FILE" to processDeathEvidenceFilePath,
                "V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256" to expectedProcessDeathEvidenceSha256,
                "process-death"
            ),
            Triple(
                "V_SLOT_FRAME_METRICS_EVIDENCE_FILE" to frameMetricsEvidenceFilePath,
                "V_SLOT_FRAME_METRICS_EVIDENCE_SHA256" to expectedFrameMetricsEvidenceSha256,
                "frame-metrics"
            )
        ).forEach { (fileInput, shaInput, evidenceName) ->
            val (fileVariable, path) = fileInput
            val (shaVariable, expectedSha) = shaInput
            if (path.isBlank()) add(fileVariable)
            if (expectedSha.isBlank()) add(shaVariable)
            if (expectedSha.isNotBlank() && normalizedSha256(expectedSha) == null) {
                add("$shaVariable(valid SHA-256 required)")
            }
            val evidenceFile = path.takeIf(String::isNotBlank)?.let(::file)
            if (evidenceFile != null && !evidenceFile.isFile) add("$fileVariable(file not found)")
            if (evidenceFile?.isFile == true && normalizedSha256(expectedSha) != null) {
                val evidenceIssues = mutableListOf<String>()
                pinnedJsonEvidenceRoot(
                    evidenceFile,
                    expectedSha,
                    fileVariable,
                    shaVariable,
                    evidenceIssues
                )
                addAll(evidenceIssues.map { issue -> "$evidenceName evidence: $issue" })
            }
        }
        if (physicalSamsungRawEvidenceFilePath.isBlank()) add("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_FILE")
        if (expectedPhysicalSamsungRawEvidenceSha256.isBlank()) {
            add("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256")
        }
        val normalizedRawEvidenceSha256 = normalizedSha256(expectedPhysicalSamsungRawEvidenceSha256)
        if (expectedPhysicalSamsungRawEvidenceSha256.isNotBlank() && normalizedRawEvidenceSha256 == null) {
            add("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256(valid SHA-256 required)")
        }
        val rawEvidenceFile = physicalSamsungRawEvidenceFilePath
            .takeIf(String::isNotBlank)
            ?.let(::file)
        if (rawEvidenceFile != null && !rawEvidenceFile.isFile) {
            add("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_FILE(file not found)")
        } else if (
            rawEvidenceFile?.isFile == true && normalizedRawEvidenceSha256 != null &&
            normalizedSha256(sha256Hex(rawEvidenceFile)) != normalizedRawEvidenceSha256
        ) {
            add("V_SLOT_PHYSICAL_SAMSUNG_RAW_EVIDENCE_SHA256(evidence mismatch)")
        }
        addAll(
            googleServicesReadinessIssues(
                googleServicesFiles.getValue("release"),
                vSlotApplicationId,
                expectedFirebaseProjectId,
                expectedFirebaseAppId
            )
        )
        if (releaseStoreFile.isBlank()) add("V_SLOT_RELEASE_STORE_FILE")
        if (releaseStoreFile.isNotBlank() && isPlaceholderReleaseValue(releaseStoreFile)) add("V_SLOT_RELEASE_STORE_FILE(real keystore path required)")
        val keystoreFile = releaseStoreFile.takeIf(String::isNotBlank)?.let(::file)
        if (keystoreFile != null && !keystoreFile.isFile) add("V_SLOT_RELEASE_STORE_FILE(file not found)")
        val expectedCertificateSha256 = expectedReleaseCertificateSha256
        if (releaseStorePassword.isBlank()) add("V_SLOT_RELEASE_STORE_PASSWORD")
        if (releaseStorePassword.isNotBlank() && isPlaceholderReleaseValue(releaseStorePassword)) add("V_SLOT_RELEASE_STORE_PASSWORD(real signing secret required)")
        if (releaseKeyAlias.isBlank()) add("V_SLOT_RELEASE_KEY_ALIAS")
        if (releaseKeyAlias.isNotBlank() && isPlaceholderReleaseValue(releaseKeyAlias)) add("V_SLOT_RELEASE_KEY_ALIAS(real signing alias required)")
        if (releaseKeyPassword.isBlank()) add("V_SLOT_RELEASE_KEY_PASSWORD")
        if (releaseKeyPassword.isNotBlank() && isPlaceholderReleaseValue(releaseKeyPassword)) add("V_SLOT_RELEASE_KEY_PASSWORD(real signing secret required)")
        if (expectedCertificateSha256.isBlank()) add("V_SLOT_RELEASE_CERT_SHA256")
        if (expectedCertificateSha256.isNotBlank() && normalizedSha256(expectedCertificateSha256) == null) {
            add("V_SLOT_RELEASE_CERT_SHA256(valid SHA-256 required)")
        }
        if (
            keystoreFile?.isFile == true &&
            releaseKeyAlias.isNotBlank() &&
            releaseStorePassword.isNotBlank() &&
            normalizedSha256(expectedCertificateSha256) != null
        ) {
            addAll(
                uploadCertificateReadinessIssues(
                    keystoreFile = keystoreFile,
                    keyAlias = releaseKeyAlias,
                    storePassword = releaseStorePassword,
                    expectedSha256 = expectedCertificateSha256
                )
            )
        }
    }
}

fun failOnStoreReadinessIssues() {
    val missing = storeReadinessIssues()
    if (missing.isNotEmpty()) {
        throw GradleException("Store readiness issues: ${missing.joinToString()}")
    }
}

android {
    namespace = "com.vslot.app"
    compileSdk = vSlotStoreSdk
    buildToolsVersion = vSlotBuildToolsVersion

    defaultConfig {
        applicationId = vSlotApplicationId
        minSdk = vSlotMinSdk
        targetSdk = vSlotStoreSdk
        versionCode = vSlotVersionCode
        versionName = vSlotVersionName

        testInstrumentationRunner = "com.vslot.app.VSlotTestRunner"

        buildConfigField("String", "PRIVACY_POLICY_URL", "".asBuildConfigString())
        buildConfigField("String", "APP_METRICA_API_KEY", "".asBuildConfigString())
        buildConfigField("Boolean", "FIREBASE_CONFIGURED", "false")
        buildConfigField("Boolean", "QA_ENABLED", "false")
    }

    signingConfigs {
        create("releaseConfig") {
            if (releaseStoreFilePath.isNotBlank()) {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            buildConfigField("String", "APP_METRICA_API_KEY", debugAppMetricaApiKey.asBuildConfigString())
            buildConfigField("String", "PRIVACY_POLICY_URL", configValue("V_SLOT_PRIVACY_POLICY_URL").asBuildConfigString())
            buildConfigField("Boolean", "FIREBASE_CONFIGURED", googleServicesConfigured.getValue("debug").toString())
            buildConfigField("Boolean", "QA_ENABLED", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "APP_METRICA_API_KEY", releaseAppMetricaApiKey.asBuildConfigString())
            buildConfigField("String", "PRIVACY_POLICY_URL", releasePrivacyPolicyUrl.asBuildConfigString())
            buildConfigField("Boolean", "FIREBASE_CONFIGURED", googleServicesConfigured.getValue("release").toString())
            val releaseStoreFile = signingConfigs.getByName("releaseConfig").storeFile
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("releaseConfig")
            }
        }

        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFile("qa-proguard-rules.pro")
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "APP_METRICA_API_KEY", qaAppMetricaApiKey.asBuildConfigString())
            buildConfigField("String", "PRIVACY_POLICY_URL", configValue("V_SLOT_PRIVACY_POLICY_URL").asBuildConfigString())
            buildConfigField("Boolean", "FIREBASE_CONFIGURED", googleServicesConfigured.getValue("qa").toString())
            buildConfigField("Boolean", "QA_ENABLED", "true")
        }
    }

    sourceSets {
        getByName("qa") {
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    testBuildType = "qa"

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.configureEach {
    val buildType = when (name) {
        "processDebugGoogleServices" -> "debug"
        "processQaGoogleServices" -> "qa"
        "processReleaseGoogleServices" -> "release"
        else -> null
    }
    if (buildType != null && !googleServicesConfigured.getValue(buildType)) {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

configurations.configureEach {
    exclude(group = "io.appmetrica.analytics", module = "analytics-ad-revenue")
    exclude(group = "io.appmetrica.analytics", module = "analytics-appsetid")
    exclude(group = "io.appmetrica.analytics", module = "analytics-billing")
    exclude(group = "io.appmetrica.analytics", module = "analytics-id-sync")
    exclude(group = "io.appmetrica.analytics", module = "analytics-identifiers")
    exclude(group = "io.appmetrica.analytics", module = "analytics-location")
    exclude(group = "io.appmetrica.analytics", module = "analytics-ndkcrashes")
    exclude(group = "io.appmetrica.analytics", module = "analytics-screenshot")
    // App Set ID is disabled at both the AppMetrica module and Google provider layers.
    exclude(group = "com.google.android.gms", module = "play-services-appset")
}

dependencies {
    // Core 1.19 requires compileSdk 37 and AGP 9.1; 1.18 is the latest release compatible with this toolchain.
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // 8.4.0 adds product-flow, which has not completed this app's privacy and license review.
    //noinspection NewerVersionAvailable
    implementation("io.appmetrica.analytics:analytics:8.3.0")
    //noinspection NewerVersionAvailable
    implementation("io.appmetrica.analytics:analytics-core-api:8.3.0")
    implementation("io.appmetrica.analytics:push:4.3.0")
    implementation("com.google.firebase:firebase-installations:19.1.2")
    implementation("com.google.firebase:firebase-messaging:25.1.1")
    implementation("com.google.android.gms:play-services-base:18.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.navigation:navigation-testing:2.9.8")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<Test>().configureEach {
    val variant = when (name) {
        "testDebugUnitTest" -> "debug"
        "testQaUnitTest" -> "qa"
        "testReleaseUnitTest" -> "release"
        else -> null
    }
    if (variant != null) {
        val variantTitle = variant.replaceFirstChar(Char::uppercase)
        dependsOn("process${variantTitle}Manifest")
        inputs.file(
            layout.buildDirectory.file(
                "intermediates/merged_manifests/$variant/process${variantTitle}Manifest/AndroidManifest.xml"
            )
        )
    }
}

val verifyDataSafetyEvidenceValidatorContract = tasks.register(
    "verifyDataSafetyEvidenceValidatorContract"
) {
    group = "verification"
    description = "Executes positive and negative fixtures for the Data Safety evidence validator."
    val validFixture = file("src/test/resources/data_safety_evidence_valid.json")
    val templateFixture = rootProject.file("docs/store/DATA_SAFETY_EVIDENCE_TEMPLATE.json")
    val validFixtureSha256 = "c986c27160acb79d514ee303578d5d46d7ff2b0e8929716fa9165273547f6841"
    val templateFixtureSha256 = "ee4a4c90ac610144888cc27f784316927757eadee13479c6d8568121d86c2360"
    val fixtureCommit = "0123456789abcdef0123456789abcdef01234567"
    val fixturePrivacyUrl = "https://privacy.vslot.test/policy"
    inputs.files(validFixture, templateFixture)
    inputs.property("validFixtureSha256", validFixtureSha256)
    inputs.property("templateFixtureSha256", templateFixtureSha256)
    outputs.upToDateWhen { false }

    doLast {
        val validIssues = dataSafetyEvidenceIssues(
            evidenceFile = validFixture,
            expectedSha256 = validFixtureSha256,
            expectedCommit = fixtureCommit,
            expectedPrivacyPolicyUrl = fixturePrivacyUrl
        )
        if (validIssues.isNotEmpty()) {
            throw GradleException("Valid Data Safety evidence fixture was rejected: ${validIssues.joinToString()}")
        }
        val wrongCommitIssues = dataSafetyEvidenceIssues(
            evidenceFile = validFixture,
            expectedSha256 = validFixtureSha256,
            expectedCommit = "fedcba9876543210fedcba9876543210fedcba98",
            expectedPrivacyPolicyUrl = fixturePrivacyUrl
        )
        if (wrongCommitIssues.none { it.contains("reviewed_commit must match release HEAD") }) {
            throw GradleException("Data Safety evidence validator accepted a mismatched release commit.")
        }
        val wrongHashIssues = dataSafetyEvidenceIssues(
            evidenceFile = validFixture,
            expectedSha256 = "A".repeat(64),
            expectedCommit = fixtureCommit,
            expectedPrivacyPolicyUrl = fixturePrivacyUrl
        )
        if (wrongHashIssues != listOf("V_SLOT_DATA_SAFETY_EVIDENCE_SHA256(evidence mismatch)")) {
            throw GradleException("Data Safety evidence validator did not reject a mismatched checksum.")
        }
        val incompleteTemplateIssues = dataSafetyEvidenceIssues(
            evidenceFile = templateFixture,
            expectedSha256 = templateFixtureSha256,
            expectedPrivacyPolicyUrl = fixturePrivacyUrl
        )
        if (
            incompleteTemplateIssues.none { it.contains("must be true") } ||
            incompleteTemplateIssues.none { it.contains("non-placeholder SHA-256") }
        ) {
            throw GradleException("Data Safety evidence validator accepted the incomplete release template.")
        }

        fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
            val target = temporaryDir.resolve("zip-${entries.keys.joinToString().hashCode()}.zip")
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                entries.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name).apply { time = 0L })
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            return target.readBytes()
        }
        fun pcapngFixture(): ByteArray {
            return ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x0A0D0D0A)
                putInt(28)
                putInt(0x1A2B3C4D)
                putShort(1.toShort())
                putShort(0.toShort())
                putLong(-1L)
                putInt(28)

                putInt(0x00000001)
                putInt(20)
                putShort(1.toShort())
                putShort(0.toShort())
                putInt(65_535)
                putInt(20)

                listOf(100, 200).forEach { timestamp ->
                    putInt(0x00000006)
                    putInt(36)
                    putInt(0)
                    putInt(0)
                    putInt(timestamp)
                    putInt(4)
                    putInt(4)
                    put(byteArrayOf(0x45, 0x00, 0x00, 0x14))
                    putInt(36)
                }
            }.array()
        }
        val networkCapture = pcapngFixture()
        val playConsoleExport = "question,answer\nanalytics,reviewed\npush,reviewed\n"
            .toByteArray(Charsets.UTF_8)
        val privacySnapshot = (
            "<!doctype html><html><head><title>V Slot privacy policy</title></head><body>" +
                "Reviewed privacy policy content. ".repeat(20) +
                "</body></html>"
            ).toByteArray(Charsets.UTF_8)
        val innerArchive = zipBytes(
            linkedMapOf(
                "network-capture.pcapng" to networkCapture,
                "play-console-export.csv" to playConsoleExport,
                "privacy-policy-snapshot.html" to privacySnapshot
            )
        )
        val rawManifestRoot = (JsonSlurper().parse(validFixture) as Map<*, *>)
            .entries
            .associateTo(linkedMapOf<String, Any?>()) { (key, value) -> key.toString() to value }
            .apply {
                this["network_capture_sha256"] = sha256Hex(networkCapture)
                this["play_console_export_sha256"] = sha256Hex(playConsoleExport)
                this["privacy_policy_snapshot_sha256"] = sha256Hex(privacySnapshot)
                this["evidence_archive_sha256"] = sha256Hex(innerArchive)
            }
        val rawManifest = temporaryDir.resolve("data-safety-raw-manifest.json").apply {
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(rawManifestRoot)) + "\n", Charsets.UTF_8)
        }
        fun rawArchive(entries: Map<String, ByteArray>): File {
            return temporaryDir.resolve("data-safety-raw-${entries.keys.joinToString().hashCode()}.zip").apply {
                writeBytes(zipBytes(entries))
            }
        }
        val validRawEntries = linkedMapOf(
            "manifests/data-safety.json" to rawManifest.readBytes(),
            "raw/network-capture.pcapng" to networkCapture,
            "raw/play-console-export.csv" to playConsoleExport,
            "raw/privacy-policy-snapshot.html" to privacySnapshot,
            "raw/evidence-archive.zip" to innerArchive
        )
        val validRawArchive = rawArchive(validRawEntries)
        val validRawIssues = dataSafetyRawEvidenceIssues(
            archiveFile = validRawArchive,
            expectedArchiveSha256 = sha256Hex(validRawArchive),
            evidenceFile = rawManifest
        )
        if (validRawIssues.isNotEmpty()) {
            throw GradleException("Valid Data Safety raw evidence was rejected: ${validRawIssues.joinToString()}")
        }
        val tamperedRawArchive = rawArchive(
            validRawEntries.toMutableMap().apply {
                this["raw/network-capture.pcapng"] = networkCapture + 0x7F.toByte()
            }
        )
        if (dataSafetyRawEvidenceIssues(
                archiveFile = tamperedRawArchive,
                expectedArchiveSha256 = sha256Hex(tamperedRawArchive),
                evidenceFile = rawManifest
            ).none { issue -> issue.contains("network_capture_sha256") }
        ) {
            throw GradleException("Data Safety raw evidence validator accepted tampered capture bytes.")
        }
        val fakePcapRawArchive = rawArchive(
            validRawEntries.toMutableMap().apply {
                this["raw/network-capture.pcapng"] = byteArrayOf(
                    0x0A, 0x0D, 0x0D, 0x0A, 0x1A, 0x2B, 0x3C, 0x4D
                )
            }
        )
        if (dataSafetyRawEvidenceIssues(
                archiveFile = fakePcapRawArchive,
                expectedArchiveSha256 = sha256Hex(fakePcapRawArchive),
                evidenceFile = rawManifest
            ).none { issue -> issue.contains("structured pcapng") }
        ) {
            throw GradleException("Data Safety raw evidence validator accepted a pcapng magic-only fixture.")
        }
        val malformedCsvRawArchive = rawArchive(
            validRawEntries.toMutableMap().apply {
                this["raw/play-console-export.csv"] = "anything,goes\n".toByteArray(Charsets.UTF_8)
            }
        )
        if (dataSafetyRawEvidenceIssues(
                archiveFile = malformedCsvRawArchive,
                expectedArchiveSha256 = sha256Hex(malformedCsvRawArchive),
                evidenceFile = rawManifest
            ).none { issue -> issue.contains("exact question,answer CSV schema") }
        ) {
            throw GradleException("Data Safety raw evidence validator accepted an arbitrary CSV fixture.")
        }
        val stubHtmlRawArchive = rawArchive(
            validRawEntries.toMutableMap().apply {
                this["raw/privacy-policy-snapshot.html"] = "<html>stub</html>"
                    .toByteArray(Charsets.UTF_8)
            }
        )
        if (dataSafetyRawEvidenceIssues(
                archiveFile = stubHtmlRawArchive,
                expectedArchiveSha256 = sha256Hex(stubHtmlRawArchive),
                evidenceFile = rawManifest
            ).none { issue -> issue.contains("complete HTML document") }
        ) {
            throw GradleException("Data Safety raw evidence validator accepted an HTML tag-only fixture.")
        }
        val incompleteRawArchive = rawArchive(
            validRawEntries.filterKeys { name -> name != "raw/play-console-export.csv" }
        )
        if (dataSafetyRawEvidenceIssues(
                archiveFile = incompleteRawArchive,
                expectedArchiveSha256 = sha256Hex(incompleteRawArchive),
                evidenceFile = rawManifest
            ).none { issue -> issue.contains("exact raw evidence entry set required") }
        ) {
            throw GradleException("Data Safety raw evidence validator accepted an incomplete archive.")
        }
    }
}

val verifyPhysicalSamsungEvidenceValidatorContract = tasks.register(
    "verifyPhysicalSamsungEvidenceValidatorContract"
) {
    group = "verification"
    description = "Executes positive and negative fixtures for the physical Samsung evidence validator."
    val samsungFixture = file("src/test/resources/physical_samsung_connected_evidence_valid.json")
    val processDeathFixture = file("src/test/resources/physical_samsung_process_death_evidence_valid.json")
    val frameMetricsFixture = file("src/test/resources/physical_samsung_frame_metrics_evidence_valid.json")
    val samsungFixtureSha256 = "de533304aa165d8f6faf53eb3eda665cfcf667e4da0c22b94da0982782d272b2"
    val processDeathFixtureSha256 = "6497de68786e6b5b4a3c557dea47d37b2755e86a0b7203b899f79aeebfab2a68"
    val frameMetricsFixtureSha256 = "938e3907ac19a5dc24261e1860e06c00bb10a88a4af9e067aedae2885200387f"
    val fixtureCommit = "0123456789abcdef0123456789abcdef01234567"
    val fixtureQaApkPayloadSha256 = "03c514879cda64e33b0613aecba5a6844deac827d7333061d2078c35fc2bb8ff"
    inputs.files(samsungFixture, processDeathFixture, frameMetricsFixture)
    inputs.properties(
        mapOf(
            "samsungFixtureSha256" to samsungFixtureSha256,
            "processDeathFixtureSha256" to processDeathFixtureSha256,
            "frameMetricsFixtureSha256" to frameMetricsFixtureSha256
        )
    )
    outputs.upToDateWhen { false }

    doLast {
        if (sha256Hex(samsungFixture) != samsungFixtureSha256) {
            throw GradleException("Physical Samsung connected evidence contract fixture checksum changed.")
        }
        val samsungFixtureText = samsungFixture.readText(Charsets.UTF_8)
        require(Regex("\\\"tests\\\": 62").findAll(samsungFixtureText).count() == 2) {
            "Physical Samsung connected evidence baseline must contain both complete 62-test stages."
        }
        require(Regex("\\\"skipped\\\": 4").findAll(samsungFixtureText).count() == 2) {
            "Physical Samsung connected evidence baseline must pin four profile-specific skips in both full stages."
        }
        val currentSamsungFixture = samsungFixture
        val currentSamsungFixtureSha256 = samsungFixtureSha256

        fun writeRawFixture(
            target: File,
            omittedEntry: String? = null,
            omitTestCases: Boolean = false,
            hiddenTestFailure: Boolean = false
        ) {
            val entries = linkedMapOf<String, ByteArray>(
                "manifests/connected-tests.json" to currentSamsungFixture.readBytes(),
                "manifests/process-death.json" to processDeathFixture.readBytes(),
                "manifests/frame-metrics.json" to frameMetricsFixture.readBytes()
            )
            expectedPhysicalSamsungStageTestIds().forEach { (stage, testIds) ->
                val skippedTestIds = expectedPhysicalSamsungSkippedTestIds()[stage].orEmpty()
                val testCases = if (omitTestCases) {
                    ""
                } else {
                    testIds.mapIndexed { index, testId ->
                        val className = testId.substringBefore('#')
                        val testName = testId.substringAfter('#')
                        if (hiddenTestFailure && index == 0) {
                            "<testcase classname=\"$className\" name=\"$testName\">" +
                                "<failure message=\"hidden failure\"/>" +
                                "</testcase>"
                        } else {
                            if (testId in skippedTestIds) {
                                "<testcase classname=\"$className\" name=\"$testName\"><skipped/></testcase>"
                            } else {
                                "<testcase classname=\"$className\" name=\"$testName\"/>"
                            }
                        }
                    }.joinToString("\n")
                }
                entries["raw/connected/$stage/TEST-fixture.xml"] =
                    (
                        "<testsuite tests=\"${testIds.size}\" failures=\"0\" errors=\"0\" " +
                            "skipped=\"${skippedTestIds.size}\">" +
                            testCases +
                            "</testsuite>"
                        ).toByteArray(Charsets.UTF_8)
            }
            entries["raw/process-death/process-death.log"] = """
                V_SLOT_PROCESS_DEATH_QA
                git_commit=$fixtureCommit
                apk_payload_sha256=$fixtureQaApkPayloadSha256
                presentation_observed=true
                first_draw_observed=true
                pending_journal_cleared=true
                second_restart_unchanged=true
                result_status=passed
            """.trimIndent().toByteArray(Charsets.UTF_8)
            entries["raw/frame-metrics/frame-metrics.log"] = """
                V_SLOT_FRAME_METRICS_QA
                git_commit=$fixtureCommit
                apk_payload_sha256=$fixtureQaApkPayloadSha256
                frame_profile=physical_samsung
                BUILD SUCCESSFUL
                SLOT_FRAME_METRICS samples=240 p95_ms=33.4 max_ms=48.7
            """.trimIndent().toByteArray(Charsets.UTF_8)
            target.parentFile.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                entries.filterKeys { name -> name != omittedEntry }.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name).apply { time = 0L })
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }

        val rawFixture = temporaryDir.resolve("physical-samsung-raw-evidence.zip")
        writeRawFixture(rawFixture)
        val rawFixtureSha256 = sha256Hex(rawFixture)
        fun validate(
            samsungFile: File = currentSamsungFixture,
            samsungSha256: String = currentSamsungFixtureSha256,
            processFile: File = processDeathFixture,
            processSha256: String = processDeathFixtureSha256,
            frameFile: File = frameMetricsFixture,
            frameSha256: String = frameMetricsFixtureSha256,
            rawFile: File = rawFixture,
            rawSha256: String = rawFixtureSha256,
            commit: String = fixtureCommit,
            qaApkPayloadSha256: String = fixtureQaApkPayloadSha256
        ): List<String> = physicalSamsungEvidenceIssues(
            samsungEvidenceFile = samsungFile,
            expectedSamsungSha256 = samsungSha256,
            processDeathEvidenceFile = processFile,
            expectedProcessDeathSha256 = processSha256,
            frameMetricsEvidenceFile = frameFile,
            expectedFrameMetricsSha256 = frameSha256,
            rawEvidenceArchiveFile = rawFile,
            expectedRawEvidenceArchiveSha256 = rawSha256,
            expectedCommit = commit,
            expectedQaApkPayloadSha256 = qaApkPayloadSha256
        )

        val validIssues = validate()
        if (validIssues.isNotEmpty()) {
            throw GradleException("Valid physical Samsung evidence fixtures were rejected: ${validIssues.joinToString()}")
        }
        if (validate(commit = "fedcba9876543210fedcba9876543210fedcba98").none {
                issue -> issue.contains("source.git_commit must match release HEAD")
            }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted a mismatched release commit.")
        }
        if (validate(qaApkPayloadSha256 = "09".repeat(32)).none {
                issue -> issue.contains("apk.payload_sha256 must match the release-gated QA APK payload")
            }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted a mismatched QA APK.")
        }
        val emulatorProcessFixture = temporaryDir.resolve("emulator-process-death.json")
        emulatorProcessFixture.writeText(
            processDeathFixture.readText(Charsets.UTF_8)
                .replaceFirst("\"physical_samsung\"", "\"emulator\""),
            Charsets.UTF_8
        )
        if (validate(
                processFile = emulatorProcessFixture,
                processSha256 = sha256Hex(emulatorProcessFixture)
            ).none { issue -> issue.contains("qa_profile physical_samsung required") }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted an emulator profile.")
        }
        val reducedConnectedFixture = temporaryDir.resolve("reduced-connected-tests.json")
        reducedConnectedFixture.writeText(
            currentSamsungFixture.readText(Charsets.UTF_8)
                .replaceFirst("\"tests\": 62", "\"tests\": 61"),
            Charsets.UTF_8
        )
        if (validate(
                samsungFile = reducedConnectedFixture,
                samsungSha256 = sha256Hex(reducedConnectedFixture)
            ).none { issue -> issue.contains("must run all 62 tests") }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted a reduced connected suite.")
        }
        val slowFrameFixture = temporaryDir.resolve("slow-frame-metrics.json")
        slowFrameFixture.writeText(
            frameMetricsFixture.readText(Charsets.UTF_8)
                .replaceFirst("\"p95_ms\": 33.4", "\"p95_ms\": 55.0"),
            Charsets.UTF_8
        )
        if (validate(
                frameFile = slowFrameFixture,
                frameSha256 = sha256Hex(slowFrameFixture)
            ).none { issue -> issue.contains("frame metrics exceed release limits") }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted over-limit frame metrics.")
        }
        val incompleteRawFixture = temporaryDir.resolve("physical-samsung-raw-evidence-incomplete.zip")
        writeRawFixture(incompleteRawFixture, omittedEntry = "raw/process-death/process-death.log")
        if (validate(
                rawFile = incompleteRawFixture,
                rawSha256 = sha256Hex(incompleteRawFixture)
            ).none { issue -> issue.contains("process-death raw log missing") }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted a raw archive without process-death proof.")
        }
        val counterOnlyRawFixture = temporaryDir.resolve("physical-samsung-counter-only-evidence.zip")
        writeRawFixture(counterOnlyRawFixture, omitTestCases = true)
        if (validate(
                rawFile = counterOnlyRawFixture,
                rawSha256 = sha256Hex(counterOnlyRawFixture)
            ).none { issue -> issue.contains("exact 62 testcase IDs") }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted XML counters without testcase IDs.")
        }
        val hiddenFailureRawFixture = temporaryDir.resolve("physical-samsung-hidden-failure-evidence.zip")
        writeRawFixture(hiddenFailureRawFixture, hiddenTestFailure = true)
        if (validate(
                rawFile = hiddenFailureRawFixture,
                rawSha256 = sha256Hex(hiddenFailureRawFixture)
            ).none { issue -> issue.contains("exact 62 testcase IDs") }
        ) {
            throw GradleException("Physical Samsung evidence validator accepted a testcase failure hidden by counters.")
        }
    }
}

val verifyAssetRightsEvidenceValidatorContract = tasks.register(
    "verifyAssetRightsEvidenceValidatorContract"
) {
    group = "verification"
    description = "Executes positive and negative fixtures for media provenance and rights evidence."
    val templateFixture = rootProject.file("docs/legal/ASSET_RIGHTS_EVIDENCE_TEMPLATE.json")
    val generator = rootProject.file("tools/generate_asset_provenance_inventory.py")
    inputs.files(assetProvenanceInventoryFile, templateFixture, generator)
    outputs.upToDateWhen { false }

    doLast {
        val inventoryIssues = assetProvenanceInventoryIssues()
        if (inventoryIssues.isNotEmpty()) {
            throw GradleException("Asset provenance inventory was rejected: ${inventoryIssues.joinToString()}")
        }
        val fixtureCommit = "0123456789abcdef0123456789abcdef01234567"
        val validFixture = temporaryDir.resolve("asset-rights-valid.json")
        validFixture.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    linkedMapOf(
                        "schema_version" to 1,
                        "application_id" to vSlotApplicationId,
                        "version_code" to vSlotVersionCode,
                        "reviewed_commit" to fixtureCommit,
                        "reviewed_at_utc" to "2026-07-20T12:00:00Z",
                        "reviewer" to "Release Rights Reviewer",
                        "inventory_sha256" to sha256Hex(assetProvenanceInventoryFile),
                        "checks" to linkedMapOf(
                            "generation_terms_and_owner_attestation_confirmed" to true,
                            "every_inventory_entry_reviewed" to true,
                            "procedural_audio_confirmed" to true,
                            "store_assets_cleared" to true,
                            "font_license_and_output_rights_confirmed" to true,
                            "names_and_trademarks_cleared" to true,
                            "third_party_content_and_notices_cleared" to true
                        ),
                        "notes" to "Reviewed against the exact candidate inventory."
                    )
                )
            ) + "\n",
            Charsets.UTF_8
        )
        val validSha256 = sha256Hex(validFixture)
        val validIssues = assetRightsEvidenceIssues(
            evidenceFile = validFixture,
            expectedSha256 = validSha256,
            expectedCommit = fixtureCommit
        )
        if (validIssues.isNotEmpty()) {
            throw GradleException("Valid asset rights evidence was rejected: ${validIssues.joinToString()}")
        }
        if (assetRightsEvidenceIssues(
                evidenceFile = validFixture,
                expectedSha256 = validSha256,
                expectedCommit = "fedcba9876543210fedcba9876543210fedcba98"
            ).none { issue -> issue.contains("reviewed_commit must match release HEAD") }
        ) {
            throw GradleException("Asset rights evidence validator accepted a mismatched release commit.")
        }
        if (assetRightsEvidenceIssues(
                evidenceFile = validFixture,
                expectedSha256 = "00".repeat(32),
                expectedCommit = fixtureCommit
            ) != listOf("V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256(evidence mismatch)")
        ) {
            throw GradleException("Asset rights evidence validator did not reject a mismatched checksum.")
        }
        val templateIssues = assetRightsEvidenceIssues(
            evidenceFile = templateFixture,
            expectedSha256 = sha256Hex(templateFixture)
        )
        if (templateIssues.none { issue -> issue.contains("must be true") }) {
            throw GradleException("Asset rights evidence validator accepted the incomplete template.")
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(
        verifyDataSafetyEvidenceValidatorContract,
        verifyPhysicalSamsungEvidenceValidatorContract,
        verifyAssetRightsEvidenceValidatorContract
    )
}

val verifyStoreReadiness = tasks.register("verifyStoreReadiness") {
    group = "verification"
    description = "Fails when production release inputs for V Slot are missing."

    doLast {
        failOnStoreReadinessIssues()
    }
}

val storeScreenshotQaApkValidationReportFile = layout.buildDirectory.file(
    "reports/release-security/store-screenshot-qa-apk-validation.txt"
)

val verifyStoreScreenshotQaApkValidatorContract = tasks.register(
    "verifyStoreScreenshotQaApkValidatorContract"
) {
    group = "verification"
    description = "Proves that Store screenshot metadata cannot claim different QA app or instrumentation payloads."
    outputs.upToDateWhen { false }

    doLast {
        val fixtureDirectory = temporaryDir.resolve("fixtures").apply {
            deleteRecursively()
            mkdirs()
        }
        val qaApk = fixtureDirectory.resolve("app-qa.apk").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("v-slot-qa-apk-fixture".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        val qaTestApk = fixtureDirectory.resolve("app-qa-androidTest.apk").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("v-slot-qa-test-apk-fixture".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        val metadata = fixtureDirectory.resolve("capture-metadata.json")
        metadata.writeText(
            JsonOutput.toJson(
                mapOf(
                    "qa_apk_payload_sha256" to apkPayloadSha256(qaApk),
                    "qa_test_apk_payload_sha256" to apkPayloadSha256(qaTestApk)
                )
            ),
            Charsets.UTF_8
        )
        if (storeScreenshotQaArtifactIssues(metadata, qaApk, qaTestApk).isNotEmpty()) {
            throw GradleException("Store screenshot validator rejected its exact QA artifact fixtures.")
        }
        metadata.writeText(
            JsonOutput.toJson(
                mapOf(
                    "qa_apk_payload_sha256" to "00".repeat(32),
                    "qa_test_apk_payload_sha256" to apkPayloadSha256(qaTestApk)
                )
            ),
            Charsets.UTF_8
        )
        if (storeScreenshotQaArtifactIssues(metadata, qaApk, qaTestApk) != listOf(
                "Play screenshots must be recaptured from the exact QA APK payload"
            )
        ) {
            throw GradleException("Store screenshot validator accepted a mismatched QA APK payload.")
        }
        metadata.writeText(
            JsonOutput.toJson(
                mapOf(
                    "qa_apk_payload_sha256" to apkPayloadSha256(qaApk),
                    "qa_test_apk_payload_sha256" to "00".repeat(32)
                )
            ),
            Charsets.UTF_8
        )
        if (storeScreenshotQaArtifactIssues(metadata, qaApk, qaTestApk) != listOf(
                "Play screenshots must be recaptured from the exact QA instrumentation APK payload"
            )
        ) {
            throw GradleException("Store screenshot validator accepted a mismatched QA instrumentation APK payload.")
        }
    }
}

val verifyStoreScreenshotsAgainstQaApk = tasks.register(
    "verifyStoreScreenshotsAgainstQaApk"
) {
    group = "verification"
    description = "Binds the reviewed Store screenshots to the assembled QA app and instrumentation payloads."
    dependsOn("assembleQa", "assembleQaAndroidTest", verifyStoreScreenshotQaApkValidatorContract)
    val metadataFile = rootProject.file("docs/store/assets/screenshots/capture-metadata.json")
    val qaApk = layout.buildDirectory.file("outputs/apk/qa/app-qa.apk")
    val qaTestApk = layout.buildDirectory.file("outputs/apk/androidTest/qa/app-qa-androidTest.apk")
    inputs.file(metadataFile)
    inputs.file(qaApk)
    inputs.file(qaTestApk)
    outputs.file(storeScreenshotQaApkValidationReportFile)
    outputs.upToDateWhen { false }

    doFirst {
        storeScreenshotQaApkValidationReportFile.get().asFile.delete()
    }

    doLast {
        val qaApkFile = qaApk.get().asFile
        val qaTestApkFile = qaTestApk.get().asFile
        val issues = storeScreenshotQaArtifactIssues(metadataFile, qaApkFile, qaTestApkFile)
        if (issues.isNotEmpty()) {
            throw GradleException("Store screenshot QA artifact issues: ${issues.joinToString()}")
        }
        val head = runCatching {
            val process = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            output.takeIf { process.waitFor() == 0 && it.matches(Regex("[0-9a-fA-F]{40,64}")) }
        }.getOrNull() ?: "UNAVAILABLE"
        val report = storeScreenshotQaApkValidationReportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("schema=v-slot-store-screenshot-qa-artifact-validation-v2")
                appendLine("release-head=$head")
                appendLine("capture-metadata-sha256=${sha256Hex(metadataFile)}")
                appendLine("qa-apk-sha256=${sha256Hex(qaApkFile)}")
                appendLine("qa-apk-payload-sha256=${apkPayloadSha256(qaApkFile)}")
                appendLine("qa-test-apk-sha256=${sha256Hex(qaTestApkFile)}")
                appendLine("qa-test-apk-payload-sha256=${apkPayloadSha256(qaTestApkFile)}")
                appendLine("status=PASS")
            },
            Charsets.UTF_8
        )
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(verifyStoreScreenshotQaApkValidatorContract)
}

val verifyStoreAssets = tasks.register("verifyStoreAssets") {
    group = "verification"
    description = "Validates the checked-in Google Play assets and media provenance inventory."

    doLast {
        val issues = storeListingAssetIssues() + assetProvenanceInventoryIssues()
        if (issues.isNotEmpty()) {
            throw GradleException("Store asset issues: ${issues.joinToString()}")
        }
    }
}

verifyStoreReadiness.configure {
    dependsOn(verifyStoreAssets)
}

val archivedAssetRightsEvidenceFile = layout.buildDirectory.file(
    "reports/release-security/asset-rights-evidence.json"
)

val verifyAssetRightsEvidence = tasks.register("verifyAssetRightsEvidence") {
    group = "verification"
    description = "Validates and archives commit-, version-, and inventory-bound asset rights evidence."
    outputs.file(archivedAssetRightsEvidenceFile)
    outputs.upToDateWhen { false }
    if (assetRightsEvidenceFilePath.isNotBlank()) {
        inputs.file(file(assetRightsEvidenceFilePath))
    }
    inputs.files(assetProvenanceInventoryFile)
    inputs.property("expectedAssetRightsEvidenceSha256", expectedAssetRightsEvidenceSha256)
    inputs.property("expectedVersionCode", vSlotVersionCode)

    doFirst {
        archivedAssetRightsEvidenceFile.get().asFile.delete()
        failOnStoreReadinessIssues()
    }

    doLast {
        val gitProcess = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val head = gitProcess.inputStream.bufferedReader().use { it.readText() }.trim()
        if (gitProcess.waitFor() != 0 || !head.matches(Regex("[0-9a-fA-F]{40,64}"))) {
            throw GradleException("Asset rights evidence requires a committed Git HEAD.")
        }
        val source = file(assetRightsEvidenceFilePath)
        val issues = assetRightsEvidenceIssues(
            evidenceFile = source,
            expectedSha256 = expectedAssetRightsEvidenceSha256,
            expectedCommit = head
        )
        if (issues.isNotEmpty()) {
            throw GradleException("Asset rights evidence issues: ${issues.joinToString()}")
        }
        val output = archivedAssetRightsEvidenceFile.get().asFile
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
    }
}

val archivedDataSafetyEvidenceFile = layout.buildDirectory.file(
    "reports/release-security/data-safety-evidence.json"
)
val archivedDataSafetyRawEvidenceFile = layout.buildDirectory.file(
    "reports/release-security/data-safety-raw-evidence.zip"
)

val verifyDataSafetyEvidence = tasks.register("verifyDataSafetyEvidence") {
    group = "verification"
    description = "Validates and archives version-bound privacy and Data Safety review evidence."
    outputs.files(archivedDataSafetyEvidenceFile, archivedDataSafetyRawEvidenceFile)
    outputs.upToDateWhen { false }
    if (dataSafetyEvidenceFilePath.isNotBlank()) {
        inputs.file(file(dataSafetyEvidenceFilePath))
    }
    if (dataSafetyRawEvidenceFilePath.isNotBlank()) {
        inputs.file(file(dataSafetyRawEvidenceFilePath))
    }
    inputs.property("expectedDataSafetyEvidenceSha256", expectedDataSafetyEvidenceSha256)
    inputs.property("expectedDataSafetyRawEvidenceSha256", expectedDataSafetyRawEvidenceSha256)
    inputs.property("expectedVersionCode", vSlotVersionCode)
    inputs.property("expectedPrivacyPolicyUrl", releasePrivacyPolicyUrl)

    doFirst {
        archivedDataSafetyEvidenceFile.get().asFile.delete()
        archivedDataSafetyRawEvidenceFile.get().asFile.delete()
        failOnStoreReadinessIssues()
    }

    doLast {
        val gitProcess = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val head = gitProcess.inputStream.bufferedReader().use { it.readText() }.trim()
        if (gitProcess.waitFor() != 0 || !head.matches(Regex("[0-9a-fA-F]{40,64}"))) {
            throw GradleException("Data Safety evidence requires a committed Git HEAD.")
        }
        val source = file(dataSafetyEvidenceFilePath)
        val issues = dataSafetyEvidenceIssues(
            evidenceFile = source,
            expectedSha256 = expectedDataSafetyEvidenceSha256,
            expectedCommit = head
        )
        if (issues.isNotEmpty()) {
            throw GradleException("Data Safety evidence issues: ${issues.joinToString()}")
        }
        val rawSource = file(dataSafetyRawEvidenceFilePath)
        val rawIssues = dataSafetyRawEvidenceIssues(
            archiveFile = rawSource,
            expectedArchiveSha256 = expectedDataSafetyRawEvidenceSha256,
            evidenceFile = source
        )
        if (rawIssues.isNotEmpty()) {
            throw GradleException("Data Safety raw evidence issues: ${rawIssues.joinToString()}")
        }
        val output = archivedDataSafetyEvidenceFile.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(source.readBytes())
        archivedDataSafetyRawEvidenceFile.get().asFile.writeBytes(rawSource.readBytes())
    }
}

val archivedPhysicalSamsungEvidenceDirectory = layout.buildDirectory.dir(
    "reports/release-security/physical-samsung"
)

val verifyPhysicalSamsungEvidence = tasks.register("verifyPhysicalSamsungEvidence") {
    group = "verification"
    description = "Validates and archives commit-, device-, and QA-APK-bound physical Samsung evidence."
    dependsOn("assembleQa")
    outputs.dir(archivedPhysicalSamsungEvidenceDirectory)
    outputs.upToDateWhen { false }
    listOf(
        samsungQaEvidenceFilePath,
        processDeathEvidenceFilePath,
        frameMetricsEvidenceFilePath,
        physicalSamsungRawEvidenceFilePath
    ).filter(String::isNotBlank).forEach { path -> inputs.file(file(path)) }
    inputs.properties(
        mapOf(
            "expectedSamsungQaEvidenceSha256" to expectedSamsungQaEvidenceSha256,
            "expectedProcessDeathEvidenceSha256" to expectedProcessDeathEvidenceSha256,
            "expectedFrameMetricsEvidenceSha256" to expectedFrameMetricsEvidenceSha256,
            "expectedPhysicalSamsungRawEvidenceSha256" to expectedPhysicalSamsungRawEvidenceSha256
        )
    )
    inputs.file(layout.buildDirectory.file("outputs/apk/qa/app-qa.apk"))

    doFirst {
        archivedPhysicalSamsungEvidenceDirectory.get().asFile.deleteRecursively()
        failOnStoreReadinessIssues()
    }

    doLast {
        val gitProcess = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val head = gitProcess.inputStream.bufferedReader().use { it.readText() }.trim()
        if (gitProcess.waitFor() != 0 || !head.matches(Regex("[0-9a-fA-F]{40,64}"))) {
            throw GradleException("Physical Samsung evidence requires a committed Git HEAD.")
        }
        val qaApk = layout.buildDirectory.file("outputs/apk/qa/app-qa.apk").get().asFile
        if (!qaApk.isFile || qaApk.length() == 0L) {
            throw GradleException("Physical Samsung evidence requires the assembled QA APK.")
        }
        val samsungSource = file(samsungQaEvidenceFilePath)
        val processDeathSource = file(processDeathEvidenceFilePath)
        val frameMetricsSource = file(frameMetricsEvidenceFilePath)
        val rawEvidenceSource = file(physicalSamsungRawEvidenceFilePath)
        val issues = physicalSamsungEvidenceIssues(
            samsungEvidenceFile = samsungSource,
            expectedSamsungSha256 = expectedSamsungQaEvidenceSha256,
            processDeathEvidenceFile = processDeathSource,
            expectedProcessDeathSha256 = expectedProcessDeathEvidenceSha256,
            frameMetricsEvidenceFile = frameMetricsSource,
            expectedFrameMetricsSha256 = expectedFrameMetricsEvidenceSha256,
            rawEvidenceArchiveFile = rawEvidenceSource,
            expectedRawEvidenceArchiveSha256 = expectedPhysicalSamsungRawEvidenceSha256,
            expectedCommit = head,
            expectedQaApkPayloadSha256 = apkPayloadSha256(qaApk)
        )
        if (issues.isNotEmpty()) {
            throw GradleException("Physical Samsung evidence issues: ${issues.joinToString()}")
        }
        val outputDirectory = archivedPhysicalSamsungEvidenceDirectory.get().asFile
        outputDirectory.mkdirs()
        samsungSource.copyTo(outputDirectory.resolve("connected-tests.json"), overwrite = true)
        processDeathSource.copyTo(outputDirectory.resolve("process-death.json"), overwrite = true)
        frameMetricsSource.copyTo(outputDirectory.resolve("frame-metrics.json"), overwrite = true)
        rawEvidenceSource.copyTo(outputDirectory.resolve("raw-evidence.zip"), overwrite = true)
    }
}

val thirdPartyNoticesAssetName = "third_party_notices.txt"
val embeddedThirdPartyNoticesAssetName = "third_party_embedded_licenses.txt"
val thirdPartyNoticesAsset = layout.projectDirectory.file("src/main/assets/$thirdPartyNoticesAssetName")
val thirdPartyNoticesMarkdown = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
val generatedEmbeddedThirdPartyNoticesDirectory = layout.buildDirectory.dir(
    "generated/third-party-notices/assets"
)
val generatedEmbeddedThirdPartyNotices = generatedEmbeddedThirdPartyNoticesDirectory.map { directory ->
    directory.file(embeddedThirdPartyNoticesAssetName)
}
val thirdPartyNoticesSha256 = "64aa2e43232a2bc633818b4f41a63b006828a3efad79b770dbfefe0ee3ddae2d"
val embeddedThirdPartyNoticesSha256 = "6d2afbad7ce49a7388fae48e730079e5ef932e6de69e0634d8066ee4ef0600d1"
val licenseManifestStart = "[release-runtime-artifact-licenses-v1]"
val licenseManifestEnd = "[/release-runtime-artifact-licenses-v1]"
val allowedRuntimeLicensePolicies = setOf(
    "Apache-2.0",
    "BSD-3-Clause",
    "MIT",
    "MPL-2.0",
    "LicenseRef-Android-SDK-Terms"
)
val thirdPartyNoticeMarkers = listOf(
    licenseManifestStart,
    licenseManifestEnd,
    "Apache License",
    "Version 2.0, January 2004",
    "https://developer.android.com/studio/terms.html",
    "The MIT License (MIT)",
    "Copyright (c) 2023 YANDEX LLC",
    "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND",
    "androidx.datastore:datastore-preferences-external-protobuf:1.2.1",
    "Protocol Buffers Java Lite 4.28.2",
    "Copyright 2008 Google Inc. All rights reserved.",
    "Neither the name of Google Inc.",
    "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS",
    "The Public Suffix List",
    "https://publicsuffix.org/list/public_suffix_list.dat",
    "https://mozilla.org/MPL/2.0/"
)

fun sha256Hex(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

val requiredAndroidPageSizeBytes = 16 * 1024L

fun inspectLlvmReadelfLoadAlignments(output: String): Pair<List<Long>, List<String>> {
    val loadRows = output.lineSequence()
        .map { line -> line.trim().split(Regex("\\s+")) }
        .filter { columns -> columns.firstOrNull() == "LOAD" }
        .toList()
    if (loadRows.isEmpty()) {
        return emptyList<Long>() to listOf("llvm-readelf output contains no LOAD program headers")
    }

    val alignments = mutableListOf<Long>()
    val issues = mutableListOf<String>()
    loadRows.forEachIndexed { index, columns ->
        val token = columns.lastOrNull().orEmpty()
        val alignment = when {
            token.startsWith("0x", ignoreCase = true) -> token.drop(2).toLongOrNull(16)
            else -> token.toLongOrNull()
        }
        when {
            alignment == null -> issues += "LOAD[$index] has an unreadable alignment: $token"
            alignment < requiredAndroidPageSizeBytes ->
                issues += "LOAD[$index] alignment 0x${alignment.toString(16)} is below 0x4000"
            alignment and (alignment - 1L) != 0L ->
                issues += "LOAD[$index] alignment 0x${alignment.toString(16)} is not a power of two"
            else -> alignments += alignment
        }
    }
    return alignments to issues
}

fun nativeSoPresenceStatus(count: Int): String {
    require(count >= 0) { "Native library count cannot be negative." }
    return if (count == 0) "NOT_PRESENT" else "PRESENT"
}

fun findAvailableLlvmReadelf(): File? {
    val executableSuffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        ".exe"
    } else {
        ""
    }
    val explicit = System.getenv("V_SLOT_LLVM_READELF").orEmpty().trim()
    if (explicit.isNotEmpty()) {
        return File(explicit).takeIf { candidate -> candidate.isFile && candidate.canExecute() }
    }

    val candidates = linkedSetOf<File>()
    fun addNdkRoot(ndkRoot: File) {
        ndkRoot.resolve("toolchains/llvm/prebuilt").listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .forEach { hostDirectory ->
                candidates += hostDirectory.resolve("bin/llvm-readelf$executableSuffix")
            }
    }
    sequenceOf(System.getenv("ANDROID_NDK_ROOT"), System.getenv("ANDROID_NDK_HOME"))
        .filterNotNull()
        .filter(String::isNotBlank)
        .map(::File)
        .forEach(::addNdkRoot)
    sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
        .filterNotNull()
        .filter(String::isNotBlank)
        .map(::File)
        .forEach { sdkRoot ->
            sdkRoot.resolve("ndk").listFiles()
                .orEmpty()
                .filter(File::isDirectory)
                .sortedByDescending(File::getName)
                .forEach(::addNdkRoot)
            addNdkRoot(sdkRoot.resolve("ndk-bundle"))
        }

    System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .filter(String::isNotBlank)
        .map(::File)
        .forEach { pathDirectory ->
            candidates += pathDirectory.resolve("llvm-readelf$executableSuffix")
            pathDirectory.listFiles()
                .orEmpty()
                .filter { candidate ->
                    candidate.name.matches(Regex("llvm-readelf-[0-9.]+${Regex.escape(executableSuffix)}"))
                }
                .sortedByDescending(File::getName)
                .forEach(candidates::add)
        }
    return candidates.firstOrNull { candidate -> candidate.isFile && candidate.canExecute() }
}

fun apkPayloadSha256(apk: File): String {
    fun isNonRuntimeMetadata(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val leaf = upper.removePrefix("META-INF/")
        return leaf == "MANIFEST.MF" || leaf == "VERSION-CONTROL-INFO.TEXTPROTO" ||
            leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") ||
            leaf.endsWith(".EC")
    }

    val entries = mutableListOf<Pair<String, String>>()
    ZipFile(apk).use { zip ->
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory || isNonRuntimeMetadata(entry.name)) continue
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val contentSha256 = digest.digest()
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            entries += entry.name to contentSha256
        }
    }

    val payload = MessageDigest.getInstance("SHA-256")
    payload.update("v-slot-apk-payload-v2\n".toByteArray(Charsets.US_ASCII))
    entries.sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second })).forEach { (name, digest) ->
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        payload.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(nameBytes.size).array())
        payload.update(nameBytes)
        payload.update(digest.toByteArray(Charsets.US_ASCII))
    }
    return payload.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

fun parseRuntimeLicenseManifest(text: String, source: String): Map<String, String> {
    if (!text.contains(licenseManifestStart) || !text.contains(licenseManifestEnd)) {
        throw GradleException("$source is missing the structured release runtime license manifest.")
    }
    val entries = sortedMapOf<String, String>()
    text.substringAfter(licenseManifestStart)
        .substringBefore(licenseManifestEnd)
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") }
        .forEach { line ->
            val fields = line.split('|').map(String::trim)
            if (fields.size != 2 || fields[0].split(':').size != 3) {
                throw GradleException("$source contains an invalid license manifest row: $line")
            }
            val policies = fields[1]
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
            if (policies.isEmpty() || policies.size != policies.distinct().size) {
                throw GradleException("$source contains an invalid license policy set for ${fields[0]}: ${fields[1]}")
            }
            val unreviewedPolicies = policies.filterNot(allowedRuntimeLicensePolicies::contains)
            if (unreviewedPolicies.isNotEmpty()) {
                throw GradleException(
                    "$source uses unreviewed license policies for ${fields[0]}: ${unreviewedPolicies.joinToString()}"
                )
            }
            val canonicalPolicies = policies.toSortedSet().joinToString(",")
            if (entries.put(fields[0], canonicalPolicies) != null) {
                throw GradleException("$source contains duplicate license coordinates: ${fields[0]}")
            }
        }
    if (entries.isEmpty()) {
        throw GradleException("$source contains an empty release runtime license manifest.")
    }
    return entries
}

fun validateThirdPartyNotices(bytes: ByteArray, source: String) {
    val text = bytes.toString(Charsets.UTF_8)
    val missing = thirdPartyNoticeMarkers.filterNot(text::contains)
    if (missing.isNotEmpty()) {
        throw GradleException("$source is missing required third-party notices: ${missing.joinToString()}")
    }
    parseRuntimeLicenseManifest(text, source)
    val actualSha256 = sha256Hex(bytes)
    if (actualSha256 != thirdPartyNoticesSha256) {
        throw GradleException(
            "$source does not match the reviewed third-party notice (SHA-256 $actualSha256)"
        )
    }
}

fun isEmbeddedLicenseEntry(path: String): Boolean {
    val normalized = path.replace('\\', '/').lowercase()
    val name = normalized.substringAfterLast('/')
    return name == "license" ||
        name.startsWith("license.") ||
        name == "notice" ||
        name.startsWith("notice.") ||
        name == "copying" ||
        name.startsWith("copying.") ||
        name.startsWith("third_party_licenses.")
}

val generateEmbeddedThirdPartyLicenses = tasks.register("generateEmbeddedThirdPartyLicenses") {
    group = "verification"
    description = "Extracts every nested release-runtime license and notice into a packaged deterministic asset."
    outputs.file(generatedEmbeddedThirdPartyNotices)
    outputs.upToDateWhen { false }

    doLast {
        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val originRows = sortedSetOf<String>()
        val contentBySha256 = sortedMapOf<String, ByteArray>()
        val maxEmbeddedLicenseBytes = 2L * 1024L * 1024L

        fun recordLicense(
            coordinate: String,
            artifactName: String,
            entryPath: String,
            bytes: ByteArray
        ) {
            if (bytes.size > maxEmbeddedLicenseBytes) {
                throw GradleException(
                    "Embedded license entry is unexpectedly large: $coordinate/$artifactName!/$entryPath (${bytes.size} bytes)"
                )
            }
            val digest = sha256Hex(bytes)
            val existing = contentBySha256.putIfAbsent(digest, bytes)
            if (existing != null && !existing.contentEquals(bytes)) {
                throw GradleException("SHA-256 collision while inventorying embedded licenses: $digest")
            }
            originRows += listOf(
                coordinate,
                artifactName,
                entryPath.replace('\t', ' '),
                bytes.size.toString(),
                digest
            ).joinToString("\t")
        }

        fun inspectNestedArchive(
            coordinate: String,
            artifactName: String,
            archivePath: String,
            bytes: ByteArray
        ) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { nestedZip ->
                while (true) {
                    val nestedEntry = nestedZip.nextEntry ?: break
                    if (!nestedEntry.isDirectory && isEmbeddedLicenseEntry(nestedEntry.name)) {
                        recordLicense(
                            coordinate,
                            artifactName,
                            "$archivePath!/${nestedEntry.name}",
                            nestedZip.readBytes()
                        )
                    }
                    nestedZip.closeEntry()
                }
            }
        }

        configuration.incoming.artifacts.artifacts
            .sortedBy { artifact -> artifact.id.componentIdentifier.displayName + ":" + artifact.file.name }
            .forEach { artifact ->
                val identifier = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: return@forEach
                val coordinate = "${identifier.group}:${identifier.module}:${identifier.version}"
                val archive = artifact.file
                if (archive.extension.lowercase() !in setOf("aar", "jar", "zip")) return@forEach
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence()
                        .filterNot { entry -> entry.isDirectory }
                        .sortedBy { entry -> entry.name }
                        .forEach { entry ->
                            when {
                                isEmbeddedLicenseEntry(entry.name) -> {
                                    recordLicense(
                                        coordinate,
                                        archive.name,
                                        entry.name,
                                        zip.getInputStream(entry).use { input -> input.readBytes() }
                                    )
                                }
                                entry.name.lowercase().endsWith(".jar") -> {
                                    inspectNestedArchive(
                                        coordinate,
                                        archive.name,
                                        entry.name,
                                        zip.getInputStream(entry).use { input -> input.readBytes() }
                                    )
                                }
                            }
                        }
                }
            }

        if (originRows.isEmpty() || contentBySha256.isEmpty()) {
            throw GradleException("No embedded release-runtime license files were discovered.")
        }
        val output = generatedEmbeddedThirdPartyNotices.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("V Slot Embedded Third-Party License Evidence")
                appendLine("============================================")
                appendLine()
                appendLine("schema=release-runtime-embedded-licenses-v1")
                appendLine("origin-count=${originRows.size}")
                appendLine("unique-content-count=${contentBySha256.size}")
                appendLine()
                appendLine("[origins]")
                appendLine("coordinate\tartifact\tentry\tbyte-count\tsha256")
                originRows.forEach(::appendLine)
                appendLine("[/origins]")
                contentBySha256.forEach { (digest, bytes) ->
                    appendLine()
                    appendLine("----- BEGIN EMBEDDED LICENSE $digest -----")
                    append(bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").trimEnd())
                    appendLine()
                    appendLine("----- END EMBEDDED LICENSE $digest -----")
                }
            },
            Charsets.UTF_8
        )
        val actualSha256 = sha256Hex(output)
        if (actualSha256 != embeddedThirdPartyNoticesSha256) {
            throw GradleException(
                "Embedded runtime license evidence changed (SHA-256 $actualSha256). " +
                    "Review every origin and license before updating the pinned digest."
            )
        }
    }
}

android.sourceSets.getByName("main").assets.srcDir(generatedEmbeddedThirdPartyNoticesDirectory)
tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("Assets") }
    .configureEach { dependsOn(generateEmbeddedThirdPartyLicenses) }
tasks.matching { task -> task.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(generateEmbeddedThirdPartyLicenses) }

val releaseDependencyInventoryFile = layout.buildDirectory.file(
    "reports/release-security/release-runtime-classpath.tsv"
)
val generatedReleaseOsvInventoryFile = layout.buildDirectory.file(
    "reports/release-security/osv-scanner-release-runtime.json"
)
val reviewedReleaseOsvInventoryFile = rootProject.file("osv-scanner-custom.json")
val releaseLicenseEvidenceFile = layout.buildDirectory.file(
    "reports/release-security/release-license-evidence.txt"
)

val generateReleaseRuntimeClasspathInventory = tasks.register("generateReleaseRuntimeClasspathInventory") {
    group = "verification"
    description = "Records every resolved release runtime component and artifact with its SHA-256."
    inputs.files(layout.projectDirectory.file("gradle.lockfile"), layout.projectDirectory.file("build.gradle.kts"))
    outputs.files(releaseDependencyInventoryFile, generatedReleaseOsvInventoryFile)
    outputs.upToDateWhen { false }

    doLast {
        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val resolvedComponents = configuration.incoming.resolutionResult.allComponents.toList()
        val runtimeArtifacts = configuration.incoming.artifacts.artifacts.toList()
        val unsupportedComponents = resolvedComponents
            .map { component -> component.id }
            .filterNot { identifier ->
                identifier is ModuleComponentIdentifier ||
                    identifier is ProjectComponentIdentifier && identifier.projectPath == project.path
            }
            .map { identifier -> identifier.displayName.replace('\t', ' ') }
            .toSortedSet()
        val unsupportedArtifacts = runtimeArtifacts
            .map { artifact -> artifact.id.componentIdentifier }
            .filterNot { identifier -> identifier is ModuleComponentIdentifier }
            .map { identifier -> identifier.displayName.replace('\t', ' ') }
            .toSortedSet()
        if (unsupportedComponents.isNotEmpty() || unsupportedArtifacts.isNotEmpty()) {
            throw GradleException(
                "Release runtime dependencies must be reviewed external Maven modules. " +
                    "Unsupported components=${unsupportedComponents.joinToString()}, " +
                    "artifacts=${unsupportedArtifacts.joinToString()}"
            )
        }
        val componentRows = resolvedComponents
            .map { component ->
                when (val identifier = component.id) {
                    is ModuleComponentIdentifier ->
                        "module\t${identifier.group}:${identifier.module}:${identifier.version}"
                    is ProjectComponentIdentifier -> "project\t${identifier.projectPath}"
                    else -> "other\t${identifier.displayName.replace('\t', ' ')}"
                }
            }
            .toSortedSet()
        val moduleIdentifiers = resolvedComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
            .distinctBy { identifier -> "${identifier.group}:${identifier.module}:${identifier.version}" }
            .sortedBy { identifier -> "${identifier.group}:${identifier.module}:${identifier.version}" }
        val artifactRows = runtimeArtifacts
            .map { artifact ->
                val identifier = artifact.id.componentIdentifier
                val identity = when (identifier) {
                    is ModuleComponentIdentifier ->
                        "module\t${identifier.group}:${identifier.module}:${identifier.version}"
                    is ProjectComponentIdentifier -> "project\t${identifier.projectPath}"
                    else -> "other\t${identifier.displayName.replace('\t', ' ')}"
                }
                "$identity\t${artifact.file.name}\t${sha256Hex(artifact.file)}"
            }
            .toSortedSet()
        if (componentRows.isEmpty() || artifactRows.isEmpty()) {
            throw GradleException("releaseRuntimeClasspath resolved to an empty component or artifact inventory.")
        }

        val output = releaseDependencyInventoryFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("schema=release-runtime-classpath-inventory-v1")
                appendLine("configuration=releaseRuntimeClasspath")
                appendLine("component-count=${componentRows.size}")
                appendLine("artifact-count=${artifactRows.size}")
                appendLine("[components]")
                componentRows.forEach(::appendLine)
                appendLine("[artifacts]")
                artifactRows.forEach(::appendLine)
            },
            Charsets.UTF_8
        )
        val osvDocument = linkedMapOf(
            "results" to listOf(
                linkedMapOf(
                    "packages" to moduleIdentifiers.map { identifier ->
                        linkedMapOf(
                            "package" to linkedMapOf(
                                "name" to "${identifier.group}:${identifier.module}",
                                "version" to identifier.version,
                                "ecosystem" to "Maven"
                            )
                        )
                    }
                )
            )
        )
        val osvOutput = generatedReleaseOsvInventoryFile.get().asFile
        osvOutput.parentFile.mkdirs()
        osvOutput.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(osvDocument)) + "\n",
            Charsets.UTF_8
        )
    }
}

val verifyReleaseOsvInventory = tasks.register("verifyReleaseOsvInventory") {
    group = "verification"
    description = "Requires the reviewed OSV inventory to exactly match releaseRuntimeClasspath."
    dependsOn(generateReleaseRuntimeClasspathInventory)
    inputs.files(generatedReleaseOsvInventoryFile, reviewedReleaseOsvInventoryFile)
    outputs.upToDateWhen { false }

    doLast {
        val generated = generatedReleaseOsvInventoryFile.get().asFile
        if (!reviewedReleaseOsvInventoryFile.isFile) {
            throw GradleException(
                "Reviewed OSV inventory is missing; run :app:updateReleaseOsvInventory after dependency review."
            )
        }
        if (!generated.readBytes().contentEquals(reviewedReleaseOsvInventoryFile.readBytes())) {
            throw GradleException(
                "Reviewed OSV inventory is stale; review dependency changes and run :app:updateReleaseOsvInventory."
            )
        }
    }
}

tasks.register("updateReleaseOsvInventory") {
    group = "help"
    description = "Updates the checked OSV inventory after an intentional release dependency review."
    dependsOn(generateReleaseRuntimeClasspathInventory)

    doLast {
        reviewedReleaseOsvInventoryFile.writeBytes(generatedReleaseOsvInventoryFile.get().asFile.readBytes())
    }
}

val verifyReleaseDependencyLicenses = tasks.register("verifyReleaseDependencyLicenses") {
    group = "verification"
    description = "Requires a reviewed packaged license mapping for every resolved release runtime artifact."
    dependsOn(
        generateReleaseRuntimeClasspathInventory,
        verifyReleaseOsvInventory,
        generateEmbeddedThirdPartyLicenses
    )
    inputs.files(
        releaseDependencyInventoryFile,
        thirdPartyNoticesAsset,
        thirdPartyNoticesMarkdown,
        generatedEmbeddedThirdPartyNotices
    )
    outputs.file(releaseLicenseEvidenceFile)
    outputs.upToDateWhen { false }

    doLast {
        val inventory = releaseDependencyInventoryFile.get().asFile.readText(Charsets.UTF_8)
        val componentRows = inventory.substringAfter("[components]\n")
            .substringBefore("[artifacts]\n")
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val artifactRows = inventory.substringAfter("[artifacts]\n")
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val invalidArtifacts = artifactRows.filter { row ->
            val fields = row.split('\t')
            fields.size != 4 || !fields[3].matches(Regex("[0-9a-f]{64}"))
        }
        if (invalidArtifacts.isNotEmpty()) {
            throw GradleException("Release dependency inventory contains invalid artifact rows: ${invalidArtifacts.joinToString()}")
        }
        val componentIds = componentRows.map { it.substringAfter('\t') }.toSet()
        val externalArtifactCoordinates = artifactRows
            .map { it.split('\t') }
            .filter { it[0] == "module" }
            .map { it[1] }
            .toSortedSet()
        val orphanedArtifacts = externalArtifactCoordinates - componentIds
        if (orphanedArtifacts.isNotEmpty()) {
            throw GradleException("Release artifacts are missing from the resolved component inventory: ${orphanedArtifacts.joinToString()}")
        }

        val asset = thirdPartyNoticesAsset.asFile
        val markdown = thirdPartyNoticesMarkdown.asFile
        if (!asset.isFile || !markdown.isFile) {
            throw GradleException("Third-party notice source files are missing.")
        }
        val assetBytes = asset.readBytes()
        validateThirdPartyNotices(assetBytes, asset.path)
        val packagedLicenses = parseRuntimeLicenseManifest(assetBytes.toString(Charsets.UTF_8), asset.path)
        val repositoryLicenses = parseRuntimeLicenseManifest(markdown.readText(Charsets.UTF_8), markdown.path)
        if (repositoryLicenses != packagedLicenses) {
            throw GradleException("Repository and packaged third-party license manifests differ.")
        }
        val missingLicenses = externalArtifactCoordinates - packagedLicenses.keys
        val staleLicenses = packagedLicenses.keys - externalArtifactCoordinates
        if (missingLicenses.isNotEmpty() || staleLicenses.isNotEmpty()) {
            throw GradleException(
                "Release runtime license inventory mismatch. Missing=${missingLicenses.joinToString()}, " +
                    "stale=${staleLicenses.joinToString()}"
            )
        }

        val output = releaseLicenseEvidenceFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("schema=release-license-evidence-v1")
                appendLine("configuration=releaseRuntimeClasspath")
                appendLine("component-count=${componentRows.size}")
                appendLine("artifact-count=${artifactRows.size}")
                appendLine("external-artifact-coordinate-count=${externalArtifactCoordinates.size}")
                appendLine("license-entry-count=${packagedLicenses.size}")
                appendLine("license-policies=${packagedLicenses.values.toSortedSet().joinToString(",")}")
                appendLine("release-dependency-inventory-sha256=${sha256Hex(releaseDependencyInventoryFile.get().asFile)}")
                appendLine("packaged-notices-sha256=${sha256Hex(assetBytes)}")
                appendLine("repository-notices-sha256=${sha256Hex(markdown)}")
                appendLine(
                    "embedded-runtime-licenses-sha256=" +
                        sha256Hex(generatedEmbeddedThirdPartyNotices.get().asFile)
                )
                appendLine("status=PASS")
            },
            Charsets.UTF_8
        )
    }
}

val verifyThirdPartyNoticesSource = tasks.register("verifyThirdPartyNoticesSource") {
    group = "verification"
    description = "Validates the reviewed notice and complete resolved release artifact license inventory."
    dependsOn(verifyReleaseDependencyLicenses)
    inputs.file(thirdPartyNoticesAsset)

    doLast {
        val asset = thirdPartyNoticesAsset.asFile
        if (!asset.isFile) {
            throw GradleException("Third-party notice asset was not found: ${asset.path}")
        }
        validateThirdPartyNotices(asset.readBytes(), asset.path)
    }
}

val verifyQaThirdPartyNotices = tasks.register("verifyQaThirdPartyNotices") {
    group = "verification"
    description = "Checks that every assembled QA APK contains the reviewed third-party notice."
    dependsOn("assembleQa", verifyThirdPartyNoticesSource)
    val qaApkDirectory = layout.buildDirectory.dir("outputs/apk/qa")
    inputs.dir(qaApkDirectory)

    doLast {
        val apks = qaApkDirectory.get().asFileTree
            .matching { include("*.apk") }
            .files
            .sortedBy { it.name }
        if (apks.isEmpty()) {
            throw GradleException("No QA APK was produced in ${qaApkDirectory.get().asFile.path}")
        }
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("assets/$thirdPartyNoticesAssetName")
                    ?: throw GradleException("${apk.path} does not contain assets/$thirdPartyNoticesAssetName")
                validateThirdPartyNotices(zip.getInputStream(entry).use { it.readBytes() }, apk.path)
                val embeddedEntry = zip.getEntry("assets/$embeddedThirdPartyNoticesAssetName")
                    ?: throw GradleException(
                        "${apk.path} does not contain assets/$embeddedThirdPartyNoticesAssetName"
                    )
                val embeddedBytes = zip.getInputStream(embeddedEntry).use { it.readBytes() }
                val embeddedSha256 = sha256Hex(embeddedBytes)
                if (embeddedSha256 != embeddedThirdPartyNoticesSha256) {
                    throw GradleException(
                        "${apk.path} contains unreviewed embedded license evidence (SHA-256 $embeddedSha256)"
                    )
                }
            }
        }
    }
}

val verifyReleaseThirdPartyNotices = tasks.register("verifyReleaseThirdPartyNotices") {
    group = "verification"
    description = "Checks the reviewed third-party notice in merged release assets."
    dependsOn("mergeReleaseAssets", verifyThirdPartyNoticesSource)
    val mergedReleaseAssets = layout.buildDirectory.dir("intermediates/assets/release/mergeReleaseAssets")
    inputs.dir(mergedReleaseAssets)

    doLast {
        val mergedAssets = mergedReleaseAssets.get().asFileTree.files.associateBy(File::getName)
        val packagedNotice = mergedAssets[thirdPartyNoticesAssetName]
            ?: throw GradleException(
                "Merged release assets do not contain $thirdPartyNoticesAssetName: " +
                    mergedReleaseAssets.get().asFile.path
            )
        validateThirdPartyNotices(packagedNotice.readBytes(), packagedNotice.path)
        val embeddedNotice = mergedAssets[embeddedThirdPartyNoticesAssetName]
            ?: throw GradleException(
                "Merged release assets do not contain $embeddedThirdPartyNoticesAssetName: " +
                    mergedReleaseAssets.get().asFile.path
            )
        val embeddedSha256 = sha256Hex(embeddedNotice)
        if (embeddedSha256 != embeddedThirdPartyNoticesSha256) {
            throw GradleException(
                "Merged release embedded licenses are not the reviewed inventory (SHA-256 $embeddedSha256)"
            )
        }
    }
}

val verifyThirdPartyNotices = tasks.register("verifyThirdPartyNotices") {
    group = "verification"
    description = "Runs the source and merged release third-party notice checks."
    dependsOn(verifyReleaseThirdPartyNotices)
}

val maxReleaseResourceArchiveBytes = 64L * 1024L * 1024L
val maxSingleReleaseResourceBytes = 1L * 1024L * 1024L
val maxLosslessSourceWebpBytes = 150_000L
val sourceWebpFiles = fileTree("src/main/res") {
    include("**/*.webp")
}

val verifySourceWebpEncoding = tasks.register("verifySourceWebpEncoding") {
    group = "verification"
    description = "Rejects large lossless WebP assets that consume the release resource budget."
    inputs.files(sourceWebpFiles)

    doLast {
        val oversizedLosslessWebps = sourceWebpFiles.files
            .asSequence()
            .filter { it.length() >= maxLosslessSourceWebpBytes }
            .filter { file ->
                val header = ByteArray(64)
                val byteCount = file.inputStream().use { it.read(header) }
                byteCount > 0 &&
                    String(header, 0, byteCount, Charsets.ISO_8859_1).contains("VP8L")
            }
            .map { file ->
                "${file.relativeTo(projectDir).path} (${file.length()} bytes)"
            }
            .sorted()
            .toList()
        if (oversizedLosslessWebps.isNotEmpty()) {
            throw GradleException(
                "Large lossless WebP resources must be visually reviewed and encoded as high-quality lossy WebP: " +
                    oversizedLosslessWebps.joinToString()
            )
        }
    }
}

val optimizedReleaseResources = layout.buildDirectory.file(
    "intermediates/optimized_processed_res/release/optimizeReleaseResources/resources-release-optimize.ap_"
)

val verifyReleaseResourceSize = tasks.register("verifyReleaseResourceSize") {
    group = "verification"
    description = "Rejects unexpected growth in optimized release resources."
    dependsOn("optimizeReleaseResources")
    dependsOn(verifySourceWebpEncoding)
    inputs.file(optimizedReleaseResources)

    doLast {
        val archive = optimizedReleaseResources.get().asFile
        if (!archive.isFile) {
            throw GradleException("Optimized release resource archive was not produced: ${archive.path}")
        }
        if (archive.length() > maxReleaseResourceArchiveBytes) {
            throw GradleException(
                "Optimized release resources exceed 64 MiB: ${archive.length()} bytes"
            )
        }

        val oversizedEntries = ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.size > maxSingleReleaseResourceBytes }
                .map { "${it.name} (${it.size} bytes)" }
                .toList()
        }
        if (oversizedEntries.isNotEmpty()) {
            throw GradleException(
                "Optimized release resources contain entries over 1 MiB: ${oversizedEntries.joinToString()}"
            )
        }

        logger.lifecycle("Optimized release resources: ${archive.length()} / $maxReleaseResourceArchiveBytes bytes")
    }
}

val appSetIdDexValidatorScript = rootProject.file("tools/verify_appsetid_compat.py")
val releaseAppSetIdDexReportFile = layout.buildDirectory.file(
    "reports/release-security/release-app-set-id-dex-validation.txt"
)
val releaseOptimizedDexDirectory = layout.buildDirectory.dir(
    "intermediates/dex/release/minifyReleaseWithR8"
)

val verifyAppSetIdCompatValidatorContract = tasks.register<org.gradle.api.tasks.Exec>(
    "verifyAppSetIdCompatValidatorContract"
) {
    group = "verification"
    description = "Executes positive and negative fixtures for the App Set ID DEX validator."
    inputs.file(appSetIdDexValidatorScript)
    outputs.upToDateWhen { false }
    commandLine("python3", appSetIdDexValidatorScript.absolutePath, "--self-test")
}

val verifyReleaseAppSetIdDisabled = tasks.register<org.gradle.api.tasks.Exec>(
    "verifyReleaseAppSetIdDisabled"
) {
    group = "verification"
    description = "Proves optimized release DEX cannot use Google App Set ID and retains a fail-closed AppMetrica ABI."
    dependsOn("minifyReleaseWithR8", verifyAppSetIdCompatValidatorContract)
    inputs.file(appSetIdDexValidatorScript)
    inputs.dir(releaseOptimizedDexDirectory)
    outputs.file(releaseAppSetIdDexReportFile)
    outputs.upToDateWhen { false }

    doFirst {
        val sdkDirectory = sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            localProperties.getProperty("sdk.dir")
        )
            .filterNotNull()
            .firstOrNull(String::isNotBlank)
            ?.let(::File)
            ?: throw GradleException(
                "App Set ID DEX validation requires ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir."
            )
        val dexdumpName = if (
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        ) {
            "dexdump.exe"
        } else {
            "dexdump"
        }
        val dexdump = sdkDirectory.resolve(
            "build-tools/$vSlotBuildToolsVersion/$dexdumpName"
        )
        if (!dexdump.isFile) {
            throw GradleException(
                "App Set ID DEX validation requires build-tools " +
                    "$vSlotBuildToolsVersion dexdump: ${dexdump.path}"
            )
        }
        val dexFiles = releaseOptimizedDexDirectory.get().asFile
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "dex" }
            .sortedBy(File::invariantSeparatorsPath)
            .toList()
        if (dexFiles.isEmpty()) {
            throw GradleException("Optimized release DEX files were not produced.")
        }
        val report = releaseAppSetIdDexReportFile.get().asFile
        report.delete()
        commandLine(
            listOf(
                "python3",
                appSetIdDexValidatorScript.absolutePath,
                "--dexdump",
                dexdump.absolutePath,
                "--report",
                report.absolutePath
            ) + dexFiles.map(File::getAbsolutePath)
        )
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(verifyAppSetIdCompatValidatorContract)
}

val archiveReleaseMapping = tasks.register<org.gradle.api.tasks.bundling.Zip>("archiveReleaseMapping") {
    group = "verification"
    description = "Archives the R8 mapping and shrinker diagnostics required to decode production crashes."
    dependsOn("minifyReleaseWithR8")
    dependsOn(rootProject.tasks.named("verifyReleaseProvenance"))
    val mappingDirectory = layout.buildDirectory.dir("outputs/mapping/release")
    from(mappingDirectory) {
        include("mapping.txt", "configuration.txt", "seeds.txt", "usage.txt", "resources.txt")
    }
    from(rootProject.layout.buildDirectory.file("reports/release-provenance.txt"))
    archiveFileName.set("v-slot-$vSlotVersionName-$vSlotVersionCode-r8-mapping.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/diagnostics/release"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    doFirst {
        val mappingFile = mappingDirectory.get().file("mapping.txt").asFile
        if (!mappingFile.isFile || mappingFile.length() == 0L) {
            throw GradleException("R8 mapping was not produced: ${mappingFile.path}")
        }
    }
}

val release16kPageSizeReportFile = layout.buildDirectory.file(
    "reports/release-security/release-16k-page-size.txt"
)
val release16kUniversalApkFile = layout.buildDirectory.file(
    "intermediates/release-16k-page-size/universal.apk"
)

val verifyRelease16kPageSizeValidatorContract = tasks.register(
    "verifyRelease16kPageSizeValidatorContract"
) {
    group = "verification"
    description = "Executes positive and negative fixtures for the 16 KB ELF LOAD alignment validator."
    outputs.upToDateWhen { false }

    doLast {
        val validOutput = """
            Elf file type is DYN (Shared object file)
            Program Headers:
              Type Offset VirtAddr PhysAddr FileSiz MemSiz Flg Align
              LOAD 0x000000 0x00000000 0x00000000 0x001000 0x001000 R E 0x4000
              LOAD 0x004000 0x00004000 0x00004000 0x000800 0x000800 RW  0x10000
        """.trimIndent()
        val (validAlignments, validIssues) = inspectLlvmReadelfLoadAlignments(validOutput)
        if (validIssues.isNotEmpty() || validAlignments != listOf(0x4000L, 0x10000L)) {
            throw GradleException("Valid 16 KB llvm-readelf fixture was rejected: ${validIssues.joinToString()}")
        }

        val (_, underAlignedIssues) = inspectLlvmReadelfLoadAlignments(
            "LOAD 0x000000 0x00000000 0x00000000 0x001000 0x001000 R E 0x1000"
        )
        if (underAlignedIssues.none { issue -> issue.contains("below 0x4000") }) {
            throw GradleException("16 KB validator accepted a 4 KB-aligned LOAD segment.")
        }
        val (_, malformedIssues) = inspectLlvmReadelfLoadAlignments(
            "LOAD 0x000000 0x00000000 0x00000000 0x001000 0x001000 R E unknown"
        )
        if (malformedIssues.none { issue -> issue.contains("unreadable alignment") }) {
            throw GradleException("16 KB validator accepted an unreadable LOAD alignment.")
        }
        val (_, missingLoadIssues) = inspectLlvmReadelfLoadAlignments("There are no program headers in this file.")
        if (missingLoadIssues.none { issue -> issue.contains("no LOAD program headers") }) {
            throw GradleException("16 KB validator accepted llvm-readelf output without LOAD segments.")
        }
        if (nativeSoPresenceStatus(0) != "NOT_PRESENT" || nativeSoPresenceStatus(1) != "PRESENT") {
            throw GradleException("16 KB validator did not record native library presence correctly.")
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(verifyRelease16kPageSizeValidatorContract)
}

val verifyRelease16kPageSize = tasks.register("verifyRelease16kPageSize") {
    group = "verification"
    description = "Builds a universal APK from the final release AAB and enforces 16 KB ZIP and ELF alignment."
    mustRunAfter("bundleRelease")
    outputs.files(release16kPageSizeReportFile, release16kUniversalApkFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = release16kPageSizeReportFile.get().asFile
        val universalApk = release16kUniversalApkFile.get().asFile
        report.delete()
        universalApk.delete()

        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        if (!bundle.isFile || bundle.length() == 0L) {
            throw GradleException("Final release AAB is missing for the 16 KB page-size gate: ${bundle.path}")
        }
        val bundletoolJar = releaseBundletoolJarPath
            .trim()
            .takeIf(String::isNotBlank)
            ?.let(::File)
            ?: throw GradleException("V_SLOT_BUNDLETOOL_JAR is required for the 16 KB page-size gate.")
        if (!bundletoolJar.isFile) {
            throw GradleException("Reviewed bundletool JAR is missing: ${bundletoolJar.path}")
        }
        val bundletoolSha256 = sha256Hex(bundletoolJar)
        if (bundletoolSha256 != requiredReleaseBundletoolSha256) {
            throw GradleException(
                "16 KB page-size validation requires bundletool $requiredReleaseBundletoolVersion " +
                    "with the reviewed SHA-256."
            )
        }
        val javaExecutable = File(System.getProperty("java.home"), "bin/java")
        if (!javaExecutable.isFile || !javaExecutable.canExecute()) {
            throw GradleException("Java executable is missing: ${javaExecutable.path}")
        }
        val sdkRoot = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .filterNotNull()
            .filter(String::isNotBlank)
            .map(::File)
            .firstOrNull(File::isDirectory)
            ?: throw GradleException("16 KB page-size validation requires ANDROID_HOME or ANDROID_SDK_ROOT.")
        val executableSuffix = if (
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        ) ".exe" else ""
        val zipalign = sdkRoot.resolve("build-tools/$vSlotBuildToolsVersion/zipalign$executableSuffix")
        if (!zipalign.isFile || !zipalign.canExecute()) {
            throw GradleException(
                "Android build-tools $vSlotBuildToolsVersion zipalign is missing: ${zipalign.path}"
            )
        }

        fun runCommand(label: String, command: List<String>): String {
            val process = ProcessBuilder(command)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException(
                    "$label failed with exit code $exitCode: ${output.trim().take(4_096)}"
                )
            }
            return output
        }

        val workDirectory = temporaryDir.resolve("release-16k-page-size")
        workDirectory.deleteRecursively()
        workDirectory.mkdirs()
        val apkSet = workDirectory.resolve("release-universal.apks")
        runCommand(
            "bundletool build-apks",
            listOf(
                javaExecutable.absolutePath,
                "-jar",
                bundletoolJar.absolutePath,
                "build-apks",
                "--bundle=${bundle.absolutePath}",
                "--output=${apkSet.absolutePath}",
                "--mode=universal",
                "--overwrite"
            )
        )
        if (!apkSet.isFile || apkSet.length() == 0L) {
            throw GradleException("bundletool did not produce a universal APK set: ${apkSet.path}")
        }

        universalApk.parentFile.mkdirs()
        ZipFile(apkSet).use { zip ->
            val universalEntries = zip.entries().asSequence()
                .filterNot { entry -> entry.isDirectory }
                .filter { entry -> entry.name == "universal.apk" }
                .toList()
            if (universalEntries.size != 1) {
                throw GradleException(
                    "bundletool APK set must contain exactly one universal.apk; found ${universalEntries.size}."
                )
            }
            zip.getInputStream(universalEntries.single()).buffered().use { input ->
                universalApk.outputStream().buffered().use(input::copyTo)
            }
        }
        if (!universalApk.isFile || universalApk.length() == 0L) {
            throw GradleException("Extracted universal APK is empty: ${universalApk.path}")
        }

        runCommand(
            "zipalign -c -P 16",
            listOf(zipalign.absolutePath, "-c", "-P", "16", "-v", "4", universalApk.absolutePath)
        )

        val reportLines = mutableListOf(
            "schema=v-slot-release-16k-page-size-v1",
            "bundle-sha256=${sha256Hex(bundle)}",
            "bundletool-version=$requiredReleaseBundletoolVersion",
            "bundletool-sha256=$bundletoolSha256",
            "universal-apk-sha256=${sha256Hex(universalApk)}",
            "zipalign-sha256=${sha256Hex(zipalign)}",
            "zipalign-page-size-bytes=$requiredAndroidPageSizeBytes",
            "zipalign-status=PASS"
        )
        ZipFile(universalApk).use { zip ->
            val nativeEntries = zip.entries().asSequence()
                .filterNot { entry -> entry.isDirectory }
                .filter { entry -> entry.name.endsWith(".so") }
                .sortedBy { entry -> entry.name }
                .toList()
            val duplicateNames = nativeEntries.groupingBy { entry -> entry.name }
                .eachCount()
                .filterValues { count -> count != 1 }
                .keys
            if (duplicateNames.isNotEmpty()) {
                throw GradleException(
                    "Universal APK contains duplicate native library entries: ${duplicateNames.sorted().joinToString()}"
                )
            }
            reportLines += "native-so-count=${nativeEntries.size}"
            reportLines += "native-so-status=${nativeSoPresenceStatus(nativeEntries.size)}"
            if (nativeEntries.isEmpty()) {
                reportLines += "llvm-readelf=NOT_REQUIRED"
                reportLines += "llvm-readelf-sha256=NOT_REQUIRED"
            } else {
                val llvmReadelf = findAvailableLlvmReadelf()
                    ?: throw GradleException(
                        "Universal APK contains ${nativeEntries.size} native libraries, but no executable " +
                            "llvm-readelf is available via V_SLOT_LLVM_READELF, the Android NDK, or PATH."
                    )
                val llvmVersionOutput = runCommand(
                    "llvm-readelf --version",
                    listOf(llvmReadelf.absolutePath, "--version")
                )
                val llvmVersion = llvmVersionOutput.lineSequence()
                    .map(String::trim)
                    .firstOrNull { line -> line.contains("version", ignoreCase = true) }
                    ?: llvmVersionOutput.lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty().trim()
                if (llvmVersion.isBlank() || llvmVersion.contains('\n') || llvmVersion.contains('\r')) {
                    throw GradleException("llvm-readelf returned an invalid version identity.")
                }
                reportLines += "llvm-readelf=$llvmVersion"
                reportLines += "llvm-readelf-sha256=${sha256Hex(llvmReadelf)}"

                val nativeDirectory = workDirectory.resolve("native")
                nativeDirectory.mkdirs()
                nativeEntries.forEachIndexed { index, entry ->
                    val entrySegments = entry.name.split('/')
                    if (entry.name.startsWith('/') || entry.name.contains('\\') ||
                        entry.name.contains('\n') || entry.name.contains('\r') ||
                        entrySegments.any { segment -> segment.isBlank() || segment == "." || segment == ".." }
                    ) {
                        throw GradleException("Universal APK contains an unsafe native library path.")
                    }
                    val extractedLibrary = nativeDirectory.resolve("${index.toString().padStart(4, '0')}.so")
                    zip.getInputStream(entry).buffered().use { input ->
                        extractedLibrary.outputStream().buffered().use(input::copyTo)
                    }
                    if (extractedLibrary.length() == 0L) {
                        throw GradleException("Universal APK contains an empty native library: ${entry.name}")
                    }
                    val readelfOutput = runCommand(
                        "llvm-readelf ${entry.name}",
                        listOf(llvmReadelf.absolutePath, "--program-headers", "--wide", extractedLibrary.absolutePath)
                    )
                    val (alignments, issues) = inspectLlvmReadelfLoadAlignments(readelfOutput)
                    if (issues.isNotEmpty()) {
                        throw GradleException(
                            "Native library ${entry.name} is not 16 KB page-size compatible: ${issues.joinToString()}"
                        )
                    }
                    reportLines += "native-so-$index-path=${entry.name}"
                    reportLines += "native-so-$index-sha256=${sha256Hex(extractedLibrary)}"
                    reportLines += "native-so-$index-load-alignments=" +
                        alignments.joinToString(",") { alignment -> "0x${alignment.toString(16)}" }
                }
            }
        }
        reportLines += "status=PASS"
        report.parentFile.mkdirs()
        report.writeText(reportLines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }
}

val releaseArtifactEvidenceFile = layout.buildDirectory.file(
    "reports/release-security/release-artifact-evidence.txt"
)
val releaseBundletoolValidationReportFile = layout.buildDirectory.file(
    "reports/release-security/bundletool-validation.txt"
)
val releaseBundletoolManifestFile = layout.buildDirectory.file(
    "reports/release-security/bundletool-base-manifest.xml"
)

val verifyReleaseBundleWithBundletool = tasks.register("verifyReleaseBundleWithBundletool") {
    group = "verification"
    description = "Validates the exact release AAB with the reviewed bundletool and inspects its delivered base manifest."
    mustRunAfter("bundleRelease")
    outputs.files(releaseBundletoolValidationReportFile, releaseBundletoolManifestFile)
    outputs.upToDateWhen { false }

    doLast {
        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        if (!bundle.isFile || bundle.length() == 0L) {
            throw GradleException("Release bundle is missing for bundletool validation: ${bundle.path}")
        }
        val bundletoolJar = releaseBundletoolJarPath
            .trim()
            .takeIf(String::isNotBlank)
            ?.let(::File)
            ?: throw GradleException("V_SLOT_BUNDLETOOL_JAR is required for release AAB validation.")
        if (!bundletoolJar.isFile) {
            throw GradleException("Reviewed bundletool JAR is missing: ${bundletoolJar.path}")
        }
        val bundletoolSha256 = sha256Hex(bundletoolJar)
        if (bundletoolSha256 != requiredReleaseBundletoolSha256) {
            throw GradleException(
                "Release AAB validation requires bundletool $requiredReleaseBundletoolVersion " +
                    "with the reviewed SHA-256."
            )
        }
        val javaExecutable = File(System.getProperty("java.home"), "bin/java")
        if (!javaExecutable.isFile) {
            throw GradleException("Java executable is missing: ${javaExecutable.path}")
        }

        fun runBundletool(vararg arguments: String): String {
            val process = ProcessBuilder(
                listOf(javaExecutable.absolutePath, "-jar", bundletoolJar.absolutePath) + arguments
            )
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException(
                    "bundletool ${arguments.firstOrNull().orEmpty()} failed with exit code $exitCode: " +
                        output.trim().take(4_096)
                )
            }
            return output
        }

        val validationOutput = runBundletool("validate", "--bundle=${bundle.absolutePath}")
        val dumpedManifest = runBundletool(
            "dump",
            "manifest",
            "--bundle=${bundle.absolutePath}",
            "--module=base"
        )
        if (!dumpedManifest.contains("<manifest") || !dumpedManifest.contains("</manifest>")) {
            throw GradleException("bundletool did not return a complete base manifest.")
        }
        val manifestDocument = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(dumpedManifest.toByteArray(Charsets.UTF_8)))
        val manifestRoot = manifestDocument.documentElement
        val packageName = manifestRoot.getAttribute("package")
        if (packageName != vSlotApplicationId) {
            throw GradleException(
                "Release AAB package mismatch: expected=$vSlotApplicationId actual=$packageName"
            )
        }
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val deliveredVersionCode = manifestRoot
            .getAttributeNS(androidNamespace, "versionCode")
            .toIntOrNull()
        val deliveredVersionName = manifestRoot.getAttributeNS(androidNamespace, "versionName")
        val usesSdk = manifestDocument.getElementsByTagName("uses-sdk").item(0)
            as? org.w3c.dom.Element
            ?: throw GradleException("Release AAB base manifest has no uses-sdk element.")
        val deliveredMinSdk = usesSdk
            .getAttributeNS(androidNamespace, "minSdkVersion")
            .toIntOrNull()
        val deliveredTargetSdk = usesSdk
            .getAttributeNS(androidNamespace, "targetSdkVersion")
            .toIntOrNull()
        if (
            deliveredVersionCode != vSlotVersionCode ||
            deliveredVersionName != vSlotVersionName ||
            deliveredMinSdk != vSlotMinSdk ||
            deliveredTargetSdk != vSlotStoreSdk
        ) {
            throw GradleException(
                "Release AAB SDK/version mismatch: " +
                    "versionCode=$deliveredVersionCode versionName=$deliveredVersionName " +
                    "minSdk=$deliveredMinSdk targetSdk=$deliveredTargetSdk"
            )
        }
        val application = manifestDocument.getElementsByTagName("application").item(0)
            as? org.w3c.dom.Element
            ?: throw GradleException("Release AAB base manifest has no application element.")
        val debuggable = application.getAttributeNS(androidNamespace, "debuggable")
        val testOnly = application.getAttributeNS(androidNamespace, "testOnly")
        if (debuggable.equals("true", ignoreCase = true) || testOnly.equals("true", ignoreCase = true)) {
            throw GradleException(
                "Release AAB must not be debuggable or testOnly: debuggable=$debuggable testOnly=$testOnly"
            )
        }
        val prohibitedQaComponents = listOf(
            "com.vslot.app.debug.QaResultDialogActivity",
            "com.vslot.app.debug.QaStateReceiver"
        ).filter(dumpedManifest::contains)
        if (prohibitedQaComponents.isNotEmpty()) {
            throw GradleException(
                "Release AAB contains QA-only components: ${prohibitedQaComponents.joinToString()}"
            )
        }

        val manifestOutput = releaseBundletoolManifestFile.get().asFile
        manifestOutput.parentFile.mkdirs()
        manifestOutput.writeText(dumpedManifest, Charsets.UTF_8)
        val report = releaseBundletoolValidationReportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("schema=v-slot-bundletool-validation-v2")
                appendLine("bundle-sha256=${sha256Hex(bundle)}")
                appendLine("bundletool-version=$requiredReleaseBundletoolVersion")
                appendLine("bundletool-sha256=$bundletoolSha256")
                appendLine("package=$packageName")
                appendLine("version-code=$deliveredVersionCode")
                appendLine("version-name=$deliveredVersionName")
                appendLine("min-sdk=$deliveredMinSdk")
                appendLine("target-sdk=$deliveredTargetSdk")
                appendLine("validate-output-sha256=${sha256Hex(validationOutput.toByteArray(Charsets.UTF_8))}")
                appendLine("base-manifest-sha256=${sha256Hex(manifestOutput)}")
                appendLine("status=PASS")
            },
            Charsets.UTF_8
        )
    }
}

val generateReleaseArtifactEvidence = tasks.register("generateReleaseArtifactEvidence") {
    group = "verification"
    description = "Binds the signed release bundle to provenance, signing, tests, lint, manifest, and R8 evidence."
    dependsOn(verifyReleaseBundleWithBundletool, verifyRelease16kPageSize)
    mustRunAfter("bundleRelease")
    outputs.file(releaseArtifactEvidenceFile)
    outputs.upToDateWhen { false }

    doFirst {
        releaseArtifactEvidenceFile.get().asFile.delete()
        val requiredTaskPaths = setOf(
            ":app:bundleRelease",
            ":app:verifyStoreRelease",
            ":app:testReleaseUnitTest",
            ":app:lintRelease",
            ":app:verifyReleaseResourceSize",
            ":app:verifyReleaseDependencyLicenses",
            ":app:verifyReleaseOsvInventory",
            ":app:verifyDataSafetyEvidenceValidatorContract",
            ":app:verifyDataSafetyEvidence",
            ":app:verifyAssetRightsEvidenceValidatorContract",
            ":app:verifyAssetRightsEvidence",
            ":app:verifyPhysicalSamsungEvidenceValidatorContract",
            ":app:verifyPhysicalSamsungEvidence",
            ":app:verifyStoreScreenshotQaApkValidatorContract",
            ":app:verifyStoreScreenshotsAgainstQaApk",
            ":app:verifyReleaseAppSetIdDisabled",
            ":app:verifyReleaseBundleWithBundletool",
            ":app:verifyRelease16kPageSizeValidatorContract",
            ":app:verifyRelease16kPageSize",
            ":app:archiveReleaseMapping",
            ":verifyReleaseSecurityEvidence",
            ":verifyReleaseOsvScanEvidence",
            ":verifyReleaseProvenance"
        )
        val graphTasks = gradle.taskGraph.allTasks.associateBy { task -> task.path }
        val incomplete = requiredTaskPaths.filter { path ->
            val state = graphTasks[path]?.state
            state == null || !state.executed || state.failure != null ||
                (state.skipped && !state.upToDate)
        }
        if (incomplete.isNotEmpty()) {
            throw GradleException(
                "Release artifact evidence requires a successful, unexcluded release graph: " +
                    incomplete.sorted().joinToString()
            )
        }
    }

    doLast {
        val evidenceInputs = sortedMapOf(
            "bundle" to layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile,
            "release-16k-page-size" to release16kPageSizeReportFile.get().asFile,
            "bundletool-base-manifest" to releaseBundletoolManifestFile.get().asFile,
            "bundletool-validation" to releaseBundletoolValidationReportFile.get().asFile,
            "asset-rights-evidence" to archivedAssetRightsEvidenceFile.get().asFile,
            "data-safety-evidence" to archivedDataSafetyEvidenceFile.get().asFile,
            "data-safety-raw-evidence" to archivedDataSafetyRawEvidenceFile.get().asFile,
            "dependency-lock" to file("gradle.lockfile"),
            "lint-report" to layout.buildDirectory.file("reports/lint-results-release.xml").get().asFile,
            "merged-manifest" to layout.buildDirectory.file(
                "intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
            ).get().asFile,
            "osv-release-runtime-inventory" to reviewedReleaseOsvInventoryFile,
            "physical-samsung" to archivedPhysicalSamsungEvidenceDirectory.get().asFile,
            "release-app-set-id-dex" to releaseAppSetIdDexReportFile.get().asFile,
            "store-screenshot-qa-apk" to storeScreenshotQaApkValidationReportFile.get().asFile,
            "r8-mapping-archive" to layout.buildDirectory.file(
                "outputs/diagnostics/release/v-slot-$vSlotVersionName-$vSlotVersionCode-r8-mapping.zip"
            ).get().asFile,
            "release-provenance" to rootProject.layout.buildDirectory.file(
                "reports/release-provenance.txt"
            ).get().asFile,
            "release-security" to rootProject.layout.buildDirectory.file(
                "reports/release-security/release-security-evidence.txt"
            ).get().asFile,
            "release-osv-scan" to rootProject.layout.buildDirectory.file(
                "reports/release-security/osv-scan-evidence.txt"
            ).get().asFile,
            "test-results" to layout.buildDirectory.dir("test-results/testReleaseUnitTest").get().asFile,
            "verification-metadata" to rootProject.file("gradle/verification-metadata.xml")
        )
        val missing = evidenceInputs.filterValues { input ->
            !input.exists() || (input.isDirectory && input.walkTopDown().none(File::isFile))
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release artifact evidence inputs missing: ${missing.keys.joinToString()}"
            )
        }

        fun evidenceSha256(input: File): String {
            if (input.isFile) return sha256Hex(input)
            val digest = MessageDigest.getInstance("SHA-256")
            input.walkTopDown()
                .filter(File::isFile)
                .sortedBy { file -> file.relativeTo(input).invariantSeparatorsPath }
                .forEach { file ->
                    digest.update(file.relativeTo(input).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(sha256Hex(file).toByteArray(Charsets.US_ASCII))
                    digest.update('\n'.code.toByte())
                }
            return digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
        }

        val expectedCertificateSha256 = normalizedSha256(expectedReleaseCertificateSha256)
            ?: throw GradleException("Release artifact evidence requires the verified upload certificate SHA-256.")
        val bundle = evidenceInputs.getValue("bundle")
        val unsignedBundleEntries = sortedSetOf<String>()
        val bundleCertificateDigests = JarFile(bundle, true).use { jar ->
            val digests = sortedSetOf<String>()
            val entries = jar.entries()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                jar.getInputStream(entry).use { input ->
                    while (input.read(buffer) != -1) {
                        // Reading each entry fully makes JarFile verify its signature.
                    }
                }
                val entryCertificates = entry.certificates.orEmpty()
                if (entryCertificates.isEmpty()) {
                    unsignedBundleEntries += entry.name
                }
                entryCertificates.forEach { certificate ->
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest(certificate.encoded)
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
                    digests += checkNotNull(normalizedSha256(digest)) {
                        "Certificate SHA-256 normalization failed."
                    }
                }
            }
            digests
        }
        if (unsignedBundleEntries.isNotEmpty()) {
            throw GradleException(
                "Signed release bundle contains unsigned non-metadata entries: " +
                    unsignedBundleEntries.joinToString()
            )
        }
        if (bundleCertificateDigests != sortedSetOf(expectedCertificateSha256)) {
            throw GradleException(
                "Signed release bundle certificate mismatch: " +
                    "expected=$expectedCertificateSha256 actual=${bundleCertificateDigests.joinToString().ifBlank { "unsigned" }}"
            )
        }
        val output = releaseArtifactEvidenceFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("schema=v-slot-release-artifact-evidence-v7")
                appendLine("application-id=$vSlotApplicationId")
                appendLine("version-code=$vSlotVersionCode")
                appendLine("version-name=$vSlotVersionName")
                appendLine("upload-certificate-sha256=$expectedCertificateSha256")
                evidenceInputs.forEach { (name, input) ->
                    appendLine("$name-sha256=${evidenceSha256(input)}")
                }
                appendLine("status=PASS")
            },
            Charsets.UTF_8
        )
    }
}

val storeReleaseArtifactTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "packageReleaseUniversalApk",
    "signReleaseBundle"
)

val requiredStoreReleaseGatePaths = setOf(
    ":app:verifyStoreRelease",
    ":app:verifyStoreReadiness",
    ":app:testReleaseUnitTest",
    ":app:lintRelease",
    ":app:verifyAssetRightsEvidence",
    ":app:verifyDataSafetyEvidence",
    ":app:verifyPhysicalSamsungEvidence",
    ":app:verifyThirdPartyNotices",
    ":app:verifyReleaseResourceSize",
    ":app:archiveReleaseMapping",
    ":app:verifyRelease16kPageSize",
    ":app:verifyReleaseAppSetIdDisabled",
    ":app:verifyStoreScreenshotsAgainstQaApk",
    ":verifyReleaseSecurityEvidence",
    ":verifyReleaseOsvScanEvidence",
    ":verifyReleaseProvenance"
)

val requiredReleaseBundlePostBuildGatePaths = setOf(
    ":app:verifyReleaseBundleWithBundletool",
    ":app:generateReleaseArtifactEvidence"
)

fun mandatoryStoreReleaseGatePaths(taskPaths: Set<String>): Set<String> {
    return requiredStoreReleaseGatePaths + if (":app:bundleRelease" in taskPaths) {
        requiredReleaseBundlePostBuildGatePaths
    } else {
        emptySet()
    }
}

fun missingStoreReleaseGatePaths(taskPaths: Set<String>): Set<String> {
    return mandatoryStoreReleaseGatePaths(taskPaths) - taskPaths
}

val verifyStoreReleaseGraphValidatorContract = tasks.register(
    "verifyStoreReleaseGraphValidatorContract"
) {
    group = "verification"
    description = "Proves that excluding any mandatory release gate is detected."
    outputs.upToDateWhen { false }
    doLast {
        if (missingStoreReleaseGatePaths(requiredStoreReleaseGatePaths).isNotEmpty()) {
            throw GradleException("Complete release graph was rejected by its validator contract.")
        }
        requiredStoreReleaseGatePaths.forEach { excluded ->
            val missing = missingStoreReleaseGatePaths(requiredStoreReleaseGatePaths - excluded)
            if (missing != setOf(excluded)) {
                throw GradleException("Release graph validator did not detect excluded gate $excluded.")
            }
        }
        val completeBundleGraph = requiredStoreReleaseGatePaths +
            requiredReleaseBundlePostBuildGatePaths +
            ":app:bundleRelease"
        if (missingStoreReleaseGatePaths(completeBundleGraph).isNotEmpty()) {
            throw GradleException("Complete release bundle graph was rejected by its validator contract.")
        }
        requiredReleaseBundlePostBuildGatePaths.forEach { excluded ->
            val missing = missingStoreReleaseGatePaths(completeBundleGraph - excluded)
            if (missing != setOf(excluded)) {
                throw GradleException("Release bundle graph validator did not detect excluded gate $excluded.")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(verifyStoreReleaseGraphValidatorContract)
}

val verifyStoreRelease = tasks.register("verifyStoreRelease") {
    group = "verification"
    description = "Runs all tests, lint, security, resource, and production-input gates for a store artifact."
    dependsOn(
        "testReleaseUnitTest",
        "lintRelease",
        verifyStoreReadiness,
        verifyAssetRightsEvidence,
        verifyDataSafetyEvidence,
        verifyPhysicalSamsungEvidence,
        verifyStoreScreenshotsAgainstQaApk,
        verifyReleaseAppSetIdDisabled,
        verifyThirdPartyNotices,
        verifyReleaseResourceSize,
        archiveReleaseMapping,
        rootProject.tasks.named("verifyReleaseSecurityEvidence"),
        rootProject.tasks.named("verifyReleaseOsvScanEvidence"),
        rootProject.tasks.named("verifyReleaseProvenance")
    )
}

tasks.configureEach {
    if (name in storeReleaseArtifactTaskNames) {
        dependsOn(verifyStoreRelease)
    }
    if (name == "bundleRelease") {
        finalizedBy(generateReleaseArtifactEvidence)
    }
}

gradle.taskGraph.whenReady {
    if (storeReleaseArtifactTaskNames.any { taskName -> hasTask(":app:$taskName") }) {
        val excludedTaskNames = gradle.startParameter.excludedTaskNames
        if (excludedTaskNames.isNotEmpty()) {
            throw GradleException(
                "Release artifact graphs forbid -x task exclusions: " +
                    excludedTaskNames.sorted().joinToString()
            )
        }
        val taskPaths = allTasks.mapTo(mutableSetOf()) { task -> task.path }
        val missingGates = missingStoreReleaseGatePaths(taskPaths)
        if (missingGates.isNotEmpty()) {
            throw GradleException(
                "Release artifact graph is missing mandatory gates; -x exclusions are forbidden: " +
                    missingGates.sorted().joinToString()
            )
        }
        failOnStoreReadinessIssues()
    }
}
