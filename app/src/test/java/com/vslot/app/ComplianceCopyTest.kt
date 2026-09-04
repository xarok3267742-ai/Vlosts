package com.vslot.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

class ComplianceCopyTest {
    private fun sourceText(path: String): String {
        return Path.of(path)
            .readText()
            .replace("setImageResourceIfChanged", "setImageResource")
    }

    @Test
    fun `runtime sources do not contain prohibited real money casino CTA copy`() {
        val sourceRoot = Path.of("src/main")
        val prohibitedPhrases = listOf(
            "win real money",
            "cash prize",
            "bet now",
            "earn dollars",
            "real casino bonus",
            "register to win",
            "cash out",
            "deposit now",
            "withdraw money"
        )

        val violations = Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .filter { it.name.endsWith(".xml") || it.name.endsWith(".kt") || it.name.endsWith(".json") }
                .flatMap { path ->
                    val text = path.readText().lowercase()
                    prohibitedPhrases
                        .filter { phrase -> text.contains(phrase) }
                        .map { phrase -> "$path: $phrase" }
                        .stream()
                }
                .toList()
        }

        assertTrue("Prohibited CTA copy found: $violations", violations.isEmpty())
    }

    @Test
    fun `runtime ui does not use platform alert dialogs`() {
        val sourceRoot = Path.of("src/main/java")
        val violations = Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .filter { it.name.endsWith(".kt") }
                .filter { path -> path.readText().contains("AlertDialog") }
                .map { it.toString() }
                .toList()
        }

        assertTrue("Platform AlertDialog usage found: $violations", violations.isEmpty())
    }

    @Test
    fun `launcher and Play listing use the production app icon`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val launcherBackground = drawableRoot.resolve("app_icon_art_v2.png")
        val launcherForeground = drawableRoot.resolve("app_icon_foreground_v2.png")
        val storeArtwork = Path.of("../docs/store/assets/v-slot-icon-512-v2.png")
        val launcherXml = Path.of("src/main/res/mipmap-anydpi/ic_launcher.xml").readText()
        val roundLauncherXml = Path.of("src/main/res/mipmap-anydpi/ic_launcher_round.xml").readText()
        val manifest = Path.of("src/main/AndroidManifest.xml").readText()

        assertTrue("Adaptive launcher background must be a 432px square", Files.exists(launcherBackground) && readBitmapSize(launcherBackground) == BitmapSize(432, 432))
        assertTrue("Adaptive launcher foreground must be a transparent 432px square", Files.exists(launcherForeground) && readBitmapSize(launcherForeground) == BitmapSize(432, 432) && readPngColorType(launcherForeground) == 6)
        assertTrue("Google Play icon must be a 512px square", Files.exists(storeArtwork) && readBitmapSize(storeArtwork) == BitmapSize(512, 512))
        assertTrue("Google Play icon must stay below the 1 MiB upload limit", Files.size(storeArtwork) <= 1_048_576L)
        assertEquals("Google Play icon must be 32-bit RGBA PNG", 6, readPngColorType(storeArtwork))
        assertTrue("Google Play icon must declare the standard sRGB color space", hasPngChunk(storeArtwork, "sRGB"))
        assertTrue("Primary adaptive icon must use separate production color layers and retain monochrome support", launcherXml.contains("@drawable/app_icon_art_v2") && launcherXml.contains("@drawable/app_icon_foreground_v2") && launcherXml.contains("@drawable/app_icon_monochrome"))
        assertTrue("Round adaptive icon must use the same production layers", roundLauncherXml.contains("@drawable/app_icon_art_v2") && roundLauncherXml.contains("@drawable/app_icon_foreground_v2") && roundLauncherXml.contains("@drawable/app_icon_monochrome"))
        assertTrue("Manifest must expose the adaptive launcher resources", manifest.contains("android:icon=\"@mipmap/ic_launcher\"") && manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test
    fun `Play feature graphic is upload ready`() {
        val featureGraphic = Path.of("../docs/store/assets/v-slot-feature-graphic-1024x500-v1.png")

        assertTrue("Google Play feature graphic must exist", Files.exists(featureGraphic))
        assertEquals("Google Play feature graphic must be exactly 1024x500", BitmapSize(1_024, 500), readBitmapSize(featureGraphic))
        assertEquals("Google Play feature graphic must be 24-bit RGB PNG without alpha", 2, readPngColorType(featureGraphic))
        assertTrue("Google Play feature graphic must declare the standard sRGB color space", hasPngChunk(featureGraphic, "sRGB"))
        assertTrue("Google Play feature graphic must contain detailed production artwork", Files.size(featureGraphic) >= 100_000L)
    }

    @Test
    fun `generated raster assets use the pinned open font toolchain`() {
        val toolsRoot = Path.of("../tools")
        val rasterManifest = Path.of("../docs/legal/RASTER_DERIVATION_MANIFEST.json").readText()
        val imagegenManifest = Path.of("../docs/legal/IMAGEGEN_DERIVATION_MANIFEST.json").readText()
        val assetInventory = Path.of("../docs/legal/ASSET_PROVENANCE_INVENTORY.json").readText()
        val appBuildScript = Path.of("build.gradle.kts").readText()
        val pythonSources = Files.walk(toolsRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.name.endsWith(".py") }
                .map { it.readText() }
                .toList()
        }
        val forbiddenFontFallbacks = listOf(
            "/System/Library/Fonts",
            "Arial",
            "Avenir",
            "Helvetica",
            "ImageFont.load_default"
        )
        val fallbackViolations = pythonSources.flatMap { source ->
            forbiddenFontFallbacks.filter(source::contains)
        }

        assertTrue("Asset generators must not depend on system fonts or Pillow's implicit fallback: $fallbackViolations", fallbackViolations.isEmpty())
        assertFalse("The conflicting third-party-notices producer must stay removed", Files.exists(toolsRoot.resolve("generate_third_party_notices_label.py")))
        assertTrue("Raster manifest must pin the exact Noto Sans and rendering toolchain", listOf("389b770410cc0b7c21c85673bfa2077420fe7f65", "bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d", "\"pillow\": \"11.3.0\"", "\"freetype\": \"2.13.3\"", "\"libwebp\": \"1.5.0\"", "\"mode\": \"byte_exact\"").all(rasterManifest::contains))
        assertEquals("Raster manifest must cover all proven byte-exact outputs", 359, JSONObject(rasterManifest).getJSONArray("entries").length())
        assertTrue("Raster manifest must bind source-derived outputs to their checked-in drawable inputs", listOf("app/src/main/res/drawable-nodpi/payline_markers_overlay_active_1.webp", "app/src/main/res/drawable-nodpi/payline_win_10.webp", "app/src/main/res/drawable-nodpi/spin_button_violet_default.webp", "app/src/main/res/drawable-nodpi/spin_button_violet_free_spins_default.png", "app/src/main/res/drawable-nodpi/slot_machine_frame_violet.webp", "app/src/main/res/drawable-nodpi/reel_cell_backdrop.webp", "app/src/main/res/drawable-nodpi/label_bet.webp", "app/src/main/res/drawable-nodpi/label_last_win.webp", "app/src/main/res/drawable-nodpi/vf_symbol_v_wild.webp", "app/src/main/res/drawable-nodpi/vf_symbol_v_wild_spin_blur.webp", "app/src/main/res/drawable-nodpi/paytable_row_panel.webp", "app/src/main/res/drawable-nodpi/paytable_row_panel_neon.webp", "app/src/main/res/drawable-nodpi/result_stage_lattice.webp", "app/src/main/res/drawable-nodpi/result_stage_lattice_roman.webp").all(rasterManifest::contains))
        assertTrue("Raster manifest must exclude absent base theme payline overlays", listOf("payline_markers_overlay_neon.webp", "payline_markers_overlay_pharaoh.webp", "payline_markers_overlay_ocean.webp").none(rasterManifest::contains))
        assertTrue("Imagegen manifest must pin canonical sources, producers, outputs, and the exact raster toolchain", listOf("\"status\": \"derivation_integrity_not_legal_clearance\"", "\"class\": \"derived_imagegen\"", "\"toolchain_gate\"", "\"mode\": \"byte_exact\"").all(imagegenManifest::contains))
        assertTrue("Media inventory must bind covered outputs through both derivation manifests", assetInventory.contains("docs/legal/RASTER_DERIVATION_MANIFEST.json") && assetInventory.contains("docs/legal/IMAGEGEN_DERIVATION_MANIFEST.json"))
        assertTrue("Store verification must validate both complete derivation contracts and fail-closed historical slicers", listOf("rasterDerivationManifestIssues", "RASTER_DERIVATION_MANIFEST.json", "exact 359 derivation entries required", "pinned Noto Sans identity mismatch", "canonical toolchain-gated producer required", "exact conditional build inputs required", "exact drawable source required", "pure procedural sources must be empty", "canonical producer/output counts mismatch", "imagegenDerivationManifestIssues", "IMAGEGEN_DERIVATION_MANIFEST.json", "expectedProducerCounts.values.sum()", "canonical imagegen producer/output counts mismatch", "noncanonicalImagegenSlicerIssues", "NONCANONICAL_HISTORICAL_SLICER").all(appBuildScript::contains))
    }

    @Test
    fun `Russian Play listing is complete and policy accurate`() {
        val listing = JSONObject(Path.of("../docs/store/store-listing-ru.json").readText())
        val manifest = Path.of("src/main/AndroidManifest.xml").readText()
        val localeConfig = Path.of("src/main/res/xml/locales_config.xml").readText()
        val title = listing.getString("title")
        val shortDescription = listing.getString("short_description")
        val fullDescription = listing.getString("full_description")
        val releaseNotes = listing.getString("release_notes")
        val featureGraphicAltText = listing.getString("feature_graphic_alt_text")
        val screenshotAltTexts = listing.getJSONObject("screenshot_alt_texts")
        val normalizedFullDescription = fullDescription.lowercase()

        assertTrue("Play title must be present and at most 30 characters", title.isNotBlank() && title.length <= 30)
        assertEquals("The initial public listing must match the supported Russian locale", "ru-RU", listing.getString("locale"))
        assertTrue("Android must declare the intentionally Russian-only locale scope", manifest.contains("android:localeConfig=\"@xml/locales_config\"") && localeConfig.contains("<locale android:name=\"ru\" />"))
        assertTrue("Play short description must be present and at most 80 characters", shortDescription.isNotBlank() && shortDescription.length <= 80)
        assertTrue("Play full description must be present and at most 4000 characters", fullDescription.isNotBlank() && fullDescription.length <= 4_000)
        assertTrue("Play release notes must be present and at most 500 characters", releaseNotes.isNotBlank() && releaseNotes.length <= 500)
        assertTrue("Feature graphic alt text must be present and at most 140 characters", featureGraphicAltText.isNotBlank() && featureGraphicAltText.length <= 140)
        val expectedScreenshotNames = setOf(
            "01-home.png",
            "02-violet-slot.png",
            "03-paytable.png",
            "04-settings.png",
            "05-free-spins.png"
        )
        assertEquals("Every prepared phone screenshot must have alt text", expectedScreenshotNames, screenshotAltTexts.keys().asSequence().toSet())
        expectedScreenshotNames.forEach { fileName ->
            val altText = screenshotAltTexts.getString(fileName)
            assertTrue("Screenshot alt text must be present and at most 140 characters: $fileName", altText.isNotBlank() && altText.length <= 140)
        }
        listOf(
            "симулятор слотов",
            "старше 18 лет",
            "виртуальные монеты",
            "игра на реальные деньги отсутствует",
            "покупок и платных ставок нет",
            "денежных и материальных призов нет"
        ).forEach { disclosure ->
            assertTrue("Play full description must disclose $disclosure", normalizedFullDescription.contains(disclosure))
        }
        assertTrue("Play listing must not promise a visible RTP value that the release UI does not expose", !normalizedFullDescription.contains("rtp"))
        assertTrue("Play listing must not promise purchases, withdrawals, or transferable value", normalizedFullDescription.contains("покупок и платных ставок нет") && normalizedFullDescription.contains("вывести, обменять или передать виртуальные монеты нельзя"))
        listOf("игровые автоматы", "игровые аппараты", "слоты", "казино").forEach { phrase ->
            val occurrences = normalizedFullDescription.windowed(phrase.length).count { it == phrase }
            assertTrue("Play full description must not stuff the repeated phrase: $phrase", occurrences <= 6)
        }
    }

    @Test
    fun `Play Store phone screenshots are complete and upload ready`() {
        val screenshotRoot = Path.of("../docs/store/assets/screenshots")
        val captureMetadata = JSONObject(screenshotRoot.resolve("capture-metadata.json").readText())
        val expectedScreenshots = listOf(
            "01-home.png",
            "02-violet-slot.png",
            "03-paytable.png",
            "04-settings.png",
            "05-free-spins.png"
        )

        expectedScreenshots.forEach { fileName ->
            val screenshot = screenshotRoot.resolve(fileName)
            assertTrue("Play Store screenshot must exist: $fileName", Files.exists(screenshot))
            assertEquals("Play Store screenshot must preserve the recommended 9x16 phone geometry: $fileName", BitmapSize(1_080, 1_920), readBitmapSize(screenshot))
            assertEquals("Play Store screenshot must be RGB PNG without an alpha channel: $fileName", 2, readPngColorType(screenshot))
            assertTrue("Play Store screenshot must contain a detailed app frame: $fileName", Files.size(screenshot) >= 100_000L)
        }
        assertEquals("Store captures must use the versioned metadata schema", 2, captureMetadata.getInt("schema_version"))
        assertTrue("Store metadata must not create a circular commit binding", !captureMetadata.has("source_commit"))
        assertEquals("Store captures must come from the minified QA package", "qa", captureMetadata.getString("build_variant"))
        assertEquals("Store captures must identify the QA application ID", "com.vslot.app.qa", captureMetadata.getString("package_name"))
        assertEquals("Store captures must identify the current versionCode", 2, captureMetadata.getInt("version_code"))
        assertTrue("Store captures must pin the exact QA APK", captureMetadata.getString("qa_apk_sha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue("Store captures must pin the reproducible QA APK payload", captureMetadata.getString("qa_apk_payload_sha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue("Store captures must pin the exact instrumentation APK", captureMetadata.getString("qa_test_apk_sha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue("Store captures must pin the reproducible instrumentation APK payload", captureMetadata.getString("qa_test_apk_payload_sha256").matches(Regex("[0-9a-f]{64}")))
        val deviceProfile = captureMetadata.getJSONObject("device")
        assertEquals("Store captures must use the target-SDK Android version", 36, deviceProfile.getInt("api_level"))
        assertEquals("Store captures must use the Russian listing locale", "ru-RU", deviceProfile.getString("locale"))
        assertEquals("Store captures must preserve the reviewed physical AVD size", "1080x2400", deviceProfile.getString("physical_size"))
        assertEquals("Store captures must preserve the reviewed physical AVD density", 420, deviceProfile.getInt("physical_density_dpi"))
        val captureProfile = captureMetadata.getJSONObject("capture")
        assertEquals(1_080, captureProfile.getInt("width"))
        assertEquals(1_920, captureProfile.getInt("height"))
        assertEquals(360, captureProfile.getInt("density_dpi"))
        assertEquals(1.0, captureProfile.getDouble("font_scale"), 0.0)
        val screenshotHashes = captureMetadata.getJSONObject("screenshot_sha256")
        assertEquals(expectedScreenshots.toSet(), screenshotHashes.keys().asSequence().toSet())
        expectedScreenshots.forEach { fileName ->
            assertEquals("Store capture metadata must pin $fileName", sha256(screenshotRoot.resolve(fileName)), screenshotHashes.getString(fileName))
        }
    }

    @Test
    fun `runtime layout drawable references are backed by bitmap image assets`() {
        val drawableRefs = runtimeLayoutDrawableRefs()
        val unresolved = mutableListOf<String>()
        val nonBitmapSelectors = mutableListOf<String>()

        drawableRefs.forEach { name ->
            val bitmap = bitmapPathForDrawable(name)
            val selector = selectorPathForDrawable(name)
            when {
                bitmap != null -> Unit
                selector != null -> {
                    val selectorText = selector.readText()
                    if (!selectorText.contains("<selector") || selectorText.contains("<shape") || selectorText.contains("<vector") || selectorText.contains("<layer-list")) {
                        nonBitmapSelectors += "$name -> $selector"
                    }
                    drawableRefs(selectorText).forEach { childName ->
                        if (bitmapPathForDrawable(childName) == null) {
                            unresolved += "$name selector state @$childName"
                        }
                    }
                }
                else -> unresolved += name
            }
        }

        assertTrue("Runtime layout drawable refs must resolve to bitmap assets or bitmap selectors: $unresolved", unresolved.isEmpty())
        assertTrue("Runtime layout selectors must not fall back to vector/layer-list drawables: $nonBitmapSelectors", nonBitmapSelectors.all { it.startsWith("vslot_") })
    }

    @Test
    fun `runtime command label and slot image assets are not placeholder quality`() {
        val runtimeBitmaps = runtimeLayoutDrawableRefs()
            .flatMap { name ->
                val selector = selectorPathForDrawable(name)
                if (selector != null) drawableRefs(selector.readText()) else listOf(name)
            }
            .toSet()
            .mapNotNull { name -> bitmapPathForDrawable(name)?.let { path -> name to path } }

        val importantAssets = runtimeBitmaps.filter { (name, _) ->
            IMPORTANT_RUNTIME_IMAGE_PREFIXES.any { prefix -> name.startsWith(prefix) } ||
                IMPORTANT_RUNTIME_IMAGE_MARKERS.any { marker -> name.contains(marker) }
        }
        val tinyFiles = importantAssets.mapNotNull { (name, path) ->
            val minimumBytes = when {
                name.startsWith("theme_ambient_overlay_") -> 250_000L
                name.startsWith("theme_spin_overlay_") -> 300_000L
                name.startsWith("theme_win_burst_") -> 400_000L
                name == "slot_level_session_panel" -> 30_000L
                name == "settings_safety_anchor" -> 40_000L
                name == "settings_push_status_console" -> 30_000L
                name == "settings_push_status_signal_pulse" -> 12_000L
                name == "privacy_loading_shield" -> 120_000L
                name == "privacy_loading_scan_rail" -> 30_000L
                name == "privacy_loading_sweep" -> 80_000L
                name.startsWith("label_") || name.startsWith("title_") -> 1_000L
                name.startsWith("btn_") || name.startsWith("spin_button") -> 4_000L
                name.startsWith("slot_card_") -> 20_000L
                name.contains("symbol_") -> 6_000L
                name.contains("modal_panel") -> 20_000L
                else -> 1_000L
            }
            val size = Files.size(path)
            "$name=$size bytes".takeIf { size < minimumBytes }
        }
        val undersizedPixels = importantAssets.mapNotNull { (name, path) ->
            val size = readBitmapSize(path)
            val minimum = when {
                name.startsWith("theme_ambient_overlay_") -> BitmapSize(width = 1_000, height = 720)
                name.startsWith("theme_spin_overlay_") -> BitmapSize(width = 1_200, height = 420)
                name.startsWith("theme_win_burst_") -> BitmapSize(width = 900, height = 680)
                name == "level_progress_panel" -> BitmapSize(width = 420, height = 118)
                name == "level_progress_milestones" -> BitmapSize(width = 360, height = 56)
                name == "level_progress_pulse" || name == "level_progress_cap" -> BitmapSize(width = 96, height = 96)
                name == "level_progress_fill" || name == "level_progress_track_glow" -> BitmapSize(width = 300, height = 32)
                name == "home_xp_readout_plate" -> BitmapSize(width = 240, height = 70)
                name == "slot_level_session_panel" -> BitmapSize(width = 720, height = 150)
                name == "settings_safety_anchor" -> BitmapSize(width = 1_180, height = 360)
                name == "settings_push_status_console" -> BitmapSize(width = 720, height = 150)
                name == "settings_push_status_signal_pulse" -> BitmapSize(width = 180, height = 180)
                name == "privacy_loading_shield" -> BitmapSize(width = 600, height = 600)
                name == "privacy_loading_scan_rail" -> BitmapSize(width = 580, height = 160)
                name == "privacy_loading_sweep" -> BitmapSize(width = 500, height = 740)
                name.startsWith("slot_card_") -> BitmapSize(width = 280, height = 180)
                name.startsWith("btn_") || name.startsWith("spin_button") -> BitmapSize(width = 96, height = 44)
                name.startsWith("label_") || name.startsWith("title_") -> BitmapSize(width = 72, height = 18)
                name.contains("symbol_") -> BitmapSize(width = 120, height = 120)
                name.contains("modal_panel") -> BitmapSize(width = 300, height = 180)
                else -> BitmapSize(width = 48, height = 24)
            }
            "$name=${size.width}x${size.height}".takeIf { size.width < minimum.width || size.height < minimum.height }
        }

        assertTrue("Important runtime image assets are unexpectedly tiny: $tinyFiles", tinyFiles.isEmpty())
        assertTrue("Important runtime image assets have placeholder pixel dimensions: $undersizedPixels", undersizedPixels.isEmpty())
    }

    @Test
    fun `slot spin strips swap to companion motion blur symbol images`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val baseSymbols = Files.list(drawableRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && Regex("(vf|rr|nn|pg|op)_symbol_.+\\.webp").matches(it.name) }
                .filter { !it.name.endsWith("_spin_blur.webp") }
                .toList()
        }
        val missingBlurSymbols = baseSymbols.map { symbol ->
            symbol.name.removeSuffix(".webp") + "_spin_blur.webp"
        }.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyBlurSymbols = baseSymbols.mapNotNull { symbol ->
            val blur = drawableRoot.resolve(symbol.name.removeSuffix(".webp") + "_spin_blur.webp")
            if (Files.exists(blur) && Files.size(blur) < 6_000L) "${blur.name}=${Files.size(blur)} bytes" else null
        }
        val wrongSizeBlurSymbols = baseSymbols.mapNotNull { symbol ->
            val blur = drawableRoot.resolve(symbol.name.removeSuffix(".webp") + "_spin_blur.webp")
            if (!Files.exists(blur)) {
                null
            } else {
                val size = readBitmapSize(blur)
                "${blur.name}=${size.width}x${size.height}".takeIf { size != BitmapSize(240, 240) }
            }
        }
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val symbolResources = Path.of("src/main/java/com/vslot/app/ui/slot/SlotSymbolResources.kt").readText()
        val generator = Path.of("../tools/generate_slot_symbol_spin_blur_assets.py").readText()
        val paytableGenerator = Path.of("../tools/generate_theme_paytable_assets.py").readText()
        val resultGenerator = Path.of("../tools/generate_theme_result_assets.py").readText()

        assertTrue("Expected all five slot themes to expose 40 base symbol images", baseSymbols.size == 40)
        assertTrue("Motion blur companion symbol assets missing: $missingBlurSymbols", missingBlurSymbols.isEmpty())
        assertTrue("Motion blur companion symbol assets are unexpectedly tiny: $tinyBlurSymbols", tinyBlurSymbols.isEmpty())
        assertTrue("Motion blur companion symbol assets must preserve 240x240 geometry: $wrongSizeBlurSymbols", wrongSizeBlurSymbols.isEmpty())
        assertTrue("SlotSymbolResources must expose dedicated spin images", symbolResources.contains("fun spinImage") && symbolResources.contains("_spin_blur"))
        assertTrue("SlotFragment must use blurred symbols while spinning and crisp symbols on stop", slotFragment.contains("SlotSymbolResources.spinImage") && slotFragment.contains("motionBlurred = true") && slotFragment.contains("motionBlurred = false"))
        assertTrue("Spin blur assets must be generated from symbol images with vertical motion blur and the pinned raster toolchain", generator.contains("GaussianBlur") && generator.contains("offsets =") && generator.contains("_spin_blur.webp") && generator.contains("verify_asset_toolchain()") && generator.contains("BYTE_EXACT_SPIN_BLUR_SYMBOLS") && generator.contains("path.stem in BYTE_EXACT_SPIN_BLUR_SYMBOLS"))
        val paytableAllowlist = paytableGenerator.substringAfter("BYTE_EXACT_SOURCE_TO_OUTPUT = {").substringBefore("}")
        val resultAllowlist = resultGenerator.substringAfter("BYTE_EXACT_SOURCE_TO_OUTPUT = {").substringBefore("}")
        assertTrue("Paytable theme producer must be toolchain-gated and write only nine byte-exact outputs", paytableGenerator.contains("verify_asset_toolchain()") && paytableGenerator.contains("for source_name, output_stem in BYTE_EXACT_SOURCE_TO_OUTPUT.items()") && !paytableAllowlist.contains("paytable_cabinet_lattice"))
        assertTrue("Result theme producer must be toolchain-gated and write only twelve byte-exact outputs", resultGenerator.contains("verify_asset_toolchain()") && resultGenerator.contains("for source_name, output_stem in BYTE_EXACT_SOURCE_TO_OUTPUT.items()") && !resultAllowlist.contains("result_modal_panel"))
    }

    @Test
    fun `global command buttons render from polished image selectors`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val selectorsRoot = Path.of("src/main/res/drawable")
        val polishedButtonAssets = mapOf(
            "btn_play_default.webp" to 14_000L,
            "btn_play_pressed.webp" to 10_000L,
            "btn_play_disabled.webp" to 10_000L,
            "btn_back_default.webp" to 9_000L,
            "btn_back_pressed.webp" to 8_000L,
            "btn_settings_default.webp" to 10_000L,
            "btn_settings_pressed.webp" to 8_000L
        )
        val missing = polishedButtonAssets.keys.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = polishedButtonAssets.filter { (asset, minimumSize) ->
            Files.exists(drawableRoot.resolve(asset)) && Files.size(drawableRoot.resolve(asset)) < minimumSize
        }.keys
        val playSelector = selectorsRoot.resolve("btn_play_selector.xml").readText()
        val backSelector = selectorsRoot.resolve("btn_back_selector.xml").readText()
        val settingsSelector = selectorsRoot.resolve("btn_settings_selector.xml").readText()
        val layouts = listOf(
            "fragment_home.xml",
            "fragment_disclaimer.xml",
            "fragment_settings.xml",
            "fragment_slot.xml",
            "fragment_privacy.xml",
            "dialog_bonus.xml",
            "dialog_low_coins.xml",
            "dialog_push_permission.xml"
        ).associateWith { Path.of("src/main/res/layout/$it").readText() }

        assertTrue("Missing global command button image assets: $missing", missing.isEmpty())
        assertTrue("Global command button image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Play selector must use default, pressed, and disabled image assets", playSelector.contains("@drawable/btn_play_default") && playSelector.contains("@drawable/btn_play_pressed") && playSelector.contains("@drawable/btn_play_disabled"))
        assertTrue("Back selector must use default and pressed image assets", backSelector.contains("@drawable/btn_back_default") && backSelector.contains("@drawable/btn_back_pressed"))
        assertTrue("Settings selector must use default and pressed image assets", settingsSelector.contains("@drawable/btn_settings_default") && settingsSelector.contains("@drawable/btn_settings_pressed"))
        assertTrue("Home settings action must render from image selector", layouts.getValue("fragment_home.xml").contains("@drawable/btn_settings_selector"))
        assertTrue("Slot back action must render from image selector", layouts.getValue("fragment_slot.xml").contains("@drawable/btn_back_selector"))
        assertTrue("Privacy back action must render from image selector", layouts.getValue("fragment_privacy.xml").contains("@drawable/btn_back_selector"))
        assertTrue("Disclaimer continue action must render from image selector", layouts.getValue("fragment_disclaimer.xml").contains("@drawable/btn_play_selector"))
        assertTrue("Daily bonus claim action must render from dedicated bonus image selector", layouts.getValue("dialog_bonus.xml").contains("@drawable/btn_bonus_claim_selector"))
        assertTrue("Push allow action must render from dedicated image selector", layouts.getValue("dialog_push_permission.xml").contains("@drawable/btn_push_allow_selector"))
    }

    @Test
    fun `slot bottom controls render from polished image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val polishedControlAssets = mapOf(
            "btn_bet_minus.webp" to 20_000L,
            "btn_bet_minus_pressed.webp" to 10_000L,
            "btn_bet_minus_disabled.webp" to 8_000L,
            "btn_bet_minus_roman.webp" to 8_000L,
            "btn_bet_minus_roman_pressed.webp" to 8_000L,
            "btn_bet_minus_roman_disabled.webp" to 8_000L,
            "btn_bet_plus.webp" to 20_000L,
            "btn_bet_plus_pressed.webp" to 10_000L,
            "btn_bet_plus_disabled.webp" to 8_000L,
            "btn_bet_plus_roman.webp" to 8_000L,
            "btn_bet_plus_roman_pressed.webp" to 8_000L,
            "btn_bet_plus_roman_disabled.webp" to 8_000L,
            "btn_max_lines_default.webp" to 10_000L,
            "btn_max_lines_pressed.webp" to 10_000L,
            "btn_max_lines_disabled.webp" to 14_000L,
            "btn_max_lines_roman_default.webp" to 10_000L,
            "btn_max_lines_roman_pressed.webp" to 10_000L,
            "btn_max_lines_roman_disabled.webp" to 10_000L,
            "btn_autospin_roman_default.webp" to 10_000L,
            "btn_autospin_roman_pressed.webp" to 10_000L,
            "btn_autospin_roman_active.webp" to 10_000L,
            "btn_autospin_roman_active_pressed.webp" to 10_000L,
            "btn_autospin_roman_disabled.webp" to 10_000L,
            "paytable_button.webp" to 20_000L,
            "paytable_button_roman.webp" to 20_000L,
            "auto_spin_active_halo_roman.webp" to 10_000L,
            "label_paytable_button.webp" to 8_000L,
            "label_paytable_button_roman.webp" to 8_000L,
            "bet_panel.webp" to 70_000L,
            "bet_panel_roman.webp" to 50_000L,
            "slot_control_meter_glow_roman.webp" to 60_000L,
            "active_lines_badge_roman.webp" to 12_000L,
            "free_spins_badge_roman.webp" to 20_000L,
            "spin_impact_flash.webp" to 20_000L,
            "spin_impact_flash_roman.webp" to 20_000L,
            "spin_impact_flash_neon.webp" to 20_000L,
            "spin_impact_flash_pharaoh.webp" to 20_000L,
            "spin_impact_flash_ocean.webp" to 20_000L,
            "slam_stop_cue.webp" to 45_000L,
            "slam_stop_cue_violet.webp" to 45_000L,
            "slam_stop_cue_roman.webp" to 45_000L,
            "slam_stop_cue_neon.webp" to 45_000L,
            "slam_stop_cue_pharaoh.webp" to 45_000L,
            "slam_stop_cue_ocean.webp" to 45_000L,
            "label_bet_roman.webp" to 7_000L,
            "label_lines_roman.webp" to 6_000L,
            "label_total_bet_roman.webp" to 14_000L,
            "label_last_win_roman.webp" to 13_000L,
            "slot_control_console_backplane_violet.webp" to 72_000L,
            "slot_control_console_backplane_roman.webp" to 72_000L
        )
        val missing = polishedControlAssets.keys.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = polishedControlAssets.filter { (asset, minimumSize) ->
            Files.exists(drawableRoot.resolve(asset)) && Files.size(drawableRoot.resolve(asset)) < minimumSize
        }.keys
        val minusSelector = Path.of("src/main/res/drawable/btn_bet_minus_selector.xml").readText()
        val plusSelector = Path.of("src/main/res/drawable/btn_bet_plus_selector.xml").readText()
        val romanMinusSelector = Path.of("src/main/res/drawable/btn_bet_minus_roman_selector.xml").readText()
        val romanPlusSelector = Path.of("src/main/res/drawable/btn_bet_plus_roman_selector.xml").readText()
        val maxLinesSelector = Path.of("src/main/res/drawable/btn_max_lines_selector.xml").readText()
        val romanMaxLinesSelector = Path.of("src/main/res/drawable/btn_max_lines_roman_selector.xml").readText()
        val romanAutoSpinSelector = Path.of("src/main/res/drawable/btn_autospin_roman_selector.xml").readText()
        val romanAutoSpinActiveSelector = Path.of("src/main/res/drawable/btn_autospin_roman_active_selector.xml").readText()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val resultLayout = Path.of("src/main/res/layout/dialog_result.xml").readText()
        val resultLandscapeLayout = Path.of("src/main/res/layout-land/dialog_result.xml").readText()
        val resultDialog = sourceText("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt")
        val slotViewModel = Path.of("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt").readText()

        assertTrue("Missing polished slot control image assets: $missing", missing.isEmpty())
        assertTrue("Slot control image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Slot screen must extend to the physical bottom edge while controls handle the live gesture inset", slotLayout.contains("android:paddingBottom=\"0dp\"") && slotFragment.contains("portraitConsoleBaseHeightDp().dp() + gestureInsets.bottom") && slotFragment.contains("bottom = gestureInsets.bottom"))
        assertTrue("Slot control console must render a unified image backplane", slotLayout.contains("@+id/slotControlConsoleBackplane") && slotLayout.contains("@drawable/slot_control_console_backplane_violet"))
        assertTrue("Slot control console must keep every field visible on 360dp-class phones with a flexible command dock", slotLayout.contains("@+id/slotControlConsole") && slotLayout.contains("android:layout_height=\"270dp\"") && slotFragment.contains("COMPACT_PORTRAIT_MAX_WIDTH_DP = 360") && slotFragment.contains("COMPACT_PORTRAIT_CONSOLE_BASE_HEIGHT_DP = 240") && slotLayout.contains("@+id/slotSpinDeck") && slotLayout.contains("android:layout_weight=\"1\"") && slotLayout.contains("android:minHeight=\"90dp\"") && slotLayout.contains("app:layout_constraintWidth_max=\"206dp\"") && slotLayout.contains("android:layout_width=\"54dp\""))
        assertTrue("Slot control console backplane must stay decorative", slotLayout.contains("@+id/slotControlConsoleBackplane") && slotLayout.split("@+id/slotControlConsoleBackplane", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot control console backplane must sit below meters and spin controls", slotLayout.indexOf("@+id/slotControlConsoleBackplane") < slotLayout.indexOf("@+id/betPanelMeterGlow") && slotLayout.indexOf("@+id/slotControlConsoleBackplane") < slotLayout.indexOf("@+id/spinDeckGlow") && slotLayout.indexOf("@+id/slotControlConsoleBackplane") < slotLayout.indexOf("android:id=\"@+id/spinButton\""))
        assertTrue("Slot control console backplane must switch by slot theme", slotFragment.contains("binding.slotControlConsoleBackplane.setImageResource") && slotFragment.contains("R.drawable.slot_control_console_backplane_roman") && slotFragment.contains("R.drawable.slot_control_console_backplane_violet"))
        assertTrue("Bet minus control must render directly from an image selector", slotLayout.contains("<ImageButton\n                                android:id=\"@+id/betMinusButton\"") && slotLayout.contains("@drawable/btn_bet_minus_selector") && minusSelector.contains("@drawable/btn_bet_minus") && minusSelector.contains("@drawable/btn_bet_minus_pressed") && minusSelector.contains("@drawable/btn_bet_minus_disabled"))
        assertTrue("Bet plus control must render directly from an image selector", slotLayout.contains("<ImageButton\n                                android:id=\"@+id/betPlusButton\"") && slotLayout.contains("@drawable/btn_bet_plus_selector") && plusSelector.contains("@drawable/btn_bet_plus") && plusSelector.contains("@drawable/btn_bet_plus_pressed") && plusSelector.contains("@drawable/btn_bet_plus_disabled"))
        assertTrue("Roman bet controls must render from dedicated image selectors", romanMinusSelector.contains("@drawable/btn_bet_minus_roman") && romanMinusSelector.contains("@drawable/btn_bet_minus_roman_pressed") && romanMinusSelector.contains("@drawable/btn_bet_minus_roman_disabled") && romanPlusSelector.contains("@drawable/btn_bet_plus_roman") && romanPlusSelector.contains("@drawable/btn_bet_plus_roman_pressed") && romanPlusSelector.contains("@drawable/btn_bet_plus_roman_disabled"))
        assertTrue("Line controls must render directly from image selectors", slotLayout.contains("<ImageButton\n                                android:id=\"@+id/linesMinusButton\"") && slotLayout.contains("<ImageButton\n                                android:id=\"@+id/linesPlusButton\"") && slotLayout.contains("@drawable/btn_bet_minus_selector") && slotLayout.contains("@drawable/btn_bet_plus_selector"))
        assertTrue("Landscape line and bet plus controls must keep explicit measured 48dp tap targets inside the same constrained row as the minus controls", slotLandscapeLayout.contains("androidx.constraintlayout.widget.ConstraintLayout") && slotLandscapeLayout.substringAfter("@+id/betStepperGroup").substringBefore("@+id/linesStepperGroup").contains("@+id/betPlusButton") && slotLandscapeLayout.substringAfter("@+id/linesStepperGroup").substringBefore("@+id/freeSpinsStakeLockOverlay").contains("@+id/linesPlusButton") && slotLandscapeLayout.substringAfter("@+id/betPlusButton").substringBefore("@+id/linesStepperGroup").contains("android:layout_width=\"48dp\"") && slotLandscapeLayout.substringAfter("@+id/betPlusButton").substringBefore("@+id/linesStepperGroup").contains("app:layout_constraintEnd_toEndOf=\"parent\"") && slotLandscapeLayout.substringAfter("@+id/linesPlusButton").substringBefore("@+id/freeSpinsStakeLockOverlay").contains("android:layout_width=\"48dp\"") && slotLandscapeLayout.substringAfter("@+id/linesPlusButton").substringBefore("@+id/freeSpinsStakeLockOverlay").contains("app:layout_constraintEnd_toEndOf=\"parent\""))
        assertTrue("Roman bet and line controls must switch image selectors by theme", slotFragment.contains("R.drawable.btn_bet_minus_roman_selector") && slotFragment.contains("R.drawable.btn_bet_plus_roman_selector"))
        assertTrue("Bet and line steppers must split the full panel without wasting meter width and keep 48dp image tap targets", slotLayout.contains("@+id/betStepperGroup") && slotLayout.contains("@+id/linesStepperGroup") && slotLayout.contains("app:layout_constraintHorizontal_weight=\"1\"") && slotLayout.contains("app:layout_constraintEnd_toStartOf=\"@id/linesStepperGroup\"") && slotLayout.contains("app:layout_constraintStart_toEndOf=\"@id/betStepperGroup\"") && slotLayout.contains("android:layout_width=\"48dp\""))
        assertTrue("Stepper controls must disable at their real bet and line boundaries", slotFragment.contains("val selectedBetIndex = state.config.bets.indexOf(state.playerState.selectedBet).coerceAtLeast(0)") && slotFragment.contains("?: state.playerState.displayedLines(state.config)") && slotFragment.contains("val freeSpinModeActive = freeSpins > 0 || (state.isSpinning && state.isCurrentSpinFreeSpin)") && slotFragment.contains("val stakeControlsEnabled = controlsEnabled && !freeSpinModeActive") && slotFragment.contains("binding.betMinusButton.isEnabled = stakeControlsEnabled && selectedBetIndex > 0") && slotFragment.contains("binding.betPlusButton.isEnabled = stakeControlsEnabled && selectedBetIndex < state.config.bets.lastIndex") && slotFragment.contains("binding.linesMinusButton.isEnabled = stakeControlsEnabled && selectedLines > PlayerState.MIN_LINES") && slotFragment.contains("binding.linesPlusButton.isEnabled = stakeControlsEnabled && selectedLines < state.config.paylines"))
        assertTrue("Total bet must pulse the bitmap meter when bet or active lines change", slotFragment.contains("animateTotalBetChangeIfNeeded(totalBet)") && slotFragment.contains("totalBetPulseAnimator") && slotFragment.contains("TOTAL_BET_CHANGE_PULSE_DURATION_MS") && slotFragment.contains("binding.totalBetDigits") && slotFragment.contains("binding.lastWinPanelMeterGlow") && slotFragment.contains("lastPresentedTotalBet = null"))
        assertTrue("Max lines control must render from a dedicated image selector", slotLayout.contains("@+id/maxLinesButton") && slotLayout.contains("@+id/maxLinesButtonIcon") && slotLayout.contains("@drawable/btn_max_lines_selector") && maxLinesSelector.contains("@drawable/btn_max_lines_default") && maxLinesSelector.contains("@drawable/btn_max_lines_pressed") && maxLinesSelector.contains("@drawable/btn_max_lines_disabled"))
        assertTrue("Roman max lines control must render from dedicated image selector", romanMaxLinesSelector.contains("@drawable/btn_max_lines_roman_default") && romanMaxLinesSelector.contains("@drawable/btn_max_lines_roman_pressed") && romanMaxLinesSelector.contains("@drawable/btn_max_lines_roman_disabled"))
        assertTrue("Roman autospin controls must render from dedicated image selectors", romanAutoSpinSelector.contains("@drawable/btn_autospin_roman_default") && romanAutoSpinSelector.contains("@drawable/btn_autospin_roman_pressed") && romanAutoSpinSelector.contains("@drawable/btn_autospin_roman_disabled") && romanAutoSpinActiveSelector.contains("@drawable/btn_autospin_roman_active") && romanAutoSpinActiveSelector.contains("@drawable/btn_autospin_roman_active_pressed"))
        assertTrue("Roman slot must switch bottom control images by theme", slotFragment.contains("R.drawable.btn_autospin_roman_selector") && slotFragment.contains("R.drawable.btn_autospin_roman_active_selector") && slotFragment.contains("R.drawable.btn_max_lines_roman_selector") && slotFragment.contains("R.drawable.paytable_button_roman") && slotFragment.contains("R.drawable.label_paytable_button_roman") && slotFragment.contains("R.drawable.auto_spin_active_halo_roman"))
        assertTrue("Roman slot must switch lower HUD panels, rails, and label images by theme", slotFragment.contains("R.drawable.bet_panel_roman") && slotFragment.contains("R.drawable.slot_control_meter_glow_roman") && slotFragment.contains("R.drawable.active_lines_badge_roman") && slotFragment.contains("R.drawable.free_spins_badge_roman") && slotFragment.contains("R.drawable.label_bet_roman") && slotFragment.contains("R.drawable.label_lines_roman") && slotFragment.contains("R.drawable.label_total_bet_roman") && slotFragment.contains("R.drawable.label_last_win_roman") && slotFragment.contains("binding.betPanelImage.setImageResource") && slotFragment.contains("binding.freeSpinsRailImage.setImageResource"))
        assertTrue("Slot session level meter imagegen asset must be present, detailed, and wide enough for the cabinet HUD", Files.exists(drawableRoot.resolve("slot_level_session_panel.webp")) && Files.size(drawableRoot.resolve("slot_level_session_panel.webp")) > 30_000L && readBitmapSize(drawableRoot.resolve("slot_level_session_panel.webp")) == BitmapSize(760, 168))
        assertTrue("Slot session level meter must render as layered image UI in portrait and landscape", slotLayout.contains("@+id/slotLevelPanel") && slotLayout.contains("@drawable/slot_level_session_panel") && slotLayout.contains("@+id/slotLevelXpProgressFill") && slotLayout.contains("@drawable/level_progress_fill") && slotLayout.contains("@+id/slotLevelXpProgressCap") && slotLayout.contains("@drawable/level_progress_cap") && slotLayout.contains("@+id/slotLevelXpProgressPulse") && slotLandscapeLayout.contains("@+id/slotLevelPanel") && slotLandscapeLayout.contains("@drawable/slot_level_session_panel") && slotLandscapeLayout.contains("@+id/slotLevelXpProgressFill") && slotLandscapeLayout.contains("@drawable/level_progress_fill") && slotLandscapeLayout.contains("@+id/slotLevelXpProgressCap") && slotLandscapeLayout.contains("@drawable/level_progress_cap") && slotLandscapeLayout.contains("@+id/slotLevelXpProgressPulse"))
        assertTrue("Slot session level meter must stay above the reel window without using text widgets", slotLayout.indexOf("@+id/slotLevelPanel") > slotLayout.indexOf("@+id/slotMarqueeGlass") && slotLayout.indexOf("@+id/slotLevelPanel") < slotLayout.indexOf("@+id/reelCellBackdropLayer") && slotLandscapeLayout.indexOf("@+id/slotLevelPanel") > slotLandscapeLayout.indexOf("@+id/slotMarqueeGlass") && slotLandscapeLayout.indexOf("@+id/slotLevelPanel") < slotLandscapeLayout.indexOf("@+id/reelCellBackdropLayer") && !slotLayout.substringAfter("@+id/slotLevelPanel").substringBefore("@+id/reelCellBackdropLayer").contains("<TextView") && !slotLandscapeLayout.substringAfter("@+id/slotLevelPanel").substringBefore("@+id/reelCellBackdropLayer").contains("<TextView"))
        assertTrue("SlotFragment must bind session level meter from PlayerState and animate XP changes", slotFragment.contains("bindSlotLevelState(state.playerState)") && slotFragment.contains("binding.slotLevelDigits.setNumber(state.playerLevel)") && slotFragment.contains("binding.slotLevelXpProgressFill.scaleX = progress") && slotFragment.contains("binding.slotLevelXpProgressCap.translationX") && slotFragment.contains("binding.slotLevelXpProgressPulse.translationX") && slotFragment.contains("animateSlotLevelChangeIfNeeded(state.playerLevel, state.levelXp)") && slotFragment.contains("SLOT_LEVEL_CHANGE_PULSE_DURATION_MS"))
        assertTrue("Slot spin impact flash must render as a decorative non-interactive image layer in portrait and landscape", slotLayout.contains("@+id/spinButtonImpactFlash") && slotLayout.contains("@drawable/spin_impact_flash") && slotLandscapeLayout.contains("@+id/spinButtonImpactFlash") && slotLandscapeLayout.contains("@drawable/spin_impact_flash") && slotLayout.split("@+id/spinButtonImpactFlash", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/spinButtonImpactFlash", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLayout.split("@+id/spinButtonImpactFlash", limit = 2)[1].contains("android:clickable=\"false\"") && slotLayout.split("@+id/spinButtonImpactFlash", limit = 2)[1].contains("android:focusable=\"false\"") && slotLandscapeLayout.split("@+id/spinButtonImpactFlash", limit = 2)[1].contains("android:clickable=\"false\"") && slotLandscapeLayout.split("@+id/spinButtonImpactFlash", limit = 2)[1].contains("android:focusable=\"false\""))
        assertTrue("Slot spin impact flash must draw above the spin button in both orientations", slotLayout.indexOf("@+id/spinButtonImpactFlash") > slotLayout.indexOf("android:id=\"@+id/spinButton\"") && slotLandscapeLayout.indexOf("@+id/spinButtonImpactFlash") > slotLandscapeLayout.indexOf("android:id=\"@+id/spinButton\""))
        assertTrue("Slot slam-stop cue imagegen asset must be present, detailed, and 512px square", Files.exists(drawableRoot.resolve("slam_stop_cue.webp")) && Files.size(drawableRoot.resolve("slam_stop_cue.webp")) > 45_000L && readBitmapSize(drawableRoot.resolve("slam_stop_cue.webp")) == BitmapSize(512, 512))
        assertTrue("Themed slot slam-stop cue imagegen assets must be present, detailed, and 512px square", listOf("slam_stop_cue_violet.webp", "slam_stop_cue_roman.webp", "slam_stop_cue_neon.webp", "slam_stop_cue_pharaoh.webp", "slam_stop_cue_ocean.webp").all { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) > 45_000L && readBitmapSize(drawableRoot.resolve(it)) == BitmapSize(512, 512) })
        assertTrue("Themed slot slam-stop cues must retain fail-closed historical source and review evidence", Path.of("../tools/slice_imagegen_theme_slam_stop_cues.py").readText().let { it.contains("vslot_theme_slam_stop_cues_imagegen.png") && it.contains("NONCANONICAL_HISTORICAL_SLICER") } && Files.exists(Path.of("../qa/source/vslot_theme_slam_stop_cues_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/theme_slam_stop_cues_contact_sheet.png")) && Files.exists(Path.of("../qa/design/slam_stop_cue_visual_philosophy.md")))
        assertTrue("Slot slam-stop cue must render as a decorative non-interactive image layer in portrait and landscape", slotLayout.contains("@+id/slamStopCue") && slotLayout.contains("@drawable/slam_stop_cue") && slotLandscapeLayout.contains("@+id/slamStopCue") && slotLandscapeLayout.contains("@drawable/slam_stop_cue") && slotLayout.split("@+id/slamStopCue", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/slamStopCue", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLayout.split("@+id/slamStopCue", limit = 2)[1].contains("android:clickable=\"false\"") && slotLayout.split("@+id/slamStopCue", limit = 2)[1].contains("android:focusable=\"false\"") && slotLandscapeLayout.split("@+id/slamStopCue", limit = 2)[1].contains("android:clickable=\"false\"") && slotLandscapeLayout.split("@+id/slamStopCue", limit = 2)[1].contains("android:focusable=\"false\""))
        assertTrue("Slot slam-stop cue must draw above the spin button and below impact flash", slotLayout.indexOf("@+id/slamStopCue") > slotLayout.indexOf("android:id=\"@+id/spinButton\"") && slotLayout.indexOf("@+id/slamStopCue") < slotLayout.indexOf("@+id/spinButtonImpactFlash") && slotLandscapeLayout.indexOf("@+id/slamStopCue") > slotLandscapeLayout.indexOf("android:id=\"@+id/spinButton\"") && slotLandscapeLayout.indexOf("@+id/slamStopCue") < slotLandscapeLayout.indexOf("@+id/spinButtonImpactFlash"))
        assertTrue("SlotFragment must switch themed imagegen slam-stop cue assets", slotFragment.contains("slamStopCueDrawable(theme)") && slotFragment.contains("R.drawable.slam_stop_cue_violet") && slotFragment.contains("R.drawable.slam_stop_cue_roman") && slotFragment.contains("R.drawable.slam_stop_cue_neon") && slotFragment.contains("R.drawable.slam_stop_cue_pharaoh") && slotFragment.contains("R.drawable.slam_stop_cue_ocean") && slotFragment.contains("binding.slamStopCue.setImageResource(slamStopCueDrawable(theme))"))
        assertTrue("SlotFragment must animate imagegen slam-stop cue only while a manual slam remains available", slotFragment.contains("updateSlamStopCue(") && slotFragment.contains("!state.isSlamStopping") && slotFragment.contains("!state.isAutoSpinEnabled") && slotFragment.contains("startSlamStopCue()") && slotFragment.contains("stopSlamStopCue(immediate = true)") && slotFragment.contains("slamStopCueAnimator") && slotFragment.contains("SLAM_STOP_CUE_PULSE_DURATION_MS") && slotFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Slot spin button must animate themed image impact flash and haptic feedback on press", slotFragment.contains("spinImpactFlashDrawable") && slotFragment.contains("binding.spinButtonImpactFlash.setImageResource") && slotFragment.contains("R.drawable.spin_impact_flash_roman") && slotFragment.contains("R.drawable.spin_impact_flash_neon") && slotFragment.contains("R.drawable.spin_impact_flash_pharaoh") && slotFragment.contains("R.drawable.spin_impact_flash_ocean") && slotFragment.contains("animateSpinImpactFlash()") && slotFragment.contains("SPIN_IMPACT_FLASH_DURATION_MS") && slotFragment.contains("performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)") && slotFragment.contains("hideSpinImpactFlash(immediate = true)"))
        assertTrue("Max lines control must use the same DataStore-backed line state", slotFragment.contains("binding.maxLinesButton.setOnClickListener { viewModel.selectMaxLines() }") && slotFragment.contains("binding.maxLinesButton.isEnabled = stakeControlsEnabled && selectedLines < state.config.paylines") && slotViewModel.contains("fun selectMaxLines()") && slotViewModel.contains("playerRepository.updateSelectedLines(config.paylines)"))
        assertTrue("Stake controls must stay locked while current-slot free spins are active and rapid updates are serialized", slotViewModel.contains("stakeUpdateMutex") && slotViewModel.contains("runSerializedStakeUpdate") && slotViewModel.contains("if (state.hasFreeSpinsForSlot(config.id)) return"))
        assertTrue("Paytable control must render from image assets", slotLayout.contains("@+id/paytableButton") && slotLayout.contains("@drawable/paytable_button") && slotLayout.contains("@+id/paytableButtonLabel") && slotLayout.contains("@drawable/label_paytable_button"))
        assertTrue("Paytable control must use the full image dock as the tap target", slotLayout.contains("@+id/paytableButton") && slotLayout.substringAfter("android:id=\"@+id/paytableButton\"").substringBefore("</FrameLayout>").contains("android:clickable=\"true\"") && slotLayout.substringAfter("android:id=\"@+id/paytableButton\"").substringBefore("</FrameLayout>").contains("android:focusable=\"true\""))
        assertTrue("Paytable control image layers must follow dock press state", slotLayout.contains("@+id/paytableButtonIcon") && slotLayout.contains("android:duplicateParentState=\"true\"") && slotLayout.contains("@+id/paytableButtonLabel"))
        assertTrue("Paytable label dock must stay large enough and lifted from the bottom edge", slotLayout.substringAfter("@+id/paytableButtonLabel").substringBefore("/>").contains("android:layout_width=\"76dp\"") && slotLayout.substringAfter("@+id/paytableButtonLabel").substringBefore("/>").contains("android:layout_height=\"18dp\"") && slotLayout.substringAfter("@+id/paytableButtonLabel").substringBefore("/>").contains("android:layout_marginBottom=\"20dp\""))
        assertTrue("Landscape paytable label must stay readable inside the compact control dock", slotLandscapeLayout.substringAfter("@+id/paytableButton").substringBefore("@+id/spinButton").contains("android:layout_width=\"70dp\"") && slotLandscapeLayout.substringAfter("@+id/paytableButtonLabel").substringBefore("/>").contains("android:layout_width=\"66dp\"") && slotLandscapeLayout.substringAfter("@+id/paytableButtonLabel").substringBefore("/>").contains("android:layout_height=\"16dp\"") && slotLandscapeLayout.substringAfter("@+id/paytableButtonLabel").substringBefore("/>").contains("android:layout_marginBottom=\"4dp\""))
        assertTrue("Bet and last-win meters must share the polished image panel", slotLayout.split("@drawable/bet_panel").size >= 3)
        assertTrue("Slot bottom controls must not render labels through android:text", !slotLayout.contains("android:text=\"@string/bet\"") && !slotLayout.contains("android:text=\"@string/last_win\"") && !slotLayout.contains("android:text=\"@string/paytable\""))
    }

    @Test
    fun `new slot themes render from dedicated image chrome assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themedChromeStems = mapOf(
            "slot_machine_frame" to 70_000L,
            "slot_marquee_glass" to 30_000L,
            "slot_cabinet_lights" to 60_000L,
            "slot_cabinet_chase_lights" to 38_000L,
            "reel_depth_dividers" to 10_000L,
            "reel_window_depth_mask" to 35_000L,
            "free_spins_mode_overlay" to 38_000L,
            "slot_spin_deck_glow" to 50_000L,
            "spin_button_ready_glow" to 30_000L,
            "slot_paytable_dock_glow" to 12_000L,
            "slot_control_console_backplane" to 90_000L,
            "bet_panel" to 24_000L,
            "slot_control_meter_glow" to 45_000L,
            "active_lines_badge" to 7_000L,
            "free_spins_badge" to 10_000L,
            "reel_cell_backdrop" to 10_000L,
            "reel_stop_flash" to 110_000L,
            "reel_motion_streak" to 70_000L
        )
        val themeSuffixes = listOf("neon", "pharaoh", "ocean")
        val expectedAssets = themeSuffixes.flatMap { suffix ->
            themedChromeStems.map { (stem, minimumSize) -> "$stem-$suffix" to minimumSize }
        }.toMap()
        val missing = expectedAssets.keys
            .map { it.replace("-", "_") + ".webp" }
            .filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets
            .mapKeys { (asset, _) -> asset.replace("-", "_") + ".webp" }
            .filter { (asset, minimumSize) ->
                Files.exists(drawableRoot.resolve(asset)) && Files.size(drawableRoot.resolve(asset)) < minimumSize
            }
            .keys
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing new-theme slot chrome image assets: $missing", missing.isEmpty())
        assertTrue("New-theme slot chrome image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        themeSuffixes.forEach { suffix ->
            themedChromeStems.keys.forEach { stem ->
                assertTrue(
                    "$suffix slot chrome must be wired through SlotFragment for $stem",
                    slotFragment.contains("R.drawable.${stem}_$suffix")
                )
            }
        }
        assertTrue("Slot chrome must use centralized theme mapping", slotFragment.contains("private fun themedChromeDrawable") && slotFragment.contains("SlotTheme.Neon -> neon") && slotFragment.contains("SlotTheme.Pharaoh -> pharaoh") && slotFragment.contains("SlotTheme.Ocean -> ocean"))
        assertTrue("Runtime slot chrome setters must use theme-specific image helpers", slotFragment.contains("binding.slotMachineFrame.setImageResource(slotMachineFrameDrawable(theme))") && slotFragment.contains("binding.slotControlConsoleBackplane.setImageResource(slotControlConsoleBackplaneDrawable(theme))") && slotFragment.contains("binding.betPanelImage.setImageResource(betPanelDrawable(theme))") && slotFragment.contains("val reelCellBackdrop = reelCellBackdropDrawable(theme)"))
    }

    @Test
    fun `slot themes render unique imagegen ambient overlays with dedicated motion`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "theme_ambient_overlay_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 250_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 1_000 || size.height < 720
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val ambientAnimator = slotFragment
            .substringAfter("private fun updateThemeAmbientOverlay")
            .substringBefore("private fun stopThemeAmbientOverlay")

        assertTrue("Missing imagegen ambient overlay assets: $missing", missing.isEmpty())
        assertTrue("Imagegen ambient overlay assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen ambient overlay assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Ambient overlay assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must include a decorative ambient overlay ImageView", slotLayout.contains("@+id/slotThemeAmbientOverlay") && slotLayout.contains("@drawable/theme_ambient_overlay_violet") && slotLayout.split("@+id/slotThemeAmbientOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Landscape slot layout must include a decorative ambient overlay ImageView", slotLandscapeLayout.contains("@+id/slotThemeAmbientOverlay") && slotLandscapeLayout.contains("@drawable/theme_ambient_overlay_violet") && slotLandscapeLayout.split("@+id/slotThemeAmbientOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme ambient overlay", slotFragment.contains("R.drawable.theme_ambient_overlay_$theme"))
        }
        assertTrue("SlotFragment must update ambient overlay from the active slot theme", slotFragment.contains("binding.slotThemeAmbientOverlay.setImageResource(themeAmbientOverlayDrawable(theme))") && slotFragment.contains("updateThemeAmbientOverlay(theme, isSpinning = state.isSpinning, freeSpinsActive = freeSpinModeActive)"))
        assertTrue("Each theme must have a dedicated ambient motion profile", slotFragment.contains("private fun themeAmbientMotion(theme: SlotTheme)") && slotFragment.contains("SlotTheme.Violet -> ThemeAmbientMotion") && slotFragment.contains("SlotTheme.Roman -> ThemeAmbientMotion") && slotFragment.contains("SlotTheme.Neon -> ThemeAmbientMotion") && slotFragment.contains("SlotTheme.Pharaoh -> ThemeAmbientMotion") && slotFragment.contains("SlotTheme.Ocean -> ThemeAmbientMotion"))
        assertTrue("Theme ambient overlay must settle with finite image animation and stop with the Fragment lifecycle", ambientAnimator.contains("themeAmbientAnimator = AnimatorSet()") && !ambientAnimator.contains("repeatCount = ValueAnimator.INFINITE") && slotFragment.contains("stopThemeAmbientOverlay()"))
    }

    @Test
    fun `slot themes render unique imagegen spin overlays with dedicated motion`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "theme_spin_overlay_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 300_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 1_200 || size.height < 420
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen spin overlay assets: $missing", missing.isEmpty())
        assertTrue("Imagegen spin overlay assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen spin overlay assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Spin overlay assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must include a decorative theme spin overlay ImageView", slotLayout.contains("@+id/themeSpinOverlay") && slotLayout.contains("@drawable/theme_spin_overlay_violet") && slotLayout.split("@+id/themeSpinOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Landscape slot layout must include a decorative theme spin overlay ImageView", slotLandscapeLayout.contains("@+id/themeSpinOverlay") && slotLandscapeLayout.contains("@drawable/theme_spin_overlay_violet") && slotLandscapeLayout.split("@+id/themeSpinOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Theme spin overlay must sit above ambient art and below free-spins/HUD layers", slotLayout.indexOf("@+id/slotThemeAmbientOverlay") < slotLayout.indexOf("@+id/themeSpinOverlay") && slotLayout.indexOf("@+id/themeSpinOverlay") < slotLayout.indexOf("@+id/freeSpinsModeOverlay") && slotLandscapeLayout.indexOf("@+id/slotThemeAmbientOverlay") < slotLandscapeLayout.indexOf("@+id/themeSpinOverlay") && slotLandscapeLayout.indexOf("@+id/themeSpinOverlay") < slotLandscapeLayout.indexOf("@+id/freeSpinsModeOverlay"))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme spin overlay", slotFragment.contains("R.drawable.theme_spin_overlay_$theme"))
        }
        assertTrue("SlotFragment must bind the active theme spin overlay before supported rich reel animation", slotFragment.contains("binding.themeSpinOverlay.setImageResource(themeSpinOverlayDrawable(theme))") && slotFragment.contains("shouldUseRichSpinEffects()") && slotFragment.contains("startThemeSpinOverlay(config.theme)") && slotFragment.contains("stopThemeSpinOverlay()"))
        assertTrue("Each slot theme must have a dedicated spin overlay motion profile", slotFragment.contains("private fun themeSpinOverlayMotion(theme: SlotTheme)") && slotFragment.contains("SlotTheme.Violet -> ThemeSpinOverlayMotion") && slotFragment.contains("SlotTheme.Roman -> ThemeSpinOverlayMotion") && slotFragment.contains("SlotTheme.Neon -> ThemeSpinOverlayMotion") && slotFragment.contains("SlotTheme.Pharaoh -> ThemeSpinOverlayMotion") && slotFragment.contains("SlotTheme.Ocean -> ThemeSpinOverlayMotion"))
        assertTrue("Theme spin overlay must use a bounded acceleration flourish and respect disabled system animators", slotFragment.contains("themeSpinOverlayAnimator = animation") && slotFragment.contains("THEME_SPIN_INTRO_DURATION_MS") && slotFragment.contains("overlay.visibility = View.INVISIBLE") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Theme spin overlay must reset with the Fragment lifecycle", slotFragment.contains("stopThemeSpinOverlay(immediate = true)") && slotFragment.contains("themeSpinOverlayAnimator?.cancel()"))
    }

    @Test
    fun `slot themes render unique imagegen reel stop flashes`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "reel_stop_flash_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 110_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 300 || size.height < 1_000
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen reel stop flash assets: $missing", missing.isEmpty())
        assertTrue("Imagegen reel stop flash assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen reel stop flash assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Reel stop flash assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme reel stop flash", slotFragment.contains("R.drawable.reel_stop_flash_$theme"))
        }
        assertTrue("SlotFragment must lazily set and release reel stop flashes from the active slot theme", slotFragment.contains("private fun reelStopFlashDrawable(theme: SlotTheme)") && slotFragment.contains("flash.setImageResource(reelStopFlashDrawable(viewModel.uiState.value.config.theme))") && slotFragment.contains("reelStopFlashViews.forEach(ImageView::clearBoundImageResource)"))
        val stopFlashSetup = slotFragment.substringAfter("private fun setupReelStopFlashLayer()")
            .substringBefore("private fun animateReelWindowDepthMask()")
        assertTrue("Hidden reel stop flashes must wait for the active theme instead of decoding a default bitmap", !stopFlashSetup.contains("setImageResource("))
    }

    @Test
    fun `slot themes render unique imagegen symbol win and bonus scatter halos`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedWinAssets = themes.map { "symbol_win_halo_$it.webp" }
        val expectedBonusAssets = themes.map { "symbol_bonus_scatter_halo_$it.webp" }
        val expectedAssets = expectedWinAssets + expectedBonusAssets
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyWin = expectedWinAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 130_000L
        }
        val tinyBonus = expectedBonusAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 170_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 620 || size.height < 620
        }
        val duplicateWinHashes = expectedWinAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val duplicateBonusHashes = expectedBonusAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen symbol halo assets: $missing", missing.isEmpty())
        assertTrue("Imagegen win halo assets are unexpectedly tiny: $tinyWin", tinyWin.isEmpty())
        assertTrue("Imagegen bonus scatter halo assets are unexpectedly tiny: $tinyBonus", tinyBonus.isEmpty())
        assertTrue("Imagegen symbol halo assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Win halo assets must be visually unique files: $duplicateWinHashes", duplicateWinHashes.isEmpty())
        assertTrue("Bonus scatter halo assets must be visually unique files: $duplicateBonusHashes", duplicateBonusHashes.isEmpty())
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme win halo", slotFragment.contains("R.drawable.symbol_win_halo_$theme"))
            assertTrue("SlotFragment must wire $theme bonus scatter halo", slotFragment.contains("R.drawable.symbol_bonus_scatter_halo_$theme"))
        }
        assertTrue("SlotFragment must lazily set and release symbol win halos from the active slot theme", slotFragment.contains("private fun symbolWinHaloDrawable(theme: SlotTheme)") && slotFragment.contains("val haloDrawable = symbolWinHaloDrawable(viewModel.uiState.value.config.theme)") && slotFragment.contains("symbolWinHalos.forEach { it.setImageResource(haloDrawable) }") && slotFragment.contains("halo.clearBoundImageResource()"))
        assertTrue("SlotFragment must lazily set and release bonus scatter halos from the active slot theme", slotFragment.contains("private fun bonusScatterHaloDrawable(theme: SlotTheme)") && slotFragment.contains("val haloDrawable = bonusScatterHaloDrawable(viewModel.uiState.value.config.theme)") && slotFragment.contains("bonusScatterHalos.forEach { it.setImageResource(haloDrawable) }") && slotFragment.contains("halo.clearBoundImageResource()"))
        val winHaloSetup = slotFragment.substringAfter("private fun setupSymbolWinHaloLayer()")
            .substringBefore("private fun setupBonusScatterHaloLayer()")
        val bonusHaloSetup = slotFragment.substringAfter("private fun setupBonusScatterHaloLayer()")
            .substringBefore("private fun setupReelCellBackdropLayer()")
        assertTrue("Hidden symbol halos must wait for the active theme instead of decoding defaults", !winHaloSetup.contains("setImageResource(") && !bonusHaloSetup.contains("setImageResource("))
    }

    @Test
    fun `slot themes render unique imagegen reel glass overlays`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "reel_glass_overlay_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 230_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 940 || size.height < 1_000
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen reel glass overlay assets: $missing", missing.isEmpty())
        assertTrue("Imagegen reel glass overlay assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen reel glass overlay assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Reel glass overlay assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must default reel glass to the Violet imagegen asset", slotLayout.contains("@+id/reelGlassOverlay") && slotLayout.contains("@drawable/reel_glass_overlay_violet"))
        assertTrue("Landscape slot layout must default reel glass to the Violet imagegen asset", slotLandscapeLayout.contains("@+id/reelGlassOverlay") && slotLandscapeLayout.contains("@drawable/reel_glass_overlay_violet"))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme reel glass overlay", slotFragment.contains("R.drawable.reel_glass_overlay_$theme"))
        }
        assertTrue("SlotFragment must set reel glass from the active slot theme", slotFragment.contains("private fun reelGlassOverlayDrawable(theme: SlotTheme)") && slotFragment.contains("binding.reelGlassOverlay.setImageResource(reelGlassOverlayDrawable(theme))"))
    }

    @Test
    fun `slot themes render unique imagegen reel spin blur overlays`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "reel_spin_blur_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 180_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 940 || size.height < 940
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen reel spin blur assets: $missing", missing.isEmpty())
        assertTrue("Imagegen reel spin blur assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen reel spin blur assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Reel spin blur assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must default spin blur to the Violet imagegen asset", slotLayout.contains("@+id/spinBlurOverlay") && slotLayout.contains("@drawable/reel_spin_blur_violet"))
        assertTrue("Landscape slot layout must default spin blur to the Violet imagegen asset", slotLandscapeLayout.contains("@+id/spinBlurOverlay") && slotLandscapeLayout.contains("@drawable/reel_spin_blur_violet"))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme reel spin blur", slotFragment.contains("R.drawable.reel_spin_blur_$theme"))
        }
        assertTrue("SlotFragment must set spin blur from the active slot theme", slotFragment.contains("private fun reelSpinBlurDrawable(theme: SlotTheme)") && slotFragment.contains("binding.spinBlurOverlay.setImageResource(reelSpinBlurDrawable(theme))"))
    }

    @Test
    fun `slot themes render unique imagegen spin energy rims`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "reel_spin_energy_rim_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 220_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 940 || size.height < 1_000
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen spin energy rim assets: $missing", missing.isEmpty())
        assertTrue("Imagegen spin energy rim assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen spin energy rim assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Spin energy rim assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must default spin energy to the Violet imagegen asset", slotLayout.contains("@+id/spinEnergyOverlay") && slotLayout.contains("@drawable/reel_spin_energy_rim_violet"))
        assertTrue("Landscape slot layout must default spin energy to the Violet imagegen asset", slotLandscapeLayout.contains("@+id/spinEnergyOverlay") && slotLandscapeLayout.contains("@drawable/reel_spin_energy_rim_violet"))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme spin energy rim", slotFragment.contains("R.drawable.reel_spin_energy_rim_$theme"))
        }
        assertTrue("SlotFragment must lazily set spin energy from the active slot theme", slotFragment.contains("private fun spinEnergyOverlayDrawable(theme: SlotTheme)") && slotFragment.contains("binding.spinEnergyOverlay.setImageResource(spinEnergyOverlayDrawable(theme))") && slotFragment.indexOf("binding.spinEnergyOverlay.setImageResource(spinEnergyOverlayDrawable(theme))") > slotFragment.indexOf("private fun startSpinEnergyOverlay()"))
    }

    @Test
    fun `slot themes render unique imagegen win glow sprites`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "win_glow_sprite_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 230_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 940 || size.height < 940
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen win glow assets: $missing", missing.isEmpty())
        assertTrue("Imagegen win glow assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen win glow assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Win glow assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must default win glow to the Violet imagegen asset", slotLayout.contains("@+id/winGlowOverlay") && slotLayout.contains("@drawable/win_glow_sprite_violet"))
        assertTrue("Landscape slot layout must default win glow to the Violet imagegen asset", slotLandscapeLayout.contains("@+id/winGlowOverlay") && slotLandscapeLayout.contains("@drawable/win_glow_sprite_violet"))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme win glow", slotFragment.contains("R.drawable.win_glow_sprite_$theme"))
        }
        assertTrue("SlotFragment must set win glow from the active slot theme", slotFragment.contains("private fun winGlowSpriteDrawable(theme: SlotTheme)") && slotFragment.contains("binding.winGlowOverlay.setImageResource(winGlowSpriteDrawable(theme))"))
    }

    @Test
    fun `slot themes render unique imagegen result banners`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val bigWinAssets = themes.map { "slot_big_win_banner_$it.webp" }
        val bonusAssets = themes.map { "slot_bonus_free_spins_banner_$it.webp" }
        val expectedAssets = bigWinAssets + bonusAssets
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 42_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            when {
                asset.startsWith("slot_big_win_banner_") -> size.width < 1_000 || size.height < 300
                else -> size.width < 1_000 || size.height < 260
            }
        }
        val duplicateBigHashes = bigWinAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val duplicateBonusHashes = bonusAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen result banner assets: $missing", missing.isEmpty())
        assertTrue("Imagegen result banner assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen result banner assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Big win result banners must be visually unique files: $duplicateBigHashes", duplicateBigHashes.isEmpty())
        assertTrue("Bonus result banners must be visually unique files: $duplicateBonusHashes", duplicateBonusHashes.isEmpty())
        assertTrue("Portrait slot layout must default result banner to the Violet imagegen asset", slotLayout.contains("@+id/bigWinBannerOverlay") && slotLayout.contains("@drawable/slot_big_win_banner_violet"))
        assertTrue("Landscape slot layout must default result banner to the Violet imagegen asset", slotLandscapeLayout.contains("@+id/bigWinBannerOverlay") && slotLandscapeLayout.contains("@drawable/slot_big_win_banner_violet"))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme big win banner", slotFragment.contains("R.drawable.slot_big_win_banner_$theme"))
            assertTrue("SlotFragment must wire $theme bonus banner", slotFragment.contains("R.drawable.slot_bonus_free_spins_banner_$theme"))
        }
        assertTrue("SlotFragment must lazily set themed result banner art selected for the result type", slotFragment.contains("private fun bigWinBannerDrawable(theme: SlotTheme)") && slotFragment.contains("private fun bonusFreeSpinsBannerDrawable(theme: SlotTheme)") && slotFragment.contains("binding.bigWinBannerOverlay.setImageResource(imageRes)"))
        assertTrue("SlotFragment must choose the themed bonus banner only for bonus results", slotFragment.contains("result.resultType == ResultType.Bonus") && slotFragment.contains("bonusFreeSpinsBannerDrawable(theme)") && slotFragment.contains("bigWinBannerDrawable(theme)") && slotFragment.contains("R.string.slot_bonus_free_spins_banner"))
    }

    @Test
    fun `slot themes render unique imagegen bonus entry portals`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "bonus_entry_portal_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 220_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 1_000 || size.height < 720
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing imagegen bonus entry portal assets: $missing", missing.isEmpty())
        assertTrue("Imagegen bonus entry portal assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen bonus entry portal assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Bonus entry portal assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must include bonus entry portal above win burst and below result banner", slotLayout.contains("@+id/bonusEntryPortalOverlay") && slotLayout.contains("@drawable/bonus_entry_portal_violet") && slotLayout.indexOf("@+id/bonusEntryPortalOverlay") > slotLayout.indexOf("@+id/coinBurstOverlay") && slotLayout.indexOf("@+id/bonusEntryPortalOverlay") < slotLayout.indexOf("@+id/bigWinBannerOverlay"))
        assertTrue("Landscape slot layout must include bonus entry portal above win burst and below result banner", slotLandscapeLayout.contains("@+id/bonusEntryPortalOverlay") && slotLandscapeLayout.contains("@drawable/bonus_entry_portal_violet") && slotLandscapeLayout.indexOf("@+id/bonusEntryPortalOverlay") > slotLandscapeLayout.indexOf("@+id/coinBurstOverlay") && slotLandscapeLayout.indexOf("@+id/bonusEntryPortalOverlay") < slotLandscapeLayout.indexOf("@+id/bigWinBannerOverlay"))
        assertTrue("Bonus entry portal must stay decorative in both orientations", slotLayout.split("@+id/bonusEntryPortalOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/bonusEntryPortalOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme bonus entry portal", slotFragment.contains("R.drawable.bonus_entry_portal_$theme"))
        }
        assertTrue("SlotFragment must bind the active theme bonus entry portal", slotFragment.contains("private fun bonusEntryPortalDrawable(theme: SlotTheme)") && slotFragment.contains("binding.bonusEntryPortalOverlay.setImageResource(bonusEntryPortalDrawable(theme))"))
        assertTrue("Bonus entry portal must animate only for bonus results and reset with lifecycle", slotFragment.contains("result.resultType == ResultType.Bonus") && slotFragment.contains("animateBonusEntryPortal(theme)") && slotFragment.contains("hideBonusEntryPortal(immediate = true)") && slotFragment.contains("bonusEntryPortalAnimator") && slotFragment.contains("BONUS_ENTRY_PORTAL_DURATION_MS") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot delayed UI callbacks must be lifecycle-scoped instead of posted to detached views", !slotFragment.contains("postDelayed(") && slotFragment.contains("delay(QA_AUTO_SPIN_START_DELAY_MS)") && slotFragment.contains("bonusEntryPortalStaticHideJob") && slotFragment.contains("delay(BONUS_ENTRY_PORTAL_STATIC_HOLD_MS)") && slotFragment.contains("bonusEntryPortalStaticHideJob?.cancel()"))
    }

    @Test
    fun `slot themes render unique imagegen win bursts with dedicated motion`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "theme_win_burst_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 400_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 900 || size.height < 680
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val resultLayout = Path.of("src/main/res/layout/dialog_result.xml").readText()
        val resultLandscapeLayout = Path.of("src/main/res/layout-land/dialog_result.xml").readText()
        val resultDialog = sourceText("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt")

        assertTrue("Missing imagegen win burst assets: $missing", missing.isEmpty())
        assertTrue("Imagegen win burst assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen win burst assets have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Win burst assets must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait slot layout must include a full-window theme win burst ImageView", slotLayout.contains("@+id/coinBurstOverlay") && slotLayout.contains("@drawable/theme_win_burst_violet") && slotLayout.split("@+id/coinBurstOverlay", limit = 2)[1].contains("android:layout_height=\"match_parent\"") && slotLayout.split("@+id/coinBurstOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Landscape slot layout must include a full-window theme win burst ImageView", slotLandscapeLayout.contains("@+id/coinBurstOverlay") && slotLandscapeLayout.contains("@drawable/theme_win_burst_violet") && slotLandscapeLayout.split("@+id/coinBurstOverlay", limit = 2)[1].contains("android:layout_height=\"match_parent\"") && slotLandscapeLayout.split("@+id/coinBurstOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        themes.forEach { theme ->
            assertTrue("SlotFragment must wire $theme win burst", slotFragment.contains("R.drawable.theme_win_burst_$theme"))
        }
        assertTrue("SlotFragment must bind the active theme win burst before feedback", slotFragment.contains("val theme = viewModel.uiState.value.config.theme") && slotFragment.contains("binding.coinBurstOverlay.setImageResource(themeWinBurstDrawable(theme))"))
        assertTrue("Each theme must have a dedicated win burst motion profile", slotFragment.contains("private fun themeWinBurstMotion(theme: SlotTheme)") && slotFragment.contains("SlotTheme.Violet -> ThemeWinBurstMotion") && slotFragment.contains("SlotTheme.Roman -> ThemeWinBurstMotion") && slotFragment.contains("SlotTheme.Neon -> ThemeWinBurstMotion") && slotFragment.contains("SlotTheme.Pharaoh -> ThemeWinBurstMotion") && slotFragment.contains("SlotTheme.Ocean -> ThemeWinBurstMotion"))
        assertTrue("Theme win burst must animate image transforms and reset with the Fragment lifecycle", slotFragment.contains("animateThemeWinBurst(theme, result)") && slotFragment.contains("winBurstAnimator = AnimatorSet()") && slotFragment.contains("hideThemeWinBurst(immediate = true)") && slotFragment.contains("ThemeWinBurstMotion"))
        assertTrue("Theme win burst must rise above reel chrome and hold at peak alpha long enough to read", slotFragment.contains("burst.bringToFront()") && slotFragment.contains("ObjectAnimator.ofFloat(burst, View.ALPHA, 0f, peakAlpha, peakAlpha, 0f)") && slotFragment.contains("binding.bigWinBannerOverlay.bringToFront()"))
        assertTrue("Result dialog must reuse the active theme win burst as a decorative image layer", resultLayout.contains("@+id/resultThemeWinBurst") && resultLayout.contains("@drawable/theme_win_burst_violet") && resultLayout.split("@+id/resultThemeWinBurst", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && resultLandscapeLayout.contains("@+id/resultThemeWinBurst") && resultLandscapeLayout.contains("@drawable/theme_win_burst_violet"))
        themes.forEach { theme ->
            assertTrue("ResultDialogFragment must wire $theme win burst", resultDialog.contains("R.drawable.theme_win_burst_$theme"))
        }
        assertTrue("Result dialog theme win burst must animate under the payout overlay", resultDialog.contains("binding.resultThemeWinBurst.setImageResource(themeWinBurstDrawable(slotTheme))") && resultDialog.contains("RESULT_THEME_BURST_SETTLED_ALPHA") && resultDialog.contains("ObjectAnimator.ofFloat(\n                themeBurst,\n                View.ALPHA"))
    }

    @Test
    fun `new slot themes render bottom controls from dedicated image selectors`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val selectorRoot = Path.of("src/main/res/drawable")
        val themeSuffixes = listOf("neon", "pharaoh", "ocean")
        val buttonAssets = mapOf(
            "spin_button_%s_default.webp" to 30_000L,
            "spin_button_%s_pressed.webp" to 28_000L,
            "spin_button_%s_disabled.webp" to 23_000L,
            "spin_button_%s_free_spins_default.webp" to 31_000L,
            "spin_button_%s_free_spins_pressed.webp" to 28_000L,
            "spin_button_%s_free_spins_disabled.webp" to 28_000L,
            "btn_autospin_%s_default.webp" to 15_000L,
            "btn_autospin_%s_pressed.webp" to 15_000L,
            "btn_autospin_%s_active.webp" to 15_000L,
            "btn_autospin_%s_active_pressed.webp" to 15_000L,
            "btn_autospin_%s_disabled.webp" to 13_000L,
            "btn_bet_minus_%s.webp" to 14_000L,
            "btn_bet_minus_%s_pressed.webp" to 14_000L,
            "btn_bet_minus_%s_disabled.webp" to 10_000L,
            "btn_bet_plus_%s.webp" to 15_000L,
            "btn_bet_plus_%s_pressed.webp" to 14_000L,
            "btn_bet_plus_%s_disabled.webp" to 10_000L,
            "btn_max_lines_%s_default.webp" to 15_000L,
            "btn_max_lines_%s_pressed.webp" to 18_000L,
            "btn_max_lines_%s_disabled.webp" to 16_000L,
            "paytable_button_%s.webp" to 18_000L,
            "label_paytable_button_%s.webp" to 13_000L,
            "auto_spin_active_halo_%s.webp" to 30_000L
        )
        val selectorNames = listOf(
            "spin_button_%s_selector.xml",
            "spin_button_%s_free_spins_selector.xml",
            "btn_autospin_%s_selector.xml",
            "btn_autospin_%s_active_selector.xml",
            "btn_bet_minus_%s_selector.xml",
            "btn_bet_plus_%s_selector.xml",
            "btn_max_lines_%s_selector.xml"
        )
        val missingAssets = themeSuffixes.flatMap { suffix ->
            buttonAssets.keys.map { it.format(suffix) }.filterNot { Files.exists(drawableRoot.resolve(it)) }
        }
        val tinyAssets = themeSuffixes.flatMap { suffix ->
            buttonAssets.filter { (template, minimumSize) ->
                val asset = drawableRoot.resolve(template.format(suffix))
                Files.exists(asset) && Files.size(asset) < minimumSize
            }.map { it.key.format(suffix) }
        }
        val missingSelectors = themeSuffixes.flatMap { suffix ->
            selectorNames.map { it.format(suffix) }.filterNot { Files.exists(selectorRoot.resolve(it)) }
        }
        val brokenSelectors = themeSuffixes.flatMap { suffix ->
            selectorNames.mapNotNull { template ->
                val selectorName = template.format(suffix)
                val text = selectorRoot.resolve(selectorName).readText()
                val stem = selectorName.removeSuffix(".xml")
                val expectedDefault = when {
                    stem.endsWith("_free_spins_selector") -> stem.removeSuffix("_selector") + "_default"
                    stem.endsWith("_active_selector") -> stem.removeSuffix("_selector")
                    stem.endsWith("_selector") && stem.startsWith("btn_bet_minus") -> stem.removeSuffix("_selector")
                    stem.endsWith("_selector") && stem.startsWith("btn_bet_plus") -> stem.removeSuffix("_selector")
                    stem.endsWith("_selector") -> stem.removeSuffix("_selector") + "_default"
                    else -> stem
                }
                if (text.contains("@drawable/$expectedDefault") && text.contains("android:state_pressed") && text.contains("android:state_enabled=\"false\"")) {
                    null
                } else {
                    selectorName
                }
            }
        }
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing new-theme slot button image assets: $missingAssets", missingAssets.isEmpty())
        assertTrue("New-theme slot button image assets are unexpectedly tiny: $tinyAssets", tinyAssets.isEmpty())
        assertTrue("Missing new-theme slot button selectors: $missingSelectors", missingSelectors.isEmpty())
        assertTrue("New-theme slot button selectors do not expose disabled, pressed, and default states: $brokenSelectors", brokenSelectors.isEmpty())
        themeSuffixes.forEach { suffix ->
            assertTrue("Spin button selector for $suffix must be wired", slotFragment.contains("R.drawable.spin_button_${suffix}_selector"))
            assertTrue("Free-spins spin button selector for $suffix must be wired", slotFragment.contains("R.drawable.spin_button_${suffix}_free_spins_selector"))
            assertTrue("Autospin selector for $suffix must be wired", slotFragment.contains("R.drawable.btn_autospin_${suffix}_selector"))
            assertTrue("Active autospin selector for $suffix must be wired", slotFragment.contains("R.drawable.btn_autospin_${suffix}_active_selector"))
            assertTrue("Stepper selectors for $suffix must be wired", slotFragment.contains("R.drawable.btn_bet_minus_${suffix}_selector") && slotFragment.contains("R.drawable.btn_bet_plus_${suffix}_selector"))
            assertTrue("Max lines selector for $suffix must be wired", slotFragment.contains("R.drawable.btn_max_lines_${suffix}_selector"))
            assertTrue("Paytable assets for $suffix must be wired", slotFragment.contains("R.drawable.paytable_button_$suffix") && slotFragment.contains("R.drawable.label_paytable_button_$suffix"))
            assertTrue("Autospin halo for $suffix must be wired", slotFragment.contains("R.drawable.auto_spin_active_halo_$suffix"))
        }
        assertTrue("Runtime slot buttons must use theme-specific helper methods", slotFragment.contains("binding.autoSpinButton.setImageResource(autoSpinButtonDrawable(theme, state.isAutoSpinEnabled))") && slotFragment.contains("binding.maxLinesButtonIcon.setImageResource(maxLinesButtonDrawable(theme))") && slotFragment.contains("binding.betMinusButton.setImageResource(betMinusButtonDrawable(theme))") && slotFragment.contains("binding.betPlusButton.setImageResource(betPlusButtonDrawable(theme))") && slotFragment.contains("binding.linesMinusButton.setImageResource(betMinusButtonDrawable(theme))") && slotFragment.contains("binding.linesPlusButton.setImageResource(betPlusButtonDrawable(theme))") && slotFragment.contains("binding.paytableButtonIcon.setImageResource(paytableButtonDrawable(theme))"))
    }

    @Test
    fun `slot meters use readable scalable labels in every orientation`() {
        val layouts = listOf(
            "src/main/res/layout/fragment_slot.xml",
            "src/main/res/layout-land/fragment_slot.xml",
            "src/main/res/layout-w600dp-land/fragment_slot.xml"
        ).associateWith { Path.of(it).readText() }

        layouts.forEach { (path, layout) ->
            listOf(
                "betLabel" to "@string/line_bet_short",
                "linesLabel" to "@string/active_lines_short",
                "totalBetLabel" to "@string/spin_cost",
                "lastWinLabel" to "@string/payout_short"
            ).forEach { (id, text) ->
                val control = layout.substringAfter("@+id/$id").substringBefore("/>")
                assertTrue("$path must expose scalable $id copy", control.contains("android:text=\"$text\"") && control.contains("@style/VSlotAccessibleCopy.MeterLabel"))
            }
        }
    }

    @Test
    fun `new slot themes render paylines from dedicated image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themeSuffixes = listOf("neon", "pharaoh", "ocean")
        val expectedAssets = themeSuffixes.flatMap { suffix ->
            (1..10).flatMap { index ->
                listOf(
                    "payline_markers_overlay_${suffix}_active_$index.webp",
                    "payline_win_${suffix}_$index.webp"
                )
            }
        }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 30_000L
        }
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing new-theme payline image assets: $missing", missing.isEmpty())
        assertTrue("New-theme payline image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Slot payline markers must use theme-specific arrays", slotFragment.contains("paylineMarkerDrawables(theme)") && slotFragment.contains("NEON_PAYLINE_MARKER_DRAWABLES") && slotFragment.contains("PHARAOH_PAYLINE_MARKER_DRAWABLES") && slotFragment.contains("OCEAN_PAYLINE_MARKER_DRAWABLES"))
        assertTrue("Slot winning paylines must use theme-specific arrays", slotFragment.contains("paylineWinDrawables(theme)") && slotFragment.contains("NEON_PAYLINE_WIN_DRAWABLES") && slotFragment.contains("PHARAOH_PAYLINE_WIN_DRAWABLES") && slotFragment.contains("OCEAN_PAYLINE_WIN_DRAWABLES"))
        themeSuffixes.forEach { suffix ->
            assertTrue("Active payline 10 for $suffix must be wired", slotFragment.contains("R.drawable.payline_markers_overlay_${suffix}_active_10"))
            assertTrue("Winning payline 10 for $suffix must be wired", slotFragment.contains("R.drawable.payline_win_${suffix}_10"))
        }
    }

    @Test
    fun `slot reels use per column motion and scatter anticipation timing`() {
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val slotSpinTimeline = sourceText("src/main/java/com/vslot/app/ui/slot/SlotSpinTimeline.kt")

        assertTrue("Reel preview must advance each reel with its own offset and align without skipping symbols", slotFragment.contains("val columnOffsets = IntArray(REEL_COUNT)") && slotFragment.contains("val alignedStopOffsets = IntArray(REEL_COUNT)") && slotFragment.contains("alignedStoppingStep(") && slotFragment.contains("columnOffsets[column] -= step") && slotFragment.contains("spinningStripSymbols(config, column, columnOffsets[column])"))
        assertTrue("Reel stop timing must share slot-like scatter anticipation with settlement", slotFragment.contains("SlotSpinTimeline.stopAtMs(config, targetResult, column)") && slotFragment.contains("SlotSpinTimeline.scatterAnticipationStartAtMs(") && slotSpinTimeline.contains("SCATTER_HOLD_MS") && slotFragment.contains("REEL_SCATTER_ANTICIPATION_WINDOW_MS"))
        assertTrue("Scatter anticipation must start only when scatters visibly land on the first two reels", slotSpinTimeline.contains("SCATTER_CHASE_TRIGGER_COUNT = 3") && slotSpinTimeline.contains("REQUIRED_SCATTER_CHASE_REELS = setOf(0, 1)") && slotSpinTimeline.contains("REQUIRED_SCATTER_CHASE_REELS.all(landedScatterColumns::contains)") && slotSpinTimeline.contains("latestRequiredScatterLandedAtMs") && slotSpinTimeline.contains("REEL_STOP_BOUNCE_DURATION_MS"))
        assertTrue("Scatter chase must strengthen strip, flash, and frame timing only after its visible start", slotFragment.contains("val scatterChaseActive = scatterChase") && slotFragment.contains("scatterChaseActive,\n                            step,\n                            frameDurationMs") && slotFragment.contains("animateSpinStripColumnAnticipation(column, scatterChaseActive)") && slotFragment.contains("REEL_SCATTER_ANTICIPATION_FRAME_MS") && slotFragment.contains("REEL_SCATTER_ANTICIPATION_FLASH_ALPHA"))
    }

    @Test
    fun `close actions use dedicated modal image selector`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val selector = Path.of("src/main/res/drawable/btn_modal_close_selector.xml").readText()
        val modalCloseAssets = mapOf(
            "btn_modal_close_default.webp" to 25_000L,
            "btn_modal_close_pressed.webp" to 20_000L
        )
        val missing = modalCloseAssets.keys.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = modalCloseAssets.filter { (asset, minimumSize) ->
            Files.exists(drawableRoot.resolve(asset)) && Files.size(drawableRoot.resolve(asset)) < minimumSize
        }.keys
        val closeLayouts = listOf(
            "layout/dialog_result.xml",
            "layout-land/dialog_result.xml",
            "layout/dialog_paytable.xml",
            "layout-land/dialog_paytable.xml",
            "layout/dialog_social_rules.xml"
        ).associateWith { Path.of("src/main/res/$it").readText() }

        assertTrue("Missing modal close button image assets: $missing", missing.isEmpty())
        assertTrue("Modal close button image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Modal close selector must render default and pressed image states", selector.contains("@drawable/btn_modal_close_default") && selector.contains("@drawable/btn_modal_close_pressed"))
        closeLayouts.forEach { (fileName, text) ->
            val usesCornerClose = fileName == "layout-land/dialog_paytable.xml"
            val expectedSelector = if (usesCornerClose) {
                "@drawable/btn_dialog_corner_close_selector"
            } else {
                "@drawable/btn_modal_close_selector"
            }
            assertTrue("$fileName must render close action from a dedicated selector", text.contains(expectedSelector))
            assertTrue("$fileName must not reuse privacy selector for close action", !text.contains("android:id=\"@+id/closeButton\"") || !text.substringAfter("android:id=\"@+id/closeButton\"", "").substringBefore("/>").contains("@drawable/btn_privacy_selector"))
            assertTrue("$fileName must keep close treatment image based", usesCornerClose || text.contains("@drawable/label_close"))
            assertTrue("$fileName must not render close as android:text", !text.contains("android:text=\"@string/close\""))
        }
    }

    @Test
    fun `push pre permission actions use compact image selectors`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val pushPromptLayout = Path.of("src/main/res/layout/dialog_push_permission.xml").readText()
        val pushPromptLandscapeLayout = Path.of("src/main/res/layout-land/dialog_push_permission.xml").readText()
        val laterSelector = Path.of("src/main/res/drawable/btn_push_later_selector.xml").readText()
        val allowSelector = Path.of("src/main/res/drawable/btn_push_allow_selector.xml").readText()
        val compactButtonAssets = mapOf(
            "btn_push_later_default.webp" to 24_000L,
            "btn_push_later_pressed.webp" to 20_000L,
            "btn_push_allow_default.webp" to 28_000L,
            "btn_push_allow_pressed.webp" to 24_000L
        )
        val missing = compactButtonAssets.keys.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = compactButtonAssets.filter { (asset, minimumSize) ->
            Files.exists(drawableRoot.resolve(asset)) && Files.size(drawableRoot.resolve(asset)) < minimumSize
        }.keys

        assertTrue("Missing compact push action image assets: $missing", missing.isEmpty())
        assertTrue("Compact push action image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Push later selector must render default and pressed image states", laterSelector.contains("@drawable/btn_push_later_default") && laterSelector.contains("@drawable/btn_push_later_pressed"))
        assertTrue("Push allow selector must render default and pressed image states", allowSelector.contains("@drawable/btn_push_allow_default") && allowSelector.contains("@drawable/btn_push_allow_pressed"))
        assertTrue("Maybe later action must use compact push selector", pushPromptLayout.contains("@+id/maybeLaterButton") && pushPromptLayout.contains("@drawable/btn_push_later_selector"))
        assertTrue("Allow action must use compact push selector", pushPromptLayout.contains("@+id/allowButton") && pushPromptLayout.contains("@drawable/btn_push_allow_selector"))
        assertTrue("Push pre-permission actions must not reuse wide global selectors", !pushPromptLayout.contains("@drawable/btn_privacy_selector") && !pushPromptLayout.contains("@drawable/btn_play_selector"))
        assertTrue("Push pre-permission action labels must remain image assets", pushPromptLayout.contains("@drawable/label_maybe_later") && pushPromptLayout.contains("@drawable/label_allow"))
        assertTrue("Push pre-permission actions must not render labels through android:text", !pushPromptLayout.contains("android:text=\"@string/maybe_later\"") && !pushPromptLayout.contains("android:text=\"@string/allow\""))
        assertTrue("Landscape push pre-permission must keep both compact image actions", pushPromptLandscapeLayout.contains("@+id/maybeLaterButton") && pushPromptLandscapeLayout.contains("@drawable/btn_push_later_selector") && pushPromptLandscapeLayout.contains("@+id/allowButton") && pushPromptLandscapeLayout.contains("@drawable/btn_push_allow_selector"))
        assertTrue("Landscape push pre-permission action labels must remain image assets", pushPromptLandscapeLayout.contains("@drawable/label_maybe_later") && pushPromptLandscapeLayout.contains("@drawable/label_allow"))
        assertTrue("Landscape push pre-permission actions must not render labels through android:text", !pushPromptLandscapeLayout.contains("android:text=\"@string/maybe_later\"") && !pushPromptLandscapeLayout.contains("android:text=\"@string/allow\""))
    }

    @Test
    fun `bonus recovery actions use dedicated treasury image selector`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val bonusSelector = Path.of("src/main/res/drawable/btn_bonus_claim_selector.xml").readText()
        val cornerCloseSelector = Path.of("src/main/res/drawable/btn_dialog_corner_close_selector.xml").readText()
        val bonusDialogLayout = Path.of("src/main/res/layout/dialog_bonus.xml").readText()
        val lowCoinsLayout = Path.of("src/main/res/layout/dialog_low_coins.xml").readText()
        val bonusDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt").readText()
        val lowCoinsDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/LowCoinsDialogFragment.kt").readText()
        val treasuryAssets = mapOf(
            "btn_bonus_claim_default.webp" to 30_000L,
            "btn_bonus_claim_pressed.webp" to 26_000L,
            "btn_dialog_corner_close_default.webp" to 12_000L,
            "btn_dialog_corner_close_pressed.webp" to 5_000L
        )
        val missing = treasuryAssets.keys.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = treasuryAssets.filter { (asset, minimumSize) ->
            Files.exists(drawableRoot.resolve(asset)) && Files.size(drawableRoot.resolve(asset)) < minimumSize
        }.keys

        assertTrue("Missing bonus claim button image assets: $missing", missing.isEmpty())
        assertTrue("Bonus claim button image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Bonus claim selector must render default and pressed image states", bonusSelector.contains("@drawable/btn_bonus_claim_default") && bonusSelector.contains("@drawable/btn_bonus_claim_pressed"))
        assertTrue("Daily bonus corner close selector must render default and pressed image states", cornerCloseSelector.contains("@drawable/btn_dialog_corner_close_default") && cornerCloseSelector.contains("@drawable/btn_dialog_corner_close_pressed"))
        assertTrue("Daily bonus auto modal must expose a visible image dismiss action", bonusDialogLayout.contains("@+id/bonusCloseButton") && bonusDialogLayout.contains("@drawable/btn_dialog_corner_close_selector") && bonusDialog.contains("binding.bonusCloseButton.setOnClickListener { dismiss() }"))
        assertTrue("Daily bonus ready action must default to treasury selector", bonusDialogLayout.contains("@+id/claimButton") && bonusDialogLayout.contains("@drawable/btn_bonus_claim_selector"))
        assertTrue("Low coins rescue action must default to treasury selector", lowCoinsLayout.contains("@+id/actionButton") && lowCoinsLayout.contains("@drawable/btn_bonus_claim_selector"))
        assertTrue("Daily bonus dialog must switch OK state away from treasury selector", bonusDialog.contains("R.drawable.btn_bonus_claim_selector") && bonusDialog.contains("R.drawable.btn_modal_close_selector"))
        assertTrue("Low coins dialog must switch OK state away from treasury selector", lowCoinsDialog.contains("R.drawable.btn_bonus_claim_selector") && lowCoinsDialog.contains("R.drawable.btn_modal_close_selector"))
        assertTrue("Bonus recovery actions must not reuse the game play selector", !bonusDialogLayout.contains("@drawable/btn_play_selector") && !lowCoinsLayout.contains("@drawable/btn_play_selector"))
        assertTrue("Bonus recovery labels must remain image assets", bonusDialogLayout.contains("@drawable/label_claim_bonus") && lowCoinsLayout.contains("@drawable/label_claim_bonus"))
    }

    @Test
    fun `settings exposes social casino rules entrypoint`() {
        val settingsLayout = Path.of("src/main/res/layout/fragment_settings.xml").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()

        assertTrue("Settings must expose rulesButton", settingsLayout.contains("@+id/rulesButton"))
        assertTrue("Rules title string missing", strings.contains("social_casino_rules"))
        assertTrue("Rules must mention virtual coins", strings.contains("Виртуальные монеты"))
        assertTrue("Rules must forbid purchase", strings.contains("нельзя купить"))
        assertTrue("Rules must forbid withdrawal", strings.contains("вывести"))
        assertTrue("Rules must forbid exchange", strings.contains("обменять"))
        assertTrue("Rules must forbid real-world prizes", strings.contains("денежных призов"))
    }

    @Test
    fun `short social disclaimer renders from image asset`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val homeLayout = Path.of("src/main/res/layout/fragment_home.xml").readText()
        val settingsLayout = Path.of("src/main/res/layout/fragment_settings.xml").readText()
        val asset = drawableRoot.resolve("label_social_disclaimer_short.webp")

        assertTrue("Short social disclaimer image asset missing", Files.exists(asset))
        assertTrue("Short social disclaimer asset is unexpectedly tiny", Files.size(asset) > 1_000)
        assertTrue("Home must render short social disclaimer from image asset", homeLayout.contains("@drawable/label_social_disclaimer_short"))
        assertTrue("Settings must render short social disclaimer from image asset", settingsLayout.contains("@drawable/label_social_disclaimer_short"))
        assertTrue("Home must keep disclaimer accessibility text", homeLayout.contains("android:contentDescription=\"@string/social_disclaimer_short\""))
        assertTrue("Settings must keep disclaimer accessibility text", settingsLayout.contains("android:contentDescription=\"@string/social_disclaimer_short\""))
        assertTrue("Home must not render short social disclaimer as TextView text", !homeLayout.contains("android:text=\"@string/social_disclaimer_short\""))
        assertTrue("Settings must provide scalable short social disclaimer copy for large font users", settingsLayout.contains("@+id/socialDisclaimerLargeText") && settingsLayout.contains("android:text=\"@string/social_disclaimer_short\""))
    }

    @Test
    fun `legal and permission copy keeps scalable disclaimer and image controls`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val requiredBodyAssets = listOf(
            "label_disclaimer_checkbox.webp",
            "checkbox_unchecked.webp",
            "checkbox_checked.webp",
            "disclaimer_modal_panel.webp",
            "disclaimer_safety_aura.webp",
            "disclaimer_accept_glow.webp",
            "body_social_rules.webp",
            "label_social_rules_footer.webp",
            "social_rules_modal_panel.webp",
            "social_rules_compliance_seal.webp",
            "body_push_prompt.webp",
            "push_permission_modal_panel.webp",
            "push_permission_modal_panel_premium.webp",
            "push_prompt_panel_lattice.webp",
            "push_permission_signal_burst.webp"
        )
        val missing = requiredBodyAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = requiredBodyAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }
        val disclaimerLayout = Path.of("src/main/res/layout/fragment_disclaimer.xml").readText()
        val disclaimerLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/fragment_disclaimer.xml").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()
        val socialRulesLayout = Path.of("src/main/res/layout/dialog_social_rules.xml").readText()
        val socialRulesLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/dialog_social_rules.xml").readText()
        val socialRulesDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/SocialRulesDialogFragment.kt").readText()
        val pushPromptLayout = Path.of("src/main/res/layout/dialog_push_permission.xml").readText()
        val pushPromptLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/dialog_push_permission.xml").readText()
        val pushPromptDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/PushPermissionDialogFragment.kt").readText()
        val disclaimerCheckSelector = Path.of("src/main/res/drawable/disclaimer_check_selector.xml").readText()
        val playSelector = Path.of("src/main/res/drawable/btn_play_selector.xml").readText()
        val disclaimerFragment = Path.of("src/main/java/com/vslot/app/ui/disclaimer/DisclaimerFragment.kt").readText()

        assertTrue("Missing legal body image assets: $missing", missing.isEmpty())
        assertTrue("Legal body image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Disclaimer body must render directly from the narrative resource", disclaimerLayout.contains("@+id/disclaimerBodyLargeText") && disclaimerLayout.contains("android:text=\"@string/disclaimer_body\"") && !disclaimerLayout.contains("@drawable/body_disclaimer"))
        assertTrue("Disclaimer checkbox label must render from image asset", disclaimerLayout.contains("@drawable/label_disclaimer_checkbox"))
        assertTrue("Disclaimer checkbox control must render from image selector", disclaimerLayout.contains("@drawable/disclaimer_check_selector"))
        assertTrue("Disclaimer unchecked checkbox image missing from selector", disclaimerCheckSelector.contains("@drawable/checkbox_unchecked"))
        assertTrue("Disclaimer checked checkbox image missing from selector", disclaimerCheckSelector.contains("@drawable/checkbox_checked"))
        assertTrue("Disclaimer continue action must have an image disabled state", playSelector.contains("android:state_enabled=\"false\"") && playSelector.contains("@drawable/btn_play_disabled"))
        assertTrue("Disclaimer continue image label must dim while disabled", disclaimerLayout.contains("@+id/continueButtonLabel") && disclaimerFragment.contains("binding.continueButtonLabel.alpha = if (disclaimerAccepted) 1f else CONTINUE_LABEL_DISABLED_ALPHA"))
        assertTrue("Disclaimer must use a seam-free dedicated image panel", disclaimerLayout.contains("@drawable/disclaimer_modal_panel") && !disclaimerLayout.contains("@drawable/modal_panel\""))
        assertTrue("Disclaimer safety aura must render from image asset", disclaimerLayout.contains("@+id/disclaimerSafetyAura") && disclaimerLayout.contains("@drawable/disclaimer_safety_aura"))
        assertTrue("Disclaimer accepted state glow must render from image asset", disclaimerLayout.contains("@+id/disclaimerAcceptGlow") && disclaimerLayout.contains("@drawable/disclaimer_accept_glow"))
        assertTrue("Disclaimer decorative polish must stay out of accessibility", disclaimerLayout.contains("@+id/disclaimerSafetyAura") && disclaimerLayout.contains("@+id/disclaimerAcceptGlow") && disclaimerLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Social rules body must render from image asset", socialRulesLayout.contains("@drawable/body_social_rules"))
        assertTrue("Social rules footer must render from image asset", socialRulesLayout.contains("@drawable/label_social_rules_footer"))
        assertTrue("Social rules dialog must use a seam-free dedicated image panel", socialRulesLayout.contains("@drawable/social_rules_modal_panel") && !socialRulesLayout.contains("@drawable/modal_panel\""))
        assertTrue("Social rules compliance seal must render from image asset", socialRulesLayout.contains("@+id/socialRulesComplianceSeal") && socialRulesLayout.contains("@drawable/social_rules_compliance_seal"))
        assertTrue("Social rules compliance seal must stay decorative", socialRulesLayout.contains("@+id/socialRulesComplianceSeal") && socialRulesLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Landscape social rules dialog must keep every binding control", listOf("@+id/socialRulesComplianceSeal", "@+id/socialRulesBadge", "@+id/closeButton").all { socialRulesLandscapeLayout.contains(it) })
        assertTrue("Landscape social rules dialog must keep image-first copy plus two scalable alternatives", Regex("<TextView").findAll(socialRulesLandscapeLayout).count() == 2 && socialRulesLandscapeLayout.contains("@+id/socialRulesBodyLargeText") && socialRulesLandscapeLayout.contains("@+id/socialRulesFooterLargeText") && socialRulesLandscapeLayout.contains("@drawable/title_social_rules") && socialRulesLandscapeLayout.contains("@drawable/body_social_rules") && socialRulesLandscapeLayout.contains("@drawable/label_social_rules_footer") && socialRulesLandscapeLayout.contains("@drawable/label_close"))
        assertTrue("Landscape social rules dialog must use a growing side-by-side panel with safe close target", socialRulesLandscapeLayout.contains("<androidx.constraintlayout.widget.ConstraintLayout") && socialRulesLandscapeLayout.contains("app:layout_constraintStart_toEndOf=\"@id/socialRulesHeaderColumn\"") && socialRulesLandscapeLayout.contains("android:minHeight=\"336dp\"") && socialRulesLandscapeLayout.contains("android:layout_height=\"52dp\"") && socialRulesLandscapeLayout.contains("android:layout_width=\"180dp\""))
        assertTrue("Landscape social rules compliance seal must stay decorative", socialRulesLandscapeLayout.contains("@drawable/social_rules_compliance_seal") && socialRulesLandscapeLayout.split("@+id/socialRulesComplianceSeal", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Push prompt body must render from image asset", pushPromptLayout.contains("@drawable/body_push_prompt"))
        assertTrue("Premium imagegen push prompt modal panel asset is too flat or tiny", Files.size(drawableRoot.resolve("push_permission_modal_panel_premium.webp")) > 60_000)
        assertTrue("Premium imagegen push prompt modal panel must preserve 900x420 geometry", readBitmapSize(drawableRoot.resolve("push_permission_modal_panel_premium.webp")) == BitmapSize(900, 420))
        assertTrue("Push prompt dialog must use a premium imagegen dedicated image panel", pushPromptLayout.contains("@drawable/push_permission_modal_panel_premium") && !pushPromptLayout.contains("@drawable/modal_panel\""))
        assertTrue("Push prompt panel lattice must render from dedicated image asset", pushPromptLayout.contains("@+id/pushPromptPanelLattice") && pushPromptLayout.contains("@drawable/push_prompt_panel_lattice"))
        assertTrue("Push prompt panel lattice must stay decorative", pushPromptLayout.contains("@+id/pushPromptPanelLattice") && pushPromptLayout.split("@+id/pushPromptPanelLattice", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Push prompt panel lattice must sit below signal animation and content", pushPromptLayout.indexOf("@+id/pushPromptPanelLattice") > pushPromptLayout.indexOf("@drawable/push_permission_modal_panel") && pushPromptLayout.indexOf("@+id/pushPromptPanelLattice") < pushPromptLayout.indexOf("@+id/pushSignalOverlay") && pushPromptLayout.indexOf("@+id/pushPromptPanelLattice") < pushPromptLayout.indexOf("@drawable/modal_badge_push"))
        assertTrue("Push prompt signal polish must render from dedicated image asset", pushPromptLayout.contains("@+id/pushSignalOverlay") && pushPromptLayout.contains("@drawable/push_permission_signal_burst"))
        assertTrue("Landscape push prompt must keep every binding control", listOf("@+id/pushPromptPanelLattice", "@+id/pushSignalOverlay", "@+id/maybeLaterButton", "@+id/allowButton").all { pushPromptLandscapeLayout.contains(it) })
        assertTrue("Landscape push prompt must keep image-first copy plus one scalable alternative", Regex("<TextView").findAll(pushPromptLandscapeLayout).count() == 1 && pushPromptLandscapeLayout.contains("@+id/pushPromptBodyLargeText") && pushPromptLandscapeLayout.contains("@drawable/title_push_notifications") && pushPromptLandscapeLayout.contains("@drawable/body_push_prompt") && pushPromptLandscapeLayout.contains("@drawable/label_maybe_later") && pushPromptLandscapeLayout.contains("@drawable/label_allow"))
        assertTrue("Landscape push prompt must use a growing compact horizontal notification panel", pushPromptLandscapeLayout.contains("android:orientation=\"horizontal\"") && pushPromptLandscapeLayout.contains("android:minHeight=\"256dp\"") && pushPromptLandscapeLayout.contains("android:layout_height=\"52dp\""))
        assertTrue("Landscape push prompt decorative polish must stay image-based and out of accessibility", pushPromptLandscapeLayout.contains("@drawable/push_prompt_panel_lattice") && pushPromptLandscapeLayout.contains("@drawable/push_permission_signal_burst") && pushPromptLandscapeLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Disclaimer body must expose its narrative as platform text", disclaimerLayout.contains("@+id/disclaimerBodyLargeText") && disclaimerLayout.contains("android:text=\"@string/disclaimer_body\""))
        assertTrue("Disclaimer 18+ title must remain an accessibility heading in every orientation and on API 26+", listOf(disclaimerLayout, disclaimerLandscapeLayout).all { layout -> layout.contains("android:contentDescription=\"@string/disclaimer_title\"") && !layout.contains("android:accessibilityHeading") } && disclaimerFragment.contains("ViewCompat.setAccessibilityHeading(binding.disclaimerTitle, true)"))
        assertTrue("Permission and legal dialog image titles must remain accessibility headings on API 26+", mapOf(socialRulesLayout to "@string/social_casino_rules", socialRulesLandscapeLayout to "@string/social_casino_rules", pushPromptLayout to "@string/push_prompt_title", pushPromptLandscapeLayout to "@string/push_prompt_title").all { (layout, title) -> layout.contains("android:contentDescription=\"$title\"") && !layout.contains("android:accessibilityHeading") } && socialRulesDialog.contains("ViewCompat.setAccessibilityHeading(binding.socialRulesTitle, true)") && pushPromptDialog.contains("ViewCompat.setAccessibilityHeading(binding.pushPromptTitle, true)"))
        assertTrue("Disclaimer checkbox row must keep accessibility text", disclaimerLayout.contains("android:contentDescription=\"@string/disclaimer_checkbox\""))
        assertTrue("Disclaimer acceptance must explicitly confirm adult age and virtual coin value", strings.contains("Мне исполнилось 18 лет") && strings.contains("монеты виртуальные и не имеют реальной ценности"))
        assertTrue("Social rules body must keep accessibility text", socialRulesLayout.contains("android:contentDescription=\"@string/social_casino_rules_body\""))
        assertTrue("Social rules footer must keep accessibility text", socialRulesLayout.contains("android:contentDescription=\"@string/social_casino_rules_footer\""))
        assertTrue("Push prompt body must keep accessibility text", pushPromptLayout.contains("android:contentDescription=\"@string/push_prompt_body\""))
        assertTrue("Landscape push prompt body must keep accessibility text", pushPromptLandscapeLayout.contains("android:contentDescription=\"@string/push_prompt_body\""))
        assertTrue("Disclaimer checkbox image row must toggle image state", disclaimerFragment.contains("binding.disclaimerCheckRow.setOnClickListener") && disclaimerFragment.contains("binding.disclaimerCheckButton.isSelected"))
        assertTrue("Disclaimer must expose one restorable TalkBack checkbox target and remain scrollable in compact windows", disclaimerFragment.contains("AccessibilityNodeInfoCompat") && disclaimerFragment.contains("info.className = \"android.widget.CheckBox\"") && disclaimerFragment.contains("info.isCheckable = true") && disclaimerFragment.contains("info.isChecked = disclaimerAccepted") && disclaimerFragment.contains("onSaveInstanceState") && disclaimerFragment.contains("KEY_DISCLAIMER_SELECTED") && disclaimerLayout.contains("<ScrollView") && disclaimerLandscapeLayout.contains("<ScrollView") && disclaimerLayout.contains("android:importantForAccessibility=\"no\"") && disclaimerLandscapeLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Disclaimer body must always provide scalable legal copy", disclaimerLayout.substringAfter("@+id/disclaimerBodyLargeText").substringBefore("/>").let { it.contains("android:text=\"@string/disclaimer_body\"") && !it.contains("android:visibility=\"gone\"") })
        assertTrue("Disclaimer checkbox must always show current scalable copy while retaining row semantics", disclaimerLayout.contains("@+id/disclaimerCheckboxLargeText") && disclaimerLayout.contains("android:text=\"@string/disclaimer_checkbox\"") && disclaimerLayout.substringAfter("@+id/disclaimerCheckboxLargeText").substringBefore("/>").let { it.contains("android:importantForAccessibility=\"no\"") && it.contains("android:visibility=\"visible\"") } && disclaimerLayout.substringAfter("@+id/disclaimerCheckboxLabelImage").substringBefore("/>").contains("android:visibility=\"gone\""))
        assertTrue("Disclaimer must not use platform CheckBox", !disclaimerLayout.contains("<CheckBox") && !disclaimerFragment.contains("disclaimerCheck.isChecked"))
        assertTrue("Landscape disclaimer must keep every binding control", listOf("@+id/disclaimerSafetyAura", "@+id/disclaimerAcceptGlow", "@+id/disclaimerCheckRow", "@+id/disclaimerCheckButton", "@+id/continueButton", "@+id/continueButtonLabel").all { disclaimerLandscapeLayout.contains(it) })
        assertTrue("Landscape disclaimer must keep direct body copy and image based controls", Regex("<TextView").findAll(disclaimerLandscapeLayout).count() == 2 && !disclaimerLandscapeLayout.contains("<CheckBox") && disclaimerLandscapeLayout.contains("@drawable/title_disclaimer_18") && !disclaimerLandscapeLayout.contains("@drawable/body_disclaimer") && disclaimerLandscapeLayout.contains("@drawable/label_disclaimer_checkbox") && disclaimerLandscapeLayout.contains("@drawable/label_continue_action") && disclaimerLandscapeLayout.contains("@+id/disclaimerBodyLargeText") && disclaimerLandscapeLayout.contains("@+id/disclaimerCheckboxLargeText"))
        assertTrue("Landscape disclaimer must use a scrollable horizontal compliance panel with safe tap targets", disclaimerLandscapeLayout.contains("android:orientation=\"horizontal\"") && disclaimerLandscapeLayout.contains("android:minHeight=\"336dp\"") && disclaimerLandscapeLayout.contains("android:layout_width=\"48dp\"") && disclaimerLandscapeLayout.contains("android:layout_height=\"48dp\"") && disclaimerLandscapeLayout.contains("android:minHeight=\"52dp\"") && disclaimerLandscapeLayout.contains("android:layout_height=\"54dp\""))
        assertTrue("Landscape disclaimer decorative polish must stay image-based and out of accessibility", disclaimerLandscapeLayout.contains("@drawable/disclaimer_safety_aura") && disclaimerLandscapeLayout.contains("@drawable/disclaimer_accept_glow") && disclaimerLandscapeLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Social rules body must expose hidden scalable text", socialRulesLayout.contains("@+id/socialRulesBodyLargeText") && socialRulesLayout.contains("android:text=\"@string/social_casino_rules_body\""))
        assertTrue("Social rules footer must expose hidden scalable text", socialRulesLayout.contains("@+id/socialRulesFooterLargeText") && socialRulesLayout.contains("android:text=\"@string/social_casino_rules_footer\""))
        assertTrue("Push prompt body must expose hidden scalable text", pushPromptLayout.contains("@+id/pushPromptBodyLargeText") && pushPromptLayout.contains("android:text=\"@string/push_prompt_body\""))
        assertTrue("Landscape push prompt body must expose hidden scalable text", pushPromptLandscapeLayout.contains("@+id/pushPromptBodyLargeText") && pushPromptLandscapeLayout.contains("android:text=\"@string/push_prompt_body\""))
        assertTrue("Disclaimer accepted feedback must be finite and respect disabled system animators", disclaimerFragment.contains("animateAcceptanceFeedback") && disclaimerFragment.contains("ValueAnimator.areAnimatorsEnabled()") && disclaimerFragment.contains("ACCEPT_GLOW_SETTLED_ALPHA") && !disclaimerFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Social rules seal polish must be finite, managed, and respect disabled system animators", socialRulesDialog.contains("animateSocialRulesSeal") && socialRulesDialog.contains("socialRulesSealAnimator") && socialRulesDialog.contains("socialRulesSealAnimator?.cancel()") && socialRulesDialog.contains("ValueAnimator.areAnimatorsEnabled()") && socialRulesDialog.contains("SOCIAL_RULES_SEAL_SETTLED_ALPHA") && !socialRulesDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Push prompt signal polish must be finite, managed, and respect disabled system animators", pushPromptDialog.contains("animatePushSignal") && pushPromptDialog.contains("pushSignalAnimator") && pushPromptDialog.contains("pushSignalAnimator?.cancel()") && pushPromptDialog.contains("ValueAnimator.areAnimatorsEnabled()") && pushPromptDialog.contains("PUSH_SIGNAL_SETTLED_ALPHA") && !pushPromptDialog.contains("ValueAnimator.INFINITE"))
    }

    @Test
    fun `critical image titles and startup failures remain accessible`() {
        val headingLayouts = listOf(
            Triple("layout/fragment_disclaimer.xml", "@string/disclaimer_title", "disclaimerTitle"),
            Triple("layout-land/fragment_disclaimer.xml", "@string/disclaimer_title", "disclaimerTitle"),
            Triple("layout/dialog_analytics_consent.xml", "@string/settings_analytics", "analyticsConsentTitle"),
            Triple("layout-land/dialog_analytics_consent.xml", "@string/settings_analytics", "analyticsConsentTitle"),
            Triple("layout/dialog_push_permission.xml", "@string/push_prompt_title", "pushPromptTitle"),
            Triple("layout-land/dialog_push_permission.xml", "@string/push_prompt_title", "pushPromptTitle"),
            Triple("layout/dialog_social_rules.xml", "@string/social_casino_rules", "socialRulesTitle"),
            Triple("layout-land/dialog_social_rules.xml", "@string/social_casino_rules", "socialRulesTitle")
        )
        headingLayouts.forEach { (relativePath, title, _) ->
            val layout = Path.of("src/main/res/$relativePath").readText()
            assertTrue("$relativePath must expose its image title without an API-28-only XML attribute", layout.contains("android:contentDescription=\"$title\"") && !layout.contains("android:accessibilityHeading"))
        }
        val headingOwners = mapOf(
            "src/main/java/com/vslot/app/ui/disclaimer/DisclaimerFragment.kt" to "disclaimerTitle",
            "src/main/java/com/vslot/app/ui/dialog/AnalyticsConsentDialogFragment.kt" to "analyticsConsentTitle",
            "src/main/java/com/vslot/app/ui/dialog/PushPermissionDialogFragment.kt" to "pushPromptTitle",
            "src/main/java/com/vslot/app/ui/dialog/SocialRulesDialogFragment.kt" to "socialRulesTitle"
        )
        headingOwners.forEach { (sourcePath, bindingId) ->
            val source = Path.of(sourcePath).readText()
            assertTrue("$sourcePath must set the heading through ViewCompat for API 26+", source.contains("ViewCompat.setAccessibilityHeading(binding.$bindingId, true)"))
        }
        val splashFragment = Path.of("src/main/java/com/vslot/app/ui/splash/SplashFragment.kt").readText()
        listOf(
            Path.of("src/main/res/layout/fragment_splash.xml").readText(),
            Path.of("src/main/res/layout-land/fragment_splash.xml").readText()
        ).forEach { splashLayout ->
            assertTrue("Storage failures must have visible scalable copy without an API-28-only XML attribute", splashLayout.contains("@+id/splashStorageErrorMessage") && splashLayout.contains("android:text=\"@string/player_state_load_error\"") && !splashLayout.contains("android:accessibilityHeading"))
            assertEquals("Splash must announce the app name only once", 1, Regex("android:contentDescription=\"@string/app_name\"").findAll(splashLayout).count())
        }
        assertTrue("Splash storage failure must be a ViewCompat heading on API 26+", splashFragment.contains("ViewCompat.setAccessibilityHeading(binding.splashStorageErrorMessage, true)"))
    }

    @Test
    fun `settings version and privacy errors render from image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val requiredAssets = listOf(
            "label_version_current.webp",
            "label_privacy_error_offline.webp",
            "label_privacy_error_load.webp",
            "label_privacy_error_invalid_url.webp",
            "label_privacy_error_not_configured.webp",
            "privacy_web_panel.webp",
            "privacy_web_panel_premium.webp",
            "privacy_web_panel_landscape_premium.webp",
            "privacy_guard_badge.webp",
            "privacy_guard_document_glow.webp",
            "privacy_loading_shield.webp",
            "privacy_loading_scan_rail.webp",
            "privacy_loading_sweep.webp",
            "btn_privacy_retry_default.webp",
            "btn_privacy_retry_pressed.webp"
        )
        val missing = requiredAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = requiredAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }
        val settingsLayout = Path.of("src/main/res/layout/fragment_settings.xml").readText()
        val settingsFragment = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt").readText()
        val privacyLayout = Path.of("src/main/res/layout/fragment_privacy.xml").readText()
        val privacyLandscapeLayout = Path.of("src/main/res/layout-land/fragment_privacy.xml").readText()
        val privacyFragment = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()
        val retrySelector = Path.of("src/main/res/drawable/btn_privacy_retry_selector.xml").readText()

        assertTrue("Missing settings/privacy image assets: $missing", missing.isEmpty())
        assertTrue("Settings/privacy image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Settings version must render from image asset", settingsLayout.contains("@drawable/label_version_current"))
        assertTrue("Settings version accessibility must remain dynamic", settingsFragment.contains("versionImage.contentDescription"))
        assertTrue("Settings version accessibility must match the visible release label in debug and QA builds", settingsFragment.contains("removeSuffix(\"-debug\")") && settingsFragment.contains("removeSuffix(\"-qa\")"))
        assertTrue("Settings visible version must render from the bundled WebP image and avoid runtime text drawing", settingsFragment.contains("binding.versionImage.setImageResource(R.drawable.label_version_current)") && !settingsFragment.contains("setImageBitmap") && !settingsFragment.contains("Bitmap.createBitmap") && !settingsFragment.contains("drawText("))
        assertTrue("Settings version renderer must not keep runtime text sizing code for visible UI", !settingsFragment.contains("VERSION_LABEL_MAX_TEXT_WIDTH_PX") && !settingsFragment.contains("VERSION_LABEL_MIN_TEXT_SIZE_PX") && !settingsFragment.contains("measurePaint.measureText"))
        assertTrue("Privacy back navigation must ignore stale rapid taps", privacyFragment.contains("popFromPrivacy") && privacyFragment.contains("currentDestination?.id != R.id.privacyFragment") && !privacyFragment.contains("findNavController().popBackStack()"))
        assertTrue("Privacy error must render from image asset", privacyLayout.contains("@drawable/label_privacy_error_load"))
        assertTrue("Premium imagegen privacy WebView panel asset is too flat or tiny", Files.size(drawableRoot.resolve("privacy_web_panel_premium.webp")) > 100_000)
        assertTrue("Premium imagegen privacy WebView panel must preserve 900x1450 geometry", readBitmapSize(drawableRoot.resolve("privacy_web_panel_premium.webp")) == BitmapSize(900, 1450))
        assertTrue("Premium imagegen landscape privacy WebView panel asset is too flat or tiny", Files.size(drawableRoot.resolve("privacy_web_panel_landscape_premium.webp")) > 90_000)
        assertTrue("Premium imagegen landscape privacy WebView panel must preserve 1500x620 geometry", readBitmapSize(drawableRoot.resolve("privacy_web_panel_landscape_premium.webp")) == BitmapSize(1500, 620))
        assertTrue("Privacy loading shield imagegen asset is too flat or tiny", Files.size(drawableRoot.resolve("privacy_loading_shield.webp")) > 120_000)
        assertTrue("Privacy loading scan rail imagegen asset is too flat or tiny", Files.size(drawableRoot.resolve("privacy_loading_scan_rail.webp")) > 30_000)
        assertTrue("Privacy loading sweep imagegen asset is too flat or tiny", Files.size(drawableRoot.resolve("privacy_loading_sweep.webp")) > 80_000)
        assertTrue("Privacy loading imagegen assets must preserve generated geometry", readBitmapSize(drawableRoot.resolve("privacy_loading_shield.webp")) == BitmapSize(640, 640) && readBitmapSize(drawableRoot.resolve("privacy_loading_scan_rail.webp")) == BitmapSize(620, 180))
        assertTrue("Privacy loading sweep imagegen asset must preserve transparent scan geometry", readBitmapSize(drawableRoot.resolve("privacy_loading_sweep.webp")) == BitmapSize(520, 760))
        assertTrue("Privacy loading sweep must be reproducible from imagegen source, slicer, design note, and contact sheet", Files.exists(Path.of("../tools/slice_imagegen_privacy_loading_sweep.py")) && Files.exists(Path.of("../qa/source/vslot_privacy_loading_sweep_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/privacy_loading_sweep_contact_sheet.png")) && Files.exists(Path.of("../qa/design/privacy_loading_sweep_visual_philosophy.md")))
        assertTrue("Privacy WebView frame must use the premium imagegen panel", privacyLayout.contains("@drawable/privacy_web_panel_premium"))
        assertTrue("Landscape privacy WebView frame must use the dedicated wide premium imagegen panel", privacyLandscapeLayout.contains("@drawable/privacy_web_panel_landscape_premium"))
        assertTrue("Privacy screen must add compact spacing after the activity applies Android navigation insets", privacyLayout.contains("android:paddingBottom=\"16dp\"") && !privacyLayout.contains("android:paddingBottom=\"14dp\""))
        assertTrue("Privacy loading state must render from imagegen sweep, shield, and scan rail in portrait and landscape", privacyLayout.contains("@+id/privacyLoadingGroup") && privacyLayout.contains("@+id/privacyLoadingSweep") && privacyLayout.contains("@drawable/privacy_loading_sweep") && privacyLayout.contains("@drawable/privacy_loading_shield") && privacyLayout.contains("@drawable/privacy_loading_scan_rail") && privacyLayout.split("@+id/privacyLoadingSweep", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && privacyLayout.split("@+id/privacyLoadingShield", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && privacyLayout.split("@+id/privacyLoadingScanRail", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && privacyLandscapeLayout.contains("@+id/privacyLoadingGroup") && privacyLandscapeLayout.contains("@+id/privacyLoadingSweep") && privacyLandscapeLayout.contains("@drawable/privacy_loading_sweep") && privacyLandscapeLayout.contains("@drawable/privacy_loading_shield") && privacyLandscapeLayout.contains("@drawable/privacy_loading_scan_rail"))
        assertTrue("Privacy loading sweep must sit behind shield and scan rail in both orientations", privacyLayout.indexOf("@+id/privacyLoadingSweep") < privacyLayout.indexOf("@+id/privacyLoadingShield") && privacyLayout.indexOf("@+id/privacyLoadingSweep") < privacyLayout.indexOf("@+id/privacyLoadingScanRail") && privacyLandscapeLayout.indexOf("@+id/privacyLoadingSweep") < privacyLandscapeLayout.indexOf("@+id/privacyLoadingShield") && privacyLandscapeLayout.indexOf("@+id/privacyLoadingSweep") < privacyLandscapeLayout.indexOf("@+id/privacyLoadingScanRail"))
        assertTrue("Privacy loading state must keep Russian accessibility text on the stage container", privacyLayout.contains("android:contentDescription=\"@string/privacy_loading\"") && privacyLandscapeLayout.contains("android:contentDescription=\"@string/privacy_loading\""))
        assertTrue("Privacy error state must use dedicated guard badge image", privacyLayout.contains("@drawable/privacy_guard_badge"))
        assertTrue("Privacy guarded document glow must render from image asset", privacyLayout.contains("@+id/privacyGuardDocumentGlow") && privacyLayout.contains("@drawable/privacy_guard_document_glow"))
        assertTrue("Privacy guarded document glow must stay decorative", privacyLayout.contains("@+id/privacyGuardDocumentGlow") && privacyLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Privacy retry action must render from dedicated image selector", privacyLayout.contains("@+id/retryButtonGroup") && privacyLayout.contains("@+id/retryButton") && privacyLayout.contains("@drawable/btn_privacy_retry_selector"))
        assertTrue("Privacy retry selector must use default and pressed image states", retrySelector.contains("@drawable/btn_privacy_retry_default") && retrySelector.contains("@drawable/btn_privacy_retry_pressed"))
        assertTrue("Privacy retry action must not reuse the game play selector", !privacyLayout.substringAfter("@+id/retryButton").substringBefore("/>").contains("@drawable/btn_play_selector"))
        assertTrue("Privacy error state must not reuse generic modal error badge", !privacyLayout.contains("@drawable/modal_badge_error"))
        assertTrue("Privacy errors must switch image resources dynamically", privacyFragment.contains("errorImage.setImageResource"))
        assertTrue("Privacy errors must include offline image", privacyFragment.contains("R.drawable.label_privacy_error_offline"))
        assertTrue("Privacy errors must include load image", privacyFragment.contains("R.drawable.label_privacy_error_load"))
        assertTrue("Privacy errors must include invalid URL image", privacyFragment.contains("R.drawable.label_privacy_error_invalid_url"))
        assertTrue("Privacy errors must include not configured image", privacyFragment.contains("R.drawable.label_privacy_error_not_configured"))
        assertTrue("Privacy retry must be hidden for non-retryable configuration errors", privacyFragment.contains("retryable = false") && privacyFragment.contains("binding.retryButtonGroup.visibility = if (retryable) View.VISIBLE else View.GONE") && privacyFragment.contains("binding.retryButton.isEnabled = retryable"))
        assertTrue("Privacy retry polish must only animate when the retry image group is visible", privacyFragment.contains("binding.retryButtonGroup.isVisible") && privacyFragment.contains("polishAnimators += listOf("))
        val privacyLoadPolicy = privacyFragment
            .substringAfter("private fun loadPolicy")
            .substringBefore("private fun prepareForPageLoad")
        assertTrue("Privacy loading polish must prepare before WebView load and hide on success or error", privacyLoadPolicy.contains("prepareForPageLoad()") && privacyLoadPolicy.contains("activeWebView?.loadUrl(loadUrl)") && privacyLoadPolicy.indexOf("prepareForPageLoad()") < privacyLoadPolicy.indexOf("activeWebView?.loadUrl(loadUrl)") && privacyFragment.contains("showPrivacyLoading()") && privacyFragment.contains("hidePrivacyLoading()") && privacyFragment.contains("privacyLoadingAnimator") && privacyFragment.contains("PRIVACY_LOADING_POLISH_DURATION_MS"))
        assertTrue("Privacy loading sweep polish must be finite, low-alpha, and cleaned up", privacyFragment.contains("binding.privacyLoadingSweep") && privacyFragment.contains("PRIVACY_LOADING_SWEEP_TRAVEL_DP") && privacyFragment.contains("PRIVACY_LOADING_SWEEP_PEAK_ALPHA") && privacyFragment.contains("PRIVACY_LOADING_SWEEP_SETTLED_ALPHA = 0.32f") && privacyFragment.contains("binding.privacyLoadingSweep.visibility = View.INVISIBLE") && !privacyFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Privacy error polish must be finite and respect disabled system animators", privacyFragment.contains("animatePrivacyErrorPolish") && privacyFragment.contains("ValueAnimator.areAnimatorsEnabled()") && privacyFragment.contains("PRIVACY_ERROR_SETTLED_ALPHA") && !privacyFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Privacy error and loading polish must be hidden when WebView content is visible", privacyFragment.contains("hidePrivacyErrorPolish()") && privacyFragment.contains("hidePrivacyLoading()") && privacyFragment.contains("activeWebView?.loadUrl(loadUrl)"))
        assertTrue("Settings version must not render as TextView", !settingsLayout.contains("@+id/versionText"))
        assertTrue("Privacy error must not render as TextView", !privacyLayout.contains("@+id/errorText"))
        assertTrue("Privacy error must not be assigned as TextView text", !privacyFragment.contains("errorText.text"))
        assertTrue("Landscape privacy layout must keep the same WebView, loading, and error-state binding controls", listOf("@+id/backButton", "@+id/privacyGuardDocumentGlow", "@+id/privacyWebView", "@+id/privacyLoadingGroup", "@+id/privacyLoadingSweep", "@+id/privacyLoadingShield", "@+id/privacyLoadingScanRail", "@+id/errorGroup", "@+id/privacyGuardBadge", "@+id/errorImage", "@+id/retryButtonGroup", "@+id/retryButton").all { privacyLandscapeLayout.contains(it) })
        assertTrue("Landscape privacy must stay image-based with no plain text widgets", !privacyLandscapeLayout.contains("<TextView") && !privacyLandscapeLayout.contains("android:text=") && privacyLandscapeLayout.contains("@drawable/title_privacy_policy") && privacyLandscapeLayout.contains("@drawable/privacy_web_panel_landscape_premium") && privacyLandscapeLayout.contains("@drawable/privacy_loading_shield") && privacyLandscapeLayout.contains("@drawable/label_privacy_error_load") && privacyLandscapeLayout.contains("@drawable/label_retry"))
        assertTrue("Landscape privacy must use a compact horizontal error state and 48dp back target", privacyLandscapeLayout.contains("android:orientation=\"horizontal\"") && privacyLandscapeLayout.contains("android:layout_height=\"58dp\"") && privacyLandscapeLayout.contains("android:layout_width=\"48dp\"") && privacyLandscapeLayout.contains("android:layout_height=\"48dp\"") && privacyLandscapeLayout.contains("android:layout_height=\"52dp\""))
    }

    @Test
    fun `runtime layouts restrict platform text widgets to scalable compliance copy`() {
        val layoutRoot = Path.of("src/main/res/layout")
        val platformWidgetDeclarations = Files.walk(layoutRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.name.endsWith(".xml") }
                .filter {
                    val text = it.readText()
                    text.contains("<TextView") || text.contains("<CheckBox") || text.contains("android:text=")
                }
                .map { it.toString() }
                .toList()
        }

        assertEquals(
            "Only legal and instructional screens may declare scalable platform text: $platformWidgetDeclarations",
            setOf(
                layoutRoot.resolve("dialog_analytics_consent.xml").toString(),
                layoutRoot.resolve("dialog_auto_spin_count.xml").toString(),
                layoutRoot.resolve("dialog_bonus.xml").toString(),
                layoutRoot.resolve("dialog_low_coins.xml").toString(),
                layoutRoot.resolve("dialog_paytable.xml").toString(),
                layoutRoot.resolve("dialog_push_permission.xml").toString(),
                layoutRoot.resolve("dialog_result.xml").toString(),
                layoutRoot.resolve("dialog_social_rules.xml").toString(),
                layoutRoot.resolve("fragment_disclaimer.xml").toString(),
                layoutRoot.resolve("fragment_settings.xml").toString(),
                layoutRoot.resolve("fragment_splash.xml").toString(),
                layoutRoot.resolve("fragment_slot.xml").toString()
            ),
            platformWidgetDeclarations.toSet()
        )
    }

    @Test
    fun `home exposes image based daily bonus entrypoint`() {
        val homeLayout = Path.of("src/main/res/layout/fragment_home.xml").readText()
        val homeLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/fragment_home.xml").readText()
        val homeFragment = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val homePremiumStripAssets = mapOf(
            "daily_bonus_ready_imagegen.webp" to 100_000L,
            "daily_bonus_wait_imagegen.webp" to 95_000L
        )
        val missingPremiumHomeStripAssets = homePremiumStripAssets.keys.filterNot {
            Files.exists(drawableRoot.resolve(it))
        }
        val flatHomeStripAssets = homePremiumStripAssets.filter { (asset, minimumBytes) ->
            Files.exists(drawableRoot.resolve(asset)) && Files.size(drawableRoot.resolve(asset)) < minimumBytes
        }.keys
        val undersizedPremiumHomeStrips = homePremiumStripAssets.keys.filter { asset ->
            if (!Files.exists(drawableRoot.resolve(asset))) {
                false
            } else {
                val size = readBitmapSize(drawableRoot.resolve(asset))
                size.width < 1_600 || size.height < 270
            }
        }

        assertTrue("Home must expose dailyBonusButton", homeLayout.contains("@+id/dailyBonusButton"))
        assertTrue("Home must render daily bonus via ImageView", homeLayout.contains("@+id/dailyBonusImage"))
        assertTrue("Premium imagegen home daily bonus assets missing: $missingPremiumHomeStripAssets", missingPremiumHomeStripAssets.isEmpty())
        assertTrue("Home daily bonus strip assets are too flat or tiny: $flatHomeStripAssets", flatHomeStripAssets.isEmpty())
        assertTrue("Premium imagegen home daily bonus assets must stay high resolution: $undersizedPremiumHomeStrips", undersizedPremiumHomeStrips.isEmpty())
        assertTrue("Portrait home must default to the premium ready daily bonus image", homeLayout.contains("@drawable/daily_bonus_ready_imagegen"))
        assertTrue("Landscape home must default to the premium ready daily bonus image", homeLandscapeLayout.contains("@drawable/daily_bonus_ready_imagegen"))
        assertTrue("HomeFragment must switch daily bonus states with premium imagegen assets", homeFragment.contains("R.drawable.daily_bonus_ready_imagegen") && homeFragment.contains("R.drawable.daily_bonus_wait_imagegen"))
        assertTrue("Ready daily bonus body label missing", Files.exists(drawableRoot.resolve("label_bonus_ready_body.webp")))
        assertTrue("Wait daily bonus body label missing", Files.exists(drawableRoot.resolve("label_bonus_wait_body.webp")))
        assertTrue("Dedicated daily bonus modal panel asset missing", Files.exists(drawableRoot.resolve("daily_bonus_modal_panel.webp")))
        assertTrue("Premium imagegen daily bonus modal panel asset missing", Files.exists(drawableRoot.resolve("daily_bonus_modal_panel_premium.webp")))
        assertTrue("Premium imagegen daily bonus modal panel asset is too flat or tiny", Files.size(drawableRoot.resolve("daily_bonus_modal_panel_premium.webp")) > 100_000)
        assertTrue("Premium imagegen daily bonus modal panel must preserve 900x420 geometry", readBitmapSize(drawableRoot.resolve("daily_bonus_modal_panel_premium.webp")) == BitmapSize(900, 420))
        assertTrue("Home daily bonus countdown rail asset missing", Files.exists(drawableRoot.resolve("daily_bonus_countdown_rail.webp")))
        assertTrue("Home daily bonus countdown rail asset is unexpectedly tiny", Files.size(drawableRoot.resolve("daily_bonus_countdown_rail.webp")) > 20_000)
        assertTrue("Home daily bonus countdown charge asset missing", Files.exists(drawableRoot.resolve("daily_bonus_countdown_charge.webp")))
        assertTrue("Home daily bonus countdown charge asset is unexpectedly tiny", Files.size(drawableRoot.resolve("daily_bonus_countdown_charge.webp")) > 18_000)
        assertTrue("Home daily bonus countdown charge must preserve rail overlay geometry", readBitmapSize(drawableRoot.resolve("daily_bonus_countdown_charge.webp")) == BitmapSize(540, 144))
        assertTrue("Home daily bonus countdown charge must be reproducible from imagegen source, slicer, design note, and contact sheet", Files.exists(Path.of("../tools/slice_imagegen_daily_bonus_countdown_charge.py")) && Files.exists(Path.of("../qa/source/vslot_daily_bonus_countdown_charge_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/daily_bonus_countdown_charge_contact_sheet.png")) && Files.exists(Path.of("../qa/design/daily_bonus_countdown_charge_visual_philosophy.md")))
        assertTrue("Daily bonus modal countdown rail asset missing", Files.exists(drawableRoot.resolve("daily_bonus_modal_countdown_rail.webp")))
        assertTrue("Home wait bonus state must hide text overlay and rely on the image asset", homeFragment.contains("View.GONE"))
        assertTrue("Home wait bonus state must not reuse modal OK copy as overlay text", !homeFragment.contains("R.string.ok_action"))
        assertTrue("Home daily bonus available state must render CTA with image label", homeLayout.contains("@drawable/label_claim_bonus"))
        assertTrue("Home daily bonus CTA must sit on a dedicated bitmap claim plate", homeLayout.contains("@+id/dailyBonusClaimPlate") && homeLayout.contains("@drawable/btn_bonus_claim_default") && homeLayout.indexOf("@+id/dailyBonusClaimPlate") < homeLayout.indexOf("@+id/dailyBonusStatusText"))
        assertTrue("Landscape home daily bonus CTA must sit on the same bitmap claim plate", homeLandscapeLayout.contains("@+id/dailyBonusClaimPlate") && homeLandscapeLayout.contains("@drawable/btn_bonus_claim_default") && homeLandscapeLayout.indexOf("@+id/dailyBonusClaimPlate") < homeLandscapeLayout.indexOf("@+id/dailyBonusStatusText"))
        assertTrue("HomeFragment must hide the claim plate with the ready label during cooldown", homeFragment.contains("binding.dailyBonusClaimPlate.visibility = binding.dailyBonusStatusText.visibility"))
        assertTrue("Home daily bonus cooldown must render a dedicated slot HUD rail", homeLayout.contains("@+id/dailyBonusCountdownRail") && homeLayout.contains("@drawable/daily_bonus_countdown_rail") && !homeLayout.substringAfter("@+id/dailyBonusCountdownRail").substringBefore("</FrameLayout>").contains("@drawable/active_lines_badge"))
        assertTrue("Home daily bonus cooldown charge must render as a decorative image behind the rail in both orientations", homeLayout.contains("@+id/dailyBonusCountdownCharge") && homeLayout.contains("@drawable/daily_bonus_countdown_charge") && homeLayout.split("@+id/dailyBonusCountdownCharge", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && homeLayout.indexOf("@+id/dailyBonusCountdownCharge") < homeLayout.indexOf("@drawable/daily_bonus_countdown_rail") && homeLandscapeLayout.contains("@+id/dailyBonusCountdownCharge") && homeLandscapeLayout.contains("@drawable/daily_bonus_countdown_charge") && homeLandscapeLayout.split("@+id/dailyBonusCountdownCharge", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && homeLandscapeLayout.indexOf("@+id/dailyBonusCountdownCharge") < homeLandscapeLayout.indexOf("@drawable/daily_bonus_countdown_rail"))
        assertTrue("Home daily bonus cooldown must preserve the large wait status while giving digits enough width", homeLayout.contains("android:layout_width=\"184dp\"") && homeLayout.contains("@+id/dailyBonusCountdownDigits") && homeLayout.contains("android:layout_width=\"118dp\""))
        assertTrue("Home daily bonus cooldown must render a live bitmap countdown rail", homeLayout.contains("@+id/dailyBonusCountdownRail") && homeLayout.contains("@+id/dailyBonusCountdownDigits") && homeFragment.contains("startDailyBonusCountdown") && homeFragment.contains("dailyBonusCountdownTickDelayMs()"))
        assertTrue("Home daily bonus countdown charge must shimmer from countdown ticks while respecting reduced motion", homeFragment.contains("bindDailyBonusCountdownCharge(cooldown)") && homeFragment.contains("DAILY_BONUS_COUNTDOWN_CHARGE_PHASES") && homeFragment.contains("DAILY_BONUS_COUNTDOWN_CHARGE_LOW_ALPHA") && homeFragment.contains("!ValueAnimator.areAnimatorsEnabled()") && homeFragment.contains("DAILY_BONUS_COUNTDOWN_CHARGE_SETTLED_ALPHA") && homeFragment.contains("translationX = 0f") && homeFragment.contains("resetDailyBonusCountdownCharge()") && !homeFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Home daily bonus countdown must tick every second only while visible and render HH:MM:SS", homeFragment.contains("DAILY_BONUS_COUNTDOWN_VISIBLE_TICK_MS = 1_000L") && homeFragment.contains("DAILY_BONUS_COUNTDOWN_BACKGROUND_TICK_MS = 15_000L") && homeFragment.contains("isDailyBonusCountdownRailVisible") && homeFragment.contains("getGlobalVisibleRect") && homeFragment.contains("DailyBonusCountdownFormatter.format") && homeFragment.contains("cooldown.seconds") && homeFragment.contains("fixedGlyphBaseWidthDp = 12f"))
        assertTrue("Home daily bonus countdown must stop when the home screen stops", homeFragment.substringAfter("override fun onStop()").substringBefore("super.onStop()").contains("stopDailyBonusCountdown()"))
        assertTrue("Home daily bonus action target must expose throttled cooldown accessibility", homeLayout.split("@+id/dailyBonusButton", limit = 2)[1].substringBefore("@+id/dailyBonusImage").contains("android:importantForAccessibility=\"yes\"") && homeFragment.contains("dailyBonusAccessibilityBucket") && homeFragment.contains("bindDailyBonusCountdownAccessibility") && homeFragment.contains("binding.dailyBonusButton.contentDescription = cooldownDescription"))
        assertTrue("Home daily bonus CTA must not be assigned via TextView text", !homeFragment.contains("dailyBonusStatusText.text"))
        assertTrue("Home daily bonus CTA must not be styled as TextView text", !homeFragment.contains("dailyBonusStatusText.setTextColor"))

        val bonusDialog = Path.of("src/main/res/layout/dialog_bonus.xml").readText()
        val bonusDialogLandscape = Path.of("src/main/res/layout-w600dp-land/dialog_bonus.xml").readText()
        val bonusDialogFragment = Path.of("src/main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt").readText()
        val countdownFormatter = Path.of("src/main/java/com/vslot/app/ui/DailyBonusCountdownFormatter.kt").readText()
        assertTrue("Daily bonus body must default to image label", bonusDialog.contains("@drawable/label_bonus_ready_body"))
        assertTrue("Daily bonus dialog must use a premium imagegen dedicated image panel", bonusDialog.contains("@drawable/daily_bonus_modal_panel_premium") && !bonusDialog.contains("@drawable/modal_panel\""))
        assertTrue("Daily bonus cooldown timer must render through bitmap digits", bonusDialog.contains("@+id/bonusCooldownTimerRail") && bonusDialog.contains("@+id/bonusCooldownTimerDigits") && bonusDialog.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("Daily bonus wait modal must label the countdown with image copy and compact bitmap glyphs", bonusDialog.contains("@drawable/label_daily_bonus_timer") && bonusDialog.contains("android:layout_width=\"244dp\"") && bonusDialog.contains("@+id/bonusCooldownTimerDigits") && bonusDialog.contains("android:layout_width=\"128dp\"") && bonusDialogFragment.contains("fixedGlyphBaseWidthDp = 13.2f"))
        assertTrue("Daily bonus wait modal must use its own countdown rail image instead of slot line UI", bonusDialog.contains("@drawable/daily_bonus_modal_countdown_rail") && !bonusDialog.substringAfter("@+id/bonusCooldownTimerRail").substringBefore("</FrameLayout>").contains("@drawable/active_lines_badge"))
        assertTrue("Dedicated daily bonus reward burst asset missing", Files.exists(drawableRoot.resolve("daily_bonus_reward_burst.webp")))
        assertTrue("Dedicated daily bonus cooldown vault asset missing", Files.exists(drawableRoot.resolve("daily_bonus_cooldown_vault.webp")))
        assertTrue("Dedicated daily bonus stage lattice asset missing", Files.exists(drawableRoot.resolve("daily_bonus_stage_lattice.webp")))
        assertTrue("Daily bonus reward polish must render from a dedicated image asset", bonusDialog.contains("@+id/bonusRewardOverlay") && bonusDialog.contains("@drawable/daily_bonus_reward_burst"))
        assertTrue("Daily bonus cooldown must render from a dedicated image asset", bonusDialog.contains("@+id/bonusCooldownOverlay") && bonusDialog.contains("@drawable/daily_bonus_cooldown_vault"))
        assertTrue("Daily bonus stage must render from a dedicated image asset", bonusDialog.contains("@+id/bonusStageLattice") && bonusDialog.contains("@drawable/daily_bonus_stage_lattice"))
        assertTrue("Daily bonus decorative overlays must stay outside accessibility traversal", bonusDialog.contains("bonusStageLattice") && bonusDialog.contains("bonusRewardOverlay") && bonusDialog.contains("bonusCooldownOverlay") && bonusDialog.split("bonusStageLattice", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && bonusDialog.split("bonusRewardOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && bonusDialog.split("bonusCooldownOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Daily bonus stage must sit above the panel and below overlays/content", bonusDialog.indexOf("@+id/bonusStageLattice") > bonusDialog.indexOf("@drawable/daily_bonus_modal_panel") && bonusDialog.indexOf("@+id/bonusStageLattice") < bonusDialog.indexOf("@+id/bonusRewardOverlay") && bonusDialog.indexOf("@+id/bonusStageLattice") < bonusDialog.indexOf("@+id/bonusCooldownOverlay") && bonusDialog.indexOf("@+id/bonusStageLattice") < bonusDialog.indexOf("@+id/bonusBadge"))
        assertTrue("Daily bonus body must keep accessibility text", bonusDialog.contains("android:contentDescription=\"@string/bonus_ready\""))
        assertTrue("Daily bonus body must swap images dynamically", bonusDialogFragment.contains("R.drawable.label_bonus_ready_body") && bonusDialogFragment.contains("R.drawable.label_bonus_wait_body"))
        assertTrue("Daily bonus wait dialog must show remaining cooldown from persisted timestamp", homeFragment.contains("lastDailyBonusTimestamp = latestPlayerState.lastDailyBonusTimestamp") && bonusDialogFragment.contains("ARG_LAST_DAILY_BONUS_TIMESTAMP") && bonusDialogFragment.contains("dailyBonusCooldown(lastDailyBonusTimestamp)") && bonusDialogFragment.contains("DailyBonusCountdownFormatter.format(lastDailyBonusTimestamp)") && countdownFormatter.contains("PlayerState.dailyBonusRemainingMs(lastDailyBonusTimestamp, now)"))
        assertTrue("Daily bonus wait dialog countdown must update while open", bonusDialogFragment.contains("cooldownTimerJob") && bonusDialogFragment.contains("BONUS_COUNTDOWN_TICK_MS") && bonusDialogFragment.contains("updateCooldownTimer(binding, lastDailyBonusTimestamp)"))
        assertTrue("Daily bonus wait dialog countdown must tick every second, render seconds, and switch to claim-ready when elapsed", bonusDialogFragment.contains("BONUS_COUNTDOWN_TICK_MS = 1_000L") && bonusDialogFragment.contains("cooldown.seconds") && bonusDialogFragment.contains("cooldown.isReady") && bonusDialogFragment.contains("renderClaimState(enabled = true)"))
        assertTrue("Daily bonus successful claim must show a persisted confirmation and the next cooldown", bonusDialogFragment.contains("var activeLastDailyBonusTimestamp") && bonusDialogFragment.contains("import kotlinx.coroutines.flow.first") && bonusDialogFragment.contains("activeLastDailyBonusTimestamp = AppGraph.playerRepository.playerState") && bonusDialogFragment.contains("renderClaimSuccess(result.amount)") && bonusDialogFragment.contains("R.string.bonus_claimed") && bonusDialogFragment.contains("STATE_CLAIMED_AMOUNT") && bonusDialogFragment.contains("bindCooldownTimer(binding, false"))
        assertTrue("Daily bonus claim action must ignore rapid repeat taps while the claim is running", bonusDialogFragment.contains("var claimInProgress = false") && bonusDialogFragment.contains("if (claimInProgress) return@setOnClickListener") && bonusDialogFragment.contains("claimInProgress = true") && bonusDialogFragment.contains("binding.claimButton.isEnabled = false"))
        assertTrue("Daily bonus claim must retry transient storage I/O, restore its CTA, and expose a visible persistent failure", bonusDialogFragment.contains("retryTransientPersistenceIo") && bonusDialogFragment.contains("catch (_: IOException)") && bonusDialogFragment.contains("if (dialogUiActive)") && bonusDialogFragment.contains("renderClaimState(enabled = true)") && bonusDialogFragment.contains("R.string.persistence_save_error_retry") && bonusDialogFragment.contains("View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        assertTrue("Daily bonus claim coroutine must not touch dialog UI after the view is destroyed", bonusDialogFragment.contains("private var dialogUiActive = false") && bonusDialogFragment.contains("dialogUiActive = true") && bonusDialogFragment.contains("if (!dialogUiActive) return@launch") && bonusDialogFragment.contains("dialogUiActive = false"))
        assertTrue("Daily bonus cooldown timer must use throttled Russian accessibility text", strings.contains("daily_bonus_cooldown_remaining_accessibility") && strings.contains("daily_bonus_cooldown_remaining_seconds_accessibility") && bonusDialogFragment.contains("cooldownAccessibilityBucket") && bonusDialogFragment.contains("R.string.daily_bonus_cooldown_remaining_accessibility"))
        assertTrue("Daily bonus stage polish must be finite, managed, and respect disabled system animators", bonusDialogFragment.contains("animateBonusStage") && bonusDialogFragment.contains("binding.bonusStageLattice") && bonusDialogFragment.contains("BONUS_STAGE_SETTLED_ALPHA") && bonusDialogFragment.contains("bonusStageAnimator") && bonusDialogFragment.contains("bonusStageAnimator?.cancel()") && bonusDialogFragment.contains("ValueAnimator.areAnimatorsEnabled()") && !bonusDialogFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Daily bonus reward polish must be finite, managed, and respect disabled system animators", bonusDialogFragment.contains("animateBonusRewardPolish") && bonusDialogFragment.contains("bonusRewardAnimator") && bonusDialogFragment.contains("bonusRewardAnimator?.cancel()") && bonusDialogFragment.contains("bonusCooldownAnimator?.cancel()") && bonusDialogFragment.contains("ValueAnimator.areAnimatorsEnabled()") && !bonusDialogFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Daily bonus reward burst must settle as a visible image layer", bonusDialogFragment.contains("BONUS_REWARD_SETTLED_ALPHA"))
        assertTrue("Daily bonus cooldown polish must be finite, managed, and image based", bonusDialogFragment.contains("animateBonusCooldownPolish") && bonusDialogFragment.contains("BONUS_COOLDOWN_SETTLED_ALPHA") && bonusDialogFragment.contains("binding.bonusCooldownOverlay") && bonusDialogFragment.contains("bonusCooldownAnimator") && bonusDialogFragment.contains("bonusCooldownAnimator?.cancel()") && bonusDialogFragment.contains("bonusRewardAnimator?.cancel()"))
        assertTrue("Daily bonus cooldown polish must animate existing image badge/button/timer only", bonusDialog.contains("@+id/bonusBadge") && bonusDialogFragment.contains("binding.bonusBadge") && bonusDialogFragment.contains("binding.claimButton") && bonusDialogFragment.contains("binding.bonusCooldownTimerRail"))
        assertTrue("Daily bonus body must not be assigned as TextView text", !bonusDialogFragment.contains("bonusBody.text"))
        assertTrue("Daily bonus body must expose hidden scalable ready text", bonusDialog.contains("@+id/bonusBodyLargeText") && bonusDialog.contains("android:text=\"@string/bonus_ready\""))
        assertTrue("Daily bonus body must not render wait text as android:text", !bonusDialog.contains("android:text=\"@string/bonus_wait\""))
        assertTrue("Landscape daily bonus dialog must keep every binding control", listOf("@+id/bonusStageLattice", "@+id/bonusRewardOverlay", "@+id/bonusCooldownOverlay", "@+id/bonusBadge", "@+id/bonusTitle", "@+id/bonusBody", "@+id/bonusCooldownTimerRail", "@+id/bonusCooldownTimerDigits", "@+id/claimButton", "@+id/claimButtonLabel", "@+id/bonusCloseButton").all { bonusDialogLandscape.contains(it) })
        assertTrue("Landscape daily bonus dialog must keep image-first copy, scalable body, and bitmap digits", Regex("<TextView").findAll(bonusDialogLandscape).count() == 1 && bonusDialogLandscape.contains("@+id/bonusBodyLargeText") && bonusDialogLandscape.contains("@drawable/title_daily_bonus") && bonusDialogLandscape.contains("@drawable/label_bonus_ready_body") && bonusDialogLandscape.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("Landscape daily bonus dialog must use a growing compact horizontal composition with safe tap targets", bonusDialogLandscape.contains("android:orientation=\"horizontal\"") && bonusDialogLandscape.contains("android:minHeight=\"300dp\"") && bonusDialogLandscape.contains("android:layout_width=\"48dp\"") && bonusDialogLandscape.contains("android:layout_height=\"48dp\"") && bonusDialogLandscape.contains("android:layout_height=\"54dp\""))
        assertTrue("Landscape daily bonus dialog must keep the dedicated image polish layers", bonusDialogLandscape.indexOf("@+id/bonusStageLattice") > bonusDialogLandscape.indexOf("@drawable/daily_bonus_modal_panel") && bonusDialogLandscape.indexOf("@+id/bonusStageLattice") < bonusDialogLandscape.indexOf("@+id/bonusRewardOverlay") && bonusDialogLandscape.contains("@drawable/daily_bonus_reward_burst") && bonusDialogLandscape.contains("@drawable/daily_bonus_cooldown_vault") && bonusDialogLandscape.contains("@drawable/daily_bonus_modal_countdown_rail"))
    }

    @Test
    fun `home slot cards use resource backed accessibility while rendering image cards`() {
        val homeLayout = Path.of("src/main/res/layout/fragment_home.xml").readText()
        val homeLandLayout = Path.of("src/main/res/layout-w600dp-land/fragment_home.xml").readText()
        val homeFragment = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()
        val slotUnlockRules = Path.of("src/main/java/com/vslot/app/ui/home/SlotUnlockRules.kt").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val primaryHomeCardGenerator = Path.of("../tools/generate_primary_home_slot_cards.py").readText()
        val landscapeHomeCardGenerator = Path.of("../tools/generate_home_landscape_slot_cards.py").readText()
        val slotLockGenerator = Path.of("../tools/generate_slot_lock_assets.py").readText()
        val homeScrollCueGenerator = Path.of("../tools/generate_home_scroll_cue_assets.py").readText()
        val homeLockedPulseGenerator = Path.of("../tools/slice_imagegen_home_locked_slot_pulse.py").readText()
        val homeUnlockBurstGenerator = Path.of("../tools/slice_imagegen_home_slot_unlock_burst.py").readText()
        val violetCardSelector = Path.of("src/main/res/drawable/slot_card_violet_fortune_selector.xml").readText()
        val romanCardSelector = Path.of("src/main/res/drawable/slot_card_roman_reels_selector.xml").readText()
        val neonCardSelector = Path.of("src/main/res/drawable/slot_card_neon_nights_selector.xml").readText()
        val pharaohCardSelector = Path.of("src/main/res/drawable/slot_card_pharaoh_gold_selector.xml").readText()
        val oceanCardSelector = Path.of("src/main/res/drawable/slot_card_ocean_pearl_selector.xml").readText()
        val violetLandCardSelector = Path.of("src/main/res/drawable/slot_card_violet_fortune_land_selector.xml").readText()
        val romanLandCardSelector = Path.of("src/main/res/drawable/slot_card_roman_reels_land_selector.xml").readText()
        val neonLandCardSelector = Path.of("src/main/res/drawable/slot_card_neon_nights_land_selector.xml").readText()
        val pharaohLandCardSelector = Path.of("src/main/res/drawable/slot_card_pharaoh_gold_land_selector.xml").readText()
        val oceanLandCardSelector = Path.of("src/main/res/drawable/slot_card_ocean_pearl_land_selector.xml").readText()
        val privacySelector = Path.of("src/main/res/drawable/btn_privacy_selector.xml").readText()
        val slotCardAssets = listOf(
            "slot_card_violet_fortune_default.webp",
            "slot_card_violet_fortune_pressed.webp",
            "slot_card_roman_reels_default.webp",
            "slot_card_roman_reels_pressed.webp",
            "slot_card_neon_nights_default.webp",
            "slot_card_neon_nights_pressed.webp",
            "slot_card_pharaoh_gold_default.webp",
            "slot_card_pharaoh_gold_pressed.webp",
            "slot_card_ocean_pearl_default.webp",
            "slot_card_ocean_pearl_pressed.webp"
        )
        val landscapeSlotCardAssets = listOf(
            "slot_card_violet_fortune_land_default.webp",
            "slot_card_violet_fortune_land_pressed.webp",
            "slot_card_roman_reels_land_default.webp",
            "slot_card_roman_reels_land_pressed.webp",
            "slot_card_neon_nights_land_default.webp",
            "slot_card_neon_nights_land_pressed.webp",
            "slot_card_pharaoh_gold_land_default.webp",
            "slot_card_pharaoh_gold_land_pressed.webp",
            "slot_card_ocean_pearl_land_default.webp",
            "slot_card_ocean_pearl_land_pressed.webp"
        )
        val lockOverlayAssets = listOf(
            "slot_card_lock_level_2.webp",
            "slot_card_lock_level_3.webp",
            "slot_card_lock_level_4.webp"
        )
        val lockPulseAssets = listOf(
            "home_locked_slot_pulse.webp",
            "home_locked_slot_pulse_land.webp"
        )
        val unlockBurstAssets = listOf(
            "home_slot_unlock_burst.webp",
            "home_slot_unlock_burst_land.webp"
        )
        val homeScrollCueAssets = listOf(
            "home_scroll_bottom_veil.webp",
            "home_scroll_right_veil.webp"
        )
        val missingSlotCards = slotCardAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinySlotCards = slotCardAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 35_000 }
        val missingLandscapeSlotCards = landscapeSlotCardAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyLandscapeSlotCards = landscapeSlotCardAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 70_000 }
        val missingLockOverlays = lockOverlayAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyLockOverlays = lockOverlayAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 50_000 }
        val missingLockPulses = lockPulseAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyLockPulses = lockPulseAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 120_000 }
        val missingUnlockBursts = unlockBurstAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyUnlockBursts = unlockBurstAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 180_000 }
        val missingHomeScrollCues = homeScrollCueAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyHomeScrollCues = homeScrollCueAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 5_000 }
        val oversizedPrimaryHomeCards = listOf(
            "slot_card_violet_fortune_default.webp",
            "slot_card_roman_reels_default.webp"
        ).filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) > 180_000 }
        val privacyButtonAssets = listOf("btn_privacy_default.webp", "btn_privacy_pressed.webp", "btn_privacy_default_premium.webp", "btn_privacy_pressed_premium.webp")
        val tinyPrivacyButtonAssets = privacyButtonAssets.filter {
            Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 10_000
        }
        assertTrue("Violet card must use Russian action accessibility", homeLayout.contains("android:contentDescription=\"@string/home_play_violet_slot\"") && strings.contains("home_play_violet_slot\">Играть в Фиолетовую Фортуну"))
        assertTrue("Roman card must use Russian action accessibility", homeLayout.contains("android:contentDescription=\"@string/home_play_roman_slot\"") && strings.contains("home_play_roman_slot\">Играть в Римские барабаны"))
        assertTrue("New slot cards must use Russian action accessibility", homeLayout.contains("android:contentDescription=\"@string/home_play_neon_slot\"") && homeLayout.contains("android:contentDescription=\"@string/home_play_pharaoh_slot\"") && homeLayout.contains("android:contentDescription=\"@string/home_play_ocean_slot\"") && strings.contains("home_play_neon_slot\">Играть в Неоновые ночи") && strings.contains("home_play_pharaoh_slot\">Играть в Золото фараона") && strings.contains("home_play_ocean_slot\">Играть в Океанскую жемчужину"))
        assertTrue("Home action cards must be exposed as full accessibility action targets", listOf("violetCard", "romanCard", "neonCard", "pharaohCard", "oceanCard").all { cardId -> homeLayout.split("@+id/$cardId", limit = 2)[1].substringBefore("</FrameLayout>").contains("android:importantForAccessibility=\"yes\"") })
        assertTrue("Missing home slot card image assets: $missingSlotCards", missingSlotCards.isEmpty())
        assertTrue("Home slot card image assets are unexpectedly tiny: $tinySlotCards", tinySlotCards.isEmpty())
        assertTrue("Missing landscape home slot card image assets: $missingLandscapeSlotCards", missingLandscapeSlotCards.isEmpty())
        assertTrue("Landscape home slot card image assets are unexpectedly tiny: $tinyLandscapeSlotCards", tinyLandscapeSlotCards.isEmpty())
        assertTrue("Missing home slot lock overlay assets: $missingLockOverlays", missingLockOverlays.isEmpty())
        assertTrue("Home slot lock overlay assets are unexpectedly tiny: $tinyLockOverlays", tinyLockOverlays.isEmpty())
        assertTrue("Home lock overlay generator must render Russian bitmap copy and a real lock graphic", slotLockGenerator.contains("ОТКРОЕТСЯ") && slotLockGenerator.contains("УРОВЕНЬ") && slotLockGenerator.contains("ИГРАЙТЕ, ЧТОБЫ ОТКРЫТЬ") && slotLockGenerator.contains("draw_lock"))
        assertTrue("Home lock overlay polish must keep diagonal texture behind a dense glass panel so underlying card copy does not compete", slotLockGenerator.indexOf("for offset in range(-120, 920, 172)") < slotLockGenerator.indexOf("(104, 30, 876, 592)") && slotLockGenerator.contains("fill=slot_assets.rgba((7, 9, 27), 224)") && slotLockGenerator.contains("(132, 58, 848, 564)"))
        assertTrue("Missing home locked slot pulse assets: $missingLockPulses", missingLockPulses.isEmpty())
        assertTrue("Home locked slot pulse assets are unexpectedly tiny: $tinyLockPulses", tinyLockPulses.isEmpty())
        assertTrue("Home locked pulse assets must retain fail-closed historical source and preview evidence", homeLockedPulseGenerator.contains("NONCANONICAL_HISTORICAL_SLICER") && homeLockedPulseGenerator.contains("vslot_home_locked_slot_pulse_imagegen.png") && homeLockedPulseGenerator.contains("vslot_home_locked_slot_pulse_land_imagegen.png") && homeLockedPulseGenerator.contains("remove_chroma_key") && homeLockedPulseGenerator.contains("home_locked_slot_pulse_contact_sheet.png") && Files.exists(Path.of("../qa/source/vslot_home_locked_slot_pulse_imagegen.png")) && Files.exists(Path.of("../qa/source/vslot_home_locked_slot_pulse_land_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/home_locked_slot_pulse_contact_sheet.png")))
        assertTrue("Missing home unlock burst assets: $missingUnlockBursts", missingUnlockBursts.isEmpty())
        assertTrue("Home unlock burst assets are unexpectedly tiny: $tinyUnlockBursts", tinyUnlockBursts.isEmpty())
        assertTrue("Home unlock burst assets must retain fail-closed historical source and preview evidence", homeUnlockBurstGenerator.contains("NONCANONICAL_HISTORICAL_SLICER") && homeUnlockBurstGenerator.contains("vslot_home_slot_unlock_burst_imagegen.png") && homeUnlockBurstGenerator.contains("remove_chroma_key") && homeUnlockBurstGenerator.contains("home_slot_unlock_burst_contact_sheet.png") && Files.exists(Path.of("../qa/source/vslot_home_slot_unlock_burst_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/home_slot_unlock_burst_contact_sheet.png")))
        assertTrue("Missing home image scroll cue assets: $missingHomeScrollCues", missingHomeScrollCues.isEmpty())
        assertTrue("Home image scroll cue assets are unexpectedly tiny: $tinyHomeScrollCues", tinyHomeScrollCues.isEmpty())
        assertTrue("Home scroll cue generator must render dedicated bitmap veils instead of XML gradients", homeScrollCueGenerator.contains("home_scroll_bottom_veil.webp") && homeScrollCueGenerator.contains("home_scroll_right_veil.webp") && homeScrollCueGenerator.contains("make_bottom_veil") && homeScrollCueGenerator.contains("make_right_veil"))
        assertTrue("Level-gated slots must render image lock overlays in portrait", homeLayout.contains("@+id/neonLockedOverlay") && homeLayout.contains("@drawable/slot_card_lock_level_2") && homeLayout.contains("@+id/pharaohLockedOverlay") && homeLayout.contains("@drawable/slot_card_lock_level_3") && homeLayout.contains("@+id/oceanLockedOverlay") && homeLayout.contains("@drawable/slot_card_lock_level_4"))
        assertTrue("Level-gated slots must render image lock overlays in landscape", homeLandLayout.contains("@+id/neonLockedOverlay") && homeLandLayout.contains("@drawable/slot_card_lock_level_2") && homeLandLayout.contains("@+id/pharaohLockedOverlay") && homeLandLayout.contains("@drawable/slot_card_lock_level_3") && homeLandLayout.contains("@+id/oceanLockedOverlay") && homeLandLayout.contains("@drawable/slot_card_lock_level_4"))
        assertTrue("Locked home slots must render dedicated image pulse layers in portrait", listOf("neon", "pharaoh", "ocean").all { id -> homeLayout.contains("@+id/${id}LockedPulse") && homeLayout.contains("@drawable/home_locked_slot_pulse") && homeLayout.indexOf("@+id/${id}LockedPulse") > homeLayout.indexOf("@+id/${id}CardShine") && homeLayout.indexOf("@+id/${id}LockedPulse") < homeLayout.indexOf("@+id/${id}LockedOverlay") })
        assertTrue("Locked home slots must render dedicated tall image pulse layers in landscape", listOf("neon", "pharaoh", "ocean").all { id -> homeLandLayout.contains("@+id/${id}LockedPulse") && homeLandLayout.contains("@drawable/home_locked_slot_pulse_land") && homeLandLayout.indexOf("@+id/${id}LockedPulse") > homeLandLayout.indexOf("@+id/${id}CardShine") && homeLandLayout.indexOf("@+id/${id}LockedPulse") < homeLandLayout.indexOf("@+id/${id}LockedOverlay") })
        assertTrue("Locked home pulse layers must stay decorative and not add duplicate screen-reader content", listOf("neonLockedPulse", "pharaohLockedPulse", "oceanLockedPulse").all { pulseId -> homeLayout.split("@+id/$pulseId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") && homeLandLayout.split("@+id/$pulseId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") && homeLayout.split("@+id/$pulseId", limit = 2)[1].substringBefore("/>").contains("android:contentDescription=\"@null\"") })
        assertTrue("Unlocked home slots must render dedicated image burst layers in portrait", listOf("neon", "pharaoh", "ocean").all { id -> homeLayout.contains("@+id/${id}UnlockBurst") && homeLayout.contains("@drawable/home_slot_unlock_burst") && homeLayout.indexOf("@+id/${id}UnlockBurst") > homeLayout.indexOf("@+id/${id}LockedPulse") && homeLayout.indexOf("@+id/${id}UnlockBurst") < homeLayout.indexOf("@+id/${id}LockedOverlay") })
        assertTrue("Unlocked home slots must render dedicated tall image burst layers in landscape", listOf("neon", "pharaoh", "ocean").all { id -> homeLandLayout.contains("@+id/${id}UnlockBurst") && homeLandLayout.contains("@drawable/home_slot_unlock_burst_land") && homeLandLayout.indexOf("@+id/${id}UnlockBurst") > homeLandLayout.indexOf("@+id/${id}LockedPulse") && homeLandLayout.indexOf("@+id/${id}UnlockBurst") < homeLandLayout.indexOf("@+id/${id}LockedOverlay") })
        assertTrue("Unlock burst layers must stay decorative and not add duplicate screen-reader content", listOf("neonUnlockBurst", "pharaohUnlockBurst", "oceanUnlockBurst").all { burstId -> homeLayout.split("@+id/$burstId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") && homeLandLayout.split("@+id/$burstId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") && homeLayout.split("@+id/$burstId", limit = 2)[1].substringBefore("/>").contains("android:contentDescription=\"@null\"") })
        assertTrue("Landscape home must fit two primary slot cards beside the right action rail without clipping", homeLandLayout.split("android:layout_width=\"248dp\"").size >= 6 && homeLandLayout.contains("android:layout_marginEnd=\"10dp\"") && homeLandLayout.contains("android:layout_width=\"310dp\"") && !homeLandLayout.contains("android:layout_width=\"256dp\"") && !homeLandLayout.contains("android:layout_width=\"330dp\""))
        assertTrue("Portrait home card list must use a compact state-driven image bottom veil", homeLayout.contains("@+id/homeSlotScrollView") && homeLayout.contains("@+id/homeScrollBottomVeil") && homeLayout.contains("@drawable/home_scroll_bottom_veil") && homeLayout.contains("android:paddingBottom=\"58dp\"") && homeLayout.contains("android:layout_height=\"44dp\"") && homeFragment.contains("bottomVeil.isVisible = view.canScrollVertically(1)") && homeLayout.indexOf("@+id/homeScrollBottomVeil") > homeLayout.indexOf("@+id/homeSlotScrollView") && homeLayout.split("@+id/homeScrollBottomVeil", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\""))
        assertTrue("Landscape home carousel must use an image right veil and trailing padding for partially visible cards", homeLandLayout.contains("@+id/homeSlotHorizontalScrollView") && homeLandLayout.contains("@+id/homeScrollRightVeil") && homeLandLayout.contains("@drawable/home_scroll_right_veil") && homeLandLayout.contains("android:paddingEnd=\"96dp\"") && homeLandLayout.indexOf("@+id/homeScrollRightVeil") > homeLandLayout.indexOf("@+id/homeSlotHorizontalScrollView") && homeLandLayout.split("@+id/homeScrollRightVeil", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\""))
        assertTrue("Home lock overlays must sit above card shine and stay decorative", listOf("neon", "pharaoh", "ocean").all { id -> homeLayout.indexOf("@+id/${id}LockedOverlay") > homeLayout.indexOf("@+id/${id}CardShine") } && listOf("neonLockedOverlay", "pharaohLockedOverlay", "oceanLockedOverlay").all { overlayId -> homeLayout.split("@+id/$overlayId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") })
        assertTrue("Home must gate new slots through tested level rules instead of always navigating", homeFragment.contains("openSlotIfUnlocked") && homeFragment.contains("bindSlotUnlockState") && homeFragment.contains("pulseLockedSlot") && homeFragment.contains("slotLockOverlayDrawable") && homeFragment.contains("SlotUnlockRules.NEON_NIGHTS") && homeFragment.contains("SlotUnlockRules.PHARAOH_GOLD") && homeFragment.contains("SlotUnlockRules.OCEAN_PEARL") && slotUnlockRules.contains("NEON_NIGHTS to 2") && slotUnlockRules.contains("PHARAOH_GOLD to 3") && slotUnlockRules.contains("OCEAN_PEARL to 4"))
        assertTrue("Locked slot tap feedback must animate the image pulse finitely and respect disabled system animators", homeFragment.contains("lockedPulseForSlot") && homeFragment.contains("pulseLockedSlotImage") && homeFragment.contains("HOME_LOCKED_IMAGE_PULSE_UP_MS") && homeFragment.contains("HOME_LOCKED_IMAGE_PULSE_DOWN_MS") && homeFragment.contains("HOME_LOCKED_PULSE_SETTLED_ALPHA") && homeFragment.contains("HOME_LOCKED_PULSE_PEAK_ALPHA") && homeFragment.contains("stopLockedSlotPulseAnimations") && homeFragment.contains("ValueAnimator.areAnimatorsEnabled()") && !homeFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Home level unlocks must animate newly opened slots without replaying on first bind", homeFragment.contains("lastObservedPlayerLevel") && homeFragment.contains("animateNewlyUnlockedSlots(state.playerLevel)") && homeFragment.contains("previousLevel == null") && homeFragment.contains("SlotUnlockRules.slotsUnlockedBetween(previousLevel, playerLevel)") && slotUnlockRules.contains("fun slotsUnlockedBetween(previousLevel: Int, currentLevel: Int)") && slotUnlockRules.contains("linkedMapOf"))
        assertTrue("Unlocked slot burst feedback must animate image layers finitely and clean up lifecycle state", homeFragment.contains("unlockBurstForSlot") && homeFragment.contains("pulseSlotUnlock") && homeFragment.contains("HOME_UNLOCK_BURST_DURATION_MS") && homeFragment.contains("HOME_UNLOCK_BURST_STAGGER_MS") && homeFragment.contains("unlockBurstAnimators") && homeFragment.contains("stopUnlockBurstAnimations") && homeFragment.contains("ValueAnimator.areAnimatorsEnabled()") && !homeFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Locked slot accessibility must stay Russian and explain the required level without grammatical gender", strings.contains("slot_locked_until_level\">Доступно с уровня %2\$d: %1\$s") && homeFragment.contains("R.string.slot_locked_until_level"))
        assertTrue("Primary home card generator must render old slots with the same title, symbol strip, and CTA card style as the new slots", primaryHomeCardGenerator.contains("slot_card_violet_fortune") && primaryHomeCardGenerator.contains("slot_card_roman_reels") && primaryHomeCardGenerator.contains("ФИОЛЕТОВАЯ ФОРТУНА") && primaryHomeCardGenerator.contains("РИМСКИЕ БАРАБАНЫ") && primaryHomeCardGenerator.contains("PRIMARY_HOME_CARD_SYMBOLS") && primaryHomeCardGenerator.contains("slot_assets.draw_card"))
        assertTrue("Landscape home card generator must render dedicated tall reel-grid cards instead of stretching wide cards", landscapeHomeCardGenerator.contains("draw_landscape_card") && landscapeHomeCardGenerator.contains("w, h = 720, 980") && landscapeHomeCardGenerator.contains("draw_symbol_grid") && landscapeHomeCardGenerator.contains("10 ЛИНИЙ") && landscapeHomeCardGenerator.contains("ФРИСПИНЫ") && landscapeHomeCardGenerator.contains("PRIMARY_HOME_CARD_THEMES"))
        assertTrue("Primary home cards must be refreshed title/CTA bitmap cards, not oversized old reel-preview exports: $oversizedPrimaryHomeCards", oversizedPrimaryHomeCards.isEmpty())
        assertTrue("Violet home card must render from pressed/default image selector", homeLayout.contains("@drawable/slot_card_violet_fortune_selector") && violetCardSelector.contains("@drawable/slot_card_violet_fortune_default") && violetCardSelector.contains("@drawable/slot_card_violet_fortune_pressed"))
        assertTrue("Roman home card must render from pressed/default image selector", homeLayout.contains("@drawable/slot_card_roman_reels_selector") && romanCardSelector.contains("@drawable/slot_card_roman_reels_default") && romanCardSelector.contains("@drawable/slot_card_roman_reels_pressed"))
        assertTrue("New home cards must render from pressed/default image selectors", homeLayout.contains("@drawable/slot_card_neon_nights_selector") && neonCardSelector.contains("@drawable/slot_card_neon_nights_default") && neonCardSelector.contains("@drawable/slot_card_neon_nights_pressed") && homeLayout.contains("@drawable/slot_card_pharaoh_gold_selector") && pharaohCardSelector.contains("@drawable/slot_card_pharaoh_gold_default") && pharaohCardSelector.contains("@drawable/slot_card_pharaoh_gold_pressed") && homeLayout.contains("@drawable/slot_card_ocean_pearl_selector") && oceanCardSelector.contains("@drawable/slot_card_ocean_pearl_default") && oceanCardSelector.contains("@drawable/slot_card_ocean_pearl_pressed"))
        assertTrue("Landscape home must use dedicated tall image selectors instead of wide card selectors", homeLandLayout.contains("@drawable/slot_card_violet_fortune_land_selector") && homeLandLayout.contains("@drawable/slot_card_roman_reels_land_selector") && homeLandLayout.contains("@drawable/slot_card_neon_nights_land_selector") && homeLandLayout.contains("@drawable/slot_card_pharaoh_gold_land_selector") && homeLandLayout.contains("@drawable/slot_card_ocean_pearl_land_selector") && !homeLandLayout.contains("@drawable/slot_card_violet_fortune_selector") && !homeLandLayout.contains("@drawable/slot_card_roman_reels_selector") && !homeLandLayout.contains("@drawable/slot_card_neon_nights_selector") && !homeLandLayout.contains("@drawable/slot_card_pharaoh_gold_selector") && !homeLandLayout.contains("@drawable/slot_card_ocean_pearl_selector"))
        assertTrue("Landscape home card selectors must point to pressed/default tall image assets", violetLandCardSelector.contains("@drawable/slot_card_violet_fortune_land_default") && violetLandCardSelector.contains("@drawable/slot_card_violet_fortune_land_pressed") && romanLandCardSelector.contains("@drawable/slot_card_roman_reels_land_default") && romanLandCardSelector.contains("@drawable/slot_card_roman_reels_land_pressed") && neonLandCardSelector.contains("@drawable/slot_card_neon_nights_land_default") && neonLandCardSelector.contains("@drawable/slot_card_neon_nights_land_pressed") && pharaohLandCardSelector.contains("@drawable/slot_card_pharaoh_gold_land_default") && pharaohLandCardSelector.contains("@drawable/slot_card_pharaoh_gold_land_pressed") && oceanLandCardSelector.contains("@drawable/slot_card_ocean_pearl_land_default") && oceanLandCardSelector.contains("@drawable/slot_card_ocean_pearl_land_pressed"))
        assertTrue("Home card shine image asset missing", Files.exists(drawableRoot.resolve("home_card_shine.webp")))
        assertTrue("Home card shine image asset is unexpectedly tiny", Files.size(drawableRoot.resolve("home_card_shine.webp")) > 1_000)
        assertTrue("Violet home card aura image asset missing", Files.exists(drawableRoot.resolve("home_violet_card_aura.webp")))
        assertTrue("Roman home card aura image asset missing", Files.exists(drawableRoot.resolve("home_roman_card_aura.webp")))
        assertTrue("New home card aura image assets missing", Files.exists(drawableRoot.resolve("home_nn_card_aura.webp")) && Files.exists(drawableRoot.resolve("home_pg_card_aura.webp")) && Files.exists(drawableRoot.resolve("home_op_card_aura.webp")))
        assertTrue("Home card aura image assets are unexpectedly tiny", listOf("home_violet_card_aura.webp", "home_roman_card_aura.webp", "home_nn_card_aura.webp", "home_pg_card_aura.webp", "home_op_card_aura.webp").all { Files.size(drawableRoot.resolve(it)) > 1_000 })
        assertTrue("Violet card must render dedicated image aura overlay", homeLayout.contains("@+id/violetCardAura") && homeLayout.contains("@drawable/home_violet_card_aura"))
        assertTrue("Roman card must render dedicated image aura overlay", homeLayout.contains("@+id/romanCardAura") && homeLayout.contains("@drawable/home_roman_card_aura"))
        assertTrue("New cards must render dedicated image aura overlays", homeLayout.contains("@+id/neonCardAura") && homeLayout.contains("@drawable/home_nn_card_aura") && homeLayout.contains("@+id/pharaohCardAura") && homeLayout.contains("@drawable/home_pg_card_aura") && homeLayout.contains("@+id/oceanCardAura") && homeLayout.contains("@drawable/home_op_card_aura"))
        assertTrue("Home card aura overlays must stay decorative", listOf("violetCardAura", "romanCardAura", "neonCardAura", "pharaohCardAura", "oceanCardAura").all { auraId -> homeLayout.split("@+id/$auraId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") })
        assertTrue("Violet card must render image shine overlay", homeLayout.contains("@+id/violetCardShine") && homeLayout.contains("@drawable/home_card_shine"))
        assertTrue("Roman card must render image shine overlay", homeLayout.contains("@+id/romanCardShine") && homeLayout.contains("@drawable/home_card_shine"))
        assertTrue("New cards must render image shine overlays", homeLayout.contains("@+id/neonCardShine") && homeLayout.contains("@+id/pharaohCardShine") && homeLayout.contains("@+id/oceanCardShine") && homeLayout.split("@drawable/home_card_shine").size >= 7)
        assertTrue("Home card CTA and titles must be embedded in bitmap cards instead of plain XML text", !homeLayout.contains("android:text=\"@string/play_action\"") && !homeLayout.contains("@drawable/label_play_action") && slotCardAssets.all { Files.size(drawableRoot.resolve(it)) > 35_000 } && landscapeSlotCardAssets.all { Files.size(drawableRoot.resolve(it)) > 70_000 })
        assertTrue("Home card art must inherit parent pressed state", listOf("violetCard", "romanCard", "neonCard", "pharaohCard", "oceanCard").all { cardId -> homeLayout.split("@+id/$cardId", limit = 2)[1].substringBefore("</FrameLayout>").contains("android:duplicateParentState=\"true\"") })
        assertTrue("Daily bonus strip must reuse the image shine overlay", homeLayout.contains("@+id/dailyBonusShine") && homeLayout.contains("@drawable/home_card_shine"))
        assertTrue("Home privacy button must render from image selector", homeLayout.contains("@+id/privacyButton") && homeLayout.contains("@drawable/btn_privacy_selector"))
        assertTrue("Home privacy row must expose the full strip as the tap target", homeLayout.split("@+id/privacyButton", limit = 2)[1].contains("android:clickable=\"true\"") && homeLayout.split("@+id/privacyButton", limit = 2)[1].contains("android:focusable=\"true\"") && homeLayout.split("@+id/privacyButton", limit = 2)[1].contains("android:importantForAccessibility=\"yes\"") && homeLayout.split("@+id/privacyButton", limit = 2)[1].contains("android:duplicateParentState=\"true\""))
        assertTrue("Home screen must add compact spacing after the activity applies Android navigation insets", homeLayout.contains("android:paddingBottom=\"16dp\""))
        assertTrue("Premium imagegen privacy default button asset is too flat or tiny", Files.size(drawableRoot.resolve("btn_privacy_default_premium.webp")) > 80_000)
        assertTrue("Premium imagegen privacy pressed button asset is too flat or tiny", Files.size(drawableRoot.resolve("btn_privacy_pressed_premium.webp")) > 80_000)
        assertTrue("Premium imagegen privacy button assets must preserve 700x150 geometry", readBitmapSize(drawableRoot.resolve("btn_privacy_default_premium.webp")) == BitmapSize(700, 150) && readBitmapSize(drawableRoot.resolve("btn_privacy_pressed_premium.webp")) == BitmapSize(700, 150))
        assertTrue("Privacy image selector must use premium default and pressed image assets", privacySelector.contains("@drawable/btn_privacy_default_premium") && privacySelector.contains("@drawable/btn_privacy_pressed_premium"))
        assertTrue("Privacy button image assets are unexpectedly tiny: $tinyPrivacyButtonAssets", tinyPrivacyButtonAssets.isEmpty())
        assertTrue("Privacy visible label must stay as an image overlay", homeLayout.contains("@drawable/label_privacy_policy") && !homeLayout.contains("android:text=\"@string/privacy_policy\""))
        assertTrue("Violet slot name string missing", strings.contains("slot_violet_fortune"))
        assertTrue("Roman slot name string missing", strings.contains("slot_roman_reels"))
        assertTrue("New slot name strings missing", strings.contains("slot_neon_nights") && strings.contains("slot_pharaoh_gold") && strings.contains("slot_ocean_pearl"))
        assertTrue("Home slot analytics labels must come from string resources", listOf("slot_violet_fortune", "slot_roman_reels", "slot_neon_nights", "slot_pharaoh_gold", "slot_ocean_pearl").all { homeFragment.contains("getString(R.string.$it)") })
        assertTrue("HomeFragment must animate and clean up image shine overlays", homeFragment.contains("startHomeShineAnimations()") && homeFragment.contains("stopHomeShineAnimations()") && homeFragment.contains("HOME_SHINE_DURATION_MS"))
        assertTrue("HomeFragment must animate and clean up image aura overlays", homeFragment.contains("startHomeAuraAnimations()") && homeFragment.contains("stopHomeAuraAnimations()") && homeFragment.contains("HOME_AURA_DURATION_MS"))
        assertTrue("Home shine animation must respect disabled system animators", homeFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        val homeShineAnimation = homeFragment.substringAfter("private fun startHomeShineAnimations").substringBefore("private fun startHomeAuraAnimations")
        val homeAuraAnimation = homeFragment.substringAfter("private fun startHomeAuraAnimations").substringBefore("private fun homeAuraViews")
        assertTrue("Home visual animations must use staged finite sweeps, not permanent idle loops", !homeShineAnimation.contains("while (true)") && !homeAuraAnimation.contains("while (true)") && !homeFragment.contains("ValueAnimator.INFINITE"))
        val levelProgressAssets = listOf(
            "level_progress_track_glow.webp",
            "level_progress_milestones.webp",
            "level_progress_cap.webp",
            "level_progress_pulse.webp",
            "home_xp_readout_plate.webp"
        )
        assertTrue("Home level XP polish image assets missing", levelProgressAssets.all { Files.exists(drawableRoot.resolve(it)) })
        assertTrue("Home level XP polish image assets are unexpectedly tiny", levelProgressAssets.all { Files.size(drawableRoot.resolve(it)) > 1_000 })
        assertTrue(
            "Home level XP progress must render as layered dynamic images, not a standard progress widget",
            homeLayout.contains("@+id/homeXpTrack") &&
                homeLayout.split("@+id/homeXpTrack", limit = 2)[1].substringBefore(">").contains("android:layout_width=\"0dp\"") &&
                homeLayout.contains("app:layout_constraintStart_toEndOf=\"@id/homeXpLabel\"") &&
                homeLayout.contains("app:layout_constraintEnd_toStartOf=\"@id/homeXpReadoutPlate\"") &&
                homeLandLayout.split("@+id/homeBalancePanel", limit = 2)[1]
                    .substringBefore(">")
                    .contains("android:layout_width=\"0dp\"") &&
                homeLandLayout.split("@+id/homeBalancePanel", limit = 2)[1]
                    .substringBefore(">")
                    .contains("android:layout_weight=\"0.45\"") &&
                homeLandLayout.split("@+id/homeLevelPanel", limit = 2)[1]
                    .substringBefore(">")
                    .contains("android:layout_width=\"0dp\"") &&
                homeLandLayout.split("@+id/homeLevelPanel", limit = 2)[1]
                    .substringBefore(">")
                    .contains("android:layout_weight=\"0.55\"") &&
                homeLandLayout.split("@+id/homeXpTrack", limit = 2)[1]
                    .substringBefore(">")
                    .contains("android:layout_width=\"match_parent\"") &&
                homeLayout.contains("@+id/homeXpDigits") &&
                homeLayout.split("@+id/homeXpDigits", limit = 2)[1].substringBefore("/>").contains("android:layout_width=\"82dp\"") &&
                homeLayout.split("@+id/homeXpDigits", limit = 2)[1].substringBefore("/>").contains("android:layout_height=\"22dp\"") &&
                homeLandLayout.split("@+id/homeXpDigits", limit = 2)[1].substringBefore("/>").contains("android:layout_width=\"112dp\"") &&
                homeLandLayout.split("@+id/homeXpDigits", limit = 2)[1].substringBefore("/>").contains("android:layout_height=\"24dp\"") &&
                homeLayout.contains("@+id/homeXpReadoutPlate") &&
                homeLayout.contains("@drawable/home_xp_readout_plate") &&
                homeLayout.split("@+id/homeXpReadoutPlate", limit = 2)[1].substringBefore("/>").contains("android:layout_width=\"88dp\"") &&
                homeLandLayout.contains("@+id/homeXpReadoutPlate") &&
                homeLandLayout.contains("@drawable/home_xp_readout_plate") &&
                homeLandLayout.split("@+id/homeXpReadoutPlate", limit = 2)[1].substringBefore("/>").contains("android:layout_width=\"124dp\"") &&
                homeLayout.contains("@+id/homeXpTrackGlow") &&
                homeLayout.contains("@drawable/level_progress_track_glow") &&
                homeLayout.contains("@+id/homeXpProgressFill") &&
                homeLayout.contains("@drawable/level_progress_fill") &&
                homeLayout.contains("@+id/homeXpMilestones") &&
                homeLayout.contains("@drawable/level_progress_milestones") &&
                homeLayout.contains("@+id/homeXpProgressCap") &&
                homeLayout.contains("@drawable/level_progress_cap") &&
                homeLayout.contains("@+id/homeXpProgressPulse") &&
                homeLayout.contains("@drawable/level_progress_pulse") &&
                homeFragment.contains("bindLevelProgressFill") &&
                homeFragment.contains("bindLevelProgressMarker(progress)") &&
                homeFragment.contains("binding.homeXpProgressFill.scaleX = progress") &&
                homeFragment.contains("binding.homeXpProgressCap.translationX") &&
                homeFragment.contains("binding.homeXpProgressPulse.translationX") &&
                homeFragment.contains("homeXpGlyphBaseWidthDp(xpText)") &&
                homeFragment.contains("HOME_XP_CONTENT_WIDTH_DP / glyphWeight.toFloat()") &&
                homeFragment.contains("binding.homeXpTrack.doOnLayout") &&
                !homeLayout.contains("<ProgressBar")
        )
        assertTrue("Home must not hardcode Violet Fortune in layout", !homeLayout.contains("android:contentDescription=\"Violet Fortune\""))
        assertTrue("Home must not hardcode Roman Reels in layout", !homeLayout.contains("android:contentDescription=\"Roman Reels\""))
    }

    @Test
    fun `low coins flow uses dedicated image modal`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val requiredAssets = listOf(
            "title_low_coins.webp",
            "label_low_coins_bonus_body.webp",
            "label_low_coins_wait_body.webp",
            "low_coins_modal_panel.webp",
            "low_coins_modal_panel_premium.webp",
            "low_coins_badge.webp",
            "low_coins_caution_stage.webp",
            "low_coins_rescue_glow.webp",
            "low_coins_cooldown_rail.webp",
            "label_daily_bonus_timer.webp"
        )
        val missing = requiredAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = requiredAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }
        val dialogLayout = Path.of("src/main/res/layout/dialog_low_coins.xml").readText()
        val dialogLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/dialog_low_coins.xml").readText()
        val lowCoinsDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/LowCoinsDialogFragment.kt").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")

        assertTrue("Missing low coins image assets: $missing", missing.isEmpty())
        assertTrue("Low coins image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Premium imagegen low coins modal panel asset is too flat or tiny", Files.size(drawableRoot.resolve("low_coins_modal_panel_premium.webp")) > 100_000)
        assertTrue("Premium imagegen low coins modal panel must preserve 900x420 geometry", readBitmapSize(drawableRoot.resolve("low_coins_modal_panel_premium.webp")) == BitmapSize(900, 420))
        assertTrue("Low coins title must render from image asset", dialogLayout.contains("@drawable/title_low_coins"))
        assertTrue("Low coins bonus body must render from image asset", dialogLayout.contains("@drawable/label_low_coins_bonus_body"))
        assertTrue("Low coins dialog must use a premium imagegen dedicated image panel", dialogLayout.contains("@drawable/low_coins_modal_panel_premium") && !dialogLayout.contains("@drawable/modal_panel\""))
        assertTrue("Low coins modal must use a dedicated image badge", dialogLayout.contains("@drawable/low_coins_badge"))
        assertTrue("Low coins modal must render a dedicated caution stage image", dialogLayout.contains("@+id/lowCoinsCautionStage") && dialogLayout.contains("@drawable/low_coins_caution_stage"))
        assertTrue("Low coins caution stage must stay decorative", dialogLayout.contains("@+id/lowCoinsCautionStage") && dialogLayout.split("@+id/lowCoinsCautionStage", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Low coins caution stage must sit above modal panel and below rescue glow/content", dialogLayout.indexOf("@+id/lowCoinsCautionStage") > dialogLayout.indexOf("@drawable/low_coins_modal_panel") && dialogLayout.indexOf("@+id/lowCoinsCautionStage") < dialogLayout.indexOf("@+id/lowCoinsRescueGlow") && dialogLayout.indexOf("@+id/lowCoinsCautionStage") < dialogLayout.indexOf("@+id/lowCoinsTitle"))
        assertTrue("Low coins rescue glow must render from image asset", dialogLayout.contains("@+id/lowCoinsRescueGlow") && dialogLayout.contains("@drawable/low_coins_rescue_glow"))
        assertTrue("Low coins rescue glow must stay decorative", dialogLayout.contains("@+id/lowCoinsRescueGlow") && dialogLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Low coins modal must not reuse the generic loss badge", !dialogLayout.contains("@drawable/modal_badge_loss"))
        assertTrue("Low coins wait body must be swapped dynamically", lowCoinsDialog.contains("R.drawable.label_low_coins_wait_body"))
        assertTrue("Low coins wait state must show the daily bonus cooldown as image and bitmap digits", dialogLayout.contains("@+id/lowCoinsCooldownTimerRail") && dialogLayout.contains("@drawable/label_daily_bonus_timer") && dialogLayout.contains("@+id/lowCoinsCooldownTimerDigits") && dialogLayout.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("Low coins cooldown timer must use a dedicated rescue rail image instead of reusing slot line UI", dialogLayout.contains("@drawable/low_coins_cooldown_rail") && !dialogLayout.substringAfter("@+id/lowCoinsCooldownTimerRail").substringBefore("</FrameLayout>").contains("@drawable/active_lines_badge"))
        assertTrue("Low coins cooldown must reuse persisted daily bonus timestamp and shared formatter", lowCoinsDialog.contains("playerRepository.playerState.first().lastDailyBonusTimestamp") && lowCoinsDialog.contains("DailyBonusCountdownFormatter.format(lastDailyBonusTimestamp)") && lowCoinsDialog.contains("LOW_COINS_COUNTDOWN_TICK_MS = 1_000L"))
        assertTrue("Low coins cooldown must switch to claim state if bonus becomes ready while open", lowCoinsDialog.contains("renderState(available = true)") && lowCoinsDialog.contains("animateLowCoinsPolish(binding, bonusAvailable = true)") && lowCoinsDialog.contains("cooldown.isReady"))
        assertTrue("Low coins cooldown timer must use throttled Russian accessibility text", lowCoinsDialog.contains("cooldownAccessibilityBucket") && lowCoinsDialog.contains("R.string.daily_bonus_cooldown_remaining_accessibility") && dialogLayout.contains("android:contentDescription=\"@string/daily_bonus_cooldown_remaining\""))
        assertTrue("Low coins polish must be finite, managed, and respect disabled system animators", lowCoinsDialog.contains("animateLowCoinsPolish") && lowCoinsDialog.contains("lowCoinsPolishAnimator") && lowCoinsDialog.contains("lowCoinsPolishAnimator?.cancel()") && lowCoinsDialog.contains("ValueAnimator.areAnimatorsEnabled()") && lowCoinsDialog.contains("LOW_COINS_GLOW_SETTLED_ALPHA") && !lowCoinsDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Low coins modal must keep accessibility strings", dialogLayout.contains("@string/low_coins") && lowCoinsDialog.contains("R.string.low_coins_wait_body"))
        assertTrue("Low coins modal must claim daily bonus when available", lowCoinsDialog.contains("claimDailyBonus()"))
        assertTrue("Low coins bonus claim action must ignore rapid repeat taps while the claim is running", lowCoinsDialog.contains("var claimInProgress = false") && lowCoinsDialog.contains("if (claimInProgress) return@setOnClickListener") && lowCoinsDialog.contains("claimInProgress = true") && lowCoinsDialog.contains("binding.actionButton.isEnabled = false"))
        assertTrue("Low coins bonus claim must retry transient storage I/O and restore its CTA after a persistent failure", lowCoinsDialog.contains("retryTransientPersistenceIo") && lowCoinsDialog.contains("catch (_: IOException)") && lowCoinsDialog.contains("if (dialogUiActive) renderState(available = true)"))
        assertTrue("Low coins claim coroutine must not touch dialog UI after the view is destroyed", lowCoinsDialog.contains("private var dialogUiActive = false") && lowCoinsDialog.contains("dialogUiActive = true") && lowCoinsDialog.contains("if (!dialogUiActive) return@launch") && lowCoinsDialog.contains("dialogUiActive = false"))
        assertTrue("Slot low coins event must show dedicated modal safely", slotFragment.contains("showLowCoinsDialog(") && slotFragment.contains("canReduceStake = event.canReduceStake") && slotFragment.contains("LowCoinsDialogFragment.newInstance(bonusAvailable, canReduceStake)") && slotFragment.contains("LOW_COINS_DIALOG_TAG") && slotFragment.contains("parentFragmentManager.isStateSaved") && slotFragment.contains("findFragmentByTag(LOW_COINS_DIALOG_TAG)") && slotFragment.contains(".show(parentFragmentManager, LOW_COINS_DIALOG_TAG)"))
        assertTrue("Low coins dialog must expose scalable body and reduction action copy", Regex("<TextView").findAll(dialogLayout).count() == 2 && dialogLayout.contains("@+id/lowCoinsBodyLargeText") && dialogLayout.contains("@+id/actionButtonText") && dialogLayout.contains("android:text=\"@string/low_coins_bonus_body\"") && dialogLayout.contains("android:text=\"@string/low_coins_reduce_action\""))
        assertTrue("Landscape low coins dialog must keep every binding control", listOf("@+id/lowCoinsCautionStage", "@+id/lowCoinsRescueGlow", "@+id/lowCoinsTitle", "@+id/lowCoinsBody", "@+id/lowCoinsCooldownTimerRail", "@+id/lowCoinsCooldownTimerDigits", "@+id/actionButton", "@+id/actionButtonLabel", "@+id/actionButtonText").all { dialogLandscapeLayout.contains(it) })
        assertTrue("Landscape low coins dialog must keep image-first copy plus scalable body and action", Regex("<TextView").findAll(dialogLandscapeLayout).count() == 2 && dialogLandscapeLayout.contains("@+id/lowCoinsBodyLargeText") && dialogLandscapeLayout.contains("@+id/actionButtonText") && dialogLandscapeLayout.contains("@drawable/title_low_coins") && dialogLandscapeLayout.contains("@drawable/label_low_coins_bonus_body") && dialogLandscapeLayout.contains("@drawable/label_daily_bonus_timer") && dialogLandscapeLayout.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("Landscape low coins dialog must grow from its short-viewport baseline", dialogLandscapeLayout.contains("android:orientation=\"horizontal\"") && dialogLandscapeLayout.contains("android:minHeight=\"320dp\"") && dialogLandscapeLayout.contains("android:layout_height=\"54dp\""))
        assertTrue("Landscape low coins cooldown must remain image and bitmap based", dialogLandscapeLayout.contains("@drawable/low_coins_cooldown_rail") && dialogLandscapeLayout.contains("@+id/lowCoinsCooldownTimerDigits") && !dialogLandscapeLayout.substringAfter("@+id/lowCoinsCooldownTimerRail").substringBefore("</FrameLayout>").contains("@drawable/active_lines_badge"))
        assertTrue("Landscape low coins decorative polish must stay out of accessibility", dialogLandscapeLayout.contains("@drawable/low_coins_caution_stage") && dialogLandscapeLayout.contains("@drawable/low_coins_rescue_glow") && dialogLandscapeLayout.contains("android:importantForAccessibility=\"no\""))
    }

    @Test
    fun `debug qa state receiver is isolated from release sources`() {
        val mainManifest = Path.of("src/main/AndroidManifest.xml").readText()
        val debugManifest = Path.of("src/debug/AndroidManifest.xml").readText()
        val debugReceiver = Path.of("src/debug/java/com/vslot/app/debug/QaStateReceiver.kt").readText()
        val debugResultActivity = Path.of("src/debug/java/com/vslot/app/debug/QaResultDialogActivity.kt").readText()
        val playerRepository = Path.of("src/main/java/com/vslot/app/data/PlayerRepository.kt").readText()
        val appGraph = Path.of("src/main/java/com/vslot/app/AppGraph.kt").readText()

        assertTrue("QA receiver must not be declared in release manifest", !mainManifest.contains("QaStateReceiver"))
        assertTrue("QA receiver must only be declared in debug manifest", debugManifest.contains("QaStateReceiver"))
        assertTrue("QA receiver must seed low-balance states through checked PlayerRepository debit APIs", debugReceiver.contains("repository.acceptDisclaimer()") && debugReceiver.contains("drainBalance(repository)") && debugReceiver.contains("check(balance <= Int.MAX_VALUE.toLong())") && debugReceiver.contains("repository.debitSpinBet(balance.toInt())") && !debugReceiver.contains("DEBUG_BALANCE_DRAIN") && !debugReceiver.contains("repository.applySpin"))
        assertTrue("QA receiver must expose reset scenarios for clean first-launch Android tests", debugReceiver.contains("SCENARIO_RESET") && debugReceiver.contains("SCENARIO_FIRST_LAUNCH") && debugReceiver.contains("resetPlayerState()") && debugReceiver.contains("it.resetForDebug()"))
        assertTrue("PlayerRepository reset must be QA guarded, clear persisted state, and preserve monotonic checkpoint revision", playerRepository.contains("fun resetForDebug()") && playerRepository.contains("check(BuildConfig.QA_ENABLED)") && playerRepository.contains("clearPlayerStatePreservingRevision()") && playerRepository.contains("val revision = this[PlayerRepository.Keys.Revision]") && playerRepository.contains("this[PlayerRepository.Keys.Revision] = revision"))
        assertTrue("QA receiver must expose deterministic slot win and bonus scenarios for visual verification", debugReceiver.contains("SCENARIO_SLOT_MULTI_WIN") && debugReceiver.contains("SCENARIO_SLOT_BONUS") && debugReceiver.contains("DEBUG_MULTI_WIN_STOPS = intArrayOf(0, 5, 11, 1, 0)") && debugReceiver.contains("DEBUG_BONUS_STOPS = intArrayOf(0, 0, 17, 20, 15)"))
        assertTrue("QA receiver must be able to seed level XP for visual verification of the dynamic image progress rail", debugReceiver.contains("EXTRA_LEVEL_XP") && debugReceiver.contains("repository.awardLevelXp(intent.getIntExtra(EXTRA_LEVEL_XP"))
        assertTrue("QA receiver must persist deterministic slot results through debug-only AppGraph override", debugReceiver.contains("AppGraph.persistSlotEngineOverrideForDebug(context, DEBUG_MULTI_WIN_STOPS)") && debugReceiver.contains("AppGraph.persistSlotEngineOverrideForDebug(context, DEBUG_BONUS_STOPS)") && debugReceiver.contains("AppGraph.clearSlotEngineOverrideForDebug(context)"))
        assertTrue("AppGraph slot engine replacement must be guarded to QA-enabled builds", appGraph.contains("replaceSlotEngineForDebug") && appGraph.contains("check(BuildConfig.QA_ENABLED)") && appGraph.contains("resetSlotEngineForDebug") && appGraph.contains("debugSlotEngineOverride") && appGraph.contains("LoopingStopsRng"))
        assertTrue("QA result dialog activity must not be declared in release manifest", !mainManifest.contains("QaResultDialogActivity"))
        assertTrue("QA result dialog activity must only be declared in debug manifest", debugManifest.contains("QaResultDialogActivity"))
        assertTrue("QA result dialog activity must not be orientation locked; dialog previews need landscape visual QA", !debugManifest.substringAfter("QaResultDialogActivity").substringBefore("/>").contains("screenOrientation"))
        assertTrue("QA result dialog activity must preview the production themed bonus dialog", debugResultActivity.contains("ResultDialogFragment") && debugResultActivity.contains("NetOutcome.Bonus") && debugResultActivity.contains("SlotTheme.Ocean"))
        assertTrue("QA result dialog activity must preview the production push prompt for visual QA", debugResultActivity.contains("PushPermissionDialogFragment") && debugResultActivity.contains("DIALOG_PUSH"))
        assertTrue("QA result dialog activity must preview both production low coins states for landscape visual QA", debugResultActivity.contains("LowCoinsDialogFragment") && debugResultActivity.contains("DIALOG_LOW_BONUS") && debugResultActivity.contains("DIALOG_LOW_WAIT") && debugResultActivity.contains("newInstance(bonusAvailable = true)") && debugResultActivity.contains("newInstance(bonusAvailable = false)"))
    }

    @Test
    fun `main activity can rotate for real orientation QA`() {
        val mainActivityManifest = Path.of("src/main/AndroidManifest.xml")
            .readText()
            .substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        val homeLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/fragment_home.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val paytableLandscapeLayout = Path.of("src/main/res/layout-land/dialog_paytable.xml").readText()
        val resultLandscapeLayout = Path.of("src/main/res/layout-land/dialog_result.xml").readText()
        val socialRulesLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/dialog_social_rules.xml").readText()
        val landscapeDimensions = Path.of("src/main/res/values-land/dimens.xml").readText()

        assertTrue("MainActivity must not be locked to portrait; orientation bugs need real device coverage", !mainActivityManifest.contains("android:screenOrientation=\"portrait\""))
        assertTrue("MainActivity must not suppress locked-orientation lint now that landscape is part of QA", !mainActivityManifest.contains("LockedOrientationActivity"))
        assertTrue("Landscape home must expose both image slot cards, daily bonus, privacy, and settings targets", homeLandscapeLayout.contains("@+id/violetCard") && homeLandscapeLayout.contains("@+id/romanCard") && homeLandscapeLayout.contains("@+id/dailyBonusButton") && homeLandscapeLayout.contains("@+id/privacyButton") && homeLandscapeLayout.contains("@+id/settingsButton"))
        assertTrue("Landscape slot must expose the same image control surface with reels and right-side controls", slotLandscapeLayout.contains("@+id/reelsGrid") && slotLandscapeLayout.contains("@+id/slotControlConsole") && slotLandscapeLayout.contains("@+id/spinButton") && slotLandscapeLayout.contains("@+id/autoSpinButton") && slotLandscapeLayout.contains("@+id/maxLinesButton") && slotLandscapeLayout.contains("@+id/paytableButton"))
        assertTrue("Landscape paytable must preserve its short baseline and keep the close action", paytableLandscapeLayout.contains("android:minHeight=\"320dp\"") && paytableLandscapeLayout.contains("@+id/paytableRowsStage") && paytableLandscapeLayout.contains("android:layout_height=\"120dp\"") && paytableLandscapeLayout.contains("@+id/closeButton") && paytableLandscapeLayout.contains("android:layout_height=\"48dp\"") && landscapeDimensions.contains("<dimen name=\"paytable_row_height\">40dp</dimen>") && !landscapeDimensions.contains("paytable_rows_stage_height"))
        assertTrue("Landscape result dialog must preserve its short baseline with free-spins award and close action", resultLandscapeLayout.contains("android:minHeight=\"272dp\"") && resultLandscapeLayout.contains("@+id/resultFreeSpinsAwardGroup") && resultLandscapeLayout.contains("@drawable/result_free_spins_award_panel") && resultLandscapeLayout.contains("@+id/closeButton") && resultLandscapeLayout.contains("android:layout_height=\"48dp\"") && resultLandscapeLayout.contains("@drawable/label_close"))
        assertTrue("Landscape social rules dialog must preserve its short baseline with all compliance copy", socialRulesLandscapeLayout.contains("android:minHeight=\"336dp\"") && socialRulesLandscapeLayout.contains("@+id/socialRulesBody") && socialRulesLandscapeLayout.contains("@+id/socialRulesFooter") && socialRulesLandscapeLayout.contains("@+id/closeButton"))
        assertTrue("Landscape game surfaces keep image art while critical numeric and persistent status labels use scalable text", !homeLandscapeLayout.contains("<TextView") && !homeLandscapeLayout.contains("<Button") && !homeLandscapeLayout.contains("android:text=") && Regex("<TextView").findAll(slotLandscapeLayout).count() == 8 && slotLandscapeLayout.contains("@string/spin_visual_label") && slotLandscapeLayout.contains("@string/slot_settlement_recovery_notice") && slotLandscapeLayout.contains("@string/auto_spin_stopped_big_win") && slotLandscapeLayout.contains("@string/free_spins_stake_locked") && !slotLandscapeLayout.contains("<Button") && Regex("<TextView").findAll(paytableLandscapeLayout).count() == 7 && Regex("<TextView").findAll(resultLandscapeLayout).count() == 1)
        assertTrue("Landscape slot stake controls must be split into separate bet and line rows", slotLandscapeLayout.contains("@+id/betStepperGroup") && slotLandscapeLayout.contains("@+id/linesStepperGroup") && slotLandscapeLayout.indexOf("@+id/betStepperGroup") < slotLandscapeLayout.indexOf("@+id/linesStepperGroup"))
    }

    @Test
    fun `splash waits for persisted datastore state before routing`() {
        val splashViewModel = Path.of("src/main/java/com/vslot/app/ui/splash/SplashViewModel.kt").readText()
        val splashFragment = Path.of("src/main/java/com/vslot/app/ui/splash/SplashFragment.kt").readText()

        assertTrue("Splash must read the repository flow directly", splashViewModel.contains("this(playerRepository.playerState)"))
        assertTrue("Splash must not route from a default PlayerState initial value", !splashViewModel.contains("initialValue = com.vslot.app.data.PlayerState()"))
        assertTrue("Splash must wait for the first persisted state and expose a retryable read failure", splashViewModel.contains("playerState.first()") && splashViewModel.contains("SplashLoadState.Failed") && splashFragment.contains("is SplashLoadState.Ready"))
        assertTrue("Splash delayed routing must be tied to the view lifecycle", splashFragment.contains("viewLifecycleOwner.lifecycleScope.launch") && !splashFragment.contains("        lifecycleScope.launch"))
        assertTrue("Splash navigation must ignore stale delayed callbacks", splashFragment.contains("navigateFromSplash") && splashFragment.contains("currentDestination?.id != R.id.splashFragment") && splashFragment.contains("navigateFromSplash(R.id.action_splash_to_home)") && splashFragment.contains("navigateFromSplash(R.id.action_splash_to_disclaimer)"))
    }

    @Test
    fun `splash renders premium startup polish from image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val splashLayout = Path.of("src/main/res/layout/fragment_splash.xml").readText()
        val splashLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/fragment_splash.xml").readText()
        val splashFragment = Path.of("src/main/java/com/vslot/app/ui/splash/SplashFragment.kt").readText()
        val splashIgnitionGenerator = Path.of("../tools/slice_imagegen_splash_ignition_overlay.py").readText()

        assertTrue("Splash logo aura image asset missing", Files.exists(drawableRoot.resolve("splash_logo_aura.webp")))
        assertTrue("Splash loading rail image asset missing", Files.exists(drawableRoot.resolve("splash_loading_rail.webp")))
        assertTrue("Splash ignition overlay image asset missing", Files.exists(drawableRoot.resolve("splash_ignition_overlay.webp")))
        assertTrue("Splash loading scan image asset missing", Files.exists(drawableRoot.resolve("splash_loading_scan.webp")))
        assertTrue("Splash polish image assets are unexpectedly tiny", Files.size(drawableRoot.resolve("splash_logo_aura.webp")) > 1_000 && Files.size(drawableRoot.resolve("splash_loading_rail.webp")) > 1_000)
        assertTrue("Splash generated ignition assets are unexpectedly tiny", Files.size(drawableRoot.resolve("splash_ignition_overlay.webp")) > 120_000 && Files.size(drawableRoot.resolve("splash_loading_scan.webp")) > 30_000)
        assertTrue("Splash ignition assets must preserve generated geometry", readBitmapSize(drawableRoot.resolve("splash_ignition_overlay.webp")) == BitmapSize(900, 900) && readBitmapSize(drawableRoot.resolve("splash_loading_scan.webp")) == BitmapSize(700, 140))
        assertTrue("Splash ignition assets must retain fail-closed historical source and contact-sheet evidence", splashIgnitionGenerator.contains("NONCANONICAL_HISTORICAL_SLICER") && splashIgnitionGenerator.contains("vslot_splash_ignition_overlay_imagegen.png") && splashIgnitionGenerator.contains("vslot_splash_loading_scan_imagegen.png") && splashIgnitionGenerator.contains("remove_chroma_key") && splashIgnitionGenerator.contains("splash_ignition_overlay_contact_sheet.png") && Files.exists(Path.of("../qa/source/vslot_splash_ignition_overlay_imagegen.png")) && Files.exists(Path.of("../qa/source/vslot_splash_loading_scan_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/splash_ignition_overlay_contact_sheet.png")))
        assertTrue("Splash must render logo aura from image asset", splashLayout.contains("@+id/splashLogoAura") && splashLayout.contains("@drawable/splash_logo_aura"))
        assertTrue("Splash must render loading rail from image asset", splashLayout.contains("@+id/splashLoadingRail") && splashLayout.contains("@drawable/splash_loading_rail"))
        assertTrue("Splash must render ignition and scan overlays from generated image assets", splashLayout.contains("@+id/splashIgnitionOverlay") && splashLayout.contains("@drawable/splash_ignition_overlay") && splashLayout.contains("@+id/splashLoadingScan") && splashLayout.contains("@drawable/splash_loading_scan"))
        assertTrue("Splash decorative polish must stay out of accessibility", listOf("splashLogoAura", "splashIgnitionOverlay", "splashLoadingRail", "splashLoadingScan").all { viewId -> splashLayout.split("@+id/$viewId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") })
        assertTrue("Splash ignition overlay must sit behind the logo and scan must sit over the loading rail", splashLayout.indexOf("@+id/splashIgnitionOverlay") > splashLayout.indexOf("@+id/splashLogoAura") && splashLayout.indexOf("@+id/splashIgnitionOverlay") < splashLayout.indexOf("@+id/logoGroup") && splashLayout.indexOf("@+id/splashLoadingScan") > splashLayout.indexOf("@+id/splashLoadingRail"))
        assertTrue("Landscape splash must keep every animated binding control", listOf("@+id/splashBg", "@+id/splashStage", "@+id/splashLogoAura", "@+id/splashIgnitionOverlay", "@+id/logoGroup", "@+id/splashLoadingRail", "@+id/splashLoadingScan").all { splashLandscapeLayout.contains(it) })
        assertTrue("Landscape splash must render the transparent production mark and the same startup polish from image assets", splashLandscapeLayout.contains("@drawable/splash_bg") && splashLandscapeLayout.contains("@drawable/splash_logo_aura") && splashLandscapeLayout.contains("@drawable/splash_ignition_overlay") && splashLandscapeLayout.contains("@drawable/app_logo_mark_v2") && splashLandscapeLayout.contains("@drawable/logo_v_slot") && splashLandscapeLayout.contains("@drawable/splash_loading_rail") && splashLandscapeLayout.contains("@drawable/splash_loading_scan"))
        assertTrue("Portrait and Android 12 splash screens must use production brand assets", Path.of("src/main/res/layout/fragment_splash.xml").readText().contains("@drawable/app_logo_mark_v2") && Path.of("src/main/res/values-v31/styles.xml").readText().contains("@mipmap/ic_launcher"))
        val runtimeLogo = drawableRoot.resolve("app_logo_mark_v2.png")
        assertTrue("Runtime splash logo must be a detailed transparent square asset", Files.exists(runtimeLogo) && Files.size(runtimeLogo) > 100_000L && readBitmapSize(runtimeLogo) == BitmapSize(1_254, 1_254) && readPngColorType(runtimeLogo) == 6)
        assertTrue("Runtime splash logo must retain its imagegen chroma source for reproducibility", Files.exists(Path.of("../qa/source/vslot_app_logo_mark_chroma_imagegen.png")))
        assertTrue("Landscape splash must fit a short landscape viewport with only the scalable storage-error message", splashLandscapeLayout.contains("android:layout_height=\"310dp\"") && Regex("<TextView").findAll(splashLandscapeLayout).count() == 1 && splashLandscapeLayout.contains("@+id/splashStorageErrorMessage") && splashLandscapeLayout.contains("android:text=\"@string/player_state_load_error\""))
        assertTrue("Landscape splash decorative polish must stay out of accessibility", listOf("splashLogoAura", "splashIgnitionOverlay", "splashLoadingRail", "splashLoadingScan").all { viewId -> splashLandscapeLayout.split("@+id/$viewId", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") })
        assertTrue("Splash animation must respect disabled system animators", splashFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Splash animation must animate generated ignition and scan image layers", splashFragment.contains("binding.splashIgnitionOverlay") && splashFragment.contains("SPLASH_IGNITION_PEAK_ALPHA") && splashFragment.contains("binding.splashLoadingScan") && splashFragment.contains("SPLASH_SCAN_PEAK_ALPHA") && splashFragment.contains("ObjectAnimator.ofFloat(binding.splashIgnitionOverlay, View.ROTATION") && splashFragment.contains("ObjectAnimator.ofFloat(binding.splashLoadingScan, View.TRANSLATION_X"))
        assertTrue("Splash animation must be finite and cleaned up", splashFragment.contains("splashAnimator?.cancel()") && !splashFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Splash must expose one visible scalable storage-error message without duplicate app-name announcements", Regex("<TextView").findAll(splashLayout).count() == 1 && splashLayout.contains("@+id/splashStorageErrorMessage") && splashLayout.contains("android:text=\"@string/player_state_load_error\"") && Regex("android:contentDescription=\"@string/app_name\"").findAll(splashLayout).count() == 1 && Regex("android:contentDescription=\"@string/app_name\"").findAll(splashLandscapeLayout).count() == 1)
    }

    @Test
    fun `home daily bonus renders persisted state and opens only from user action`() {
        val homeViewModel = Path.of("src/main/java/com/vslot/app/ui/home/HomeViewModel.kt").readText()
        val homeFragment = Path.of("src/main/java/com/vslot/app/ui/home/HomeFragment.kt").readText()

        assertTrue("Home must collect the repository flow directly", homeViewModel.contains("val playerState = playerRepository.playerState"))
        assertTrue("Home must not render daily bonus from a synthetic default PlayerState", !homeViewModel.contains("initialValue = PlayerState()"))
        assertTrue("Home analytics must read a persisted balance", homeViewModel.contains("playerRepository.playerState.first().coinsBalance"))
        assertTrue("HomeFragment must render daily bonus availability from collected state", homeFragment.contains("viewModel.playerState.collect { state ->") && homeFragment.contains("renderDailyBonusState(state)"))
        assertTrue("HomeFragment must open daily bonus only from the explicit action", homeFragment.contains("binding.dailyBonusButton.setOnClickListener") && homeFragment.contains("openDailyBonusFromUserAction()") && homeFragment.substringBefore("private fun openDailyBonusFromUserAction").let { !it.contains("showDailyBonusDialog(") } && !homeFragment.contains("bonusShown") && !homeFragment.contains("KEY_BONUS_SHOWN"))
        assertTrue("HomeFragment daily bonus dialog must show safely with a stable tag", homeFragment.contains("showDailyBonusDialog(") && homeFragment.contains("DAILY_BONUS_DIALOG_TAG") && homeFragment.contains("parentFragmentManager.isStateSaved") && homeFragment.contains("findFragmentByTag(DAILY_BONUS_DIALOG_TAG)") && homeFragment.contains(".show(parentFragmentManager, DAILY_BONUS_DIALOG_TAG)"))
        assertTrue("Home navigation must ignore stale rapid taps", homeFragment.contains("navigateFromHome") && homeFragment.contains("currentDestination?.id != R.id.homeFragment") && homeFragment.contains("R.id.action_home_to_slot"))
    }

    @Test
    fun `restored slot screens and dialogs tolerate missing argument bundles`() {
        val slotFragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val slotViewModel = Path.of("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt").readText()
        val paytableDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/PaytableDialogFragment.kt").readText()
        val resultDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt").readText()
        val dailyBonusDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt").readText()
        val lowCoinsDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/LowCoinsDialogFragment.kt").readText()
        val runtimeFiles = listOf(slotFragment, paytableDialog, resultDialog, dailyBonusDialog, lowCoinsDialog)

        assertTrue("Runtime slot/dialog code must not crash on a missing argument Bundle", runtimeFiles.none { it.contains("requireArguments()") })
        assertTrue("SlotFragment and PaytableDialog must let SlotRepository fallback when slotId is absent or unknown", slotFragment.contains("arguments?.getString(\"slotId\").orEmpty()") && slotViewModel.contains("private val config = slotRepository.getSlot(slotId)") && paytableDialog.contains("arguments?.getString(ARG_SLOT_ID).orEmpty()"))
        assertTrue("Result and bonus dialogs must use Bundle.EMPTY defaults when restored without args", resultDialog.contains("val args = arguments ?: Bundle.EMPTY") && dailyBonusDialog.contains("val args = arguments ?: Bundle.EMPTY") && lowCoinsDialog.contains("val args = arguments ?: Bundle.EMPTY"))
    }

    @Test
    fun `shared image panels do not keep duplicate bitmap files`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val homeLayout = Path.of("src/main/res/layout/fragment_home.xml").readText()
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val coinIcon = drawableRoot.resolve("coin_icon.webp")
        val homeBalanceMeterGlow = drawableRoot.resolve("home_balance_meter_glow.webp")
        val topBarPremium = drawableRoot.resolve("top_bar_premium.webp")

        assertTrue("Premium imagegen shared top bar asset missing", Files.exists(topBarPremium))
        assertTrue("Premium imagegen shared top bar asset is too flat or tiny", Files.size(topBarPremium) > 45_000)
        assertTrue("Premium imagegen shared top bar must preserve 1200x220 geometry", readBitmapSize(topBarPremium) == BitmapSize(1200, 220))
        assertTrue("Home balance panel must reuse premium top_bar image", homeLayout.contains("@drawable/top_bar_premium") && !homeLayout.contains("@drawable/coin_balance_panel"))
        assertTrue("Balance HUD must use the shared coin image asset", Files.exists(coinIcon) && Files.size(coinIcon) > 10_000)
        assertTrue("Home balance HUD must include the coin image", homeLayout.contains("@+id/homeBalanceCoin") && homeLayout.contains("@drawable/coin_icon"))
        assertTrue("Home balance HUD must include a crafted decorative glow image", Files.exists(homeBalanceMeterGlow) && Files.size(homeBalanceMeterGlow) > 1_000)
        assertTrue("Home balance glow must be wired into the layout", homeLayout.contains("@+id/homeBalanceMeterGlow") && homeLayout.contains("@drawable/home_balance_meter_glow"))
        assertTrue("Home balance glow must stay hidden from accessibility traversal", homeLayout.substringAfter("@+id/homeBalanceMeterGlow").substringBefore("@+id/homeBalanceCoin").contains("android:importantForAccessibility=\"no\""))
        assertTrue("Home balance glow must stay below coin and digits", homeLayout.indexOf("@+id/homeBalanceMeterGlow") < homeLayout.indexOf("@+id/homeBalanceCoin") && homeLayout.indexOf("@+id/homeBalanceMeterGlow") < homeLayout.indexOf("@+id/balanceDigits"))
        assertTrue("Slot balance HUD must include the coin image", slotLayout.contains("@+id/slotBalanceCoin") && slotLayout.contains("@drawable/coin_icon"))
        assertTrue("Slot win panel must reuse bet_panel image", slotLayout.contains("@drawable/bet_panel") && !slotLayout.contains("@drawable/win_panel"))
        assertTrue("Duplicate coin_balance_panel asset must be removed", !Files.exists(drawableRoot.resolve("coin_balance_panel.webp")))
        assertTrue("Duplicate win_panel asset must be removed", !Files.exists(drawableRoot.resolve("win_panel.webp")))
    }

    @Test
    fun `static titles render from image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val requiredTitles = listOf(
            "title_violet_fortune.webp",
            "title_roman_reels.webp",
            "title_neon_nights.webp",
            "title_pharaoh_gold.webp",
            "title_ocean_pearl.webp",
            "title_paytable_neon_nights.webp",
            "title_paytable_pharaoh_gold.webp",
            "title_paytable_ocean_pearl.webp",
            "title_settings.webp",
            "title_privacy_policy.webp",
            "title_disclaimer_18.webp",
            "title_daily_bonus.webp",
            "title_low_coins.webp",
            "title_push_notifications.webp",
            "title_social_rules.webp"
        )
        val missing = requiredTitles.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = requiredTitles.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }

        assertTrue("Missing title image assets: $missing", missing.isEmpty())
        assertTrue("Title image assets are unexpectedly tiny: $tiny", tiny.isEmpty())

        mapOf(
            "fragment_home.xml" to listOf("slot_card_violet_fortune_selector", "slot_card_roman_reels_selector", "slot_card_neon_nights_selector", "slot_card_pharaoh_gold_selector", "slot_card_ocean_pearl_selector"),
            "fragment_slot.xml" to listOf("slotTitle"),
            "fragment_settings.xml" to listOf("title_settings"),
            "fragment_privacy.xml" to listOf("title_privacy_policy"),
            "fragment_disclaimer.xml" to listOf("title_disclaimer_18"),
            "dialog_bonus.xml" to listOf("title_daily_bonus"),
            "dialog_low_coins.xml" to listOf("title_low_coins"),
            "dialog_push_permission.xml" to listOf("title_push_notifications"),
            "dialog_social_rules.xml" to listOf("title_social_rules")
        ).forEach { (fileName, markers) ->
            val text = Path.of("src/main/res/layout/$fileName").readText()
            markers.forEach { marker ->
                assertTrue("$fileName must render static title via image marker $marker", text.contains(marker))
            }
        }

        val forbiddenTitleText = mapOf(
            "fragment_home.xml" to listOf("Violet Fortune", "Roman Reels", "Neon Nights", "Pharaoh Gold", "Ocean Pearl"),
            "fragment_settings.xml" to listOf("settings"),
            "fragment_privacy.xml" to listOf("privacy_policy"),
            "fragment_disclaimer.xml" to listOf("disclaimer_title"),
            "dialog_bonus.xml" to listOf("daily_bonus"),
            "dialog_low_coins.xml" to listOf("low_coins"),
            "dialog_push_permission.xml" to listOf("push_prompt_title"),
            "dialog_social_rules.xml" to listOf("social_casino_rules")
        )
        forbiddenTitleText.forEach { (fileName, strings) ->
            val text = Path.of("src/main/res/layout/$fileName").readText()
            strings.forEach { stringName ->
                assertTrue("$fileName must not render static title $stringName as android:text", !text.contains("android:text=\"@string/$stringName\"") && !text.contains("android:text=\"$stringName\""))
            }
        }

        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val bonusDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt").readText()
        assertTrue("SlotFragment must map slot title to image resources", slotFragment.contains("R.drawable.title_roman_reels") && slotFragment.contains("R.drawable.title_violet_fortune") && slotFragment.contains("R.drawable.title_neon_nights") && slotFragment.contains("R.drawable.title_pharaoh_gold") && slotFragment.contains("R.drawable.title_ocean_pearl"))
        assertTrue("SlotFragment must keep title accessibility description", slotFragment.contains("slotTitle.contentDescription = state.config.name"))
        assertTrue("Slot back navigation must ignore stale rapid taps", slotFragment.contains("popFromSlot") && slotFragment.contains("currentDestination?.id != R.id.slotFragment") && !slotFragment.contains("findNavController().popBackStack()"))
        assertTrue("Daily bonus title must not be assigned as TextView text", !bonusDialog.contains("bonusTitle.text"))
    }

    @Test
    fun `command button labels render from image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val requiredLabels = listOf(
            "label_continue_action.webp",
            "label_claim_bonus.webp",
            "label_ok_action.webp",
            "label_close.webp",
            "label_privacy_policy.webp",
            "label_social_rules.webp",
            "label_push_reminders.webp",
            "label_push_enabled.webp",
            "label_push_unconfigured_action.webp",
            "label_retry.webp",
            "label_allow.webp",
            "label_maybe_later.webp"
        )
        val missing = requiredLabels.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = requiredLabels.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }

        assertTrue("Missing command label image assets: $missing", missing.isEmpty())
        assertTrue("Command label image assets are unexpectedly tiny: $tiny", tiny.isEmpty())

        mapOf(
            "fragment_home.xml" to listOf("label_privacy_policy"),
            "fragment_disclaimer.xml" to listOf("label_continue_action"),
            "dialog_bonus.xml" to listOf("label_claim_bonus"),
            "dialog_result.xml" to listOf("label_close"),
            "dialog_paytable.xml" to listOf("label_close"),
            "dialog_social_rules.xml" to listOf("label_close"),
            "dialog_push_permission.xml" to listOf("label_maybe_later", "label_allow"),
            "fragment_privacy.xml" to listOf("label_retry"),
            "fragment_settings.xml" to listOf("label_privacy_policy", "label_social_rules", "label_push_reminders")
        ).forEach { (fileName, labels) ->
            val text = Path.of("src/main/res/layout/$fileName").readText()
            labels.forEach { label ->
                assertTrue("$fileName must render command label @$label as ImageView source", text.contains("@drawable/$label"))
            }
        }

        val forbiddenButtonText = mapOf(
            "fragment_home.xml" to listOf("play_action", "privacy_policy"),
            "fragment_disclaimer.xml" to listOf("continue_action"),
            "dialog_bonus.xml" to listOf("claim_bonus"),
            "dialog_result.xml" to listOf("close"),
            "dialog_paytable.xml" to listOf("close"),
            "dialog_social_rules.xml" to listOf("close"),
            "dialog_push_permission.xml" to listOf("maybe_later", "allow"),
            "fragment_privacy.xml" to listOf("retry")
        )
        forbiddenButtonText.forEach { (fileName, strings) ->
            val text = Path.of("src/main/res/layout/$fileName").readText()
            strings.forEach { stringName ->
                assertTrue("$fileName must not render button copy @$stringName as android:text", !text.contains("android:text=\"@string/$stringName\""))
            }
        }

        val bonusDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt").readText()
        val settingsFragment = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt").readText()
        assertTrue("Daily bonus dialog must swap image labels dynamically", bonusDialog.contains("binding.claimButtonLabel.setImageResource"))
        assertTrue("Settings push button must swap image labels dynamically", settingsFragment.contains("binding.pushButtonLabel.setImageResource"))
    }

    @Test
    fun `settings push status renders from image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val requiredLabels = listOf(
            "label_push_status_enabled.webp",
            "label_push_status_off.webp",
            "label_push_status_asked.png",
            "label_push_unconfigured_action.webp",
            "settings_modal_backplate.webp",
            "settings_modal_panel.webp",
            "settings_modal_panel_premium.webp",
            "settings_modal_panel_landscape_premium.webp",
            "settings_safety_anchor.webp",
            "settings_safety_panel.webp",
            "settings_center_ornament.webp",
            "settings_push_status_console.webp",
            "settings_push_status_signal_pulse.webp"
        )
        val missing = requiredLabels.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = requiredLabels.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }
        val settingsLayout = Path.of("src/main/res/layout/fragment_settings.xml").readText()
        val settingsLandscapeLayout = Path.of("src/main/res/layout-w600dp-land/fragment_settings.xml").readText()
        val settingsFragment = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()

        assertTrue("Missing push status image assets: $missing", missing.isEmpty())
        assertTrue("Push status image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Settings must default the hidden push status to the disabled image label", settingsLayout.contains("@drawable/label_push_status_off"))
        assertTrue("Settings version must render from the bundled image label instead of runtime text drawing", settingsLayout.contains("@drawable/label_version_current") && settingsFragment.contains("binding.versionImage.setImageResource(R.drawable.label_version_current)") && !settingsFragment.contains("renderVersionLabelBitmap") && !settingsFragment.contains("Canvas(") && !settingsFragment.contains("drawText("))
        assertTrue("Settings push action must default to the reminders label while its stage stays hidden", settingsLayout.substringAfter("@+id/pushButtonLabel").substringBefore("/>").contains("@drawable/label_push_reminders"))
        assertTrue("Settings must not expose an unconfigured push status", !settingsLayout.contains("@string/push_unconfigured_status"))
        assertTrue("Premium imagegen settings modal panel asset is too flat or tiny", Files.size(drawableRoot.resolve("settings_modal_panel_premium.webp")) > 130_000)
        assertTrue("Premium imagegen settings modal panel must preserve 1000x1500 geometry", readBitmapSize(drawableRoot.resolve("settings_modal_panel_premium.webp")) == BitmapSize(1000, 1500))
        assertTrue("Premium imagegen landscape settings modal panel asset is too flat or tiny", Files.size(drawableRoot.resolve("settings_modal_panel_landscape_premium.webp")) > 100_000)
        assertTrue("Premium imagegen landscape settings modal panel must preserve 1500x620 geometry", readBitmapSize(drawableRoot.resolve("settings_modal_panel_landscape_premium.webp")) == BitmapSize(1500, 620))
        assertTrue("Settings push status console imagegen asset is too flat or tiny", Files.size(drawableRoot.resolve("settings_push_status_console.webp")) > 30_000)
        assertTrue("Settings push status signal imagegen asset is too flat or tiny", Files.size(drawableRoot.resolve("settings_push_status_signal_pulse.webp")) > 12_000)
        assertTrue("Settings push status console assets must preserve generated geometry", readBitmapSize(drawableRoot.resolve("settings_push_status_console.webp")) == BitmapSize(760, 164) && readBitmapSize(drawableRoot.resolve("settings_push_status_signal_pulse.webp")) == BitmapSize(220, 220))
        assertTrue("Settings safety anchor imagegen asset is too flat or tiny", Files.size(drawableRoot.resolve("settings_safety_anchor.webp")) > 40_000)
        assertTrue("Settings safety anchor must preserve wide transparent dock geometry", readBitmapSize(drawableRoot.resolve("settings_safety_anchor.webp")) == BitmapSize(1180, 360))
        assertTrue("Settings safety anchor must retain fail-closed historical source and review evidence", Path.of("../tools/slice_imagegen_settings_safety_anchor.py").readText().let { it.contains("vslot_settings_safety_anchor_imagegen.png") && it.contains("NONCANONICAL_HISTORICAL_SLICER") } && Files.exists(Path.of("../qa/source/vslot_settings_safety_anchor_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/settings_safety_anchor_contact_sheet.png")) && Files.exists(Path.of("../qa/design/settings_safety_panel_visual_philosophy.md")))
        assertTrue("Settings must use dedicated premium imagegen backplate and panel", settingsLayout.contains("@drawable/settings_modal_backplate") && settingsLayout.contains("@drawable/settings_modal_panel_premium") && !settingsLayout.contains("@drawable/modal_panel_backplate") && !settingsLayout.contains("@drawable/modal_panel\""))
        assertTrue("Settings must retain legacy safety art without showing its unverified copy", settingsLayout.contains("@+id/settingsSafetyPanel") && settingsLayout.contains("@drawable/settings_safety_panel") && settingsLayout.substringAfter("@+id/settingsSafetyPanel").substringBefore("/>").contains("android:visibility=\"gone\""))
        assertTrue("Settings safety anchor must render as a decorative image layer behind the safety panel", settingsLayout.contains("@+id/settingsSafetyAnchor") && settingsLayout.contains("@drawable/settings_safety_anchor") && settingsLayout.indexOf("@+id/settingsSafetyAnchor") < settingsLayout.indexOf("@+id/settingsSafetyPanel") && settingsLayout.split("@+id/settingsSafetyAnchor", limit = 2)[1].substringBefore("@+id/settingsSafetyPanel").contains("android:importantForAccessibility=\"no\"") && settingsLayout.substringAfter("@+id/settingsSafetyPanel").contains("android:layout_marginBottom=\"6dp\""))
        assertTrue("Landscape settings safety stage must keep decorative anchor behind the readable safety panel", settingsLandscapeLayout.contains("@+id/settingsSafetyStage") && settingsLandscapeLayout.contains("@+id/settingsSafetyAnchor") && settingsLandscapeLayout.contains("@drawable/settings_safety_anchor") && settingsLandscapeLayout.indexOf("@+id/settingsSafetyAnchor") < settingsLandscapeLayout.indexOf("@+id/settingsSafetyPanel") && settingsLandscapeLayout.split("@+id/settingsSafetyAnchor", limit = 2)[1].substringBefore("@+id/settingsSafetyPanel").contains("android:importantForAccessibility=\"no\""))
        assertTrue("Settings safety disclaimer must be exposed by visible scalable text", settingsLayout.substringAfter("@+id/settingsSafetyLargeText").substringBefore("/>").let { it.contains("android:text=\"@string/settings_safety_panel\"") && it.contains("android:visibility=\"visible\"") })
        assertTrue("Settings center ornament must render from a dedicated image asset", settingsLayout.contains("@+id/settingsCenterOrnament") && settingsLayout.contains("@drawable/settings_center_ornament"))
        assertTrue("Settings center ornament must stay decorative", settingsLayout.contains("@+id/settingsCenterOrnament") && settingsLayout.split("@+id/settingsCenterOrnament", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Settings safety panel string must mention no purchases, no payouts, and no money prizes", strings.lowercase().let { it.contains("без покупок") && it.contains("без выплат") && it.contains("денежных призов") })
        assertTrue("Settings screen must add compact spacing after the activity applies Android navigation insets", settingsLayout.contains("android:paddingBottom=\"16dp\"") && settingsLandscapeLayout.contains("android:paddingTop=\"4dp\"") && settingsLandscapeLayout.contains("android:paddingBottom=\"4dp\"") && settingsLandscapeLayout.contains("android:layout_height=\"48dp\"") && settingsLandscapeLayout.contains("android:layout_marginTop=\"2dp\"") && !settingsLayout.contains("android:padding=\"18dp\""))
        assertTrue("Settings safety panel must provide scalable legal copy for large font users", settingsLayout.contains("@+id/settingsSafetyLargeText") && settingsLayout.contains("android:text=\"@string/settings_safety_panel\""))
        assertTrue("Settings must not render a duplicate safety footer", !settingsLayout.contains("@+id/settingsComplianceStrip"))
        assertTrue("Settings must swap configured push status image resources dynamically", settingsFragment.contains("R.drawable.label_push_status_enabled") && settingsFragment.contains("R.drawable.label_push_status_off") && settingsFragment.contains("R.drawable.label_push_status_asked"))
        assertTrue("Settings must hide both push stages when service inputs are absent", settingsFragment.contains("binding.pushActionStage.visibility = if (pushConfigured) View.VISIBLE else View.GONE") && settingsFragment.contains("binding.pushStatusStage.visibility = if (pushConfigured) View.VISIBLE else View.GONE") && settingsFragment.contains("if (!pushConfigured) {"))
        assertTrue("Settings must not report push enabled unless service inputs, explicit consent, system permission, and runtime registration agree", settingsFragment.contains("arePushNotificationsConfigured()") && settingsFragment.contains("BuildConfig.FIREBASE_CONFIGURED") && settingsFragment.contains("BuildConfig.APP_METRICA_API_KEY.isNotBlank()") && settingsFragment.contains("state.pushPermissionAsked &&") && settingsFragment.contains("isNotificationPermissionGranted()") && settingsFragment.contains("registrationStatus == PushRegistrationStatus.Registered"))
        assertTrue("Settings push status must render as an imagegen console with decorative signal layer", settingsLayout.contains("@+id/pushStatusStage") && settingsLayout.contains("@+id/pushStatusConsole") && settingsLayout.contains("@drawable/settings_push_status_console") && settingsLayout.contains("@+id/pushStatusSignalPulse") && settingsLayout.contains("@drawable/settings_push_status_signal_pulse") && settingsLayout.split("@+id/pushStatusSignalPulse", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && settingsLandscapeLayout.contains("@+id/pushStatusStage") && settingsLandscapeLayout.contains("@+id/pushStatusConsole") && settingsLandscapeLayout.contains("@drawable/settings_push_status_console") && settingsLandscapeLayout.contains("@+id/pushStatusSignalPulse") && settingsLandscapeLayout.contains("@drawable/settings_push_status_signal_pulse"))
        assertTrue("Settings push status must update accessibility dynamically on the stage container", settingsFragment.contains("binding.pushStatusStage.contentDescription = pushStatus.first"))
        assertTrue("Settings push status console must use finite image polish and lifecycle cleanup", settingsFragment.contains("updatePushStatusPolish(pushConfigured = pushConfigured, granted = registered, asked = state.pushPermissionAsked)") && settingsFragment.contains("pushStatusAnimator") && settingsFragment.contains("PUSH_STATUS_POLISH_DURATION_MS") && settingsFragment.contains("stopPushStatusPolish()") && !settingsFragment.contains("pushStatusText.contentDescription = pushStatus.first"))
        assertTrue("Settings safety anchor must use finite image polish and lifecycle cleanup", settingsFragment.contains("binding.settingsSafetyAnchor") && settingsFragment.contains("SETTINGS_SAFETY_ANCHOR_LOW_ALPHA") && settingsFragment.contains("SETTINGS_SAFETY_ANCHOR_SETTLED_ALPHA") && settingsFragment.contains("SETTINGS_SAFETY_ANCHOR_PEAK_ALPHA") && settingsFragment.contains("ObjectAnimator.ofFloat(binding.settingsSafetyAnchor, View.ALPHA") && settingsFragment.contains("stopSettingsConsolePolish()"))
        assertTrue("Settings push status must update visible scalable text", settingsFragment.contains("binding.pushStatusLargeText.text = pushStatus.first") && settingsFragment.contains("binding.pushStatusText.visibility = View.GONE") && settingsFragment.contains("binding.pushStatusLargeText.visibility = View.VISIBLE"))
        assertTrue("Settings must keep a scalable push status peer ready for configured builds", settingsLayout.contains("@+id/pushStatusLargeText") && settingsLayout.contains("android:text=\"@string/push_not_enabled_status\"") && settingsLayout.contains("@drawable/label_push_status_off"))
        assertTrue("Landscape settings layout must keep the same binding controls", listOf("@+id/backButton", "@+id/versionImage", "@+id/versionLargeText", "@+id/socialDisclaimerLargeText", "@+id/privacyButton", "@+id/rulesButton", "@+id/pushButton", "@+id/pushButtonLabel", "@+id/pushStatusStage", "@+id/pushStatusConsole", "@+id/pushStatusSignalPulse", "@+id/pushStatusText", "@+id/settingsControlGlow", "@+id/settingsCenterOrnament", "@+id/settingsSafetyStage", "@+id/settingsSafetyAnchor", "@+id/settingsSafetyPanel", "@+id/settingsSafetyLargeText").all { settingsLandscapeLayout.contains(it) })
        assertTrue("Landscape settings must keep scalable commands and visible feedback copy", Regex("<TextView").findAll(settingsLandscapeLayout).count() == 14 && listOf("@+id/privacyButtonLargeText", "@+id/noticesButtonLargeText", "@+id/rulesButtonLargeText", "@+id/pushButtonLargeText", "@+id/pushStatusLargeText", "@+id/soundToggleLabel", "@+id/soundToggleState", "@+id/analyticsToggleLabel", "@+id/analyticsToggleState", "@+id/hapticsToggleLabel", "@+id/hapticsToggleState").all { settingsLandscapeLayout.contains(it) } && settingsLandscapeLayout.contains("@drawable/title_settings") && settingsLandscapeLayout.contains("@drawable/settings_modal_panel_landscape_premium") && settingsLandscapeLayout.contains("@drawable/settings_safety_anchor") && settingsLandscapeLayout.contains("@drawable/settings_safety_panel") && settingsLandscapeLayout.contains("@drawable/settings_push_status_console") && settingsLandscapeLayout.contains("@drawable/label_push_status_off"))
        assertTrue("Landscape settings must use a scrollable two-column panel with complete 48dp feedback rows", settingsLandscapeLayout.contains("<ScrollView") && settingsLandscapeLayout.contains("android:orientation=\"horizontal\"") && settingsLandscapeLayout.contains("@+id/settingsFeedbackControls") && settingsLandscapeLayout.contains("android:minHeight=\"48dp\"") && settingsLandscapeLayout.contains("android:minHeight=\"32dp\"") && settingsLandscapeLayout.contains("android:layout_width=\"48dp\""))
    }

    @Test
    fun `game hud and result labels render from image assets`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val activePaylineMarkerAssets = (1..10).flatMap { lines ->
            listOf(
                "payline_markers_overlay_active_$lines.webp",
                "payline_markers_overlay_roman_active_$lines.webp"
            )
        }
        val requiredLabels = activePaylineMarkerAssets + listOf(
            "label_bet.webp",
            "label_bet_roman.webp",
            "label_lines.webp",
            "label_lines_roman.webp",
            "label_total_bet.webp",
            "label_total_bet_roman.webp",
            "label_last_win.webp",
            "label_last_win_roman.webp",
            "active_lines_badge.webp",
            "active_lines_badge_roman.webp",
            "free_spins_badge_roman.webp",
            "label_symbol.webp",
            "label_bets.webp",
            "slot_control_meter_glow.webp",
            "slot_control_meter_glow_roman.webp",
            "total_bet_link_pulse.webp",
            "total_bet_link_pulse_roman.webp",
            "total_bet_link_pulse_neon.webp",
            "total_bet_link_pulse_pharaoh.webp",
            "total_bet_link_pulse_ocean.webp",
            "slot_balance_meter_glow.webp",
            "coin_icon.webp",
            "slot_cabinet_lights.webp",
            "slot_cabinet_chase_lights.webp",
            "slot_cabinet_lights_roman.webp",
            "slot_cabinet_chase_lights_roman.webp",
            "slot_machine_frame.webp",
            "slot_machine_frame_roman.webp",
            "slot_machine_frame_violet.webp",
            "slot_marquee_glass.webp",
            "slot_marquee_glass_roman.webp",
            "reel_cell_backdrop.webp",
            "reel_cell_backdrop_roman.webp",
            "reel_depth_dividers.webp",
            "reel_depth_dividers_roman.webp",
            "reel_window_depth_mask.webp",
            "reel_window_depth_mask_roman.webp",
            "symbol_win_halo.webp",
            "symbol_win_halo_violet.webp",
            "symbol_win_halo_roman.webp",
            "symbol_win_halo_neon.webp",
            "symbol_win_halo_pharaoh.webp",
            "symbol_win_halo_ocean.webp",
            "symbol_bonus_scatter_halo_violet.webp",
            "symbol_bonus_scatter_halo_roman.webp",
            "symbol_bonus_scatter_halo_neon.webp",
            "symbol_bonus_scatter_halo_pharaoh.webp",
            "symbol_bonus_scatter_halo_ocean.webp",
            "payline_markers_overlay.webp",
            "payline_win_1.webp",
            "payline_win_2.webp",
            "payline_win_3.webp",
            "payline_win_4.webp",
            "payline_win_5.webp",
            "payline_win_6.webp",
            "payline_win_7.webp",
            "payline_win_8.webp",
            "payline_win_9.webp",
            "payline_win_10.webp",
            "payline_win_roman_1.webp",
            "payline_win_roman_2.webp",
            "payline_win_roman_3.webp",
            "payline_win_roman_4.webp",
            "payline_win_roman_5.webp",
            "payline_win_roman_6.webp",
            "payline_win_roman_7.webp",
            "payline_win_roman_8.webp",
            "payline_win_roman_9.webp",
            "payline_win_roman_10.webp",
            "label_result_win_body.webp",
            "label_result_bonus_body.webp",
            "label_result_lose_body.webp",
            "result_modal_panel.webp",
            "result_modal_panel_roman.webp",
            "result_modal_panel_neon.webp",
            "result_modal_panel_pharaoh.webp",
            "result_modal_panel_ocean.webp",
            "result_modal_panel_violet_premium.webp",
            "result_modal_panel_roman_premium.webp",
            "result_modal_panel_neon_premium.webp",
            "result_modal_panel_pharaoh_premium.webp",
            "result_modal_panel_ocean_premium.webp",
            "result_free_spins_award_panel.webp",
            "result_free_spins_award_panel_roman.webp",
            "result_free_spins_award_panel_neon.webp",
            "result_free_spins_award_panel_pharaoh.webp",
            "result_free_spins_award_panel_ocean.webp",
            "result_stage_lattice.webp",
            "result_stage_lattice_roman.webp",
            "result_stage_lattice_neon.webp",
            "result_stage_lattice_pharaoh.webp",
            "result_stage_lattice_ocean.webp",
            "result_win_payout_burst.webp",
            "result_win_payout_burst_roman.webp",
            "result_win_payout_burst_neon.webp",
            "result_win_payout_burst_pharaoh.webp",
            "result_win_payout_burst_ocean.webp",
            "result_reward_sparkle.webp",
            "win_glow_sprite_violet.webp",
            "win_glow_sprite_roman.webp",
            "win_glow_sprite_neon.webp",
            "win_glow_sprite_pharaoh.webp",
            "win_glow_sprite_ocean.webp",
            "reel_spin_blur.webp",
            "reel_spin_blur_violet.webp",
            "reel_spin_blur_roman.webp",
            "reel_spin_blur_neon.webp",
            "reel_spin_blur_pharaoh.webp",
            "reel_spin_blur_ocean.webp",
            "reel_spin_energy_rim.webp",
            "reel_spin_energy_rim_violet.webp",
            "reel_spin_energy_rim_roman.webp",
            "reel_spin_energy_rim_neon.webp",
            "reel_spin_energy_rim_pharaoh.webp",
            "reel_spin_energy_rim_ocean.webp",
            "reel_stop_flash.webp",
            "reel_stop_flash_violet.webp",
            "reel_stop_flash_roman.webp",
            "reel_stop_flash_neon.webp",
            "reel_stop_flash_pharaoh.webp",
            "reel_stop_flash_ocean.webp",
            "reel_brake_clamp.webp",
            "reel_brake_clamp_roman.webp",
            "reel_brake_clamp_neon.webp",
            "reel_brake_clamp_pharaoh.webp",
            "reel_brake_clamp_ocean.webp",
            "reel_glass_overlay.webp",
            "reel_glass_overlay_violet.webp",
            "reel_glass_overlay_roman.webp",
            "reel_glass_overlay_neon.webp",
            "reel_glass_overlay_pharaoh.webp",
            "reel_glass_overlay_ocean.webp",
            "slot_big_win_banner.webp",
            "slot_bonus_free_spins_banner.webp",
            "slot_big_win_banner_violet.webp",
            "slot_big_win_banner_roman.webp",
            "slot_big_win_banner_neon.webp",
            "slot_big_win_banner_pharaoh.webp",
            "slot_big_win_banner_ocean.webp",
            "slot_bonus_free_spins_banner_violet.webp",
            "slot_bonus_free_spins_banner_roman.webp",
            "slot_bonus_free_spins_banner_neon.webp",
            "slot_bonus_free_spins_banner_pharaoh.webp",
            "slot_bonus_free_spins_banner_ocean.webp",
            "bonus_entry_portal_violet.webp",
            "bonus_entry_portal_roman.webp",
            "bonus_entry_portal_neon.webp",
            "bonus_entry_portal_pharaoh.webp",
            "bonus_entry_portal_ocean.webp",
            "spin_button_ready_glow.webp",
            "spin_button_ready_glow_roman.webp",
            "spin_button_ready_glow_violet.webp",
            "spin_button_roman_default.webp",
            "spin_button_roman_pressed.webp",
            "spin_button_roman_disabled.webp",
            "spin_button_roman_free_spins_default.webp",
            "spin_button_roman_free_spins_pressed.webp",
            "spin_button_roman_free_spins_disabled.webp",
            "spin_button_violet_default.webp",
            "spin_button_violet_pressed.webp",
            "spin_button_violet_disabled.webp",
            "spin_button_violet_free_spins_default.png",
            "spin_button_violet_free_spins_pressed.png",
            "spin_button_violet_free_spins_disabled.png",
            "btn_autospin_default.webp",
            "btn_autospin_pressed.webp",
            "btn_autospin_disabled.webp",
            "btn_autospin_active.webp",
            "btn_autospin_active_pressed.webp",
            "auto_spin_active_halo.webp",
            "free_spins_badge.webp",
            "free_spins_rail_charge.webp",
            "free_spins_rail_charge_roman.webp",
            "free_spins_rail_charge_neon.webp",
            "free_spins_rail_charge_pharaoh.webp",
            "free_spins_rail_charge_ocean.webp",
            "free_spins_stake_lock_overlay_violet.webp",
            "free_spins_stake_lock_overlay_roman.webp",
            "free_spins_stake_lock_overlay_neon.webp",
            "free_spins_stake_lock_overlay_pharaoh.webp",
            "free_spins_stake_lock_overlay_ocean.webp",
            "free_spins_stake_lock_overlay_violet_land.webp",
            "free_spins_stake_lock_overlay_roman_land.webp",
            "free_spins_stake_lock_overlay_neon_land.webp",
            "free_spins_stake_lock_overlay_pharaoh_land.webp",
            "free_spins_stake_lock_overlay_ocean_land.webp",
            "free_spins_mode_overlay_violet.webp",
            "free_spins_mode_overlay_roman.webp",
            "slot_paytable_dock_glow.webp",
            "slot_paytable_dock_glow_roman.webp",
            "slot_paytable_dock_glow_violet.webp",
            "label_paytable_button.webp",
            "slot_spin_deck_glow.webp",
            "slot_spin_deck_glow_roman.webp",
            "slot_spin_deck_glow_violet.webp",
            "paytable_modal_panel.webp",
            "paytable_modal_panel_violet.webp",
            "paytable_modal_panel_roman.webp",
            "paytable_modal_panel_neon.webp",
            "paytable_modal_panel_pharaoh.webp",
            "paytable_modal_panel_ocean.webp",
            "paytable_cabinet_lattice.webp",
            "title_bonus.webp",
            "title_win.webp",
            "title_lose.webp",
            "title_paytable_violet_fortune.webp",
            "title_paytable_roman_reels.webp"
        )
        val missing = requiredLabels.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = requiredLabels.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val paytableLayout = Path.of("src/main/res/layout/dialog_paytable.xml").readText()
        val resultLayout = Path.of("src/main/res/layout/dialog_result.xml").readText()
        val resultLandscapeLayout = Path.of("src/main/res/layout-land/dialog_result.xml").readText()
        val resultDialog = sourceText("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt")
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val slotViewModel = Path.of("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt").readText()
        val slotResultPresentationPolicy = Path.of("src/main/java/com/vslot/app/ui/slot/SlotResultPresentationPolicy.kt").readText()
        val romanSpinSelector = Path.of("src/main/res/drawable/spin_button_roman_selector.xml").readText()
        val violetSpinSelector = Path.of("src/main/res/drawable/spin_button_violet_selector.xml").readText()
        val romanFreeSpinsSpinSelector = Path.of("src/main/res/drawable/spin_button_roman_free_spins_selector.xml").readText()
        val violetFreeSpinsSpinSelector = Path.of("src/main/res/drawable/spin_button_violet_free_spins_selector.xml").readText()
        val autoSpinSelector = Path.of("src/main/res/drawable/btn_autospin_selector.xml").readText()
        val autoSpinActiveSelector = Path.of("src/main/res/drawable/btn_autospin_active_selector.xml").readText()
        val slotMarqueeAnimation = slotFragment
            .substringAfter("private fun animateSlotMarqueeGlass")
            .substringBefore("private fun renderReels")
        val strings = Path.of("src/main/res/values/strings.xml").readText()
        val stakeLockAssets = listOf(
            "free_spins_stake_lock_overlay_violet.webp",
            "free_spins_stake_lock_overlay_roman.webp",
            "free_spins_stake_lock_overlay_neon.webp",
            "free_spins_stake_lock_overlay_pharaoh.webp",
            "free_spins_stake_lock_overlay_ocean.webp",
            "free_spins_stake_lock_overlay_violet_land.webp",
            "free_spins_stake_lock_overlay_roman_land.webp",
            "free_spins_stake_lock_overlay_neon_land.webp",
            "free_spins_stake_lock_overlay_pharaoh_land.webp",
            "free_spins_stake_lock_overlay_ocean_land.webp"
        )
        val tinyStakeLockAssets = stakeLockAssets.filter {
            Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 80_000
        }
        val stakeLockGenerator = Path.of("../tools/slice_imagegen_free_spins_stake_lock_overlay.py").readText()

        assertTrue("Missing game label image assets: $missing", missing.isEmpty())
        assertTrue("Game label image assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Free-spins stake-lock overlays are unexpectedly tiny: $tinyStakeLockAssets", tinyStakeLockAssets.isEmpty())
        assertTrue("Free-spins stake-lock overlays must retain fail-closed historical source and contact-sheet evidence", stakeLockGenerator.contains("NONCANONICAL_HISTORICAL_SLICER") && stakeLockGenerator.contains("vslot_free_spins_stake_lock_overlay_imagegen.png") && stakeLockGenerator.contains("vslot_free_spins_stake_lock_overlay_land_imagegen.png") && stakeLockGenerator.contains("remove_chroma_key") && stakeLockGenerator.contains("free_spins_stake_lock_overlay_contact_sheet.png") && Files.exists(Path.of("../qa/source/vslot_free_spins_stake_lock_overlay_imagegen.png")) && Files.exists(Path.of("../qa/source/vslot_free_spins_stake_lock_overlay_land_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/free_spins_stake_lock_overlay_contact_sheet.png")))
        assertTrue("Slot bet label must render as scalable Russian text", slotLayout.substringAfter("@+id/betLabel").substringBefore("/>").let { it.contains("@style/VSlotAccessibleCopy.MeterLabel") && it.contains("@string/line_bet_short") })
        assertTrue("Slot last win label must render as scalable Russian text", slotLayout.substringAfter("@+id/lastWinLabel").substringBefore("/>").let { it.contains("@style/VSlotAccessibleCopy.MeterLabel") && it.contains("@string/payout_short") })
        assertTrue("Slot control meters must render a dedicated image glow layer", slotLayout.contains("@+id/betPanelMeterGlow") && slotLayout.contains("@+id/lastWinPanelMeterGlow") && slotLayout.contains("@drawable/slot_control_meter_glow"))
        assertTrue("Slot total bet link pulse must render as a decorative image cue in both orientations", slotLayout.contains("@+id/totalBetLinkPulse") && slotLayout.contains("@drawable/total_bet_link_pulse") && slotLayout.split("@+id/totalBetLinkPulse", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.contains("@+id/totalBetLinkPulse") && slotLandscapeLayout.contains("@drawable/total_bet_link_pulse") && slotLandscapeLayout.split("@+id/totalBetLinkPulse", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot total bet link pulse must sit above console backplane and below controls", slotLayout.indexOf("@+id/totalBetLinkPulse") > slotLayout.indexOf("@+id/slotControlConsoleBackplane") && slotLayout.indexOf("@+id/totalBetLinkPulse") < slotLayout.indexOf("@+id/betPanelImage") && slotLandscapeLayout.indexOf("@+id/totalBetLinkPulse") > slotLandscapeLayout.indexOf("@+id/slotControlConsoleBackplane") && slotLandscapeLayout.indexOf("@+id/totalBetLinkPulse") < slotLandscapeLayout.indexOf("@+id/betPanelImage"))
        assertTrue("Slot total bet link pulse must switch theme image assets and animate only on total bet changes", slotFragment.contains("totalBetLinkPulseDrawable") && slotFragment.contains("R.drawable.total_bet_link_pulse_roman") && slotFragment.contains("R.drawable.total_bet_link_pulse_neon") && slotFragment.contains("R.drawable.total_bet_link_pulse_pharaoh") && slotFragment.contains("R.drawable.total_bet_link_pulse_ocean") && slotFragment.contains("binding.totalBetLinkPulse.setImageResource") && slotFragment.contains("animateTotalBetChangeIfNeeded(totalBet)") && slotFragment.contains("TOTAL_BET_LINK_PEAK_ALPHA") && slotFragment.contains("ObjectAnimator.ofFloat(binding.totalBetLinkPulse, View.ALPHA") && slotFragment.contains("binding.totalBetLinkPulse.visibility = View.INVISIBLE"))
        assertTrue("Slot Roman control meter layers must be independently addressable image views", slotLayout.contains("@+id/betPanelImage") && slotLayout.contains("@+id/lastWinPanelImage") && slotLayout.contains("@+id/betLabel") && slotLayout.contains("@+id/linesLabel") && slotLayout.contains("@+id/totalBetLabel") && slotLayout.contains("@+id/lastWinLabel"))
        assertTrue("Slot control meter glow must stay decorative", slotLayout.contains("@+id/betPanelMeterGlow") && slotLayout.contains("@+id/lastWinPanelMeterGlow") && slotLayout.split("@+id/betPanelMeterGlow", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLayout.split("@+id/lastWinPanelMeterGlow", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot balance HUD must render coin icon from image asset", slotLayout.contains("@+id/slotBalanceCoin") && slotLayout.contains("@drawable/coin_icon"))
        assertTrue("Slot balance meter must render a dedicated image glow layer", slotLayout.contains("@+id/slotBalanceMeterGlow") && slotLayout.contains("@drawable/slot_balance_meter_glow"))
        assertTrue("Slot balance meter glow must stay decorative", slotLayout.contains("@+id/slotBalanceMeterGlow") && slotLayout.split("@+id/slotBalanceMeterGlow", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot balance meter glow must sit below coin and bitmap digits", slotLayout.indexOf("@+id/slotBalanceMeterGlow") < slotLayout.indexOf("@+id/slotBalanceCoin") && slotLayout.indexOf("@+id/slotBalanceMeterGlow") < slotLayout.indexOf("@+id/slotBalanceDigits"))
        assertTrue("Slot balance changes must pulse existing image HUD and bitmap digits", slotFragment.contains("animateBalanceChangeIfNeeded(state.playerState.coinsBalance)") && slotFragment.contains("balancePulseAnimator") && slotFragment.contains("BALANCE_CHANGE_PULSE_DURATION_MS") && slotFragment.contains("binding.slotBalancePanel") && slotFragment.contains("binding.slotBalanceMeterGlow") && slotFragment.contains("binding.slotBalanceCoin") && slotFragment.contains("binding.slotBalanceDigits") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot balance pulse must settle image HUD transforms and reset with view", slotFragment.contains("settleBalancePulseTargets()") && slotFragment.contains("lastPresentedBalance") && slotFragment.contains("balancePulseAnimator?.cancel()") && slotFragment.contains("balancePulseAnimator = null"))
        assertTrue("Slot top HUD must use a responsive row for title and balance", slotLayout.contains("@+id/slotTopHudRow") && slotLayout.contains("android:layout_weight=\"1\""))
        assertTrue("Slot machine frame must render from an image asset", slotLayout.contains("@+id/slotMachineFrame") && slotLayout.contains("@drawable/slot_machine_frame"))
        assertTrue("Slot machine frame must switch to theme image assets", slotFragment.contains("R.drawable.slot_machine_frame_roman") && slotFragment.contains("R.drawable.slot_machine_frame_violet") && slotFragment.contains("binding.slotMachineFrame.setImageResource"))
        assertTrue("Slot machine frame must stay decorative", slotLayout.contains("@+id/slotMachineFrame") && slotLayout.split("@+id/slotMachineFrame", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot machine frame must sit below cabinet lights and reel symbols", slotLayout.indexOf("@+id/slotMachineFrame") in 0 until slotLayout.indexOf("@+id/slotCabinetLights") && slotLayout.indexOf("@+id/slotMachineFrame") < slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot cabinet lights must render from an image asset", slotLayout.contains("@+id/slotCabinetLights") && slotLayout.contains("@drawable/slot_cabinet_lights"))
        assertTrue("Slot cabinet lights must switch to Roman image assets for Roman Reels", slotFragment.contains("R.drawable.slot_cabinet_lights_roman") && slotFragment.contains("R.drawable.slot_cabinet_chase_lights_roman") && slotFragment.contains("R.drawable.slot_cabinet_lights") && slotFragment.contains("R.drawable.slot_cabinet_chase_lights"))
        assertTrue("Slot cabinet lights must sit below reel symbols", slotLayout.indexOf("@+id/slotCabinetLights") in 0 until slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot cabinet chase lights must render from an image asset", slotLayout.contains("@+id/slotCabinetChaseLights") && slotLayout.contains("@drawable/slot_cabinet_chase_lights"))
        assertTrue("Slot cabinet chase lights must stay decorative", slotLayout.contains("@+id/slotCabinetChaseLights") && slotLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot cabinet chase lights must sit above static lights and below paylines", slotLayout.indexOf("@+id/slotCabinetChaseLights") > slotLayout.indexOf("@+id/slotCabinetLights") && slotLayout.indexOf("@+id/slotCabinetChaseLights") < slotLayout.indexOf("@+id/paylineMarkersOverlay"))
        assertTrue("Slot marquee glass must render from an image asset", slotLayout.contains("@+id/slotMarqueeGlass") && slotLayout.contains("@drawable/slot_marquee_glass"))
        assertTrue("Slot marquee glass must switch to Roman image asset for Roman Reels", slotFragment.contains("R.drawable.slot_marquee_glass_roman") && slotFragment.contains("R.drawable.slot_marquee_glass"))
        assertTrue("Slot marquee glass must stay decorative", slotLayout.contains("@+id/slotMarqueeGlass") && slotLayout.split("@+id/slotMarqueeGlass", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot marquee glass must sit above cabinet lights and below reel layers", slotLayout.indexOf("@+id/slotMarqueeGlass") > slotLayout.indexOf("@+id/slotCabinetChaseLights") && slotLayout.indexOf("@+id/slotMarqueeGlass") < slotLayout.indexOf("@+id/reelCellBackdropLayer") && slotLayout.indexOf("@+id/slotMarqueeGlass") < slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("SlotFragment must finite-animate the marquee glass image layer", slotFragment.contains("animateSlotMarqueeGlass()") && slotFragment.contains("binding.slotMarqueeGlass") && slotFragment.contains("slotMarqueeGlassAnimator") && slotFragment.contains("SLOT_MARQUEE_GLASS_POLISH_DURATION_MS") && slotFragment.contains("SLOT_MARQUEE_GLASS_SETTLED_ALPHA") && slotMarqueeAnimation.contains("ValueAnimator.areAnimatorsEnabled()") && !slotMarqueeAnimation.contains("ValueAnimator.INFINITE"))
        assertTrue("Slot reel cell backdrops must be image-backed", slotLayout.contains("@+id/reelCellBackdropLayer") && slotFragment.contains("R.drawable.reel_cell_backdrop"))
        assertTrue("Slot reel cell backdrops must switch to Roman image assets for Roman Reels", slotFragment.contains("R.drawable.reel_cell_backdrop_roman") && slotFragment.contains("reelCellBackdrops.forEach"))
        assertTrue("Slot reel cell backdrops must stay decorative", slotLayout.contains("@+id/reelCellBackdropLayer") && slotLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("SlotFragment must create all image reel cell backdrops", slotFragment.contains("setupReelCellBackdropLayer()") && slotFragment.contains("reelCellBackdrops") && slotFragment.contains("binding.reelCellBackdropLayer.addView"))
        assertTrue("Slot reel cell backdrops must sit below paylines and reel symbols", slotLayout.indexOf("@+id/reelCellBackdropLayer") < slotLayout.indexOf("@+id/paylineMarkersOverlay") && slotLayout.indexOf("@+id/reelCellBackdropLayer") < slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot reel depth dividers must render from an image asset", slotLayout.contains("@+id/reelDepthDividers") && slotLayout.contains("@drawable/reel_depth_dividers"))
        assertTrue("Slot reel depth dividers must switch to Roman image assets for Roman Reels", slotFragment.contains("R.drawable.reel_depth_dividers_roman") && slotFragment.contains("binding.reelDepthDividers.setImageResource"))
        assertTrue("Slot reel depth dividers must stay decorative", slotLayout.contains("@+id/reelDepthDividers") && slotLayout.split("@+id/reelDepthDividers", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot reel depth dividers must sit above backdrops and below payline markers and reel symbols", slotLayout.indexOf("@+id/reelDepthDividers") > slotLayout.indexOf("@+id/reelCellBackdropLayer") && slotLayout.indexOf("@+id/reelDepthDividers") < slotLayout.indexOf("@+id/paylineMarkersOverlay") && slotLayout.indexOf("@+id/reelDepthDividers") < slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot reel window depth mask must render from an image asset", slotLayout.contains("@+id/reelWindowDepthMask") && slotLayout.contains("@drawable/reel_window_depth_mask"))
        assertTrue("Slot reel window depth mask must switch to Roman image assets for Roman Reels", slotFragment.contains("R.drawable.reel_window_depth_mask_roman") && slotFragment.contains("binding.reelWindowDepthMask.setImageResource"))
        assertTrue("Slot reel window depth mask must stay decorative", slotLayout.contains("@+id/reelWindowDepthMask") && slotLayout.split("@+id/reelWindowDepthMask", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot reel window depth mask must sit above symbols and below payline feedback and glass", slotLayout.indexOf("@+id/reelWindowDepthMask") > slotLayout.indexOf("@+id/reelsGrid") && slotLayout.indexOf("@+id/reelWindowDepthMask") < slotLayout.indexOf("@+id/winningPaylineOverlay") && slotLayout.indexOf("@+id/reelWindowDepthMask") < slotLayout.indexOf("@+id/spinBlurOverlay") && slotLayout.indexOf("@+id/reelWindowDepthMask") < slotLayout.indexOf("@+id/reelGlassOverlay"))
        assertTrue("SlotFragment must finite-animate the reel window depth image layer", slotFragment.contains("animateReelWindowDepthMask()") && slotFragment.contains("binding.reelWindowDepthMask") && slotFragment.contains("reelWindowDepthAnimator") && slotFragment.contains("REEL_WINDOW_DEPTH_POLISH_DURATION_MS") && slotFragment.contains("REEL_WINDOW_DEPTH_SETTLED_ALPHA") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot spin energy must render from theme image assets", slotLayout.contains("@+id/spinEnergyOverlay") && slotLayout.contains("@drawable/reel_spin_energy_rim_violet") && slotFragment.contains("spinEnergyOverlayDrawable(theme)") && slotFragment.contains("binding.spinEnergyOverlay.setImageResource"))
        assertTrue("Slot spin energy must stay decorative", slotLayout.contains("@+id/spinEnergyOverlay") && slotLayout.split("@+id/spinEnergyOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot spin energy must sit above depth and below paylines, blur, and glass", slotLayout.indexOf("@+id/spinEnergyOverlay") > slotLayout.indexOf("@+id/reelWindowDepthMask") && slotLayout.indexOf("@+id/spinEnergyOverlay") < slotLayout.indexOf("@+id/winningPaylineOverlay") && slotLayout.indexOf("@+id/spinEnergyOverlay") < slotLayout.indexOf("@+id/spinBlurOverlay") && slotLayout.indexOf("@+id/spinEnergyOverlay") < slotLayout.indexOf("@+id/reelGlassOverlay"))
        assertTrue("SlotFragment must use bounded spin-energy feedback without a full-spin overdraw loop", slotFragment.contains("startSpinEnergyOverlay()") && slotFragment.contains("stopSpinEnergyOverlay()") && slotFragment.contains("spinEnergyAnimator = animation") && slotFragment.contains("binding.spinEnergyOverlay") && slotFragment.contains("SPIN_ENERGY_HIGH_ALPHA") && slotFragment.contains("SPIN_ENERGY_PULSE_DURATION_MS") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot winning symbol halo layer must render lazily from theme image assets", slotLayout.contains("@+id/symbolWinHaloLayer") && slotFragment.contains("R.drawable.symbol_win_halo_violet") && slotFragment.contains("symbolWinHaloDrawable(viewModel.uiState.value.config.theme)") && slotFragment.contains("halo.clearBoundImageResource()") && !slotFragment.contains("setImageResource(R.drawable.symbol_win_halo)"))
        assertTrue("Slot winning symbol halo layer must stay decorative", slotLayout.contains("@+id/symbolWinHaloLayer") && slotLayout.split("@+id/symbolWinHaloLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("SlotFragment must create winning symbol halos as ImageViews", slotFragment.contains("setupSymbolWinHaloLayer()") && slotFragment.contains("symbolWinHalos") && slotFragment.contains("binding.symbolWinHaloLayer.addView"))
        assertTrue("Slot winning symbol halo layer must sit above global win glow and below reel symbols", slotLayout.indexOf("@+id/symbolWinHaloLayer") > slotLayout.indexOf("@+id/winGlowOverlay") && slotLayout.indexOf("@+id/symbolWinHaloLayer") < slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot winning symbols must not use view backgrounds for payout polish", !slotFragment.contains("setBackgroundResource(R.drawable.symbol_win_highlight)"))
        assertTrue("Slot winning symbol halos must animate as finite image feedback", slotFragment.contains("renderSymbolWinHalos(highlightedCells, shouldAnimateHighlights)") && slotFragment.contains("animateSymbolWinHalos(highlightedCells)") && slotFragment.contains("symbolWinHaloAnimator") && slotFragment.contains("duration = 430L") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot payline markers must render from an image asset", slotLayout.contains("@+id/paylineMarkersOverlay") && slotLayout.contains("@drawable/payline_markers_overlay"))
        assertTrue("Slot payline markers must switch to dynamic image states by displayed line count", slotFragment.contains("binding.paylineMarkersOverlay.setImageResource") && slotFragment.contains("paylineMarkersOverlayDrawable(state.config.theme, selectedLines)") && slotFragment.contains("R.drawable.payline_markers_overlay_active_1") && slotFragment.contains("R.drawable.payline_markers_overlay_active_10") && slotFragment.contains("R.drawable.payline_markers_overlay_roman_active_1") && slotFragment.contains("R.drawable.payline_markers_overlay_roman_active_10"))
        assertTrue("Slot active line changes must pulse existing image markers and bitmap line digits", slotFragment.contains("animateActiveLinesChangeIfNeeded(selectedLines)") && slotFragment.contains("activeLinesPulseAnimator") && slotFragment.contains("ACTIVE_LINES_PULSE_DURATION_MS") && slotFragment.contains("binding.paylineMarkersOverlay") && slotFragment.contains("binding.activeLinesRail") && slotFragment.contains("binding.linesDigits") && slotFragment.contains("binding.activeLinesRailDigits") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot active line pulse must be cancelled with the slot view", slotFragment.contains("activeLinesPulseAnimator?.cancel()") && slotFragment.contains("activeLinesPulseAnimator = null"))
        assertTrue("Slot payline markers must keep accessibility text", slotLayout.contains("android:contentDescription=\"@string/slot_paylines\""))
        assertTrue("Slot free spins counter must render from image rail and bitmap digits", slotLayout.contains("@+id/freeSpinsRail") && slotLayout.contains("@drawable/free_spins_badge") && slotLayout.contains("@+id/freeSpinsDigits") && slotLayout.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("Slot free spins rail charge must render as a decorative image layer below the rail", slotLayout.contains("@+id/freeSpinsRailCharge") && slotLayout.contains("@drawable/free_spins_rail_charge") && slotLayout.indexOf("@+id/freeSpinsRailCharge") in 0 until slotLayout.indexOf("@+id/freeSpinsRailImage") && slotLayout.split("@+id/freeSpinsRailCharge", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot Roman free spins and active line rails must switch dedicated image assets", slotLayout.contains("@+id/freeSpinsRailImage") && slotLayout.contains("@+id/activeLinesRailImage") && slotLayout.contains("@+id/activeLinesRailLabel") && slotFragment.contains("binding.freeSpinsRailImage.setImageResource") && slotFragment.contains("binding.activeLinesRailImage.setImageResource") && slotFragment.contains("binding.activeLinesRailLabel.setImageResource"))
        assertTrue("Slot free spins rail charge must switch dedicated theme image assets", slotFragment.contains("freeSpinsRailChargeDrawable(theme)") && slotFragment.contains("binding.freeSpinsRailCharge.setImageResource") && slotFragment.contains("R.drawable.free_spins_rail_charge_roman") && slotFragment.contains("R.drawable.free_spins_rail_charge_neon") && slotFragment.contains("R.drawable.free_spins_rail_charge_pharaoh") && slotFragment.contains("R.drawable.free_spins_rail_charge_ocean"))
        assertTrue("Slot free spins counter must bind persisted current-slot free spin balance", slotFragment.contains("state.playerState.freeSpinsForSlot(state.config.id)") && slotFragment.contains("binding.freeSpinsDigits.setNumber(freeSpins)") && slotFragment.contains("R.string.free_spins_remaining"))
        assertTrue("Slot free spins changes must pulse existing image rail and bitmap digits", slotFragment.contains("animateFreeSpinsChangeIfNeeded(freeSpins)") && slotFragment.contains("freeSpinsPulseAnimator") && slotFragment.contains("FREE_SPINS_PULSE_DURATION_MS") && slotFragment.contains("binding.freeSpinsRail") && slotFragment.contains("binding.freeSpinsDigits") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        val freeSpinsRailAnimation = slotFragment.substringAfter("private fun startFreeSpinsRailCharge").substringBefore("private fun stopFreeSpinsRailCharge")
        assertTrue("Slot free spins rail charge must animate finitely while a free spin is available or active", slotFragment.contains("updateFreeSpinsRailCharge(freeSpinModeActive)") && slotFragment.contains("startFreeSpinsRailCharge()") && slotFragment.contains("stopFreeSpinsRailCharge(immediate = true)") && slotFragment.contains("freeSpinsRailChargeAnimator") && slotFragment.contains("FREE_SPINS_RAIL_CHARGE_HIGH_ALPHA") && !freeSpinsRailAnimation.contains("ValueAnimator.INFINITE") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot free spins pulse must settle and reset with the slot view", slotFragment.contains("settleFreeSpinsPulseTargets") && slotFragment.contains("freeSpinsPulseAnimator?.cancel()") && slotFragment.contains("lastPresentedFreeSpins = null"))
        assertTrue("Slot free spins mode must render from a dedicated image overlay", slotLayout.contains("@+id/freeSpinsModeOverlay") && slotLayout.contains("@drawable/free_spins_mode_overlay_violet"))
        assertTrue("Slot free spins mode overlay must switch theme assets", slotFragment.contains("R.drawable.free_spins_mode_overlay_roman") && slotFragment.contains("R.drawable.free_spins_mode_overlay_violet") && slotFragment.contains("binding.freeSpinsModeOverlay.setImageResource"))
        assertTrue("Slot free spins mode overlay must stay decorative", slotLayout.contains("@+id/freeSpinsModeOverlay") && slotLayout.split("@+id/freeSpinsModeOverlay", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot free spins mode overlay must sit above reel depth and below spin/win feedback", slotLayout.indexOf("@+id/freeSpinsModeOverlay") > slotLayout.indexOf("@+id/reelWindowDepthMask") && slotLayout.indexOf("@+id/freeSpinsModeOverlay") < slotLayout.indexOf("@+id/spinEnergyOverlay") && slotLayout.indexOf("@+id/freeSpinsModeOverlay") < slotLayout.indexOf("@+id/winningPaylineOverlay"))
        val freeSpinsModeAnimation = slotFragment.substringAfter("private fun startFreeSpinsModeOverlay").substringBefore("private fun stopFreeSpinsModeOverlay")
        assertTrue("SlotFragment must animate free spins mode finitely while a free spin is available or active", slotFragment.contains("updateFreeSpinsModeOverlay(freeSpinModeActive)") && slotFragment.contains("startFreeSpinsModeOverlay()") && slotFragment.contains("stopFreeSpinsModeOverlay") && slotFragment.contains("freeSpinsModeAnimator") && slotFragment.contains("FREE_SPINS_MODE_HIGH_ALPHA") && !freeSpinsModeAnimation.contains("ValueAnimator.INFINITE") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Free-spins stake controls must show a dedicated image lock overlay in both orientations", slotLayout.contains("@+id/freeSpinsStakeLockOverlay") && slotLayout.contains("@drawable/free_spins_stake_lock_overlay_violet") && slotLandscapeLayout.contains("@+id/freeSpinsStakeLockOverlay") && slotLandscapeLayout.contains("@drawable/free_spins_stake_lock_overlay_violet_land"))
        assertTrue("Free-spins stake-lock overlay must sit above bet and line controls, stay decorative, and not block accessibility", slotLayout.indexOf("@+id/freeSpinsStakeLockOverlay") > slotLayout.indexOf("@+id/linesStepperGroup") && slotLayout.indexOf("@+id/freeSpinsStakeLockOverlay") < slotLayout.indexOf("@+id/lastWinPanelImage") && slotLayout.split("@+id/freeSpinsStakeLockOverlay", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/freeSpinsStakeLockOverlay", limit = 2)[1].substringBefore("/>").contains("android:importantForAccessibility=\"no\""))
        assertTrue("Free-spins stake-lock overlay must switch theme and orientation assets", slotFragment.contains("freeSpinsStakeLockOverlayDrawable(theme)") && slotFragment.contains("binding.freeSpinsStakeLockOverlay.setImageResource") && slotFragment.contains("Configuration.ORIENTATION_LANDSCAPE") && slotFragment.contains("R.drawable.free_spins_stake_lock_overlay_roman") && slotFragment.contains("R.drawable.free_spins_stake_lock_overlay_neon") && slotFragment.contains("R.drawable.free_spins_stake_lock_overlay_pharaoh") && slotFragment.contains("R.drawable.free_spins_stake_lock_overlay_ocean") && slotFragment.contains("R.drawable.free_spins_stake_lock_overlay_roman_land") && slotFragment.contains("R.drawable.free_spins_stake_lock_overlay_ocean_land"))
        assertTrue("Free-spins stake-lock overlay must animate only while stake controls are locked and keep the fixed values readable", slotFragment.contains("updateFreeSpinsStakeLockOverlay(freeSpinModeActive)") && slotFragment.contains("freeSpinsStakeLockActive") && !slotFragment.contains("setStakeControlsVisible(false)") && slotFragment.contains("setStakeControlsVisible(true)") && slotFragment.contains("binding.betStepperGroup.visibility") && slotFragment.contains("binding.linesStepperGroup.visibility") && slotFragment.contains("R.string.free_spins_stake_locked") && slotFragment.contains("displayedLineBet") && slotFragment.contains("selectedLines") && slotFragment.contains("startFreeSpinsStakeLockOverlay()") && slotFragment.contains("stopFreeSpinsStakeLockOverlay(immediate = true)") && slotFragment.contains("FREE_SPINS_STAKE_LOCK_SETTLED_ALPHA") && slotFragment.contains("FREE_SPINS_STAKE_LOCK_PEAK_ALPHA") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Autospin started during current-slot free spins must stop before paid spins resume", slotViewModel.contains("freeSpinsAfter = updatedState.freeSpinsForSlot(config.id)") && slotViewModel.contains("spin.autoTriggered && spin.isFreeSpin && settlement.freeSpinsAfter <= 0") && slotViewModel.contains("pauseAutoSpin()"))
        assertTrue("Slot free spins mode overlay must reset with the slot view", slotFragment.contains("stopFreeSpinsModeOverlay(immediate = true)") && slotFragment.contains("freeSpinsModeAnimator?.cancel()") && slotFragment.contains("freeSpinsModeAnimator = null"))
        assertTrue("Slot free spins counter must not render visible copy through android:text", !slotLayout.contains("android:text=\"@string/free_spins_remaining\""))
        assertTrue("Slot active paylines accessibility must use correct Russian line forms", strings.contains("slot_active_paylines_one") && strings.contains("линия выплат") && strings.contains("slot_active_paylines_few") && strings.contains("линии выплат") && strings.contains("slot_active_paylines_many") && strings.contains("линий выплат") && slotFragment.contains("private fun activePaylinesDescription") && slotFragment.contains("normalizedLines % 100 in 11..14") && slotFragment.contains("R.string.slot_active_paylines_one") && slotFragment.contains("R.string.slot_active_paylines_few") && slotFragment.contains("R.string.slot_active_paylines_many"))
        assertTrue("Slot payline markers must sit below reel symbols", slotLayout.indexOf("@+id/paylineMarkersOverlay") in 0 until slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot payline markers must not render through android:text", !slotLayout.contains("android:text=\"@string/slot_paylines\""))
        assertTrue("Slot winning payline overlay must default to an image asset", slotLayout.contains("@+id/winningPaylineOverlay") && slotLayout.contains("@drawable/payline_win_1"))
        assertTrue("Roman Reels must use theme-specific image payline win overlays", slotFragment.contains("ROMAN_PAYLINE_WIN_DRAWABLES") && slotFragment.contains("R.drawable.payline_win_roman_10") && slotFragment.contains("paylineWinDrawable(theme, lineIndex)"))
        assertTrue("Slot winning payline overlay must sit above reel symbols", slotLayout.indexOf("@+id/winningPaylineOverlay") > slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot winning payline overlay must sit below spin blur and glass", slotLayout.indexOf("@+id/winningPaylineOverlay") < slotLayout.indexOf("@+id/spinBlurOverlay") && slotLayout.indexOf("@+id/winningPaylineOverlay") < slotLayout.indexOf("@+id/reelGlassOverlay"))
        assertTrue("Slot winning payline overlay must not render through android:text", !slotLayout.contains("android:text=\"@string/slot_winning_payline\""))
        assertTrue("Slot winning payline accessibility string missing", strings.contains("slot_winning_payline"))
        assertTrue("Slot win glow overlay must render from theme image assets", slotLayout.contains("@+id/winGlowOverlay") && slotLayout.contains("@drawable/win_glow_sprite_violet") && slotFragment.contains("winGlowSpriteDrawable(theme)") && slotFragment.contains("binding.winGlowOverlay.setImageResource"))
        assertTrue("Slot coin burst overlay must render from a theme win burst image asset", slotLayout.contains("@+id/coinBurstOverlay") && slotLayout.contains("@drawable/theme_win_burst_violet"))
        assertTrue("Slot bonus entry portal must render from theme image assets", slotLayout.contains("@+id/bonusEntryPortalOverlay") && slotLayout.contains("@drawable/bonus_entry_portal_violet") && slotFragment.contains("bonusEntryPortalDrawable(theme)"))
        assertTrue("Slot big win banner must render from theme image assets", slotLayout.contains("@+id/bigWinBannerOverlay") && slotLayout.contains("@drawable/slot_big_win_banner_violet") && slotFragment.contains("bigWinBannerDrawable(theme)"))
        assertTrue("Slot bonus free-spins banner must render from a theme image asset", slotFragment.contains("bonusFreeSpinsBannerDrawable(theme)") && slotFragment.contains("R.drawable.slot_bonus_free_spins_banner_violet") && slotFragment.contains("R.string.slot_bonus_free_spins_banner"))
        assertTrue("Slot big win banner must keep accessibility text", slotLayout.contains("android:contentDescription=\"@string/slot_big_win_banner\""))
        assertTrue("Slot spin ready glow must render from an image asset", slotLayout.contains("@+id/spinButtonReadyGlow") && slotLayout.contains("@drawable/spin_button_ready_glow"))
        assertTrue("Slot spin ready glow must switch to Roman image asset for Roman Reels", slotFragment.contains("R.drawable.spin_button_ready_glow_roman") && slotFragment.contains("binding.spinButtonReadyGlow.setImageResource"))
        assertTrue("Slot spin ready glow must switch to Violet image asset for Violet Fortune", slotFragment.contains("R.drawable.spin_button_ready_glow_violet") && slotFragment.contains("binding.spinButtonReadyGlow.setImageResource"))
        assertTrue("Slot spin ready glow must be decorative only", slotLayout.contains("@+id/spinButtonReadyGlow") && slotLayout.contains("android:contentDescription=\"@null\""))
        assertTrue("Slot spin deck must render from a dedicated image asset", slotLayout.contains("@+id/spinDeckGlow") && slotLayout.contains("@drawable/slot_spin_deck_glow"))
        assertTrue("Slot spin deck must switch to Roman image asset for Roman Reels", slotFragment.contains("R.drawable.slot_spin_deck_glow_roman") && slotFragment.contains("binding.spinDeckGlow.setImageResource"))
        assertTrue("Slot spin deck must switch to Violet image asset for Violet Fortune", slotFragment.contains("R.drawable.slot_spin_deck_glow_violet") && slotFragment.contains("binding.spinDeckGlow.setImageResource"))
        assertTrue("Slot spin deck must stay decorative", slotLayout.contains("@+id/spinDeckGlow") && slotLayout.split("@+id/spinDeckGlow", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot paytable button dock must render from an image asset", slotLayout.contains("@+id/paytableButtonDockGlow") && slotLayout.contains("@drawable/slot_paytable_dock_glow"))
        assertTrue("Slot paytable button dock must switch to Roman image asset for Roman Reels", slotFragment.contains("R.drawable.slot_paytable_dock_glow_roman") && slotFragment.contains("binding.paytableButtonDockGlow.setImageResource"))
        assertTrue("Slot paytable button dock must switch to Violet image asset for Violet Fortune", slotFragment.contains("R.drawable.slot_paytable_dock_glow_violet") && slotFragment.contains("binding.paytableButtonDockGlow.setImageResource"))
        assertTrue("Slot paytable button dock must stay decorative", slotLayout.contains("@+id/paytableButtonDockGlow") && slotLayout.split("@+id/paytableButtonDockGlow", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot paytable button label must render from an image asset", slotLayout.contains("@+id/paytableButtonLabel") && slotLayout.contains("@drawable/label_paytable_button"))
        assertTrue("Slot paytable button label must stay inside the clickable dock", slotLayout.indexOf("@+id/paytableButtonLabel") > slotLayout.indexOf("android:id=\"@+id/paytableButton\"") && slotLayout.indexOf("@+id/paytableButtonLabel") < slotLayout.indexOf("android:id=\"@+id/spinButton\""))
        assertTrue("Slot paytable button must not render visible label through android text", !slotLayout.contains("android:text=\"@string/paytable\""))
        assertTrue("Roman spin button selector must render image states only", romanSpinSelector.contains("@drawable/spin_button_roman_default") && romanSpinSelector.contains("@drawable/spin_button_roman_pressed") && romanSpinSelector.contains("@drawable/spin_button_roman_disabled") && !romanSpinSelector.contains("android:text"))
        assertTrue("Violet spin button selector must render image states only", violetSpinSelector.contains("@drawable/spin_button_violet_default") && violetSpinSelector.contains("@drawable/spin_button_violet_pressed") && violetSpinSelector.contains("@drawable/spin_button_violet_disabled") && !violetSpinSelector.contains("android:text"))
        assertTrue("Roman free-spins spin selector must render image states only", romanFreeSpinsSpinSelector.contains("@drawable/spin_button_roman_free_spins_default") && romanFreeSpinsSpinSelector.contains("@drawable/spin_button_roman_free_spins_pressed") && romanFreeSpinsSpinSelector.contains("@drawable/spin_button_roman_free_spins_disabled") && !romanFreeSpinsSpinSelector.contains("android:text"))
        assertTrue("Violet free-spins spin selector must render image states only", violetFreeSpinsSpinSelector.contains("@drawable/spin_button_violet_free_spins_default") && violetFreeSpinsSpinSelector.contains("@drawable/spin_button_violet_free_spins_pressed") && violetFreeSpinsSpinSelector.contains("@drawable/spin_button_violet_free_spins_disabled") && !violetFreeSpinsSpinSelector.contains("android:text"))
        assertTrue("Slot spin button must switch to theme and free-spins image selectors", slotFragment.contains("private fun spinButtonDrawable(theme: SlotTheme, hasFreeSpins: Boolean)") && slotFragment.contains("R.drawable.spin_button_roman_selector") && slotFragment.contains("R.drawable.spin_button_violet_selector") && slotFragment.contains("R.drawable.spin_button_roman_free_spins_selector") && slotFragment.contains("R.drawable.spin_button_violet_free_spins_selector") && slotFragment.contains("binding.spinButton.setImageResource(spinButtonDrawable(state.config.theme, freeSpinModeActive))"))
        assertTrue("Slot spin button must expose Russian free-spins accessibility while keeping visible copy image-based", slotFragment.contains("R.string.spin_free_spins") && strings.contains("spin_free_spins") && !slotLayout.contains("android:text=\"@string/spin_free_spins\""))
        assertTrue("Slot autospin control must render from image selectors with active state", slotLayout.contains("@+id/autoSpinButton") && slotLayout.contains("@drawable/btn_autospin_selector") && autoSpinSelector.contains("@drawable/btn_autospin_default") && autoSpinSelector.contains("@drawable/btn_autospin_pressed") && autoSpinSelector.contains("@drawable/btn_autospin_disabled") && autoSpinActiveSelector.contains("@drawable/btn_autospin_active") && autoSpinActiveSelector.contains("@drawable/btn_autospin_active_pressed"))
        assertTrue("Slot autospin control must open a bounded batch picker, expose remaining spins, and stop an active batch", slotFragment.contains("binding.autoSpinButton.setOnClickListener { handleAutoSpinClick() }") && slotFragment.contains("viewModel.startAutoSpin(count)") && slotFragment.contains("viewModel.stopAutoSpin()") && slotFragment.contains("state.autoSpinsRemaining") && slotFragment.contains("R.string.auto_spin_configure") && slotFragment.contains("R.string.auto_spin_stop_remaining") && slotLayout.contains("@+id/autoSpinRemainingDigits") && slotLayout.contains("@+id/autoSpinStopOverlay"))
        assertTrue("Slot autospin active mode must render a dedicated image halo behind the button", slotLayout.contains("@+id/autoSpinActiveHalo") && slotLayout.contains("@drawable/auto_spin_active_halo") && slotLayout.indexOf("@+id/autoSpinActiveHalo") in 0 until slotLayout.indexOf("@+id/autoSpinButton"))
        assertTrue("Slot autospin active halo must stay decorative", slotLayout.contains("@+id/autoSpinActiveHalo") && slotLayout.split("@+id/autoSpinActiveHalo", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot autospin active halo must follow ViewModel state and animate as image feedback", slotFragment.contains("updateAutoSpinActiveHalo(state.isAutoSpinEnabled)") && slotFragment.contains("startAutoSpinActiveHalo()") && slotFragment.contains("stopAutoSpinActiveHalo(immediate = true)") && slotFragment.contains("autoSpinHaloAnimator") && slotFragment.contains("AUTO_SPIN_HALO_PULSE_DURATION_MS") && slotFragment.contains("AUTO_SPIN_HALO_ROTATION_DURATION_MS") && slotFragment.contains("ValueAnimator.INFINITE") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("Slot spinning blur must render from theme image assets", slotLayout.contains("@+id/spinBlurOverlay") && slotLayout.contains("@drawable/reel_spin_blur_violet") && slotFragment.contains("reelSpinBlurDrawable(theme)") && slotFragment.contains("binding.spinBlurOverlay.setImageResource"))
        assertTrue("Slot reel stop flash must have an image-backed layer", slotLayout.contains("@+id/reelStopFlashLayer") && slotFragment.contains("R.drawable.reel_stop_flash"))
        assertTrue("Slot reel brake clamp must have an image-backed layer", slotLayout.contains("@+id/reelBrakeLayer") && slotFragment.contains("R.drawable.reel_brake_clamp"))
        assertTrue("Slot reel glass overlay must render from theme image assets", slotLayout.contains("@+id/reelGlassOverlay") && slotLayout.contains("@drawable/reel_glass_overlay_violet") && slotFragment.contains("reelGlassOverlayDrawable(theme)") && slotFragment.contains("binding.reelGlassOverlay.setImageResource"))
        assertTrue("Slot win glow must sit inside the slot machine frame before reel symbols", slotLayout.indexOf("@+id/winGlowOverlay") in 0 until slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot spinning blur must sit above reel symbols during spin", slotLayout.indexOf("@+id/spinBlurOverlay") > slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot reel brake clamp must sit above moving strips and below the window mask", slotLayout.indexOf("@+id/reelBrakeLayer") > slotLayout.indexOf("@+id/reelSpinStripLayer") && slotLayout.indexOf("@+id/reelBrakeLayer") < slotLayout.indexOf("@+id/reelWindowDepthMask") && slotLayout.indexOf("@+id/reelBrakeLayer") < slotLayout.indexOf("@+id/reelGlassOverlay"))
        assertTrue("Slot reel stop flash must sit above spin blur and below glass", slotLayout.indexOf("@+id/reelStopFlashLayer") > slotLayout.indexOf("@+id/spinBlurOverlay") && slotLayout.indexOf("@+id/reelStopFlashLayer") < slotLayout.indexOf("@+id/reelGlassOverlay"))
        assertTrue("Slot reel glass must sit above reel symbols, spin blur, and stop flash", slotLayout.indexOf("@+id/reelGlassOverlay") > slotLayout.indexOf("@+id/spinBlurOverlay") && slotLayout.indexOf("@+id/reelGlassOverlay") > slotLayout.indexOf("@+id/reelStopFlashLayer"))
        assertTrue("Slot reel glass must stay below coin burst feedback", slotLayout.indexOf("@+id/reelGlassOverlay") < slotLayout.indexOf("@+id/coinBurstOverlay"))
        assertTrue("Slot coin burst must sit above reel symbols for visible win feedback", slotLayout.indexOf("@+id/coinBurstOverlay") > slotLayout.indexOf("@+id/reelsGrid"))
        assertTrue("Slot big win banner must sit above reel symbols and coin burst feedback", slotLayout.indexOf("@+id/bigWinBannerOverlay") > slotLayout.indexOf("@+id/reelsGrid") && slotLayout.indexOf("@+id/bigWinBannerOverlay") > slotLayout.indexOf("@+id/coinBurstOverlay"))
        assertTrue("Slot spin deck must sit below all bottom controls", slotLayout.indexOf("@+id/spinDeckGlow") in 0 until slotLayout.indexOf("@+id/spinButtonReadyGlow") && slotLayout.indexOf("@+id/spinDeckGlow") < slotLayout.indexOf("@+id/paytableButtonDockGlow") && slotLayout.indexOf("@+id/spinDeckGlow") < slotLayout.indexOf("android:id=\"@+id/paytableButton\"") && slotLayout.indexOf("@+id/spinDeckGlow") < slotLayout.indexOf("android:id=\"@+id/spinButton\""))
        assertTrue("Slot spin ready glow must sit below the spin button", slotLayout.indexOf("@+id/spinButtonReadyGlow") in 0 until slotLayout.indexOf("android:id=\"@+id/spinButton\""))
        assertTrue("Slot paytable button dock must sit below the paytable button", slotLayout.indexOf("@+id/paytableButtonDockGlow") in 0 until slotLayout.indexOf("android:id=\"@+id/paytableButton\""))
        assertTrue("Slot control meter glow must sit above panel art and below controls", slotLayout.indexOf("@drawable/slot_control_meter_glow") > slotLayout.indexOf("@drawable/bet_panel") && slotLayout.indexOf("@drawable/slot_control_meter_glow") < slotLayout.indexOf("@+id/betMinusButton") && slotLayout.indexOf("@+id/lastWinPanelMeterGlow") < slotLayout.indexOf("@+id/lastWinDigits"))
        assertTrue("Slot big win and bonus banner accessibility strings missing", strings.contains("slot_big_win_banner") && strings.contains("slot_bonus_free_spins_banner"))
        assertTrue("Slot big win banner must not render through android:text", !slotLayout.contains("android:text=\"@string/slot_big_win_banner\""))
        assertTrue("SlotFragment must use bounded image blur feedback at spin acceleration", slotFragment.contains("startSpinBlurOverlay()") && slotFragment.contains("stopSpinBlurOverlay()") && slotFragment.contains("SPIN_BLUR_INTRO_DURATION_MS") && slotFragment.contains("overlay.visibility = View.INVISIBLE"))
        assertTrue("SlotFragment spin blur must respect disabled system animators", slotFragment.contains("startSpinBlurOverlay()") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("SlotFragment must present cabinet chase lights by slot state without a full-spin animation loop", slotFragment.contains("updateCabinetLights(if (state.isSpinning) CabinetLightMode.Spinning else CabinetLightMode.Idle)") && slotFragment.contains("slotCabinetChaseLights") && slotFragment.contains("CABINET_SPIN_CHASE_ALPHA"))
        assertTrue("SlotFragment must reset cabinet lights with lifecycle feedback", slotFragment.contains("updateCabinetLights") && slotFragment.contains("stopCabinetLights()"))
        assertTrue("SlotFragment must animate image reel-stop flashes once per durable result", slotFragment.contains("animateReelStopIfNeeded(state.lastResult, state.lastResultPresentationId)") && slotFragment.contains("presentationId == completedSpinPreviewPresentationId") && slotFragment.contains("setupReelStopFlashLayer()") && slotFragment.contains("hideReelStopFlashLayer(immediate = true)") && slotFragment.contains("column * 95L"))
        assertTrue("SlotFragment must animate image brake clamps for physical reel stopping", slotFragment.contains("setupReelBrakeLayer()") && slotFragment.contains("reelBrakeViews") && slotFragment.contains("binding.reelBrakeLayer.addView") && slotFragment.contains("reelBrakeClampDrawable") && slotFragment.contains("pulseReelBrakeColumn(column, scatterChase = scatterChase)") && slotFragment.contains("pulseReelBrakeColumn(column, scatterChase = false, finalStop = true)") && slotFragment.contains("animateReelBrakeSequence()"))
        assertTrue("SlotFragment reel-stop flash must respect disabled system animators", slotFragment.contains("animateReelStopFlashLayer()") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("SlotFragment must reserve the image big win banner for bonus or large 10x net wins", slotFragment.contains("private fun shouldShowBigWinBanner(result: SpinResult)") && slotFragment.contains("SlotResultPresentationPolicy.shouldShowResultDialog(result)") && slotResultPresentationPolicy.contains("result.netOutcome == NetOutcome.Bonus") && slotResultPresentationPolicy.contains("result.netOutcome != NetOutcome.NetWin") && slotResultPresentationPolicy.contains("result.winAmount.toLong()") && slotResultPresentationPolicy.contains("BIG_WIN_TOTAL_BET_MULTIPLIER = 10"))
        assertTrue("SlotFragment must animate the image big win banner only after the big-win gate", slotFragment.contains("val showBigWinBanner = shouldShowBigWinBanner(result)") && slotFragment.contains("if (showBigWinBanner)") && slotFragment.contains("animateBigWinBanner(result)") && slotFragment.contains("hideBigWinBanner(immediate = true)") && slotFragment.contains("bigWinBannerAnimator") && slotFragment.contains("AnimatorSet"))
        assertTrue("SlotFragment must animate the spin ready glow only when controls are enabled", slotFragment.contains("updateSpinReadyGlow(controlsEnabled)") && slotFragment.contains("startSpinReadyGlow()") && slotFragment.contains("stopSpinReadyGlow(immediate = true)") && slotFragment.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue("SlotFragment must map all ten winning paylines to image assets", slotFragment.contains("paylineWinDrawable") && slotFragment.contains("R.drawable.payline_win_1") && slotFragment.contains("R.drawable.payline_win_10"))
        assertTrue("SlotFragment must order winning paylines before rendering image feedback", slotFragment.contains("renderWinningPaylineOverlay(state.config.theme, state.lastResult)") && slotFragment.contains("orderedWinningLines") && slotFragment.contains("compareByDescending<WinningLine>") && slotFragment.contains("thenByDescending { it.count }"))
        assertTrue(
            "SlotFragment must cycle multiple winning payline image overlays and settle on the strongest line",
            slotFragment.contains("winningPaylineCarouselJob") &&
                slotFragment.contains("startWinningPaylineCarousel") &&
                slotFragment.contains("SlotWinFeedbackTiming.PAYLINE_CAROUSEL_STEP_MS") &&
                slotFragment.contains("distinctBy { it.paylineIndex }") &&
                slotFragment.contains("showWinningPaylineOverlay(theme, result, lineIndexes.first(), animate = true)") &&
                slotFragment.contains("highlightedCellIndexes(result, lineIndex)")
        )
        assertTrue("SlotFragment must update winning payline accessibility dynamically", slotFragment.contains("R.string.slot_winning_payline") && slotFragment.contains("overlay.contentDescription"))
        assertTrue("Slot reels must expose one Russian accessibility summary for all three rows", strings.contains("slot_reels_accessibility") && strings.contains("slot_reels_rows_accessibility") && strings.contains("Верхний ряд: %1\$s. Средний ряд: %2\$s. Нижний ряд: %3\$s.") && slotFragment.contains("updateReelsContentDescription(theme, reels)") && slotFragment.contains("SlotSymbolResources.label(theme, reels[column][row])") && !slotFragment.contains("reelSymbolContentDescription"))
        assertTrue("Slot active line badge must render dynamically from image assets and bitmap digits", slotLayout.contains("@+id/activeLinesRail") && slotLayout.contains("@drawable/active_lines_badge") && slotLayout.contains("@+id/activeLinesRailDigits") && slotFragment.contains("binding.activeLinesRailDigits.setNumber"))
        assertTrue("Paytable symbol label must render from image asset", paytableLayout.contains("@drawable/label_symbol"))
        assertTrue("Paytable bets label must render from image asset", paytableLayout.contains("@drawable/label_bets"))
        assertTrue("Paytable title must default to image asset", paytableLayout.contains("@drawable/title_paytable_violet_fortune"))
        assertTrue("Result dialog must include win title image asset", resultLayout.contains("@drawable/title_win"))
        assertTrue("Result dialog must include bonus title image asset", Files.exists(drawableRoot.resolve("title_bonus.webp")) && strings.contains("result_bonus_title"))
        assertTrue("Result dialog must default body to image asset", resultLayout.contains("@drawable/label_result_win_body"))
        val premiumResultPanels = listOf(
            "result_modal_panel_violet_premium.webp",
            "result_modal_panel_roman_premium.webp",
            "result_modal_panel_neon_premium.webp",
            "result_modal_panel_pharaoh_premium.webp",
            "result_modal_panel_ocean_premium.webp"
        )
        val tinyPremiumResultPanels = premiumResultPanels.filter { Files.size(drawableRoot.resolve(it)) < 100_000L }
        val wrongSizePremiumResultPanels = premiumResultPanels.mapNotNull { asset ->
            val size = readBitmapSize(drawableRoot.resolve(asset))
            "$asset=${size.width}x${size.height}".takeIf { size != BitmapSize(900, 420) }
        }
        assertTrue("Premium imagegen result modal panels are too flat or tiny: $tinyPremiumResultPanels", tinyPremiumResultPanels.isEmpty())
        assertTrue("Premium imagegen result modal panels must preserve 900x420 geometry: $wrongSizePremiumResultPanels", wrongSizePremiumResultPanels.isEmpty())
        assertTrue("Result dialog must use a premium imagegen dedicated image panel", resultLayout.contains("@+id/resultModalPanel") && resultLayout.contains("@drawable/result_modal_panel_violet_premium") && !resultLayout.contains("@drawable/modal_panel\""))
        assertTrue("Result dialog modal panel must switch to premium theme image assets", resultDialog.contains("resultModalPanelDrawable") && resultDialog.contains("SlotTheme.Roman -> R.drawable.result_modal_panel_roman_premium") && resultDialog.contains("SlotTheme.Neon -> R.drawable.result_modal_panel_neon_premium") && resultDialog.contains("SlotTheme.Pharaoh -> R.drawable.result_modal_panel_pharaoh_premium") && resultDialog.contains("SlotTheme.Ocean -> R.drawable.result_modal_panel_ocean_premium") && resultDialog.contains("SlotTheme.Violet -> R.drawable.result_modal_panel_violet_premium"))
        assertTrue("Result dialog stage must render from a dedicated image asset", resultLayout.contains("@+id/resultStageLattice") && resultLayout.contains("@drawable/result_stage_lattice"))
        assertTrue("Result dialog stage must switch to theme image assets", resultDialog.contains("resultStageLatticeDrawable") && resultDialog.contains("SlotTheme.Roman -> R.drawable.result_stage_lattice_roman") && resultDialog.contains("SlotTheme.Neon -> R.drawable.result_stage_lattice_neon") && resultDialog.contains("SlotTheme.Pharaoh -> R.drawable.result_stage_lattice_pharaoh") && resultDialog.contains("SlotTheme.Ocean -> R.drawable.result_stage_lattice_ocean"))
        assertTrue("Result dialog stage must stay decorative", resultLayout.contains("@+id/resultStageLattice") && resultLayout.split("@+id/resultStageLattice", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Result dialog stage must sit below reward animation and content", resultLayout.indexOf("@+id/resultStageLattice") > resultLayout.indexOf("@drawable/result_modal_panel") && resultLayout.indexOf("@+id/resultStageLattice") < resultLayout.indexOf("@+id/resultRewardOverlay") && resultLayout.indexOf("@+id/resultStageLattice") < resultLayout.indexOf("@+id/resultGlow") && resultLayout.indexOf("@+id/resultStageLattice") < resultLayout.indexOf("@+id/resultBody"))
        assertTrue("Result dialog stage polish must be finite, managed, and respect disabled system animators", resultDialog.contains("animateResultStage") && resultDialog.contains("binding.resultStageLattice") && resultDialog.contains("RESULT_STAGE_SETTLED_ALPHA") && resultDialog.contains("resultStageAnimator") && resultDialog.contains("AnimatorSet") && resultDialog.contains("resultStageAnimator?.cancel()") && resultDialog.contains("ValueAnimator.areAnimatorsEnabled()") && !resultDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Result dialog reward polish must render from a dedicated payout image asset", resultLayout.contains("@+id/resultRewardOverlay") && resultLayout.contains("@drawable/result_win_payout_burst"))
        assertTrue("Result dialog reward polish must switch to theme image assets", resultDialog.contains("resultRewardOverlayDrawable") && resultDialog.contains("SlotTheme.Roman -> R.drawable.result_win_payout_burst_roman") && resultDialog.contains("SlotTheme.Neon -> R.drawable.result_win_payout_burst_neon") && resultDialog.contains("SlotTheme.Pharaoh -> R.drawable.result_win_payout_burst_pharaoh") && resultDialog.contains("SlotTheme.Ocean -> R.drawable.result_win_payout_burst_ocean"))
        val resultSparkleAsset = drawableRoot.resolve("result_reward_sparkle.webp")
        assertTrue("Result dialog reward sparkle must be a high-resolution imagegen bitmap", Files.exists(resultSparkleAsset) && Files.size(resultSparkleAsset) > 80_000L && readBitmapSize(resultSparkleAsset) == BitmapSize(1000, 760))
        assertTrue("Result dialog reward sparkle must render in both orientations from an image asset", resultLayout.contains("@+id/resultRewardSparkle") && resultLayout.contains("@drawable/result_reward_sparkle") && resultLandscapeLayout.contains("@+id/resultRewardSparkle") && resultLandscapeLayout.contains("@drawable/result_reward_sparkle"))
        assertTrue("Result dialog reward sparkle must stay decorative", resultLayout.split("@+id/resultRewardSparkle", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && resultLandscapeLayout.split("@+id/resultRewardSparkle", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Result dialog reward sparkle must sit above the payout burst and below readable content", resultLayout.indexOf("@+id/resultRewardSparkle") > resultLayout.indexOf("@+id/resultRewardOverlay") && resultLayout.indexOf("@+id/resultRewardSparkle") < resultLayout.indexOf("@+id/resultGlow") && resultLayout.indexOf("@+id/resultRewardSparkle") < resultLayout.indexOf("@+id/resultBody") && resultLandscapeLayout.indexOf("@+id/resultRewardSparkle") > resultLandscapeLayout.indexOf("@+id/resultRewardOverlay") && resultLandscapeLayout.indexOf("@+id/resultRewardSparkle") < resultLandscapeLayout.indexOf("@+id/resultGlow") && resultLandscapeLayout.indexOf("@+id/resultRewardSparkle") < resultLandscapeLayout.indexOf("@+id/resultBody"))
        assertTrue("Result dialog reward sparkle polish must be finite, managed, and respect disabled system animators", resultDialog.contains("binding.resultRewardSparkle") && resultDialog.contains("RESULT_SPARKLE_POLISH_DURATION_MS") && resultDialog.contains("RESULT_SPARKLE_POLISH_DELAY_MS") && resultDialog.contains("RESULT_SPARKLE_PEAK_ALPHA") && resultDialog.contains("RESULT_SPARKLE_SETTLED_ALPHA") && resultDialog.contains("ObjectAnimator.ofFloat(sparkle, View.ALPHA") && !resultDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Result dialog bonus free-spins award must render from a dedicated image panel and bitmap digits", resultLayout.contains("@+id/resultFreeSpinsAwardGroup") && resultLayout.contains("@+id/resultFreeSpinsAwardPanel") && resultLayout.contains("@drawable/result_free_spins_award_panel") && resultLayout.contains("@+id/resultFreeSpinsAwardDigits") && resultLayout.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("Result dialog bonus free-spins award panel must switch to theme image assets", resultDialog.contains("resultFreeSpinsAwardPanelDrawable") && resultDialog.contains("SlotTheme.Roman -> R.drawable.result_free_spins_award_panel_roman") && resultDialog.contains("SlotTheme.Neon -> R.drawable.result_free_spins_award_panel_neon") && resultDialog.contains("SlotTheme.Pharaoh -> R.drawable.result_free_spins_award_panel_pharaoh") && resultDialog.contains("SlotTheme.Ocean -> R.drawable.result_free_spins_award_panel_ocean"))
        assertTrue("Result dialog bonus free-spins award must be shown only for a new award, never for the final summary", resultDialog.contains("val hasFreeSpinsAward = !isFreeSpinsSummary && freeSpinsAwarded > 0") && resultDialog.contains("binding.resultFreeSpinsAwardGroup.visibility = if (hasFreeSpinsAward) View.VISIBLE else View.GONE"))
        assertTrue("Result dialog bonus free-spins amount must bind dynamically as bitmap plus digits", resultDialog.contains("binding.resultFreeSpinsAwardDigits.setNumber(freeSpinsAwarded, showPlus = true)") && !resultLayout.contains("android:text=\"@string/result_free_spins_award\""))
        assertTrue("Result dialog bonus free-spins award must keep Russian accessibility and actual awarded amount", strings.contains("result_free_spins_award") && resultDialog.contains("R.string.result_free_spins_award") && resultDialog.contains("freeSpinsAwarded"))
        assertTrue("Result dialog win and neutral free-spins summary amounts must come from Russian resources", strings.contains("result_win_amount_accessibility") && strings.contains("free_spins_summary_amount_accessibility") && resultDialog.contains("R.string.result_win_amount_accessibility") && resultDialog.contains("R.string.free_spins_summary_amount_accessibility") && !resultDialog.contains("\"Выигрыш \${"))
        assertTrue("Result dialog must keep body accessibility text", resultLayout.contains("android:contentDescription=\"@string/result_win_body\""))
        assertTrue("Result dialog must swap title images from net outcome", resultDialog.contains("when (netOutcome)") && resultDialog.contains("NetOutcome.Bonus -> R.drawable.title_bonus") && resultDialog.contains("NetOutcome.NetWin -> R.drawable.title_win") && resultDialog.contains("R.drawable.title_lose"))
        assertTrue("Result dialog bonus title accessibility must come from Russian resources", resultDialog.contains("NetOutcome.Bonus -> R.string.result_bonus_title") && !resultDialog.contains("\"Бонус\""))
        assertTrue("Result dialog must swap body images dynamically", resultDialog.contains("R.drawable.label_result_win_body") && resultDialog.contains("R.drawable.label_result_bonus_body") && resultDialog.contains("R.drawable.label_result_lose_body"))
        assertTrue("Result dialog must use a dedicated bonus badge image", resultDialog.contains("R.drawable.modal_badge_bonus"))
        assertTrue("Result dialog reward polish must be finite, managed, and respect disabled system animators", resultDialog.contains("animateRewardPolish") && resultDialog.contains("rewardPolishAnimator") && resultDialog.contains("AnimatorSet") && resultDialog.contains("rewardPolishAnimator?.cancel()") && resultDialog.contains("ValueAnimator.areAnimatorsEnabled()") && !resultDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Result dialog bonus free-spins award polish must be finite and image based", resultDialog.contains("BONUS_AWARD_POLISH_DURATION_MS") && resultDialog.contains("BONUS_AWARD_POLISH_DELAY_MS") && resultDialog.contains("BONUS_AWARD_DIGITS_POP_DURATION_MS") && resultDialog.contains("BONUS_AWARD_DIGITS_POP_DELAY_MS") && resultDialog.contains("BONUS_AWARD_SETTLED_ALPHA") && resultDialog.contains("binding.resultFreeSpinsAwardGroup") && resultDialog.contains("binding.resultFreeSpinsAwardDigits") && !resultDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Result dialog reward polish must settle as a visible image layer", resultDialog.contains("REWARD_SETTLED_ALPHA"))
        assertTrue("Slot bet label must not render as android:text", !slotLayout.contains("android:text=\"@string/bet\""))
        assertTrue("Slot last win label must not render as android:text", !slotLayout.contains("android:text=\"@string/last_win\""))
        assertTrue("Paytable symbol header must not render as plain text", !paytableLayout.contains("android:text=\"Символ\""))
        assertTrue("Result title must not be assigned as TextView text", !resultDialog.contains("resultTitle.text"))
        assertTrue("Result body must not be assigned as TextView text", !resultDialog.contains("resultBody.text"))
        assertTrue("Result dialog must not accept free-form body copy", !resultDialog.contains("ARG_BODY"))
    }

    @Test
    fun `privacy webview enforces https policy before loading`() {
        val privacyFragment = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()
        val privacyUrlPolicy = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyUrlPolicy.kt").readText()
        val mainManifest = Path.of("src/main/AndroidManifest.xml").readText()
        val buildScript = Path.of("../build.gradle.kts").readText() + Path.of("build.gradle.kts").readText()

        assertTrue("PrivacyFragment must validate loadable URL", privacyFragment.contains("PrivacyUrlPolicy.isLoadable"))
        assertTrue("PrivacyFragment must validate navigation host", privacyFragment.contains("PrivacyUrlPolicy.isAllowed"))
        assertTrue("Privacy analytics success must not send full URL path or query", privacyFragment.contains("PrivacyUrlPolicy.analyticsOrigin(url)") && !privacyFragment.contains("mapOf(\"url\" to url)"))
        val pageFinishedBlock = privacyFragment.substringAfter("override fun onPageFinished").substringBefore("AppGraph.analyticsTracker.track(\n                        AnalyticsEvents.PrivacyLoadSuccess")
        val showErrorBlock = privacyFragment.substringAfter("private fun showError").substringBefore("private fun showPrivacyLoading")
        val showLoadingBlock = privacyFragment.substringAfter("private fun showPrivacyLoading").substringBefore("private fun hidePrivacyLoading")
        val hideErrorBlock = privacyFragment.substringAfter("private fun hidePrivacyErrorPolish").substringBefore("override fun onDestroyView")
        assertTrue("Privacy WebView callbacks and helpers must ignore late callbacks after view destruction", listOf(pageFinishedBlock, showErrorBlock, showLoadingBlock, hideErrorBlock).all { it.contains("val binding = _binding ?: return") })
        assertTrue("Release manifest must explicitly block cleartext traffic", mainManifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue("Build readiness must require HTTPS Privacy URL", buildScript.contains("https URL required"))
        assertTrue("Privacy URLs must reject embedded credentials in release configuration and runtime navigation", buildScript.contains("uri.rawUserInfo == null") && privacyUrlPolicy.contains("rawUserInfo == null"))
    }

    @Test
    fun `store setup docs do not suggest fake production values`() {
        val readme = Path.of("../README.md").readText()
        val forbiddenPlaceholders = listOf(
            "example.com",
            "real-appmetrica-key",
            "https://"
        )
        val violations = forbiddenPlaceholders.filter { readme.contains(it, ignoreCase = true) }

        assertTrue("README must list release inputs without fake copy-paste values", listOf("V_SLOT_PRIVACY_POLICY_URL=", "V_SLOT_APPMETRICA_API_KEY=", "V_SLOT_APPMETRICA_API_KEY_SHA256=", "V_SLOT_FIREBASE_PROJECT_ID=", "V_SLOT_FIREBASE_APP_ID=", "V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE=", "V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE=", "V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256=", "V_SLOT_RELEASE_CERT_SHA256=").all(readme::contains))
        assertTrue("Production release values must be environment-only for reproducible provenance", readme.contains("provide every release value through environment variables") && readme.contains("rejects `-P` and `local.properties`"))
        assertTrue("README must explicitly tell release builders to leave inputs unset until real production data exists", readme.contains("Leave these values unset until the real production inputs are available."))
        assertFalse("README must never suggest Gradle properties for signing passwords", readme.contains("-PV_SLOT_RELEASE_STORE_PASSWORD") || readme.contains("-PV_SLOT_RELEASE_KEY_PASSWORD"))
        assertTrue("README contains placeholder production values: $violations", violations.isEmpty())
    }

    @Test
    fun `all app data is excluded from backup and device transfer`() {
        val backupRules = Path.of("src/main/res/xml/backup_rules.xml").readText()
        val dataExtractionRules = Path.of("src/main/res/xml/data_extraction_rules.xml").readText()
        val domains = listOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref"
        )

        domains.forEach { domain ->
            val exclusion = "domain=\"$domain\" path=\".\""
            assertTrue("Legacy backup must exclude the complete $domain domain", backupRules.contains(exclusion))
            assertEquals("Cloud and D2D rules must both exclude the complete $domain domain", 2, dataExtractionRules.windowed(exclusion.length).count { it == exclusion })
        }
        assertTrue("Android 12+ rules must explicitly cover cloud backup and device transfer", dataExtractionRules.contains("<cloud-backup>") && dataExtractionRules.contains("<device-transfer>"))
    }

    @Test
    fun `store readiness checks release keystore path exists`() {
        val buildScript = Path.of("build.gradle.kts").readText()

        assertTrue("Store readiness must detect missing release keystore file", buildScript.contains("file not found"))
        assertTrue("Store readiness must require public support and legal identity", listOf("V_SLOT_SUPPORT_EMAIL", "V_SLOT_DEVELOPER_LEGAL_NAME", "valid production email required", "real legal name required").all(buildScript::contains))
        assertTrue("Store readiness must validate checked-in Play text and artwork", listOf("storeListingAssetIssues", "storeGraphicsExportIssues", "store-graphics-export-manifest.json", "exporter SHA-256 mismatch", "output SHA-256 mismatch", "verifyStoreAssets", "v-slot-icon-512-v2.png", "v-slot-feature-graphic-1024x500-v1.png", "Play phone screenshot set must contain exactly the five reviewed PNG files", "StorePngInfo(1_080, 1_920, 8, 2)", "capture-metadata.json", "Play screenshot hash mismatch", "storeScreenshotReleaseIssues", "Play screenshot assets must match the release HEAD", "qa_test_apk_payload_sha256", "assembleQaAndroidTest").all(buildScript::contains))
        assertTrue("Store readiness must reject placeholder release inputs", buildScript.contains("isPlaceholderReleaseValue") && buildScript.contains("example.") && buildScript.contains("real production URL required") && buildScript.contains("real production key required"))
        assertTrue("Store readiness must validate and pin the complete release backend configuration", buildScript.contains("googleServicesReadinessIssues") && buildScript.contains("src/release/google-services.json") && listOf("valid JSON required", "project_id required", "project_number required", "client package_name", "mobilesdk_app_id required", "api_key.current_key required", "expected Firebase project mismatch", "expected Firebase app mismatch", "V_SLOT_FIREBASE_PROJECT_ID", "V_SLOT_FIREBASE_APP_ID", "V_SLOT_APPMETRICA_API_KEY_SHA256", "AppMetrica key mismatch").all(buildScript::contains) && buildScript.contains("vSlotApplicationId"))
        assertTrue("Every production release value must reject local Gradle and local.properties overrides", buildScript.contains("fun releaseConfigValue") && buildScript.contains("only through an environment variable for a reproducible release"))
        assertTrue("Store builds must use the reviewed SDK and Build Tools without local overrides", buildScript.contains("val vSlotStoreSdk = 36") && buildScript.contains("val vSlotBuildToolsVersion = \"36.0.0\"") && buildScript.contains("compileSdk = vSlotStoreSdk") && buildScript.contains("buildToolsVersion = vSlotBuildToolsVersion") && buildScript.contains("targetSdk = vSlotStoreSdk") && !buildScript.contains("V_SLOT_COMPILE_SDK") && !buildScript.contains("V_SLOT_TARGET_SDK"))
        assertTrue("Debug, QA, and release must use independent opt-in backend inputs", listOf("src/debug/google-services.json", "src/qa/google-services.json", "src/release/google-services.json", "V_SLOT_DEBUG_APPMETRICA_API_KEY", "V_SLOT_QA_APPMETRICA_API_KEY", "V_SLOT_APPMETRICA_API_KEY").all(buildScript::contains) && buildScript.contains("buildConfigField(\"String\", \"APP_METRICA_API_KEY\", \"\".asBuildConfigString())") && buildScript.contains("buildConfigField(\"Boolean\", \"FIREBASE_CONFIGURED\", \"false\")"))
        assertTrue("Signing passwords must come only from environment providers", buildScript.contains("fun signingSecret") && buildScript.contains("providers.environmentVariable(name)") && buildScript.contains("never -P or local.properties") && buildScript.contains("val releaseStorePassword = signingSecret(\"V_SLOT_RELEASE_STORE_PASSWORD\")") && buildScript.contains("val releaseKeyPassword = signingSecret(\"V_SLOT_RELEASE_KEY_PASSWORD\")") && !buildScript.contains("configValue(\"V_SLOT_RELEASE_STORE_PASSWORD\")") && !buildScript.contains("configValue(\"V_SLOT_RELEASE_KEY_PASSWORD\")"))
        assertTrue("Store readiness must verify the expected upload certificate without passing a password argument", buildScript.contains("V_SLOT_RELEASE_CERT_SHA256") && buildScript.contains("uploadCertificateReadinessIssues") && buildScript.contains("MessageDigest.getInstance(\"SHA-256\")") && buildScript.contains("-storepass:env") && buildScript.contains("upload certificate mismatch") && !buildScript.contains("\"-storepass\","))
        assertTrue("Store readiness must require a Data Safety review for the current release version", buildScript.contains("V_SLOT_DATA_SAFETY_REVIEWED_VERSION_CODE") && buildScript.contains("dataSafetyReviewedVersionCode.isBlank()") && buildScript.contains("dataSafetyReviewedVersionCode != vSlotVersionCode.toString()") && buildScript.contains("current versionCode \$vSlotVersionCode required"))
        assertTrue("Store readiness must require checksum-pinned Data Safety evidence bound to the release commit", listOf("V_SLOT_DATA_SAFETY_EVIDENCE_FILE", "V_SLOT_DATA_SAFETY_EVIDENCE_SHA256", "dataSafetyEvidenceIssues", "reviewed_commit must match release HEAD", "verifyDataSafetyEvidence", "data-safety-evidence.json").all(buildScript::contains))
        assertTrue("Store readiness must require checksum-pinned asset rights evidence bound to the version, commit, and media inventory", listOf("V_SLOT_ASSET_RIGHTS_REVIEWED_VERSION_CODE", "V_SLOT_ASSET_RIGHTS_EVIDENCE_FILE", "V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256", "assetProvenanceInventoryIssues", "assetRightsEvidenceIssues", "inventory_sha256 must match the checked-in provenance inventory", "font_license_and_output_rights_confirmed", "verifyAssetRightsEvidence", "asset-rights-evidence.json").all(buildScript::contains))
        assertTrue("Store readiness must require one checksum-pinned physical Samsung, process-death, and frame-metrics evidence set", listOf("V_SLOT_SAMSUNG_QA_EVIDENCE_FILE", "V_SLOT_SAMSUNG_QA_EVIDENCE_SHA256", "V_SLOT_PROCESS_DEATH_EVIDENCE_FILE", "V_SLOT_PROCESS_DEATH_EVIDENCE_SHA256", "V_SLOT_FRAME_METRICS_EVIDENCE_FILE", "V_SLOT_FRAME_METRICS_EVIDENCE_SHA256", "physicalSamsungEvidenceIssues", "verifyPhysicalSamsungEvidence", "physical-samsung").all(buildScript::contains))
        assertTrue("Every terminal release artifact task must be blocked until store verification succeeds", buildScript.contains("storeReleaseArtifactTaskNames") && listOf("\"assembleRelease\"", "\"bundleRelease\"", "\"packageRelease\"", "\"packageReleaseBundle\"", "\"packageReleaseUniversalApk\"", "\"signReleaseBundle\"").all(buildScript::contains) && buildScript.contains("tasks.register(\"verifyStoreRelease\")") && buildScript.contains("\"testReleaseUnitTest\"") && buildScript.contains("\"lintRelease\"") && buildScript.contains("dependsOn(verifyStoreRelease)") && buildScript.contains("storeReleaseArtifactTaskNames.any { taskName -> hasTask(\":app:\$taskName\") }"))
        assertTrue("Store verification must create a reproducible versioned R8 mapping archive", buildScript.contains("archiveReleaseMapping") && buildScript.contains("dependsOn(\"minifyReleaseWithR8\")") && buildScript.contains("r8-mapping.zip") && buildScript.contains("isPreserveFileTimestamps = false") && buildScript.contains("isReproducibleFileOrder = true") && buildScript.contains("mapping.txt") && buildScript.contains("release-provenance.txt") && buildScript.contains("archiveReleaseMapping,"))
        assertTrue("Release artifacts must enforce optimized resource size budgets", buildScript.contains("tasks.register(\"verifyReleaseResourceSize\")") && buildScript.contains("dependsOn(\"optimizeReleaseResources\")") && buildScript.contains("maxReleaseResourceArchiveBytes = 64L * 1024L * 1024L") && buildScript.contains("maxSingleReleaseResourceBytes = 1L * 1024L * 1024L") && buildScript.contains("verifyReleaseResourceSize"))
        assertTrue("Release resources must reject large lossless source WebP assets", buildScript.contains("tasks.register(\"verifySourceWebpEncoding\")") && buildScript.contains("maxLosslessSourceWebpBytes = 150_000L") && buildScript.contains("contains(\"VP8L\")") && buildScript.contains("dependsOn(verifySourceWebpEncoding)"))
        val rootBuildScript = Path.of("../build.gradle.kts").readText()
        val gitIgnore = Path.of("../.gitignore").readText()
        val productionWorkflow = Path.of("../.github/workflows/production-release.yml").readText()
        val ciWorkflow = Path.of("../.github/workflows/android-ci.yml").readText()
        assertTrue("Production workflow must pass public support, legal identity, and rights evidence from the protected Environment", listOf("V_SLOT_SUPPORT_EMAIL: \${{ vars.V_SLOT_SUPPORT_EMAIL }}", "V_SLOT_DEVELOPER_LEGAL_NAME: \${{ vars.V_SLOT_DEVELOPER_LEGAL_NAME }}", "V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256: \${{ vars.V_SLOT_ASSET_RIGHTS_EVIDENCE_SHA256 }}", "V_SLOT_ASSET_RIGHTS_EVIDENCE_JSON_BASE64: \${{ secrets.V_SLOT_ASSET_RIGHTS_EVIDENCE_JSON_BASE64 }}").all(productionWorkflow::contains))
        assertTrue("Pull-request CI must fail on store provenance, rights-validator, and release-resource regressions", listOf(":app:verifyStoreAssets", ":app:verifyAssetRightsEvidenceValidatorContract", ":app:verifyReleaseResourceSize").all(ciWorkflow::contains))
        assertTrue("Release artifact tasks must run the workspace secret, license, and runtime vulnerability inventory gates", buildScript.contains("rootProject.tasks.named(\"verifyReleaseSecurityEvidence\")") && rootBuildScript.contains("tasks.register(\"verifyWorkspaceSecurity\")") && rootBuildScript.contains("tasks.register(\"verifyReleaseSecurityEvidence\")") && rootBuildScript.contains("verifyWorkspaceSecurity,") && rootBuildScript.contains("\":app:verifyReleaseDependencyLicenses\"") && rootBuildScript.contains("\":app:verifyReleaseOsvInventory\"") && rootBuildScript.contains("gitBytes(\"ls-files\", \"--stage\", \"-z\")") && listOf("\"--branches\",", "\"--remotes\",", "\"--tags\"").all(rootBuildScript::contains) && !rootBuildScript.contains("gitBytes(\"rev-list\", \"--objects\", \"--all\")") && rootBuildScript.contains("\"--cached\",") && rootBuildScript.contains("\"--others\",") && rootBuildScript.contains("\"--exclude-standard\",") && rootBuildScript.contains("ProcessBuilder(\"git\", \"cat-file\", \"--batch\")") && rootBuildScript.contains("Sensitive files are eligible for commit"))
        assertTrue("Release diagnostics must be bound to a clean committed Git revision", buildScript.contains("rootProject.tasks.named(\"verifyReleaseProvenance\")") && rootBuildScript.contains("tasks.register(\"verifyReleaseProvenance\")") && rootBuildScript.contains("rev-parse\", \"--verify\", \"HEAD\"") && rootBuildScript.contains("--porcelain=v1") && rootBuildScript.contains("--untracked-files=all") && rootBuildScript.contains("commit=\${head.lowercase()}") && rootBuildScript.contains("clean Git worktree"))
        assertTrue("Workspace secret files, variant Firebase configs, generated QA captures, and release artifacts must remain ignored", listOf(".env", ".env.*", "*.jks", "*.keystore", "*.p12", "*.pfx", "*.pem", "*.key", "*.p8", "*.der", "credentials*.json", "service-account*.json", "firebase-adminsdk*.json", "*.apk", "*.aab", "*.idsig", "app/google-services.json", "app/src/*/google-services.json", "local.properties", "qa/screenshots/", "qa/logs/").all(gitIgnore::contains))
    }

    @Test
    fun `dependency supply chain is locked and checksum verified`() {
        val wrapperProperties = Path.of("../gradle/wrapper/gradle-wrapper.properties").readText()
        val rootGradleProperties = Path.of("../gradle.properties").readText()
        val appBuildScript = Path.of("build.gradle.kts").readText()
        val verificationMetadata = Path.of("../gradle/verification-metadata.xml").readText()
        val dependencyLocks = Path.of("gradle.lockfile").readText()
        val wrapperJarSha256 = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(Path.of("../gradle/wrapper/gradle-wrapper.jar")))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

        assertTrue("Gradle wrapper distribution must have the reviewed 8.14.5 SHA-256", wrapperProperties.contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.5-bin.zip") && wrapperProperties.contains("distributionSha256Sum=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854") && wrapperProperties.contains("validateDistributionUrl=true"))
        assertEquals("Gradle wrapper JAR must match the reviewed Gradle 8.14.5 wrapper", "7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172", wrapperJarSha256)
        assertTrue("Dependency verification must remain strict and verify artifact metadata", rootGradleProperties.contains("org.gradle.dependency.verification=strict") && verificationMetadata.contains("<verify-metadata>true</verify-metadata>") && verificationMetadata.contains("<verify-signatures>false</verify-signatures>"))
        assertTrue("Every app configuration must remain dependency locked", appBuildScript.contains("dependencyLocking") && appBuildScript.contains("lockAllConfigurations()"))
        assertTrue("Verification metadata must pin critical build and runtime components", listOf("group=\"com.android.tools.build\" name=\"gradle\" version=\"8.13.2\"", "group=\"org.jetbrains.kotlin\" name=\"kotlin-gradle-plugin\" version=\"2.2.21\"", "group=\"com.google.firebase\" name=\"firebase-installations\" version=\"19.1.2\"", "group=\"com.google.firebase\" name=\"firebase-messaging\" version=\"25.1.2\"", "group=\"io.appmetrica.analytics\" name=\"analytics\" version=\"8.3.0\"", "group=\"io.appmetrica.analytics\" name=\"push\" version=\"4.3.0\"").all(verificationMetadata::contains))
        assertTrue("Dependency locks must pin critical runtime modules", listOf("com.google.firebase:firebase-installations:19.1.2=", "com.google.firebase:firebase-messaging:25.1.2=", "io.appmetrica.analytics:analytics:8.3.0=", "io.appmetrica.analytics:push:4.3.0=", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0=", "androidx.datastore:datastore-preferences:1.2.1=").all(dependencyLocks::contains))
    }

    @Test
    fun `samsung qa script requires a ready authorized adb device`() {
        val qaScript = Path.of("../tools/qa_samsung_connected_tests.sh").readText()
        val readme = Path.of("../README.md").readText()

        assertTrue("Samsung QA script must reject unauthorized or offline adb states before probing device properties", qaScript.contains("get-state") && qaScript.contains("adb state:") && qaScript.contains("Authorize USB debugging") && qaScript.indexOf("device_state=") < qaScript.indexOf("ro.product.manufacturer"))
        assertTrue("Samsung QA auto-detection must run on the Bash 3.2 bundled with macOS", !qaScript.contains("mapfile") && qaScript.contains("while IFS= read -r device"))
        assertTrue("Samsung QA script must expose testable ADB and Gradle launchers", qaScript.contains("V_SLOT_ADB") && qaScript.contains("V_SLOT_GRADLE"))
        assertTrue("Samsung QA script must capture and verify restoration of the exact stay-awake setting on every exit", qaScript.contains("trap cleanup EXIT") && qaScript.contains("settings get global stay_on_while_plugged_in") && qaScript.contains("restore_setting global stay_on_while_plugged_in") && qaScript.contains("\$power_state_captured\" != \"1\""))
        assertTrue("Samsung QA script must clear stale generated connected-test reports before Gradle snapshots them", qaScript.contains("rm -rf") && qaScript.contains("app/build/reports/androidTests/connected") && qaScript.contains("app/build/outputs/androidTest-results/connected"))
        assertTrue("Samsung QA script must run the minified release-like variant on only the selected serial", qaScript.contains("ANDROID_SERIAL=\"\$serial\" \"\$GRADLE\" connectedQaAndroidTest"))
        val appBuild = Path.of("build.gradle.kts").readText()
        assertTrue("Samsung QA must use a shrink-enabled non-debuggable build with explicit QA hooks and debug signing", appBuild.contains("create(\"qa\")") && appBuild.contains("initWith(getByName(\"release\"))") && appBuild.contains("isDebuggable = false") && appBuild.contains("buildConfigField(\"Boolean\", \"QA_ENABLED\", \"true\")") && appBuild.contains("signingConfig = signingConfigs.getByName(\"debug\")") && appBuild.contains("proguardFile(\"qa-proguard-rules.pro\")") && appBuild.contains("java.srcDir(\"src/debug/java\")") && appBuild.contains("testBuildType = \"qa\""))
        val qaProguard = Path.of("qa-proguard-rules.pro").readText()
        assertTrue("Minified Samsung QA target must retain shared runtime APIs required by AndroidJUnitRunner", qaProguard.contains("-keep class androidx.tracing.**") && qaProguard.contains("-keep class kotlin.**") && qaProguard.contains("-keep class kotlinx.**"))
        assertTrue("Samsung QA README must tell testers to wait for adb state device", readme.contains("adb devices -l") && readme.contains("state `device`") && readme.contains("authorize USB debugging"))
    }

    @Test
    fun `spin result dialog renders win amount with bitmap digits`() {
        val resultLayout = Path.of("src/main/res/layout/dialog_result.xml").readText()
        val resultDialog = sourceText("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt")
        val dialogWindowPolish = Path.of("src/main/java/com/vslot/app/ui/dialog/DialogWindowPolish.kt").readText()
        val slotFragment = sourceText("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt")
        val slotViewModel = Path.of("src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt").readText()
        val slotSpinTimeline = Path.of("src/main/java/com/vslot/app/ui/slot/SlotSpinTimeline.kt").readText()
        val slotResultPresentationPolicy = Path.of("src/main/java/com/vslot/app/ui/slot/SlotResultPresentationPolicy.kt").readText()

        assertTrue("Result dialog must contain bitmap win amount digits", resultLayout.contains("@+id/winAmountDigits"))
        assertTrue("Result dialog must use BitmapNumberView", resultLayout.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("ResultDialogFragment must accept win amount argument", resultDialog.contains("ARG_WIN_AMOUNT"))
        assertTrue("ResultDialogFragment must compact bitmap win amount width", resultDialog.contains("bitmapAmountWidthPx") && resultDialog.contains("winAmountDigits.layoutParams"))
        assertTrue("ResultDialogFragment must keep formatted accessibility for coin amounts", resultDialog.contains("winAmount.asCoins()"))
        assertTrue("Result dialog must dim the busy slot HUD enough to keep image modal text readable", resultDialog.contains("applyGameDialogDim(RESULT_DIALOG_DIM_AMOUNT)") && resultDialog.contains("RESULT_DIALOG_DIM_AMOUNT = 0.78f") && dialogWindowPolish.contains("FLAG_DIM_BEHIND") && dialogWindowPolish.contains("dimAmount.coerceIn"))
        assertTrue("SlotFragment must pass SpinResult win amount, awarded free spins, and slot theme separately", slotFragment.contains("result.winAmount") && slotFragment.contains("freeSpinsAwarded") && slotFragment.contains("viewModel.uiState.value.config.theme"))
        val spinResultIndex = slotViewModel.indexOf("result = slotEngine.spin(config, bet, lines, isFreeSpin)")
        val revealDelayIndex = slotViewModel.indexOf("awaitSpinReveal(result, slamStopSignal)")
        assertTrue("Slot RNG result must be computed before reel reveal animation", spinResultIndex >= 0 && revealDelayIndex > spinResultIndex)
        val reservationIndex = slotViewModel.indexOf("val reservationAttempt = playerRepository.reserveSpinAttempt(")
        val settlementIndex = slotViewModel.indexOf("val settlement = withContext(NonCancellable)")
        val balanceAfterIndex = slotViewModel.indexOf("\"balance_after\" to settlement.updatedState.coinsBalance")
        assertTrue("Slot bet reservation must own RNG outcome creation before reel reveal", reservationIndex >= 0 && reservationIndex < spinResultIndex && spinResultIndex < revealDelayIndex)
        assertTrue("Slot settlement must happen after reveal delay and before balance_after analytics", settlementIndex > revealDelayIndex && balanceAfterIndex > settlementIndex)
        val playerRepository = Path.of("src/main/java/com/vslot/app/data/PlayerRepository.kt").readText()
        val pendingSettlement = Path.of("src/main/java/com/vslot/app/data/PendingSpinSettlement.kt").readText()
        val mainActivity = Path.of("src/main/java/com/vslot/app/MainActivity.kt").readText()
        val processSession = Path.of("src/main/java/com/vslot/app/ProcessSession.kt").readText()
        val playerState = Path.of("src/main/java/com/vslot/app/data/PlayerState.kt").readText()
        val playerStateCheckpoint = Path.of("src/main/java/com/vslot/app/data/PlayerStateCheckpoint.kt").readText()
        val transactionalPlayerState = Path.of("src/main/java/com/vslot/app/data/TransactionalPlayerStateStore.kt").readText()
        val slotRules = Path.of("src/main/java/com/vslot/app/SlotRules.kt").readText()
        val slotEngine = Path.of("src/main/java/com/vslot/app/game/SlotEngine.kt").readText()
        val slotMathIdentity = Path.of("src/main/java/com/vslot/app/game/SlotMathIdentity.kt").readText()
        val releasedSlotMath = Path.of("src/main/java/com/vslot/app/game/ReleasedSlotMathV5.kt").readText()
        val releasedMathRegistry = Path.of("src/main/java/com/vslot/app/game/ReleasedSlotMathRegistry.kt").readText()
        val settlementVerifier = Path.of("src/main/java/com/vslot/app/game/SpinSettlementVerifier.kt").readText()
        val slotRepository = Path.of("src/main/java/com/vslot/app/game/SlotRepository.kt").readText()
        assertTrue("PlayerRepository must keep checked standalone debit and credit operations", playerRepository.contains("suspend fun debitSpinBet(totalBet: Int): Boolean") && playerRepository.contains("if (currentBalance < totalBet.toLong()) return false") && playerRepository.contains("return debited") && playerRepository.contains("suspend fun creditSpinWin(winAmount: Int)") && playerRepository.contains("if (winAmount <= 0) return"))
        assertTrue("Spin wager, paid-bonus feature autoplay intent, and outcome must persist atomically before reveal while a blocked journal returns its reason and settle leaves a newer journal intact", playerRepository.contains("override suspend fun <T> reserveSpinAttempt(") && playerRepository.contains("SpinReservationAttempt.BlockedByPendingSpin") && playerRepository.contains("autoPlayFreeSpins: Boolean") && playerRepository.contains("autoPlayFreeSpins ||") && playerRepository.contains("!verifiedSettlement.isFreeSpin && verifiedSettlement.freeSpinsAwarded > 0") && playerRepository.indexOf("preferences.writeFreeSpinAutoPlaySlots(autoPlaySlots)") < playerRepository.indexOf("preferences[Keys.PendingSpinSettlement] = verifiedSettlement.serialize()") && playerRepository.contains("override suspend fun settleSpin(") && playerRepository.contains("presentationConsumerId: String?") && playerRepository.contains("val updatedState = editPlayerState { preferences ->") && playerRepository.contains("updatedState = updatedState") && playerRepository.contains("private fun Preferences.toPlayerState(): PlayerState") && !playerRepository.contains("return playerState.first()") && playerRepository.contains("if (pendingSettlement.id != settlement.id) return@editPlayerState") && playerRepository.contains("preferences.applySpinSettlement(") && playerRepository.contains("remove(Keys.PendingSpinSettlement)") && pendingSettlement.contains("sealed interface SpinReservationAttempt") && pendingSettlement.contains("data class SpinReservation<T>") && pendingSettlement.contains("data class SpinSettlementReceipt(") && pendingSettlement.contains("lineBet.toLong() * lines.toLong() == totalBet.toLong()") && slotViewModel.contains("autoPlayFreeSpins = autoTriggered && isAutoPlayActive() && isFreeSpin"))
        assertTrue("Pending spin recovery must defer only while the concrete same-process owner is alive, including scoped view-model consumers", pendingSettlement.contains("processSessionId") && processSession.contains("activeSpinSettlements") && processSession.contains("isSpinSettlementActive") && playerRepository.contains("shouldDeferPendingSpinRecovery") && playerRepository.contains("belongsToProcessSession") && playerRepository.contains("startsWith(\"\$processSessionId:\")") && mainActivity.contains("recoverPendingSpinSettlement(ProcessSession.id)") && slotViewModel.contains("registerSettlementOwnership") && slotViewModel.contains("releaseSettlementOwnership"))
        assertTrue("A settled spin must keep its exact visual result until reels or the required modal actually render and acknowledge it", pendingSettlement.contains("visualResult: SpinResult? = null") && pendingSettlement.contains("serializePresentation") && playerRepository.contains("Keys.PendingSpinPresentation") && playerRepository.contains("claimSpinPresentation(") && playerRepository.contains("acknowledgeSpinPresentation(id: String)") && playerRepository.contains("pendingSpinPresentationSlotId()") && slotViewModel.contains("restorePendingSpinPresentation()") && slotViewModel.contains("visualResult = result") && slotViewModel.contains("onResultDialogPresented") && slotFragment.contains("onSpinPresentationRendered") && resultDialog.contains("PRESENTED_REQUEST_KEY"))
        assertTrue("A pending result presentation must block a new atomic wager before stake debit", playerRepository.indexOf("val pendingPresentation = preferences[Keys.PendingSpinPresentation]") < playerRepository.indexOf("preferences.debitSpinBet(totalBet)") && playerRepository.contains("pendingPresentation?.settlement?.let(settlementVerifier::verify) != null"))
        assertTrue("Persisted spins must use strict v3 integrity, immutable released math descriptors, and preserve unsupported outcomes or verifier failures without refund", pendingSettlement.contains("PENDING_SPIN_JOURNAL_VERSION = 3") && pendingSettlement.contains("sealed interface PendingSpinJournalDecode") && pendingSettlement.contains("data class UnsupportedFormat") && pendingSettlement.contains("decodePendingSpinSettlement") && pendingSettlement.contains("canonicalJournalPayload") && pendingSettlement.contains("journalChecksum") && pendingSettlement.contains("stopIndexes: List<Int>") && pendingSettlement.contains("mathVersion: Int") && pendingSettlement.contains("configFingerprint: String") && slotMathIdentity.contains("MessageDigest.getInstance(\"SHA-256\")") && releasedSlotMath.contains("ASSET_SHA256") && releasedSlotMath.contains("fun evaluateStops(") && releasedSlotMath.contains("fun xpForSpin(") && releasedSlotMath.contains("fun fingerprint(") && releasedMathRegistry.contains("ReleasedSlotMathV5.ASSET_PATH") && releasedMathRegistry.contains("ReleasedSlotMathV5.verifyReleasedAsset(bytes)") && settlementVerifier.contains("sealed interface SpinSettlementVerification") && settlementVerifier.contains("data class Verified") && settlementVerifier.contains("data class UnsupportedMath") && settlementVerifier.contains("data object Corrupt") && settlementVerifier.contains("releasedMathRegistry.release") && settlementVerifier.contains("releasedMath.getSlotExact") && settlementVerifier.contains("releasedMath.evaluateStops(") && settlementVerifier.contains("releasedMath.xpForSpin(") && settlementVerifier.contains("catch (_: Exception)") && playerRepository.contains("PendingSpinRecoveryStatus.UnsupportedMath") && playerRepository.contains("is PendingSpinJournalDecode.UnsupportedFormat -> return false") && playerRepository.contains("is SpinSettlementVerification.UnsupportedMath -> return false") && slotRepository.contains("fun getSlotExact") && slotEngine.contains("fun evaluateStops("))
        assertTrue("Coin ledger must migrate from the legacy int key to a distinct long key without narrowing balances", playerState.contains("val coinsBalance: Long") && playerRepository.contains("longPreferencesKey(\"coinsBalanceLong\")") && playerRepository.contains("intPreferencesKey(\"coinsBalance\")") && playerRepository.indexOf("Keys.CoinsBalanceLong]") < playerRepository.indexOf("Keys.LegacyCoinsBalance]?.toLong()") && playerRepository.contains("preferences.migrateCoinsBalance()") && playerRepository.contains("remove(PlayerRepository.Keys.LegacyCoinsBalance)") && playerStateCheckpoint.contains("coinsBalance = requiredLong(KEY_COINS_BALANCE)") && slotFragment.contains("binding.slotBalanceDigits.setNumber(state.playerState.coinsBalance)") && !slotFragment.contains("state.playerState.coinsBalance.toInt()"))
        assertTrue("Settlement must credit the full int win while preserving the engine result and winning lines", playerRepository.contains("val updatedBalance = saturatedNonNegativeAdd(currentBalance, settlement.winAmount)") && playerRepository.contains("return settlement.winAmount") && playerRepository.contains("settlement.serializePresentation(") && !playerRepository.contains("withCreditedWinAmount") && slotViewModel.contains("val presentedResult = result") && slotViewModel.contains("\"win_amount\" to result.winAmount") && !slotViewModel.contains("withCreditedWinAmount") && !slotViewModel.contains("\"balance_capped\""))
        assertTrue("Player economy and all spin journals must use one checksummed transactional primary with one-shot legacy migration and fail-closed corruption", playerRepository.contains("TransactionalPlayerStateStoreRegistry") && playerRepository.contains("PlayerStateCheckpointStore.PRIMARY_FILE_NAME") && playerRepository.contains("stateStore.update { checkpoint ->") && playerRepository.contains("migrateLegacyPlayerState()") && playerRepository.contains("valuesBefore = preferences.asMap().toMap()") && playerRepository.contains("if (changed)") && playerRepository.contains("generation = this[Keys.Revision] ?: 0L") && playerRepository.contains("nextPlayerStateRevision(currentRevision)") && playerRepository.contains("Player state revision overflow") && transactionalPlayerState.contains("DataStoreFactory.create(") && transactionalPlayerState.contains("dataStore.updateData") && transactionalPlayerState.contains("PlayerStateCheckpointSerializer") && transactionalPlayerState.contains("LegacyPlayerStateMigration") && transactionalPlayerState.contains("!currentData.migrationComplete") && transactionalPlayerState.contains("throw CorruptionException") && transactionalPlayerState.contains("PlayerStateCheckpointCodec.MAX_FILE_BYTES") && playerStateCheckpoint.contains("SHA-256") && playerStateCheckpoint.contains("rawPendingSpinSettlement") && playerStateCheckpoint.contains("rawPendingSpinRefundEnvelope") && playerStateCheckpoint.contains("rawPendingSpinPresentation") && playerStateCheckpoint.contains("migrationComplete"))
        assertTrue("Free spins must persist slot-scoped locked stake, consume atomically without coin debit, retrigger only from a settled journaled outcome, and enter a crash-resumable automatic feature sequence", playerRepository.contains("freeSpinsBalance") && playerRepository.contains("freeSpinBet") && playerRepository.contains("freeSpinLines") && playerRepository.contains("freeSpinSlotId") && playerRepository.contains("freeSpinBonuses") && playerRepository.contains("FreeSpinAutoPlaySlots") && playerRepository.contains("suspend fun consumeFreeSpin(slotId: String): Boolean") && playerRepository.contains("return consumed") && playerRepository.contains("suspend fun awardFreeSpins(count: Int, lineBet: Int, lines: Int, slotId: String)") && playerRepository.contains("preferences.consumeFreeSpin(slotId, preserveAutoPlayMarker = true)") && playerRepository.contains("awardCount = settlement.freeSpinsAwarded") && playerRepository.contains("writeFreeSpinAutoPlaySlots(autoPlaySlots)") && slotViewModel.contains("val freeSpinsBefore = state.freeSpinsForSlot(config.id)") && slotViewModel.contains("val isFreeSpin = freeSpinsBefore > 0") && slotViewModel.contains("state.effectiveBet(isFreeSpin)") && slotViewModel.contains("state.effectiveLines(isFreeSpin)") && slotViewModel.contains("freeSpinsAwarded = result.freeSpinsAwarded") && !slotViewModel.contains("if (result.resultType == ResultType.Bonus) {\n                PlayerState.FREE_SPINS_BONUS_AWARD") && slotViewModel.contains("receipt.outcomeSettled &&") && slotViewModel.contains("freeSpinsAwarded > 0 &&") && slotViewModel.contains("!spin.isFreeSpin &&") && slotViewModel.contains("canResumeAutoPlayAfter(spin)") && slotViewModel.contains("startFreeSpinsFeature()") && slotViewModel.contains("resumeFreeSpinsFeatureIfNeeded()") && slotViewModel.contains("autoPlayState.value = AutoPlayState.FreeSpins"))
        assertTrue("Slot ViewModel must not reveal or settle a spin unless the paid/free stake and journal were actually reserved", slotViewModel.contains("var stakeReserved = false") && slotViewModel.contains("stakeReserved = reservation != null") && slotViewModel.contains("reservation?.value") && slotViewModel.contains("if (spin == null)") && slotViewModel.contains("stakeReserved && !spinSettled"))
        assertTrue("A changed wager mode or paid stake must retry once from the atomic reservation state without reporting low coins or risking an automatic paid retry", slotViewModel.contains("val wagerModeChanged = latestIsFreeSpin != isFreeSpin") && slotViewModel.contains("val stakeChanged = latestBet != bet || latestLines != lines") && slotViewModel.contains("val avoidsUnexpectedPaidWager = latestIsFreeSpin || !wagerModeChanged") && slotViewModel.contains("latestState.shouldAutoPlayFreeSpinsForSlot(config.id)") && slotViewModel.contains("RESERVATION_STATE_RETRY_ATTEMPTS = 1") && slotViewModel.contains("reservationRetriesRemaining = reservationRetriesRemaining - 1") && slotViewModel.contains("selectedBetSnapshot = state.selectedBet") && slotViewModel.contains("selectedLinesSnapshot = state.selectedLines") && !slotViewModel.contains("state.coinsBalance < totalBet") && playerRepository.contains("preferences[Keys.SelectedBet] ?: PlayerState.DEFAULT_BET") && playerRepository.contains("preferences[Keys.SelectedLines] ?: PlayerState.DEFAULT_LINES"))
        assertTrue("Free-spins award count and max persisted paylines must live in shared slot rules instead of coupling PlayerState to SlotEngine", slotRules.contains("FREE_SPINS_BONUS_AWARD = 5") && slotRules.contains("MAX_PAYLINES = 10") && playerState.contains("SlotRules.FREE_SPINS_BONUS_AWARD") && playerState.contains("SlotRules.MAX_PAYLINES") && playerState.contains("selectedLines.coerceIn(PlayerState.MIN_LINES, PlayerState.MAX_LINES)") && !playerState.contains("import com.vslot.app.game.SlotEngine"))
        assertTrue("PlayerRepository must normalize persisted line counts and last played slot before writing", playerRepository.contains("lines.coerceIn(PlayerState.MIN_LINES, PlayerState.MAX_LINES)") && playerRepository.contains("PlayerState.normalizedLastPlayedSlot(slotId)") && playerState.contains("lastPlayedSlot = PlayerState.normalizedLastPlayedSlot(lastPlayedSlot)"))
        assertTrue("Committed spins must settle exactly once and remain journaled for foreground recovery after retry exhaustion", slotViewModel.contains("CommittedSpin(") && slotViewModel.contains("withContext(NonCancellable)") && slotViewModel.contains("catch (cancellation: CancellationException)") && slotViewModel.contains("catch (_: IOException)") && slotViewModel.contains("settleCommittedSpinWithRetry(spin)") && slotViewModel.contains("SPIN_SETTLEMENT_RECOVERY_ATTEMPTS = 2") && slotViewModel.contains("stakeReserved && !spinSettled") && slotViewModel.contains("persisted journal") && slotViewModel.contains("queueSettlementRecovery(spin)") && slotViewModel.contains("retryPendingSettlementRecovery()") && slotViewModel.contains("isSettlementRecoveryPending") && slotViewModel.contains("activeSpin.value = null"))
        assertTrue("Autospin must run only bounded paid batches, enforce loss and win safeguards, stop after the current spin, pause across lifecycle changes, and resume an unfinished free-spin feature", slotViewModel.contains("SUPPORTED_AUTO_SPIN_COUNTS = setOf(10, 25, 50)") && slotViewModel.contains("fun startAutoSpin(count: Int)") && slotViewModel.contains("createPaidAutoSpinBatch(count, state)") && slotViewModel.contains("AUTO_SPIN_LOSS_LIMIT_BETS = 10L") && slotViewModel.contains("applyAutoSpinSafeguards(") && slotViewModel.contains("AutoSpinStopReason.BigWin") && slotViewModel.contains("AutoSpinStopReason.Bonus") && slotViewModel.contains("AutoSpinStopReason.LossLimit") && slotViewModel.contains("remainingToStart = paidBatch.remainingToStart - 1") && slotViewModel.contains("fun stopAutoSpin()") && slotViewModel.contains("fun pauseAutoSpin()") && slotViewModel.contains("fun resumeFreeSpinsFeatureIfNeeded()") && slotViewModel.contains("scheduleNextAutoSpin(nextSpinDelayMs)") && slotViewModel.contains("fun onResultDialogDismissed(") && slotFragment.contains("viewModel.pauseAutoSpin()") && slotFragment.contains("viewModel.resumeFreeSpinsFeatureIfNeeded()"))
        assertTrue("Slot ViewModel controls must ignore stale rapid taps while spin start or result presentation is reserved", slotViewModel.contains("private fun canChangeStake(): Boolean") && slotViewModel.contains("!isSpinStartReserved.value") && slotViewModel.contains("isSpinStartReserved.value ||") && slotViewModel.contains("activeSpin.value != null ||") && slotViewModel.contains("!isResultPending.value") && slotViewModel.contains("pendingPresentationId.value != null") && slotViewModel.contains("if (!canChangeStake()) return"))
        assertTrue("Committed spins must keep navigation in the slot until their exact result is presented, and idle reels must come from configured strips", slotFragment.contains("OnBackPressedCallback") && slotFragment.contains("state.isSpinStartReserved ||") && slotFragment.contains("state.isSpinning ||") && slotFragment.contains("state.isResultPending ||") && slotFragment.contains("state.pendingPresentationId != null") && slotFragment.contains("viewModel.pauseAutoSpin()") && slotFragment.contains("viewModel.requestSlamStop()") && slotFragment.contains("initialSlotReels(state.config)") && !slotFragment.contains("initialReels(state.config.symbols)"))
        assertTrue("Manual spin must support event-driven Pixi-like slam-stop without making autospin taps interactive", slotViewModel.contains("fun requestSlamStop()") && slotViewModel.contains("CompletableDeferred<Unit>") && slotViewModel.contains("val slamStopSignal = if (autoTriggered) null else CompletableDeferred<Unit>()") && slotSpinTimeline.contains("SLAM_STOP_MIN_REVEAL_MS = 1_180L") && slotViewModel.contains("withTimeoutOrNull(revealDurationMs)") && slotViewModel.contains("revealSignal.await()") && !slotViewModel.contains("SPIN_REVEAL_POLL_MS") && slotFragment.contains("SlotSpinTimeline.slamStopStartAtMs(requestedElapsedMs)") && slotFragment.contains("applySlamStopSchedule(slamRequestElapsedMs(elapsedMs))") && slotFragment.contains("viewModel.requestSlamStop()") && slotFragment.contains("R.string.spin_slam_stop") && slotFragment.contains("binding.spinButton.isEnabled = !state.isSpinStartReserved") && slotFragment.contains("(!state.isSpinning || !state.isAutoSpinEnabled)"))
        assertTrue("Slot UI state must expose a durable active result and timing for deterministic reel stops", slotViewModel.contains("pendingResult: SpinResult?") && slotViewModel.contains("ActiveSpinPresentation(") && slotViewModel.contains("startedAtMonotonicMs = monotonicTimeMs()") && slotViewModel.contains("spinStopRequestedAtMonotonicMs") && slotFragment.contains("targetResult = state.pendingResult") && slotFragment.contains("startedAtMonotonicMs = state.spinStartedAtMonotonicMs") && slotFragment.contains("stopRequestedAtMonotonicMs = state.spinStopRequestedAtMonotonicMs"))
        assertTrue("Ordinary outcomes must not open a result modal except for the mandatory final free-spins summary", slotViewModel.contains("completedFreeSpinsTotalWin != null ||") && slotViewModel.contains("SlotResultPresentationPolicy.shouldShowResultDialog(presentedResult)") && slotViewModel.contains("if (!shouldShowResultDialog)") && slotViewModel.contains("return@launch") && slotResultPresentationPolicy.contains("result.netOutcome == NetOutcome.Bonus || isBigWin(result)") && slotResultPresentationPolicy.contains("result.netOutcome != NetOutcome.NetWin") && !slotViewModel.contains("REEL_STOP_RESULT_DIALOG_DELAY_MS"))
        assertTrue(
            "Winning results must leave time for image win feedback before a modal or the next autospin",
            slotViewModel.contains("SlotWinFeedbackTiming.resultPresentationDurationMs(") &&
                slotViewModel.contains("val reducedMotion = activeSpin.value?.stopMode == SpinStopMode.ReducedMotion") &&
                slotViewModel.contains("if (resultPresentationDurationMs > 0L)") &&
                slotViewModel.contains("delay(resultPresentationDurationMs)") &&
                slotViewModel.contains("settlement.freeSpinsAwarded,") &&
                slotViewModel.contains("spin.settlement.id")
        )
        val slotLayout = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val slotLandscapeLayout = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val reelApertureAssets = listOf(
            "reel_aperture_shadow.webp",
            "reel_aperture_shadow_roman.webp",
            "reel_aperture_shadow_neon.webp",
            "reel_aperture_shadow_pharaoh.webp",
            "reel_aperture_shadow_ocean.webp"
        )
        val reelBrakeClampAssets = listOf(
            "reel_brake_clamp.webp",
            "reel_brake_clamp_roman.webp",
            "reel_brake_clamp_neon.webp",
            "reel_brake_clamp_pharaoh.webp",
            "reel_brake_clamp_ocean.webp"
        )
        val reelMotionStreakAssets = listOf(
            "reel_motion_streak.webp",
            "reel_motion_streak_roman.webp",
            "reel_motion_streak_neon.webp",
            "reel_motion_streak_pharaoh.webp",
            "reel_motion_streak_ocean.webp"
        )
        val reelAnticipationBeamAssets = listOf(
            "reel_anticipation_beam_violet.webp",
            "reel_anticipation_beam_roman.webp",
            "reel_anticipation_beam_neon.webp",
            "reel_anticipation_beam_pharaoh.webp",
            "reel_anticipation_beam_ocean.webp"
        )
        val reelLandingSparkAssets = listOf(
            "reel_landing_spark.webp",
            "reel_landing_spark_violet.webp",
            "reel_landing_spark_roman.webp",
            "reel_landing_spark_neon.webp",
            "reel_landing_spark_pharaoh.webp",
            "reel_landing_spark_ocean.webp"
        )
        val missingReelApertureAssets = reelApertureAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyReelApertureAssets = reelApertureAssets.filter {
            Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 20_000
        }
        val missingReelBrakeClampAssets = reelBrakeClampAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyReelBrakeClampAssets = reelBrakeClampAssets.filter {
            Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 20_000
        }
        val missingReelMotionStreakAssets = reelMotionStreakAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyReelMotionStreakAssets = reelMotionStreakAssets.filter {
            Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 70_000
        }
        val missingReelAnticipationBeamAssets = reelAnticipationBeamAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyReelAnticipationBeamAssets = reelAnticipationBeamAssets.filter {
            Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 45_000
        }
        val missingReelLandingSparkAssets = reelLandingSparkAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyReelLandingSparkAssets = reelLandingSparkAssets.filter {
            Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 80_000
        }
        val wrongSizeReelLandingSparkAssets = reelLandingSparkAssets.mapNotNull { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@mapNotNull null
            val size = readBitmapSize(path)
            "$asset=${size.width}x${size.height}".takeIf { size != BitmapSize(320, 1080) }
        }

        assertTrue("Reel aperture shadow image assets missing: $missingReelApertureAssets", missingReelApertureAssets.isEmpty())
        assertTrue("Reel aperture shadow image assets are unexpectedly tiny: $tinyReelApertureAssets", tinyReelApertureAssets.isEmpty())
        assertTrue("Reel brake clamp image assets missing: $missingReelBrakeClampAssets", missingReelBrakeClampAssets.isEmpty())
        assertTrue("Reel brake clamp image assets are unexpectedly tiny: $tinyReelBrakeClampAssets", tinyReelBrakeClampAssets.isEmpty())
        assertTrue("Reel motion streak image assets missing: $missingReelMotionStreakAssets", missingReelMotionStreakAssets.isEmpty())
        assertTrue("Reel motion streak image assets are unexpectedly tiny: $tinyReelMotionStreakAssets", tinyReelMotionStreakAssets.isEmpty())
        assertTrue("Reel anticipation beam image assets missing: $missingReelAnticipationBeamAssets", missingReelAnticipationBeamAssets.isEmpty())
        assertTrue("Reel anticipation beam image assets are unexpectedly tiny: $tinyReelAnticipationBeamAssets", tinyReelAnticipationBeamAssets.isEmpty())
        assertTrue("Reel landing spark image asset missing: $missingReelLandingSparkAssets", missingReelLandingSparkAssets.isEmpty())
        assertTrue("Reel landing spark image asset is unexpectedly tiny: $tinyReelLandingSparkAssets", tinyReelLandingSparkAssets.isEmpty())
        assertTrue("Reel landing spark image asset must preserve 320x1080 geometry: $wrongSizeReelLandingSparkAssets", wrongSizeReelLandingSparkAssets.isEmpty())
        assertTrue("Reel anticipation beams must be reproducible from imagegen source and visual QA contact sheet", Path.of("../tools/slice_imagegen_reel_anticipation_beams.py").readText().contains("vslot_reel_anticipation_beams_imagegen.png") && Files.exists(Path.of("../qa/source/vslot_reel_anticipation_beams_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/reel_anticipation_beams_contact_sheet.png")))
        assertTrue("Reel landing spark must retain fail-closed historical source and visual QA evidence", Path.of("../tools/slice_imagegen_reel_landing_spark.py").readText().let { it.contains("vslot_reel_landing_spark_imagegen.png") && it.contains("NONCANONICAL_HISTORICAL_SLICER") } && Files.exists(Path.of("../qa/source/vslot_reel_landing_spark_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/reel_landing_spark_contact_sheet.png")))
        assertTrue("Themed reel landing sparks must retain fail-closed historical source and review evidence", Path.of("../tools/slice_imagegen_theme_reel_landing_sparks.py").readText().let { it.contains("vslot_theme_reel_landing_sparks_imagegen.png") && it.contains("NONCANONICAL_HISTORICAL_SLICER") } && Files.exists(Path.of("../qa/source/vslot_theme_reel_landing_sparks_imagegen.png")) && Files.exists(Path.of("../qa/screenshots/theme_reel_landing_sparks_contact_sheet.png")) && Files.exists(Path.of("../qa/design/reel_landing_spark_visual_philosophy.md")))
        assertTrue("Slot reel aperture must render as an image layer in portrait and landscape", slotLayout.contains("@+id/reelApertureShadow") && slotLayout.contains("@drawable/reel_aperture_shadow") && slotLandscapeLayout.contains("@+id/reelApertureShadow") && slotLandscapeLayout.contains("@drawable/reel_aperture_shadow"))
        assertTrue("Slot reel aperture must stay decorative and not enter accessibility traversal", slotLayout.split("@+id/reelApertureShadow", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/reelApertureShadow", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot reel aperture must switch theme image assets and pulse with reel stop anticipation", slotFragment.contains("reelApertureShadowDrawable") && slotFragment.contains("R.drawable.reel_aperture_shadow_roman") && slotFragment.contains("R.drawable.reel_aperture_shadow_neon") && slotFragment.contains("R.drawable.reel_aperture_shadow_pharaoh") && slotFragment.contains("R.drawable.reel_aperture_shadow_ocean") && slotFragment.contains("binding.reelApertureShadow.setImageResource") && slotFragment.contains("REEL_APERTURE_SETTLED_ALPHA") && slotFragment.contains("ObjectAnimator.ofFloat(aperture, View.TRANSLATION_Y"))
        assertTrue("Slot reel brake clamp must render as a decorative image layer in portrait and landscape", slotLayout.contains("@+id/reelBrakeLayer") && slotLandscapeLayout.contains("@+id/reelBrakeLayer") && slotLayout.split("@+id/reelBrakeLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/reelBrakeLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot reel brake clamp must lazily switch and release all theme image assets", slotFragment.contains("reelBrakeClampDrawable") && slotFragment.contains("R.drawable.reel_brake_clamp_roman") && slotFragment.contains("R.drawable.reel_brake_clamp_neon") && slotFragment.contains("R.drawable.reel_brake_clamp_pharaoh") && slotFragment.contains("R.drawable.reel_brake_clamp_ocean") && slotFragment.contains("brake.setImageResource(reelBrakeClampDrawable(viewModel.uiState.value.config.theme))") && slotFragment.contains("reelBrakeViews.forEach(ImageView::clearBoundImageResource)"))
        assertTrue("Slot reel motion streak must render as a decorative image layer in portrait and landscape", slotLayout.contains("@+id/reelMotionStreakLayer") && slotLandscapeLayout.contains("@+id/reelMotionStreakLayer") && slotLayout.split("@+id/reelMotionStreakLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/reelMotionStreakLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot reel motion streak must lazily switch and release all theme image assets", slotFragment.contains("reelMotionStreakDrawable") && slotFragment.contains("R.drawable.reel_motion_streak_roman") && slotFragment.contains("R.drawable.reel_motion_streak_neon") && slotFragment.contains("R.drawable.reel_motion_streak_pharaoh") && slotFragment.contains("R.drawable.reel_motion_streak_ocean") && slotFragment.contains("val motionStreakDrawable = reelMotionStreakDrawable(config.theme)") && slotFragment.contains("reelMotionStreakViews.forEach(ImageView::clearBoundImageResource)"))
        assertTrue("Slot reel anticipation beam must render as a decorative image layer in portrait and landscape", slotLayout.contains("@+id/reelAnticipationBeamLayer") && slotLandscapeLayout.contains("@+id/reelAnticipationBeamLayer") && slotLayout.split("@+id/reelAnticipationBeamLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/reelAnticipationBeamLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot reel anticipation beam must lazily switch and release all theme image assets", slotFragment.contains("reelAnticipationBeamDrawable") && slotFragment.contains("R.drawable.reel_anticipation_beam_roman") && slotFragment.contains("R.drawable.reel_anticipation_beam_neon") && slotFragment.contains("R.drawable.reel_anticipation_beam_pharaoh") && slotFragment.contains("R.drawable.reel_anticipation_beam_ocean") && slotFragment.contains("beam.setImageResource(reelAnticipationBeamDrawable(viewModel.uiState.value.config.theme))") && slotFragment.contains("reelAnticipationBeamViews.forEach(ImageView::clearBoundImageResource)"))
        assertTrue("Slot reel landing spark must render as a decorative image layer in portrait and landscape", slotLayout.contains("@+id/reelLandingSparkLayer") && slotLandscapeLayout.contains("@+id/reelLandingSparkLayer") && slotLayout.split("@+id/reelLandingSparkLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && slotLandscapeLayout.split("@+id/reelLandingSparkLayer", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Slot reel landing spark must lazily switch and release all theme image assets", slotFragment.contains("reelLandingSparkDrawable") && slotFragment.contains("R.drawable.reel_landing_spark_violet") && slotFragment.contains("R.drawable.reel_landing_spark_roman") && slotFragment.contains("R.drawable.reel_landing_spark_neon") && slotFragment.contains("R.drawable.reel_landing_spark_pharaoh") && slotFragment.contains("R.drawable.reel_landing_spark_ocean") && slotFragment.contains("spark.setImageResource(reelLandingSparkDrawable(viewModel.uiState.value.config.theme))") && slotFragment.contains("reelLandingSparkViews.forEach(ImageView::clearBoundImageResource)"))
        assertTrue("Slot reel landing spark must use imagegen bitmaps and finite resettable motion", slotFragment.contains("setupReelLandingSparkLayer()") && slotFragment.contains("R.drawable.reel_landing_spark_violet") && slotFragment.contains("pulseReelLandingSparkColumn(column)") && slotFragment.contains("reelLandingSparkAnimators") && slotFragment.contains("REEL_LANDING_SPARK_DURATION_MS") && slotFragment.contains("hideReelLandingSparkLayer(immediate = true)") && !slotFragment.substringAfter("private fun pulseReelLandingSparkColumn").substringBefore("private fun pulseReelStopColumn").contains("ValueAnimator.INFINITE"))
        assertTrue("Slot reel brake clamp must sit above moving strips and below masks in both orientations", slotLayout.indexOf("@+id/reelBrakeLayer") > slotLayout.indexOf("@+id/reelSpinStripLayer") && slotLayout.indexOf("@+id/reelBrakeLayer") < slotLayout.indexOf("@+id/reelWindowDepthMask") && slotLandscapeLayout.indexOf("@+id/reelBrakeLayer") > slotLandscapeLayout.indexOf("@+id/reelSpinStripLayer") && slotLandscapeLayout.indexOf("@+id/reelBrakeLayer") < slotLandscapeLayout.indexOf("@+id/reelWindowDepthMask"))
        assertTrue("Slot reel motion streak must sit above moving strips and below brake clamps in both orientations", slotLayout.indexOf("@+id/reelMotionStreakLayer") > slotLayout.indexOf("@+id/reelSpinStripLayer") && slotLayout.indexOf("@+id/reelMotionStreakLayer") < slotLayout.indexOf("@+id/reelBrakeLayer") && slotLandscapeLayout.indexOf("@+id/reelMotionStreakLayer") > slotLandscapeLayout.indexOf("@+id/reelSpinStripLayer") && slotLandscapeLayout.indexOf("@+id/reelMotionStreakLayer") < slotLandscapeLayout.indexOf("@+id/reelBrakeLayer"))
        assertTrue("Slot reel anticipation beam must sit above motion streaks and below brake clamps in both orientations", slotLayout.indexOf("@+id/reelAnticipationBeamLayer") > slotLayout.indexOf("@+id/reelMotionStreakLayer") && slotLayout.indexOf("@+id/reelAnticipationBeamLayer") < slotLayout.indexOf("@+id/reelBrakeLayer") && slotLandscapeLayout.indexOf("@+id/reelAnticipationBeamLayer") > slotLandscapeLayout.indexOf("@+id/reelMotionStreakLayer") && slotLandscapeLayout.indexOf("@+id/reelAnticipationBeamLayer") < slotLandscapeLayout.indexOf("@+id/reelBrakeLayer"))
        assertTrue("Slot reel landing spark must sit above anticipation beams and below brake clamps in both orientations", slotLayout.indexOf("@+id/reelLandingSparkLayer") > slotLayout.indexOf("@+id/reelAnticipationBeamLayer") && slotLayout.indexOf("@+id/reelLandingSparkLayer") < slotLayout.indexOf("@+id/reelBrakeLayer") && slotLandscapeLayout.indexOf("@+id/reelLandingSparkLayer") > slotLandscapeLayout.indexOf("@+id/reelAnticipationBeamLayer") && slotLandscapeLayout.indexOf("@+id/reelLandingSparkLayer") < slotLandscapeLayout.indexOf("@+id/reelBrakeLayer"))
        assertTrue("Landscape reel-window image layers must use a safe runtime inset so edge symbols do not sit under cabinet art", slotLandscapeLayout.contains("android:layout_marginStart=\"30dp\"") && slotLandscapeLayout.contains("android:layout_marginEnd=\"30dp\"") && slotFragment.contains("applyLandscapeReelWindowInsets()") && slotFragment.contains("REEL_WINDOW_LANDSCAPE_HORIZONTAL_INSET_DP = 30") && slotFragment.contains("binding.reelSpinStripLayer") && slotFragment.contains("binding.spinBlurOverlay") && slotFragment.contains("params.marginStart = horizontalInset") && slotFragment.contains("params.marginEnd = horizontalInset"))
        assertTrue("Reel preview must use a clipped ImageView strip layer like a slot cabinet reel window", slotLayout.contains("@+id/reelSpinStripLayer") && slotLayout.contains("android:clipChildren=\"true\"") && slotFragment.contains("setupReelSpinStripLayer()") && slotFragment.contains("reelSpinSymbolViews") && slotFragment.contains("renderSpinStripColumn"))
        assertTrue("Reel preview must use per-column image motion streaks during spin instead of only a whole-window blur", slotFragment.contains("setupReelMotionStreakLayer()") && slotFragment.contains("animateReelMotionStreak(column, phase, scatterChase, durationMs)") && slotFragment.contains("pulseReelMotionStreakColumn(column, scatterChase)") && slotFragment.contains("settleReelMotionStreakColumn(column)") && slotFragment.contains("hideReelMotionStreakLayer(immediate = true)") && slotFragment.contains("REEL_MOTION_STREAK_LAYER_FADE_MS"))
        assertTrue("Reel preview must use image anticipation beams for bonus scatter chase", slotFragment.contains("setupReelAnticipationBeamLayer()") && slotFragment.contains("pulseReelAnticipationBeamColumn(column, scatterChase)") && slotFragment.contains("reelAnticipationBeamAnimators") && slotFragment.contains("REEL_SCATTER_BEAM_DURATION_MS") && slotFragment.contains("hideReelAnticipationBeamLayer(immediate = true)") && slotFragment.contains("if (!scatterChase) return"))
        assertTrue("Reel strip preview must hide static cells per symbol, then reveal stopped columns while image strips continue spinning", slotFragment.contains("binding.reelsGrid.alpha = 1f") && slotFragment.contains("cell.alpha = 0f") && slotFragment.contains("revealStoppedReelColumn(config.theme, column, targetColumn)") && slotFragment.contains("fadeStoppedSpinColumn(column)") && slotFragment.contains("hideReelSpinStripLayer()"))
        assertTrue("Reel preview must animate independent vertical columns with staggered stops", slotFragment.contains("renderReelColumn") && slotFragment.contains("animateReelColumnSpin") && slotFragment.contains("animateReelColumnStop") && slotFragment.contains("SlotSpinTimeline.stopAtMs") && slotFragment.contains("pulseReelStopColumn"))
        assertTrue("Reel strip preview must cruise continuously, then align each column to the precomputed RNG stop", slotFragment.contains("val alignedStopOffsets = IntArray(REEL_COUNT)") && slotFragment.contains("ReelSpinTrajectory.shouldBeginAlignment(remainingToStopMs)") && slotFragment.contains("ReelStopAlignment.targetOffsetWithMinimumTravel(") && slotFragment.contains("alignedStoppingStep(") && slotFragment.contains("spinningStripSymbols(config, column, columnOffsets[column])") && slotFragment.contains("animateSpinStripColumnStop(column, slamStopping = isSlamStop)") && slotFragment.contains("REEL_STOP_STRIP_BOUNCE_DURATION_MS"))
        assertTrue("Reel strip preview must use real strip stop indexes for physical neighbor symbols", slotFragment.contains("targetResult?.stopIndexes") && slotFragment.contains("targetStopIndexes?.getOrNull(column)") && slotFragment.contains("ReelStopAlignment.targetOffsetWithMinimumTravel(") && slotFragment.contains("motionBlurred = false"))
        assertTrue("Reel preview must use each active configured reel strip instead of a generic symbol loop", slotFragment.contains("config.reelStripsFor(") && slotFragment.contains("activeReelStrips.getOrNull(column)") && slotFragment.contains("?: config.symbols") && slotFragment.contains("spinningStripSymbols(config, column, columnOffsets[column])") && !slotFragment.contains("spinningColumnSymbols(config.symbols, column, offset)"))
        assertTrue("Reel preview must travel by measured reel-window cell height instead of a tiny symbol jiggle", slotFragment.contains("private fun reelStripCellHeightPx(column: Int)") && slotFragment.contains("measuredColumnCellHeight") && slotFragment.contains("REEL_SPIN_STRIP_SYMBOL_COUNT") && !slotFragment.contains("REEL_SPIN_TRAVEL_DP = 30"))
        assertTrue("Reel preview must use downward strip steps with safe wrapped indexes", slotFragment.contains("columnOffsets[column] -= step") && slotFragment.contains("reelSpinSymbolStep(phase, column, scatterChaseActive)") && slotFragment.contains("wrappedStripIndex(offset + column * REEL_COLUMN_OFFSET + stripRow, symbols.size)"))
        assertTrue("Reel preview must use phase interpolators and a continuous stop bounce so braking does not snap", slotFragment.contains("AccelerateInterpolator") && slotFragment.contains("DecelerateInterpolator") && slotFragment.contains("reelSpinInterpolatorFor(phase, scatterChase)") && slotFragment.contains("ReelSpinTrajectory.stopBounceCellOffsets(stopStartCellOffset)") && slotFragment.contains("stopStartScaleY"))
        assertTrue("Reel preview must use linear symbol-height ticks so spinning strips do not snap between frames", slotFragment.contains("LinearInterpolator") && slotFragment.contains(".setInterpolator(reelSpinInterpolator)") && slotFragment.contains("REEL_SPIN_OVERLAP_MS = 0L"))
        assertTrue("Reel preview must advance continuous strip geometry for cruise and target-alignment steps", slotFragment.contains("REEL_SPIN_ACCEL_STEP_SYMBOLS = 1") && slotFragment.contains("REEL_SPIN_CRUISE_STEP_SYMBOLS = 1") && slotFragment.contains("REEL_SPIN_DECEL_STEP_SYMBOLS = 1") && slotFragment.contains("REEL_SCATTER_ANTICIPATION_STEP_SYMBOLS = 1") && slotFragment.contains("ReelSpinTrajectory.animationStartCellOffset(step)"))
        assertTrue("Reel preview must feel like a slot reel with acceleration, cruise, deceleration, and anticipation before stop", slotFragment.contains("enum class ReelSpinPhase") && slotFragment.contains("ReelSpinPhase.Acceleration") && slotFragment.contains("ReelSpinPhase.Cruise") && slotFragment.contains("ReelSpinPhase.Deceleration") && slotFragment.contains("reelSpinPhase(elapsedMs, visualStopAtMs)") && slotFragment.contains("animateSpinStripColumnAnticipation(column, scatterChaseActive)") && slotFragment.contains("REEL_STOP_ANTICIPATION_MS"))
        assertTrue("Reel anticipation must pulse image window and stop-flash layers before each column settles", slotFragment.contains("animateReelAnticipationKick(column, scatterChase)") && slotFragment.contains("pulseReelAnticipationColumn(column, scatterChase)") && slotFragment.contains("binding.reelWindowDepthMask") && slotFragment.contains("REEL_STOP_WINDOW_KICK_DURATION_MS") && slotFragment.contains("REEL_STOP_ANTICIPATION_FLASH_ALPHA"))
        assertTrue("Reel anticipation and final stop must pulse image brake clamps instead of only swapping symbols", slotFragment.contains("pulseReelBrakeColumn(column, scatterChase = scatterChase)") && slotFragment.contains("pulseReelBrakeColumn(column, scatterChase = false, finalStop = true)") && slotFragment.contains("REEL_BRAKE_FINAL_PULSE_DURATION_MS") && slotFragment.contains("REEL_BRAKE_COLUMN_STAGGER_MS") && slotFragment.contains("hideReelBrakeLayer(immediate = true)"))
        assertTrue("Reel preview and settlement must share a complete dynamic reveal timeline", slotViewModel.contains("SlotSpinTimeline.revealDurationMs(config, result)") && slotFragment.contains("normalStopTimesMs.maxOrNull()") && slotFragment.contains("SlotSpinTimeline.REEL_STOP_BOUNCE_DURATION_MS") && slotFragment.contains("SlotSpinTimeline.REVEAL_SETTLE_MS") && slotSpinTimeline.contains("BASE_SPIN_DURATION_MS = 1_520L") && slotSpinTimeline.contains("REEL_STOP_STAGGER_MS = 300L") && slotSpinTimeline.contains("REEL_STOP_BOUNCE_DURATION_MS = 520L") && slotSpinTimeline.contains("lastStopMs + REEL_STOP_BOUNCE_DURATION_MS + REVEAL_SETTLE_MS"))
        assertTrue("Winning results must use image overlay animation before result dialog", slotFragment.contains("animateWinResultIfNeeded") && slotFragment.contains("animateWinOverlay(result)"))
        assertTrue("Bonus scatter trigger must use a dedicated lazy theme image halo layer instead of only generic win highlighting", slotLayout.contains("@+id/bonusScatterHaloLayer") && slotFragment.contains("setupBonusScatterHaloLayer()") && slotFragment.contains("R.drawable.symbol_bonus_scatter_halo_violet") && slotFragment.contains("bonusScatterHaloDrawable(viewModel.uiState.value.config.theme)") && slotFragment.contains("halo.clearBoundImageResource()") && !slotFragment.contains("setImageResource(R.drawable.symbol_bonus_scatter_halo)") && slotFragment.contains("bonusScatterCellIndexes") && slotFragment.contains("animateBonusScatterHalos(bonusScatterCells)") && slotFragment.contains("hideBonusScatterHalos(immediate = true)"))
        assertTrue("Bonus results must show a free-spins theme image banner and feature-entry portal instead of generic win feedback only", slotFragment.contains("animateBigWinBanner(result)") && slotFragment.contains("bonusFreeSpinsBannerDrawable(theme)") && slotFragment.contains("animateBonusEntryPortal(theme)") && slotFragment.contains("R.string.slot_bonus_free_spins_banner"))
        assertTrue("Slot result dialog event must be buffered so delayed UI feedback is not dropped", slotViewModel.contains("Channel.BUFFERED") && slotViewModel.contains("receiveAsFlow()") && slotViewModel.contains("data class ResultReady(") && slotViewModel.contains("val presentationId: String"))
        val publishSettledSpinBlock = slotViewModel
            .substringAfter("private suspend fun publishSettledSpin(")
            .substringBefore("private suspend fun settleCommittedSpin(")
        val resultReadyBlock = publishSettledSpinBlock
            .substringAfter("if (resultPresentationDurationMs > 0L) delay(resultPresentationDurationMs)")
            .substringBefore("return true")
        val resultDismissedBlock = slotViewModel.substringAfter("fun onResultDialogDismissed(").substringBefore("fun onSpinPresentationRendered")
        assertTrue("Slot ViewModel must keep winning results pending until the result dialog is dismissed", !resultReadyBlock.contains("isResultPending.value = false") && resultDismissedBlock.contains("isResultPending.value = false"))
        assertTrue("Slot result dialog must be shown from a one-shot result event with actual award, theme, and durable presentation id", slotFragment.contains("is SlotEvent.ResultReady -> showResultDialog(") && slotFragment.contains("event.presentationId") && slotFragment.contains("ResultDialogFragment.newInstance(") && slotFragment.contains("viewModel.uiState.value.config.theme") && slotFragment.contains("result.netOutcome == NetOutcome.Bonus && freeSpinsAwarded <= 0"))
        assertTrue("Slot result dialog dismissal must resume active autospin through fragment result", resultDialog.contains("REQUEST_KEY") && resultDialog.contains("setFragmentResult") && slotFragment.contains("setFragmentResultListener(ResultDialogFragment.REQUEST_KEY)") && slotFragment.contains("viewModel.onResultDialogDismissed("))
        assertTrue("Active autospin must not wait for a manual close after win, bonus, or final free-spins summary modals", slotFragment.contains("autoSpinResultDismissJob") && slotFragment.contains("scheduleAutoSpinResultDismiss(result, freeSpinsAwarded, freeSpinsTotalWin != null)") && slotFragment.contains("isFreeSpinsSummary ||") && slotFragment.contains("viewModel.uiState.value.isAutoSpinEnabled") && slotFragment.contains("AUTO_SPIN_RESULT_DISMISS_DELAY_MS") && slotFragment.contains("AUTO_SPIN_BONUS_RESULT_DISMISS_DELAY_MS") && slotFragment.contains("dialog.dismissAllowingStateLoss()"))
        val showResultDialogBlock = slotFragment.substringAfter("private fun showResultDialog").substringBefore("private fun showLowCoinsDialog")
        assertTrue("Slot result dialog must show safely with a stable tag", showResultDialogBlock.contains("parentFragmentManager.isStateSaved") && showResultDialogBlock.contains("findFragmentByTag(SPIN_RESULT_DIALOG_TAG)") && showResultDialogBlock.contains(".show(parentFragmentManager, SPIN_RESULT_DIALOG_TAG)"))
        assertTrue("Autospin dialog auto-dismiss job must use the stable spin result tag and be cancelled with the slot view", slotFragment.contains("SPIN_RESULT_DIALOG_TAG") && slotFragment.contains("findFragmentByTag(SPIN_RESULT_DIALOG_TAG)") && slotFragment.contains("autoSpinResultDismissJob?.cancel()") && slotFragment.contains("autoSpinResultDismissJob = null"))
        assertTrue("Slot paytable dialog must show safely with a stable tag and stay blocked during active gameplay", slotFragment.contains("private fun showPaytable(): Boolean") && slotFragment.contains("state.isSpinStartReserved") && slotFragment.contains("state.isSpinning") && slotFragment.contains("state.isResultPending") && slotFragment.contains("state.isAutoSpinEnabled") && slotFragment.contains("binding.paytableButton.isEnabled = controlsEnabled") && slotFragment.contains("PAYTABLE_CONTROL_DISABLED_ALPHA") && slotFragment.contains("PAYTABLE_DIALOG_TAG") && slotFragment.contains("parentFragmentManager.isStateSaved") && slotFragment.contains("findFragmentByTag(PAYTABLE_DIALOG_TAG)") && slotFragment.contains(".show(parentFragmentManager, PAYTABLE_DIALOG_TAG)"))
    }

    @Test
    fun `analytics tracks bonus and app opens without trusting launcher extras`() {
        val bonusDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/DailyBonusDialogFragment.kt").readText()
        val mainActivity = Path.of("src/main/java/com/vslot/app/MainActivity.kt").readText()
        val slotFragment = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val appMetricaTracker = Path.of("src/main/java/com/vslot/app/analytics/AppMetricaAnalyticsTracker.kt").readText()
        val lazyAnalyticsRuntime = Path.of("src/main/java/com/vslot/app/analytics/LazyAnalyticsRuntime.kt").readText()
        val application = Path.of("src/main/java/com/vslot/app/VSlotApplication.kt").readText()
        val disabledAppSetIdCompat = Path.of(
            "src/main/java/io/appmetrica/analytics/appsetid/internal/DisabledAppSetIdCompat.kt"
        ).readText()
        val mainManifest = Path.of("src/main/AndroidManifest.xml").readText()
        val buildGradle = Path.of("build.gradle.kts").readText()
        val proguardRules = Path.of("proguard-rules.pro").readText()
        val splashFragment = Path.of("src/main/java/com/vslot/app/ui/splash/SplashFragment.kt").readText()
        val disclaimerFragment = Path.of("src/main/java/com/vslot/app/ui/disclaimer/DisclaimerFragment.kt").readText()
        val disclaimerViewModel = Path.of("src/main/java/com/vslot/app/ui/disclaimer/DisclaimerViewModel.kt").readText()

        assertTrue("bonus_claim must include balance_after", bonusDialog.contains("\"balance_after\""))
        assertTrue("first_launch analytics must not send raw timestamps", splashFragment.contains("AnalyticsEvents.FirstLaunch") && !splashFragment.contains("install_timestamp") && !splashFragment.contains("System.currentTimeMillis()"))
        assertTrue("disclaimer_accept analytics must not send raw timestamps", disclaimerViewModel.contains("AnalyticsEvents.DisclaimerAccept") && !disclaimerViewModel.contains("timestamp") && !disclaimerViewModel.contains("System.currentTimeMillis()"))
        assertTrue("Disclaimer continue flow must ignore repeat taps while acceptance is being persisted", disclaimerFragment.contains("acceptanceInProgress") && disclaimerFragment.contains("return@setOnClickListener"))
        assertTrue("Disclaimer persistence must finish bounded transient I/O and expose failure without navigating", disclaimerViewModel.contains("finishTransientPersistenceIo") && disclaimerViewModel.contains("catch (_: IOException)") && disclaimerViewModel.contains("DisclaimerAcceptanceState.Failed") && disclaimerFragment.contains("DisclaimerAcceptanceState.Saved"))
        assertTrue("Disclaimer accepted navigation must survive view recreation and wait for a resumed destination", disclaimerFragment.contains("viewModel.acceptanceState.collect") && disclaimerFragment.contains("Lifecycle.State.RESUMED") && disclaimerFragment.contains("parentFragmentManager.isStateSaved") && disclaimerFragment.contains("currentDestination?.id != R.id.disclaimerFragment"))
        assertTrue("MainActivity must track app_open once after consent on initial creation", mainActivity.contains("if (savedInstanceState == null)") && mainActivity.contains("trackInitialOpenWhenConsentReady()") && mainActivity.contains("trackAppOpen()") && mainActivity.contains("AnalyticsEvents.AppOpen"))
        val initialOpenBlock = mainActivity.substringAfter("private fun trackInitialOpenWhenConsentReady()").substringBefore("private suspend fun applyPersistedAnalyticsConsent")
        assertTrue("App open must wait for persisted analytics consent", mainActivity.contains("playerState.first().analyticsEnabled") && initialOpenBlock.indexOf("applyPersistedAnalyticsConsent()") < initialOpenBlock.indexOf("trackAppOpen()"))
        assertTrue("A player-state I/O failure must keep analytics fail-closed without crashing startup", initialOpenBlock.contains("catch (error: IOException)") && initialOpenBlock.contains("setAnalyticsEnabled(false)") && initialOpenBlock.indexOf("trackAppOpen()") < initialOpenBlock.indexOf("catch (error: IOException)"))
        assertFalse("Exported launcher must not enumerate or trust arbitrary extras for manual push analytics", mainActivity.contains(".extras") || mainActivity.contains("keySet()") || mainActivity.contains("PushOpenPayload") || mainActivity.contains("AnalyticsEvents.PushOpen"))
        assertTrue("AppMetrica analytics must sanitize params and swallow SDK runtime failures", appMetricaTracker.contains("analyticsName") && appMetricaTracker.contains("analyticsStringValue") && appMetricaTracker.contains("hasUnsafeAnalyticsMarker") && appMetricaTracker.contains("contains(\"://\")") && appMetricaTracker.contains("catch (_: RuntimeException)") && appMetricaTracker.contains("Analytics failures must never interrupt gameplay"))
        assertTrue("AppMetrica must activate lazily and disable automatic collection before explicit consent", application.contains("LazyAnalyticsRuntime") && application.contains("activateAppMetricaRuntime(apiKey)") && lazyAnalyticsRuntime.contains("if (!enabled)") && lazyAnalyticsRuntime.contains("delegate?.setAnalyticsEnabled(false) ?: true") && application.contains("withDataSendingEnabled(false)") && application.contains("withAdvIdentifiersTracking(false)") && application.contains("withLocationTracking(false)") && application.contains("withCrashReporting(false)") && application.contains("withNativeCrashReporting(false)") && application.contains("withSessionsAutoTrackingEnabled(false)") && application.contains("withAppOpenTrackingEnabled(false)") && application.contains("withAnrMonitoring(false)") && application.contains("withRevenueAutoTrackingEnabled(false)"))
        assertTrue(
            "Push registration must stay fail-closed until the in-app prompt and system permission are accepted",
            mainManifest.contains("firebase_messaging_auto_init_enabled") &&
                mainManifest.contains("firebase_messaging_installation_id_enabled") &&
                mainManifest.contains("firebase_analytics_collection_enabled") &&
                mainManifest.contains("firebase_messaging_notification_delegation_enabled") &&
                mainManifest.contains("delivery_metrics_exported_to_big_query_enabled") &&
                mainManifest.windowed(160, 1, partialWindows = true).any { block ->
                    block.contains("firebase_messaging_auto_init_enabled") && block.contains("android:value=\"false\"")
                } &&
                mainManifest.windowed(160, 1, partialWindows = true).any { block ->
                    block.contains("firebase_messaging_installation_id_enabled") &&
                        block.contains("android:value=\"false\"")
                } &&
                application.contains("pushRegistrationCommand(") &&
                application.contains("if (!command.requiresRuntimeMutation) return@reconcile") &&
                application.contains("isAutoInitEnabled = false") &&
                !application.contains("isAutoInitEnabled = true") &&
                !application.contains("isAutoInitEnabled = enabled") &&
                application.contains("AppMetricaPush.activate(applicationContext)") &&
                application.contains("firebaseMessaging.awaitLegacyRegistrationToken()") &&
                application.contains("firebaseMessaging.deleteLegacyRegistrationToken()") &&
                application.contains("private suspend fun FirebaseMessaging.awaitLegacyRegistrationToken()") &&
                application.contains("private suspend fun FirebaseMessaging.deleteLegacyRegistrationToken()") &&
                Regex("""@Suppress\("DEPRECATION"\)\s+private suspend fun FirebaseMessaging\.""")
                    .findAll(application).count() == 2 &&
                application.contains("FirebaseInstallations.getInstance().delete()") &&
                mainActivity.contains("refreshPushRegistration()")
        )
        assertTrue("Unused AppMetrica data collection modules must stay excluded", listOf("analytics-ad-revenue", "analytics-appsetid", "analytics-billing", "analytics-id-sync", "analytics-identifiers", "analytics-location", "analytics-ndkcrashes", "analytics-screenshot").all { module -> buildGradle.contains("module = \"$module\"") } && buildGradle.contains("module = \"play-services-appset\"") && !buildGradle.contains("androidx.legacy:legacy-support-v4"))
        assertTrue("AppMetrica App Set ID compatibility must remain fail-closed", buildGradle.contains("analytics-core-api:8.3.0") && disabledAppSetIdCompat.contains("class AppSetIdRetriever : IAppSetIdRetriever") && disabledAppSetIdCompat.contains("listener.onFailure(") && !disabledAppSetIdCompat.contains("listener.onAppSetIdRetrieved("))
        assertFalse("R8 must not suppress missing AppMetrica runtime classes", proguardRules.contains("-dontwarn io.appmetrica"))
        assertTrue("AppMetrica tracker must drop events while consent is disabled", appMetricaTracker.contains("if (!dataSendingEnabled) return") && appMetricaTracker.contains("setAnalyticsEnabled"))
        assertTrue("MainActivity must render the game in immersive fullscreen", mainActivity.contains("prepareImmersiveWindow") && mainActivity.contains("WindowCompat.setDecorFitsSystemWindows(window, false)") && mainActivity.contains("hideSystemBars"))
        assertTrue("MainActivity must hide status and navigation bars transiently", mainActivity.contains("WindowInsetsCompat.Type.systemBars()") && mainActivity.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"))
        assertTrue("Every destination must remain inside cutout, gesture, and visible system-bar safe areas without double-padding the slot", mainActivity.contains("installSafeAreaInsets") && mainActivity.contains("ViewCompat.setOnApplyWindowInsetsListener(navHostView)") && mainActivity.contains("currentDestinationId == R.id.slotFragment") && mainActivity.contains("WindowInsetsCompat.Type.displayCutout()") && mainActivity.contains("WindowInsetsCompat.Type.mandatorySystemGestures()") && mainActivity.contains("WindowInsetsCompat.Type.systemBars()") && mainActivity.contains("view.updatePadding(") && slotFragment.contains("WindowInsetsCompat.Type.displayCutout()") && slotFragment.contains("WindowInsetsCompat.Type.mandatorySystemGestures()") && slotFragment.contains("gestureInsets.bottom"))
        assertTrue("Slot destination must retain its local cutout ownership without consuming child insets", mainActivity.contains("currentDestinationId == R.id.slotFragment") && mainActivity.contains("Insets.NONE") && mainActivity.contains("addOnDestinationChangedListener") && mainActivity.contains("ViewCompat.requestApplyInsets(navHostView)") && mainActivity.contains("\n            insets\n"))
    }

    @Test
    fun `image dialogs keep immersive fullscreen`() {
        val dialogWindowPolish = Path.of("src/main/java/com/vslot/app/ui/dialog/DialogWindowPolish.kt").readText()
        val dialogFiles = listOf(
            "DailyBonusDialogFragment.kt",
            "LowCoinsDialogFragment.kt",
            "PaytableDialogFragment.kt",
            "AnalyticsConsentDialogFragment.kt",
            "PushPermissionDialogFragment.kt",
            "ResultDialogFragment.kt",
            "SocialRulesDialogFragment.kt",
            "ThirdPartyNoticesDialogFragment.kt"
        )

        assertTrue("Dialog fullscreen helper must hide all system bars", dialogWindowPolish.contains("fun Dialog.keepGameFullscreen()") && dialogWindowPolish.contains("WindowInsetsCompat.Type.systemBars()") && dialogWindowPolish.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"))
        dialogFiles.forEach { fileName ->
            val dialogFile = Path.of("src/main/java/com/vslot/app/ui/dialog/$fileName").readText()
            assertTrue("$fileName must keep image modal windows fullscreen", dialogFile.contains("setOnShowListener") && dialogFile.contains("keepGameFullscreen()"))
        }
    }

    @Test
    fun `settings push button reflects permission state`() {
        val settingsLayout = Path.of("src/main/res/layout/fragment_settings.xml").readText()
        val settingsFragment = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt").readText()
        val settingsViewModel = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsViewModel.kt").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")

        assertTrue("Settings control console glow asset missing", Files.exists(drawableRoot.resolve("settings_control_console_glow.webp")))
        assertTrue("Settings layout must expose push label", settingsLayout.contains("@+id/pushButtonLabel"))
        assertTrue("Settings layout must expose push status", settingsLayout.contains("@+id/pushStatusText"))
        assertTrue("Settings layout must expose push status image stage", settingsLayout.contains("@+id/pushStatusStage") && settingsLayout.contains("@+id/pushStatusSignalPulse"))
        assertTrue("Settings must render control console glow from image asset", settingsLayout.contains("@+id/settingsControlGlow") && settingsLayout.contains("@drawable/settings_control_console_glow"))
        assertTrue("Settings control console glow must stay decorative", settingsLayout.contains("@+id/settingsControlGlow") && settingsLayout.contains("android:importantForAccessibility=\"no\""))
        assertTrue("SettingsViewModel must expose playerState", settingsViewModel.contains("val playerState"))
        val persistenceHelper = settingsViewModel
            .substringAfter("private fun persistSetting(")
            .substringBefore("class Factory")
        assertTrue("Settings persistence must finish bounded transient I/O without enabling analytics before consent is saved", persistenceHelper.contains("finishTransientPersistenceIo(operation = update)") && persistenceHelper.indexOf("finishTransientPersistenceIo(operation = update)") < persistenceHelper.indexOf("onPersisted()") && persistenceHelper.contains("catch (_: IOException)"))
        assertTrue("SettingsFragment must collect player and runtime push state together", settingsFragment.contains("combine(") && settingsFragment.contains("viewModel.playerState") && settingsFragment.contains("application.pushRegistrationStatus") && settingsFragment.contains(".collect"))
        assertTrue("SettingsFragment must check notification permission", settingsFragment.contains("isNotificationPermissionGranted"))
        assertTrue("SettingsFragment must honor package and channel-level notification blocking on every supported Android version", settingsFragment.contains("context.areNotificationsDeliverable()") && settingsFragment.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU"))
        assertTrue("Settings push action must route completed permission decisions to system notification settings", settingsFragment.contains("pushPermissionAction(") && settingsFragment.contains("PushPermissionAction.OpenSystemSettings -> openNotificationSettings()") && settingsFragment.contains("AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS") && settingsFragment.contains("AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue("Settings console polish must be finite and respect disabled system animators", settingsFragment.contains("animateSettingsConsolePolish") && settingsFragment.contains("ValueAnimator.areAnimatorsEnabled()") && settingsFragment.contains("SETTINGS_CONSOLE_SETTLED_ALPHA") && !settingsFragment.contains("ValueAnimator.INFINITE"))
        assertTrue("Push asked status string missing", strings.contains("push_asked_status"))
        assertTrue("Push enabled status string missing", strings.contains("push_enabled_status"))
        assertTrue("Unconfigured push service must not expose a dead-end status", !strings.contains("push_unconfigured_status"))
        assertTrue("Settings must disable push action when push service is not configured or registration is pending", settingsFragment.contains("if (!pushConfigured) {") && settingsFragment.contains("binding.pushButton.isEnabled = false") && settingsFragment.contains("val pushActionEnabled = !registrationPending") && settingsFragment.contains("binding.pushButton.isEnabled = pushActionEnabled"))
        assertTrue("Settings dialogs must show safely with stable tags", settingsFragment.contains("showSocialRulesDialog()") && settingsFragment.contains("SOCIAL_RULES_DIALOG_TAG") && settingsFragment.contains("PUSH_PERMISSION_DIALOG_TAG") && settingsFragment.contains("parentFragmentManager.isStateSaved") && settingsFragment.contains("findFragmentByTag(SOCIAL_RULES_DIALOG_TAG)") && settingsFragment.contains("findFragmentByTag(PUSH_PERMISSION_DIALOG_TAG)") && settingsFragment.contains(".show(parentFragmentManager, SOCIAL_RULES_DIALOG_TAG)") && settingsFragment.contains(".show(parentFragmentManager, PUSH_PERMISSION_DIALOG_TAG)"))
        assertTrue("Settings navigation must ignore stale rapid taps", settingsFragment.contains("navigateFromSettings") && settingsFragment.contains("popFromSettings") && settingsFragment.contains("currentDestination?.id != R.id.settingsFragment"))
        assertTrue("Settings push permission result listener must be scoped to the view lifecycle", settingsFragment.contains("parentFragmentManager.setFragmentResultListener(PushPermissionDialogFragment.REQUEST_KEY, viewLifecycleOwner)") && !settingsFragment.contains("setFragmentResultListener(PushPermissionDialogFragment.REQUEST_KEY) {"))
        assertTrue("Deferring the explanatory push prompt must not be recorded as a system permission denial", settingsFragment.contains("viewModel.onPushPermissionDeferred()") && !settingsFragment.contains("viewModel.onPushPermissionResult(false)"))
    }

    @Test
    fun `all modal panel layouts include image backplate`() {
        val layoutRoot = Path.of("src/main/res/layout")
        val layoutFiles = Files.walk(layoutRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.name.endsWith(".xml") }
                .toList()
        }
        val violations = layoutFiles.mapNotNull { path ->
            val text = path.readText()
            val panelIndex = listOf(
                text.indexOf("@drawable/modal_panel\""),
                text.indexOf("@drawable/result_modal_panel"),
                text.indexOf("@drawable/social_rules_modal_panel\""),
                text.indexOf("@drawable/disclaimer_modal_panel\""),
                text.indexOf("@drawable/low_coins_modal_panel"),
                text.indexOf("@drawable/daily_bonus_modal_panel"),
                text.indexOf("@drawable/paytable_modal_panel"),
                text.indexOf("@drawable/settings_modal_panel"),
                text.indexOf("@drawable/push_permission_modal_panel")
            ).filter { it >= 0 }.minOrNull() ?: -1
            if (panelIndex < 0) {
                null
            } else {
                val backplateIndex = listOf(
                    text.indexOf("@drawable/modal_panel_backplate"),
                    text.indexOf("@drawable/settings_modal_backplate")
                ).filter { it >= 0 }.minOrNull() ?: -1
                if (backplateIndex in 0 until panelIndex) null else path.toString()
            }
        }

        assertTrue("Modal panel layouts without leading image backplate: $violations", violations.isEmpty())
    }

    @Test
    fun `paytable themes render unique imagegen modal panels`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val themes = listOf("violet", "roman", "neon", "pharaoh", "ocean")
        val expectedAssets = themes.map { "paytable_modal_panel_$it.webp" }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            Files.exists(path) && Files.size(path) < 90_000L
        }
        val undersized = expectedAssets.filter { asset ->
            val path = drawableRoot.resolve(asset)
            if (!Files.exists(path)) return@filter false
            val size = readBitmapSize(path)
            size.width < 1_000 || size.height < 1_400
        }
        val duplicateHashes = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .groupBy { sha256(drawableRoot.resolve(it)) }
            .filterValues { it.size > 1 }
            .values
            .toList()
        val paytableLayout = Path.of("src/main/res/layout/dialog_paytable.xml").readText()
        val paytableLandscapeLayout = Path.of("src/main/res/layout-land/dialog_paytable.xml").readText()
        val paytableDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/PaytableDialogFragment.kt").readText()

        assertTrue("Missing imagegen paytable modal panels: $missing", missing.isEmpty())
        assertTrue("Imagegen paytable modal panels are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Imagegen paytable modal panels have placeholder pixel dimensions: $undersized", undersized.isEmpty())
        assertTrue("Paytable modal panels must be visually unique files: $duplicateHashes", duplicateHashes.isEmpty())
        assertTrue("Portrait paytable layout must default to the Violet imagegen panel", paytableLayout.contains("@+id/paytableModalPanel") && paytableLayout.contains("@drawable/paytable_modal_panel_violet"))
        assertTrue("Landscape paytable layout must default to the Violet imagegen panel", paytableLandscapeLayout.contains("@+id/paytableModalPanel") && paytableLandscapeLayout.contains("@drawable/paytable_modal_panel_violet"))
        themes.forEach { theme ->
            assertTrue("PaytableDialogFragment must wire $theme modal panel", paytableDialog.contains("R.drawable.paytable_modal_panel_$theme"))
        }
        assertTrue("PaytableDialogFragment must set the modal panel from the active slot theme", paytableDialog.contains("private fun paytableModalPanelDrawable()") && paytableDialog.contains("binding.paytableModalPanel.setImageResource(paytableModalPanelDrawable())"))
    }

    @Test
    fun `paytable renders readable scalable payout multipliers`() {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val paytableLayout = Path.of("src/main/res/layout/dialog_paytable.xml").readText()
        val paytableLandscapeLayout = Path.of("src/main/res/layout-land/dialog_paytable.xml").readText()
        val paytableDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/PaytableDialogFragment.kt").readText()
        val paytableBonusLaneGenerator = Path.of("../tools/generate_paytable_bonus_lane_assets.py").readText()
        val dimensions = Path.of("src/main/res/values/dimens.xml").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()
        val requiredFooters = listOf(
            "label_paytable_footer_violet.webp",
            "label_paytable_footer_roman.webp",
            "paytable_modal_panel_violet.webp",
            "paytable_modal_panel_roman.webp",
            "paytable_modal_panel_neon.webp",
            "paytable_modal_panel_pharaoh.webp",
            "paytable_modal_panel_ocean.webp",
            "paytable_odds_header_glow.webp",
            "paytable_odds_header_glow_neon.webp",
            "paytable_odds_header_glow_pharaoh.webp",
            "paytable_odds_header_glow_ocean.webp",
            "paytable_cabinet_lattice.webp",
            "paytable_cabinet_lattice_roman.webp",
            "paytable_cabinet_lattice_neon.webp",
            "paytable_cabinet_lattice_pharaoh.webp",
            "paytable_cabinet_lattice_ocean.webp",
            "paytable_row_panel.webp",
            "paytable_row_panel_roman.webp",
            "paytable_row_panel_neon.webp",
            "paytable_row_panel_pharaoh.webp",
            "paytable_row_panel_ocean.webp",
            "paytable_bonus_lane.webp",
            "paytable_bonus_lane_roman.webp",
            "paytable_bonus_lane_neon.webp",
            "paytable_bonus_lane_pharaoh.webp",
            "paytable_bonus_lane_ocean.webp",
            "paytable_scroll_hint.webp",
            "paytable_payline_guide.webp",
            "paytable_payline_guide_roman.webp",
            "paytable_payline_guide_neon.webp",
            "paytable_payline_guide_pharaoh.webp",
            "paytable_payline_guide_ocean.webp",
            "label_paytable_bet_explanation.webp",
            "modal_badge_bonus.webp"
        )
        val missingFooters = requiredFooters.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tinyFooters = requiredFooters.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 1_000 }

        assertTrue("Missing paytable footer image assets: $missingFooters", missingFooters.isEmpty())
        assertTrue("Paytable footer image assets are unexpectedly tiny: $tinyFooters", tinyFooters.isEmpty())
        listOf("paytableHeaderThree", "paytableHeaderFour", "paytableHeaderFive").forEach { id ->
            assertTrue("Paytable header $id must exist", paytableLayout.contains("@+id/$id"))
        }
        assertTrue("Paytable headers must use compact autosized text", listOf("paytableHeaderThree", "paytableHeaderFour", "paytableHeaderFive").all { id -> paytableLayout.substringAfter("@+id/$id").substringBefore("/>").let { header -> header.contains("@style/VSlotAccessibleCopy.PaytableHeader") && header.contains("android:layout_height=\"match_parent\"") && header.contains("app:autoSizeTextType=\"uniform\"") && header.contains("app:autoSizeMinTextSize=\"8sp\"") } } && !paytableLayout.contains("com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue("Paytable bets must use scalable styled text", paytableLayout.substringAfter("@+id/paytableBetsDigits").substringBefore("/>").contains("@style/VSlotAccessibleCopy.PaytableValue"))
        assertTrue("Paytable dialog must use a theme image modal panel", paytableLayout.contains("@+id/paytableModalPanel") && paytableLayout.contains("@drawable/paytable_modal_panel_violet") && paytableDialog.contains("binding.paytableModalPanel.setImageResource(paytableModalPanelDrawable())"))
        assertTrue("Paytable modal panel must switch to every slot theme image asset", paytableDialog.contains("paytableModalPanelDrawable") && paytableDialog.contains("SlotTheme.Roman -> R.drawable.paytable_modal_panel_roman") && paytableDialog.contains("SlotTheme.Neon -> R.drawable.paytable_modal_panel_neon") && paytableDialog.contains("SlotTheme.Pharaoh -> R.drawable.paytable_modal_panel_pharaoh") && paytableDialog.contains("SlotTheme.Ocean -> R.drawable.paytable_modal_panel_ocean") && paytableDialog.contains("SlotTheme.Violet -> R.drawable.paytable_modal_panel_violet"))
        assertTrue("Paytable odds header must render from a dedicated image asset", paytableLayout.contains("@+id/paytableOddsHeaderGlow") && paytableLayout.contains("@drawable/paytable_odds_header_glow"))
        assertTrue("Paytable odds header must switch to theme image assets for new slots", paytableDialog.contains("paytableOddsHeaderGlowDrawable") && paytableDialog.contains("SlotTheme.Neon -> R.drawable.paytable_odds_header_glow_neon") && paytableDialog.contains("SlotTheme.Pharaoh -> R.drawable.paytable_odds_header_glow_pharaoh") && paytableDialog.contains("SlotTheme.Ocean -> R.drawable.paytable_odds_header_glow_ocean"))
        assertTrue("Paytable odds header must stay decorative", paytableLayout.contains("@+id/paytableOddsHeaderGlow") && paytableLayout.split("@+id/paytableOddsHeaderGlow", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Paytable odds header must sit behind title and table header content", paytableLayout.indexOf("@+id/paytableOddsHeaderGlow") > paytableLayout.indexOf("@drawable/paytable_modal_panel_violet") && paytableLayout.indexOf("@+id/paytableOddsHeaderGlow") < paytableLayout.indexOf("@+id/paytableTitle") && paytableLayout.indexOf("@+id/paytableOddsHeaderGlow") < paytableLayout.indexOf("@+id/paytableRows"))
        assertTrue("Paytable cabinet lattice must render from a dedicated image asset", paytableLayout.contains("@+id/paytableCabinetLattice") && paytableLayout.contains("@drawable/paytable_cabinet_lattice"))
        assertTrue("Paytable cabinet lattice must switch to Roman image asset for Roman Reels", paytableDialog.contains("R.drawable.paytable_cabinet_lattice_roman") && paytableDialog.contains("R.drawable.paytable_cabinet_lattice"))
        assertTrue("Paytable cabinet lattice must switch to theme image assets for new slots", paytableDialog.contains("paytableCabinetLatticeDrawable") && paytableDialog.contains("SlotTheme.Neon -> R.drawable.paytable_cabinet_lattice_neon") && paytableDialog.contains("SlotTheme.Pharaoh -> R.drawable.paytable_cabinet_lattice_pharaoh") && paytableDialog.contains("SlotTheme.Ocean -> R.drawable.paytable_cabinet_lattice_ocean"))
        assertTrue("Paytable cabinet lattice must stay decorative", paytableLayout.contains("@+id/paytableCabinetLattice") && paytableLayout.split("@+id/paytableCabinetLattice", limit = 2)[1].contains("android:importantForAccessibility=\"no\""))
        assertTrue("Paytable cabinet lattice must sit above the modal panel and below header/content", paytableLayout.indexOf("@+id/paytableCabinetLattice") > paytableLayout.indexOf("@drawable/paytable_modal_panel_violet") && paytableLayout.indexOf("@+id/paytableCabinetLattice") < paytableLayout.indexOf("@+id/paytableOddsHeaderGlow") && paytableLayout.indexOf("@+id/paytableCabinetLattice") < paytableLayout.indexOf("@+id/paytableTitle") && paytableLayout.indexOf("@+id/paytableCabinetLattice") < paytableLayout.indexOf("@+id/paytableRows"))
        assertTrue("Paytable cabinet lattice polish must be finite, managed, and image based", paytableDialog.contains("animatePaytableCabinetLattice") && paytableDialog.contains("binding.paytableCabinetLattice") && paytableDialog.contains("paytableCabinetLatticeAnimator") && paytableDialog.contains("paytableCabinetLatticeAnimator?.cancel()") && paytableDialog.contains("PAYTABLE_LATTICE_SETTLED_ALPHA") && paytableDialog.contains("ValueAnimator.areAnimatorsEnabled()") && !paytableDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Paytable footer must default to image asset", paytableLayout.contains("@drawable/label_paytable_footer_violet"))
        assertTrue("Paytable footer must keep accessibility text", paytableLayout.contains("android:contentDescription=\"@string/paytable_footer_violet\""))
        assertTrue("Paytable footer accessibility must explain wilds, scatters, retriggers, and virtual coins", strings.contains("paytable_footer_violet\">Вайлд заменяет") && strings.contains("3 и более скаттеров в любых позициях") && strings.contains("добавляют ещё 5 фриспинов") && strings.contains("только на виртуальные монеты") && strings.contains("paytable_footer_roman\">Вайлд заменяет"))
        assertTrue("Paytable dynamic accessibility copy must come from Russian resources", strings.contains("paytable_bonus_symbol_accessibility") && strings.contains("paytable_payout_accessibility") && paytableDialog.contains("R.string.paytable_bonus_symbol_accessibility") && paytableDialog.contains("R.string.paytable_payout_accessibility") && !paytableDialog.contains("\"Бонусный символ:") && !paytableDialog.contains("\"по линии\"") && !paytableDialog.contains("\"от общей ставки\"") && !paytableDialog.contains("\"нет выплаты\""))
        assertTrue("Paytable title must not render through TextView text", !paytableDialog.contains("paytableTitle.text"))
        assertTrue("Paytable bets subtitle must not render through TextView text", !paytableDialog.contains("paytableSubtitle.text"))
        assertTrue("Paytable footer must not render through TextView text", !paytableDialog.contains("paytableFooter.text"))
        assertTrue("Paytable dialog must swap title images dynamically", paytableDialog.contains("R.drawable.title_paytable_roman_reels") && paytableDialog.contains("R.drawable.title_paytable_violet_fortune"))
        assertTrue("Paytable dialog must swap footer images dynamically", paytableDialog.contains("R.drawable.label_paytable_footer_roman") && paytableDialog.contains("R.drawable.label_paytable_footer_violet"))
        assertTrue("Paytable dialog must render configured bets through scalable text", paytableDialog.contains("binding.paytableBetsDigits.text = compactBets"))
        assertTrue("Paytable bets must have responsive width in landscape", paytableLandscapeLayout.substringAfter("@+id/paytableBetsDigits").substringBefore("/>").contains("android:layout_width=\"match_parent\""))
        assertTrue("Paytable bets must render compact visual odds while keeping readable accessibility copy", paytableDialog.contains("joinToString(\"/\")") && paytableDialog.contains("paytableBetsDigits.contentDescription = bets"))
        assertTrue("Paytable bets accessibility copy must identify the listed values as line bets", strings.contains("paytable_bets_content_description\">Ставки на линию:"))
        assertTrue("Paytable must render a visual 10-payline guide from image assets", paytableLayout.contains("@+id/paytablePaylineGuide") && paytableLayout.contains("@drawable/paytable_payline_guide") && paytableLayout.contains("@string/paytable_payline_guide"))
        assertTrue("Paytable guide accessibility copy must identify the payline diagram", strings.contains("paytable_payline_guide\">Схема 10 линий выплат"))
        assertTrue("Paytable must visibly explain line and scatter multipliers, total bet, left-to-right evaluation, and best-win handling", strings.contains("paytable_bet_explanation\">Выплата по линии = множитель × ставка на линию") && strings.contains("Скаттер оплачивается отдельно: множитель × общая ставка, в любых позициях") && strings.contains("Общая ставка = ставка на линию × активные линии") && strings.contains("непрерывные комбинации слева направо, начиная с первого барабана") && strings.contains("только одна комбинация с наибольшей выплатой") && paytableLayout.contains("@+id/paytableBetExplanation") && paytableLayout.contains("android:contentDescription=\"@string/paytable_bet_explanation\"") && paytableLayout.contains("android:src=\"@drawable/label_paytable_bet_explanation\"") && paytableLandscapeLayout.contains("@+id/paytableBetExplanation") && paytableLandscapeLayout.contains("android:contentDescription=\"@string/paytable_bet_explanation\"") && paytableLandscapeLayout.contains("android:src=\"@drawable/label_paytable_bet_explanation\""))
        assertTrue("Paytable guide must switch to Roman image asset for Roman Reels", paytableDialog.contains("R.drawable.paytable_payline_guide_roman") && paytableDialog.contains("binding.paytablePaylineGuide.setImageResource"))
        assertTrue("Paytable guide must switch to theme image assets for new slots", paytableDialog.contains("paytablePaylineGuideDrawable") && paytableDialog.contains("SlotTheme.Neon -> R.drawable.paytable_payline_guide_neon") && paytableDialog.contains("SlotTheme.Pharaoh -> R.drawable.paytable_payline_guide_pharaoh") && paytableDialog.contains("SlotTheme.Ocean -> R.drawable.paytable_payline_guide_ocean"))
        assertTrue("Paytable guide must keep image-first copy plus a hidden scalable peer", paytableLayout.contains("@+id/paytablePaylineGuideLargeText") && paytableLayout.contains("android:text=\"@string/paytable_payline_guide\""))
        assertTrue("Paytable dynamic payouts must use readable scalable text", paytableDialog.contains("TextView(requireContext())") && paytableDialog.contains("textSize = if (compactBonusRow) 15f else 17f"))
        assertTrue("Paytable rows must render image-backed row panels", paytableDialog.contains("R.drawable.paytable_row_panel") && paytableDialog.contains("paytableRowContent(symbol)"))
        assertTrue("Paytable rows must switch to a Roman image row panel for Roman Reels", paytableDialog.contains("paytableRowPanelDrawable") && paytableDialog.contains("SlotTheme.Roman -> R.drawable.paytable_row_panel_roman"))
        assertTrue("Paytable rows must switch to theme image panels for new slots", paytableDialog.contains("SlotTheme.Neon -> R.drawable.paytable_row_panel_neon") && paytableDialog.contains("SlotTheme.Pharaoh -> R.drawable.paytable_row_panel_pharaoh") && paytableDialog.contains("SlotTheme.Ocean -> R.drawable.paytable_row_panel_ocean"))
        assertTrue("Paytable must render scatter bonus payouts as a separate image row", paytableDialog.contains("createScatterBonusRow") && paytableDialog.contains("scatterBonusRowContent") && paytableDialog.contains("config.scatterBonus"))
        assertTrue("Paytable scatter bonus row must sit next to the scatter symbol row", paytableDialog.contains("symbol == config.scatter"))
        assertTrue("Paytable scatter bonus row must render a dedicated decorative free-spins lane image", paytableDialog.contains("includeBonusLane = true") && paytableDialog.contains("paytableBonusLaneDrawable") && paytableDialog.contains("R.drawable.paytable_bonus_lane") && paytableDialog.contains("paytableBonusLaneViews += this") && paytableDialog.contains("View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue("Paytable bonus lane must remain an edge-only frame that cannot cover payout digits", paytableBonusLaneGenerator.contains("draw.rounded_rectangle") && !paytableBonusLaneGenerator.contains("draw_bonus_glints") && !paytableBonusLaneGenerator.contains("draw.line(") && !paytableBonusLaneGenerator.contains("draw.ellipse(") && !paytableBonusLaneGenerator.contains("draw_plus_five"))
        assertTrue("Paytable scatter bonus lane must switch all theme image assets", paytableDialog.contains("SlotTheme.Roman -> R.drawable.paytable_bonus_lane_roman") && paytableDialog.contains("SlotTheme.Neon -> R.drawable.paytable_bonus_lane_neon") && paytableDialog.contains("SlotTheme.Pharaoh -> R.drawable.paytable_bonus_lane_pharaoh") && paytableDialog.contains("SlotTheme.Ocean -> R.drawable.paytable_bonus_lane_ocean"))
        assertTrue("Paytable scatter bonus lane must sit above the row panel but below symbol and multiplier content", paytableDialog.contains("addView(content)") && paytableDialog.indexOf("setImageResource(paytableRowPanelDrawable())") < paytableDialog.indexOf("setImageResource(paytableBonusLaneDrawable())") && paytableDialog.indexOf("setImageResource(paytableBonusLaneDrawable())") < paytableDialog.indexOf("addView(content)"))
        assertTrue("Paytable scatter bonus lane polish must be finite and cleaned up", paytableDialog.contains("animatePaytableBonusLane()") && paytableDialog.contains("paytableBonusLaneAnimator") && paytableDialog.contains("PAYTABLE_BONUS_LANE_SETTLED_ALPHA") && paytableDialog.contains("PAYTABLE_BONUS_LANE_PEAK_ALPHA") && paytableDialog.contains("ValueAnimator.areAnimatorsEnabled()") && paytableDialog.contains("paytableBonusLaneAnimator?.cancel()") && !paytableDialog.contains("ValueAnimator.INFINITE"))
        assertTrue("Paytable scatter bonus lane must stay behind payout digits instead of overpowering them", paytableDialog.contains("PAYTABLE_BONUS_LANE_SETTLED_ALPHA = 0.32f") && paytableDialog.contains("PAYTABLE_BONUS_LANE_PEAK_ALPHA = 0.68f"))
        assertTrue("Paytable scatter bonus row must keep one clear symbol without a competing halo or badge", paytableDialog.contains("private fun bonusSymbolCell()") && paytableDialog.contains("SlotSymbolResources.image(config.theme, config.scatter)") && !paytableDialog.contains("R.drawable.symbol_bonus_scatter_halo") && !paytableDialog.contains("R.drawable.modal_badge_bonus"))
        assertTrue("Paytable scatter bonus payouts must autosize inside full-height cells", paytableDialog.contains("compactBonusRow = true") && paytableDialog.contains("setAutoSizeTextTypeUniformWithConfiguration") && paytableDialog.contains("PAYTABLE_BONUS_MULTIPLIER_MIN_TEXT_SP") && paytableDialog.contains("PAYTABLE_BONUS_MULTIPLIER_MAX_TEXT_SP") && paytableDialog.contains("PAYTABLE_MULTIPLIER_HORIZONTAL_MARGIN_DP") && !paytableDialog.contains("PAYTABLE_BONUS_MULTIPLIER_HEIGHT_DP"))
        assertTrue("Paytable line payouts must be distinguished from total-bet bonus payouts", paytableDialog.contains("R.string.paytable_line_payout_suffix") && paytableDialog.contains("R.string.paytable_total_bet_payout_suffix"))
        assertTrue("Paytable headers must be populated with text and accessibility descriptions", paytableDialog.contains("private fun TextView.setMultiplierHeader") && paytableDialog.contains("contentDescription = value"))
        assertTrue("Paytable rows must use a fixed image-safe height", dimensions.contains("paytable_row_height") && paytableDialog.contains("R.dimen.paytable_row_height"))
        assertTrue("Paytable row image assets must not control row height", paytableDialog.contains("LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)"))
        assertTrue("Paytable viewport must derive its exact pixel height from whole rows", paytableDialog.contains("bindWholeRowViewportHeight(binding)") && paytableDialog.contains("height = rowHeightPx * visibleRows") && paytableDialog.contains("PAYTABLE_PORTRAIT_VISIBLE_ROWS = 4") && paytableDialog.contains("PAYTABLE_LANDSCAPE_VISIBLE_ROWS = 3"))
        assertTrue("Paytable portrait panel must give the payout table enough vertical room", paytableLayout.contains("android:layout_height=\"620dp\""))
        assertTrue(
            "Paytable viewport must show four complete 54dp image rows instead of clipping a partial row",
            paytableLayout.contains("android:layout_height=\"@dimen/paytable_rows_stage_height\"") &&
                dimensions.contains("<dimen name=\"paytable_row_height\">54dp</dimen>") &&
                dimensions.contains("<dimen name=\"paytable_rows_stage_height\">216dp</dimen>")
        )
        assertTrue("Paytable rows stage must hard-clip payout cells at the viewport", paytableLayout.contains("@+id/paytableRowsStage") && paytableLayout.split("@+id/paytableRowsStage", limit = 2)[1].substringBefore("@+id/paytableScrollHint").contains("android:clipChildren=\"true\"") && paytableLayout.split("@+id/paytableRowsStage", limit = 2)[1].substringBefore("@+id/paytableScrollHint").contains("android:clipToPadding=\"true\""))
        val paytableScrollView = paytableLayout.substringAfter("@+id/paytableScrollView").substringBefore("</ScrollView>")
        assertTrue("Paytable payout rows must clip at the viewport while the sibling scroll hint can extend beyond it", paytableScrollView.contains("android:clipChildren=\"true\"") && paytableScrollView.contains("android:clipToPadding=\"true\""))
        assertTrue("Paytable must expose native and image scroll guidance", paytableLayout.contains("@+id/paytableScrollView") && paytableLayout.contains("android:scrollbars=\"vertical\"") && paytableLayout.contains("android:fadeScrollbars=\"false\"") && paytableDialog.contains("paytableScrollView.isVerticalScrollBarEnabled = true") && paytableLayout.contains("@+id/paytableScrollHint") && paytableLayout.contains("@drawable/paytable_scroll_hint") && paytableLayout.contains("android:layout_width=\"18dp\"") && paytableLayout.contains("android:layout_height=\"78dp\"") && paytableLayout.split("@+id/paytableScrollHint", limit = 2)[1].substringBefore("/>").contains("android:layout_marginEnd=\"0dp\""))
        assertTrue("Paytable scroll hint must stay decorative, managed, and state driven", paytableLayout.contains("@+id/paytableScrollHint") && paytableLayout.split("@+id/paytableScrollHint", limit = 2)[1].contains("android:importantForAccessibility=\"no\"") && paytableDialog.contains("updatePaytableScrollHint") && paytableDialog.contains("paytableScrollView.canScrollVertically(1)") && paytableDialog.contains("setOnScrollChangeListener") && paytableDialog.contains("paytableScrollHintAnimator") && paytableDialog.contains("paytableScrollHintAnimator?.cancel()") && paytableDialog.contains("PAYTABLE_SCROLL_HINT_SETTLED_ALPHA"))
        assertTrue("Paytable dialog must keep the game fullscreen while open", paytableDialog.contains("keepGameFullscreen()"))
        assertTrue("Landscape paytable must grow from a 320dp short-viewport baseline", paytableLandscapeLayout.contains("android:minHeight=\"320dp\"") && Regex("<TextView").findAll(paytableLandscapeLayout).count() == 7)
        assertTrue("Landscape paytable must show three complete 40dp rows and retain a 48dp close target", paytableLandscapeLayout.contains("android:layout_height=\"120dp\"") && paytableLandscapeLayout.contains("android:layout_height=\"48dp\""))
        assertTrue("Paytable footer must keep image-first copy plus a hidden scalable peer", paytableLayout.contains("@+id/paytableFooterLargeText") && paytableLayout.contains("android:text=\"@string/paytable_footer_violet\"") && !paytableLayout.contains("android:text=\"@string/paytable_footer_roman\""))
        assertTrue("Paytable multiplier headers must come from string resources", paytableLayout.contains("@string/paytable_header_three") && paytableLayout.contains("@string/paytable_header_four") && paytableLayout.contains("@string/paytable_header_five"))
        assertTrue("Paytable dialog must create payout TextViews", paytableDialog.contains("TextView(requireContext())"))
        assertTrue("Paytable dialog must import TextView for payout cells", paytableDialog.contains("android.widget.TextView"))
    }

    @Test
    fun `bitmap number view supports multiplier glyph assets`() {
        val bitmapNumberView = sourceText("src/main/java/com/vslot/app/ui/widget/BitmapNumberView.kt")
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val requiredDigits = listOf(
            "digit_0.webp",
            "digit_1.webp",
            "digit_2.webp",
            "digit_3.webp",
            "digit_4.webp",
            "digit_5.webp",
            "digit_6.webp",
            "digit_7.webp",
            "digit_8.webp",
            "digit_9.webp",
            "digit_comma.webp",
            "digit_plus.webp",
            "digit_minus.webp",
            "digit_x.webp",
            "digit_colon.webp",
            "digit_slash.webp",
            "digit_space.webp"
        )
        val missing = requiredDigits.filterNot { Files.exists(drawableRoot.resolve(it)) }

        assertTrue("BitmapNumberView must map lowercase x to the cached digit_x glyph", bitmapNumberView.contains("'x', 'X' -> X_GLYPH") && bitmapNumberView.contains("X_GLYPH = Glyph(R.drawable.digit_x"))
        assertTrue("BitmapNumberView must map slash to cached regular and compact glyphs", bitmapNumberView.contains("'/' -> if (compactSeparators) COMPACT_SLASH_GLYPH else SLASH_GLYPH") && bitmapNumberView.contains("COMPACT_SLASH_GLYPH = Glyph(R.drawable.digit_slash, weight = 0.92f)") && bitmapNumberView.contains("SLASH_GLYPH = Glyph(R.drawable.digit_slash, weight = 0.72f)"))
        assertTrue("BitmapNumberView must map cooldown colon to cached regular and compact glyphs", bitmapNumberView.contains("':' -> if (compactSeparators) COMPACT_COLON_GLYPH else COLON_GLYPH") && bitmapNumberView.contains("COMPACT_COLON_GLYPH = Glyph(R.drawable.digit_colon, weight = 0.34f)") && bitmapNumberView.contains("COLON_GLYPH = Glyph(R.drawable.digit_colon, weight = 0.42f)"))
        assertTrue("BitmapNumberView must map Russian thousands space to cached glyphs", bitmapNumberView.contains("' ' -> if (compactSeparators) COMPACT_SPACE_GLYPH else SPACE_GLYPH") && bitmapNumberView.contains("Glyph(R.drawable.digit_space"))
        assertTrue("BitmapNumberView must support compact spacing for long bitmap rows", bitmapNumberView.contains("compactSeparators") && bitmapNumberView.contains("spacingPx"))
        assertTrue("BitmapNumberView must support fixed glyph widths for compressed image rows", bitmapNumberView.contains("fixedGlyphBaseWidthDp") && bitmapNumberView.contains("resources.displayMetrics.density"))
        assertTrue("BitmapNumberView must format numbers with Russian space grouping without reverse/chunk lists", bitmapNumberView.contains("digitsRemaining % 3 == 0") && bitmapNumberView.contains("append(' ')") && !bitmapNumberView.contains("chunked(3)"))
        assertTrue("BitmapNumberView must not use US comma number formatting", !bitmapNumberView.contains("Locale.US") && !bitmapNumberView.contains("%,d"))
        assertTrue("Missing bitmap digit assets: $missing", missing.isEmpty())
        requiredDigits.forEach { fileName ->
            val size = Files.size(drawableRoot.resolve(fileName))
            val minimumSize = if (fileName == "digit_space.webp") 20 else 200
            assertTrue("Bitmap digit asset $fileName is unexpectedly tiny: $size bytes", size > minimumSize)
        }
    }

    @Test
    fun `slot rng uses configured reel strips instead of independent cell rolls`() {
        val config = Path.of("src/main/assets/slots_config.json").readText()
        val parser = Path.of("src/main/java/com/vslot/app/game/SlotConfigParser.kt").readText()
        val engine = Path.of("src/main/java/com/vslot/app/game/SlotEngine.kt").readText()
        val releasedMath = Path.of("src/main/java/com/vslot/app/game/ReleasedSlotMathV5.kt").readText()
        val models = Path.of("src/main/java/com/vslot/app/game/SlotModels.kt").readText()
        val mathBalanceTest = Path.of("src/test/java/com/vslot/app/game/SlotMathBalanceTest.kt").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()

        assertTrue("Production slot config must define reel strips for every bundled slot", Regex("\"reelStrips\"").findAll(config).count() == 5)
        assertTrue("Every bundled slot must define explicit physical free-spin reel strips", Regex("\"freeSpinReelStrips\"").findAll(config).count() == 5 && models.contains("val freeSpinReelStrips: List<List<String>>") && parser.contains("getJSONArray(\"freeSpinReelStrips\")") && engine.contains("reelStripsFor(isFreeSpin)"))
        assertTrue("Free spins must use a reviewed extra-wild feature on the third reel", parser.contains("FREE_SPIN_ENHANCED_WILD_REEL_INDEX = 2") && parser.contains("FREE_SPIN_EXTRA_WILDS = 1") && parser.contains("must contain exactly one additional wild") && strings.contains("Во фриспинах на третьем барабане вдвое больше вайлдов"))
        assertTrue("SlotConfig must require configured reel strips without an independent-cell fallback", models.contains("val reelStrips: List<List<String>>") && !models.contains("val reelStrips: List<List<String>> = emptyList()"))
        assertTrue("SpinResult must carry reel strip stop indexes for animation", models.contains("val stopIndexes: List<Int> = emptyList()"))
        assertTrue("SlotConfigParser must require reel strips from JSON and keep every payable symbol reachable on every reel", parser.contains("getJSONArray(\"reelStrips\")") && !parser.contains("optJSONArray(\"reelStrips\")") && parser.contains("toStringListList()") && parser.contains("slot.symbols.all(strip::contains)") && parser.contains("every configured symbol"))
        assertTrue("SlotConfigParser must reject paytables where longer matches pay less", parser.contains("strictlyIncreaseWithPayoutCount") && parser.contains("payouts must increase with match length") && parser.contains("scatter bonuses must increase with scatter count"))
        assertTrue("Slot economy arithmetic must reject integer overflow before a spin can corrupt wager or payout values", parser.contains("validateEconomyRange(slot)") && parser.contains("maximum total bet exceeds") && parser.contains("maximum combined payout exceeds") && engine.contains("checkedSlotMultiply") && engine.contains("checkedSlotAdd"))
        assertTrue("Released SlotEngine math must choose one stop per active reel and never roll independent visible cells", engine.contains("ReleasedSlotMathV5.spin") && releasedMath.contains("val stopIndexes = reelStripsFor(config, isFreeSpin).map { strip -> rng.nextInt(strip.size) }") && releasedMath.contains("return evaluateStops(config, stopIndexes, bet, lines, isFreeSpin)") && releasedMath.contains("visibleReelWindow(config, reelIndex, stopIndexes[reelIndex], isFreeSpin)") && releasedMath.contains("validateReelStrips(config)") && !releasedMath.contains("config.reelStrips.isNotEmpty()") && !releasedMath.contains("config.symbols[rng.nextInt(config.symbols.size)]"))
        assertTrue("Production reel stops must use a cryptographically unpredictable RNG while retaining injectable QA RNG", models.contains("class SecureSlotRng(") && models.contains("SecureRandom()") && engine.contains("private val rng: SlotRng = SecureSlotRng()") && !models.contains("kotlin.random.Random.Default"))
        assertTrue("Released SlotEngine evaluate must reject malformed reel windows and stop indexes before scoring", releasedMath.contains("validateEvaluationInputs(config, reels, bet, stopIndexes, isFreeSpin)") && releasedMath.contains("reels.size == config.reels") && releasedMath.contains("symbols.size == config.rows") && releasedMath.contains("symbols.all { it in config.symbols }") && releasedMath.contains("stopIndexes.isEmpty() || stopIndexes.size == config.reels") && releasedMath.contains("stopIndex < strips[reelIndex].size") && releasedMath.contains("reels == expectedReels"))
        assertTrue("Slot RTP balance test must enumerate each reel strip length independently", mathBalanceTest.contains("forEachStopCombination(slot.reelStrips)") && mathBalanceTest.contains("reelStrips[reelIndex].indices") && !mathBalanceTest.contains("reelStrips.first().indices"))
        assertTrue("Slot RTP metrics must separate gross payout hits from net outcomes", mathBalanceTest.contains("payoutHitRate") && mathBalanceTest.contains("partialReturnRate") && mathBalanceTest.contains("breakEvenRate") && mathBalanceTest.contains("netWinRate") && !mathBalanceTest.contains("val hitRate"))
    }

    @Test
    fun `configured slot symbols have theme specific image assets`() {
        val slots = JSONObject(Path.of("src/main/assets/slots_config.json").readText()).getJSONArray("slots")
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        val slotSymbolResources = Path.of("src/main/java/com/vslot/app/ui/slot/SlotSymbolResources.kt").readText()
        val themePrefixes = mapOf(
            "violet" to "vf",
            "roman" to "rr",
            "neon" to "nn",
            "pharaoh" to "pg",
            "ocean" to "op"
        )
        val expectedAssets = buildSet {
            repeat(slots.length()) { slotIndex ->
                val slot = slots.getJSONObject(slotIndex)
                val prefix = themePrefixes.getValue(slot.getString("theme"))
                val symbols = slot.getJSONArray("symbols")
                repeat(symbols.length()) { symbolIndex ->
                    add("${prefix}_symbol_${symbols.getString(symbolIndex)}.webp")
                }
            }
        }
        val missing = expectedAssets.filterNot { Files.exists(drawableRoot.resolve(it)) }
        val tiny = expectedAssets.filter { Files.exists(drawableRoot.resolve(it)) && Files.size(drawableRoot.resolve(it)) < 8_000 }
        val undersized = expectedAssets
            .filter { Files.exists(drawableRoot.resolve(it)) }
            .mapNotNull { asset ->
                val size = readWebpSize(drawableRoot.resolve(asset))
                asset.takeIf { size.width < 240 || size.height < 240 }?.let { "$it=${size.width}x${size.height}" }
            }
        val unwired = expectedAssets.filterNot { asset ->
            slotSymbolResources.contains("R.drawable.${asset.removeSuffix(".webp")}")
        }

        assertTrue("Missing configured slot symbol image assets: $missing", missing.isEmpty())
        assertTrue("Configured slot symbol assets are unexpectedly tiny: $tiny", tiny.isEmpty())
        assertTrue("Configured slot symbol assets must be at least 240x240: $undersized", undersized.isEmpty())
        assertTrue("Configured slot symbol image assets must be wired in SlotSymbolResources: $unwired", unwired.isEmpty())
    }

    private fun readWebpSize(path: Path): BitmapSize {
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= 30 && bytes.asAscii(0, 4) == "RIFF" && bytes.asAscii(8, 4) == "WEBP") {
            "$path is not a valid WebP file"
        }
        return when (bytes.asAscii(12, 4)) {
            "VP8X" -> BitmapSize(
                width = bytes.readLittleEndian24(24) + 1,
                height = bytes.readLittleEndian24(27) + 1
            )
            "VP8 " -> BitmapSize(
                width = bytes.readLittleEndian16(26) and 0x3fff,
                height = bytes.readLittleEndian16(28) and 0x3fff
            )
            "VP8L" -> {
                val b0 = bytes[21].toInt() and 0xff
                val b1 = bytes[22].toInt() and 0xff
                val b2 = bytes[23].toInt() and 0xff
                val b3 = bytes[24].toInt() and 0xff
                BitmapSize(
                    width = 1 + (((b1 and 0x3f) shl 8) or b0),
                    height = 1 + (((b3 and 0x0f) shl 10) or (b2 shl 2) or ((b1 and 0xc0) shr 6))
                )
            }
            else -> error("Unsupported WebP chunk in $path")
        }
    }

    private fun readBitmapSize(path: Path): BitmapSize {
        return when {
            path.name.endsWith(".webp") -> readWebpSize(path)
            path.name.endsWith(".png") -> readPngSize(path)
            else -> error("Unsupported bitmap type: $path")
        }
    }

    private fun readPngSize(path: Path): BitmapSize {
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= 24 && bytes.asAscii(1, 3) == "PNG" && bytes.asAscii(12, 4) == "IHDR") {
            "$path is not a valid PNG file"
        }
        return BitmapSize(
            width = bytes.readBigEndian32(16),
            height = bytes.readBigEndian32(20)
        )
    }

    private fun readPngColorType(path: Path): Int {
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= 26 && bytes.asAscii(1, 3) == "PNG" && bytes.asAscii(12, 4) == "IHDR") {
            "$path is not a valid PNG file"
        }
        return bytes[25].toInt() and 0xff
    }

    private fun hasPngChunk(path: Path, expectedType: String): Boolean {
        require(expectedType.length == 4)
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= 8 && bytes.asAscii(1, 3) == "PNG") {
            "$path is not a valid PNG file"
        }
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = bytes.readBigEndian32(offset)
            require(length >= 0 && offset.toLong() + length + 12 <= bytes.size) {
                "$path contains an invalid PNG chunk"
            }
            if (bytes.asAscii(offset + 4, 4) == expectedType) return true
            offset += length + 12
        }
        return false
    }

    private fun runtimeLayoutDrawableRefs(): Set<String> {
        return listOf(
            Path.of("src/main/res/layout"),
            Path.of("src/main/res/layout-land")
        ).flatMap { root ->
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.name.endsWith(".xml") }
                    .flatMap { drawableRefs(it.readText()).stream() }
                    .toList()
            }
        }.toSet()
    }

    private fun drawableRefs(text: String): List<String> {
        return Regex("@drawable/([A-Za-z0-9_]+)")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun bitmapPathForDrawable(name: String): Path? {
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")
        return listOf(
            drawableRoot.resolve("$name.webp"),
            drawableRoot.resolve("$name.png")
        ).firstOrNull { Files.exists(it) }
    }

    private fun selectorPathForDrawable(name: String): Path? {
        val selector = Path.of("src/main/res/drawable/$name.xml")
        return selector.takeIf { Files.exists(it) }
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.readLittleEndian16(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.readLittleEndian24(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)
    }

    private fun ByteArray.readBigEndian32(offset: Int): Int {
        return ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
    }

    private companion object {
        val IMPORTANT_RUNTIME_IMAGE_PREFIXES = listOf(
            "btn_",
            "label_",
            "title_",
            "spin_button",
            "slot_card_",
            "level_progress_",
            "theme_ambient_overlay_",
            "theme_spin_overlay_",
            "theme_win_burst_"
        )
        val IMPORTANT_RUNTIME_IMAGE_MARKERS = listOf(
            "modal_panel",
            "modal_badge",
            "symbol_",
            "paytable_button",
            "home_xp_readout_plate",
            "slot_level_session_panel",
            "settings_push_status_console",
            "settings_push_status_signal_pulse",
            "settings_safety_anchor",
            "privacy_loading_shield",
            "privacy_loading_scan_rail",
            "privacy_loading_sweep"
        )
    }

    private fun sha256(path: Path): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }

    private data class BitmapSize(val width: Int, val height: Int)
}
