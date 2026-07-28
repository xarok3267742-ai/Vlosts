package com.vslot.app.licenses

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.readText

class ReleaseDependencyInventoryContractTest {
    private val appBuild = Path.of("build.gradle.kts").readText()
    private val packagedNotice = Path.of("src/main/assets/third_party_notices.txt").readText()
    private val repositoryNotice = Path.of("../THIRD_PARTY_NOTICES.md").readText()
    private val verificationMetadata = Path.of("../gradle/verification-metadata.xml").readText()

    @Test
    fun packagedManifestMapsEachReviewedRuntimeArtifactToAllLicensePolicies() {
        val entries = parseManifest(packagedNotice)

        assertEquals(131, entries.size)
        assertEquals(103, entries.values.count { it == "Apache-2.0" })
        assertEquals(20, entries.values.count { it == "MIT" })
        assertEquals(6, entries.values.count { it == "LicenseRef-Android-SDK-Terms" })
        assertEquals(1, entries.values.count { it == "BSD-3-Clause" })
        assertEquals(1, entries.values.count { it == "Apache-2.0,MPL-2.0" })
        assertEquals(
            "BSD-3-Clause",
            entries["androidx.datastore:datastore-preferences-external-protobuf:1.2.1"]
        )
        assertEquals(
            "LicenseRef-Android-SDK-Terms",
            entries["com.google.android.gms:play-services-base:18.10.0"]
        )
        assertEquals("MIT", entries["io.appmetrica.analytics:analytics:8.3.0"])
        assertEquals("Apache-2.0", entries["com.google.firebase:firebase-messaging:25.1.1"])
        assertEquals("Apache-2.0,MPL-2.0", entries["com.squareup.okhttp3:okhttp:4.11.0"])
        assertFalse(entries.containsKey("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0"))
    }

    @Test
    fun repositoryAndPackagedLicenseManifestsAreIdentical() {
        assertEquals(parseManifest(repositoryNotice), parseManifest(packagedNotice))
        assertTrue(packagedNotice.contains("Version 2.0, January 2004"))
        assertTrue(packagedNotice.contains("The MIT License (MIT)"))
        assertTrue(packagedNotice.contains("Copyright 2008 Google Inc. All rights reserved."))
        assertTrue(packagedNotice.contains("https://developer.android.com/studio/terms.html"))
        assertTrue(packagedNotice.contains("https://publicsuffix.org/list/public_suffix_list.dat"))
        assertTrue(packagedNotice.contains("https://mozilla.org/MPL/2.0/"))
    }

    @Test
    fun resolvedReleaseInventoryIncludesComponentsArtifactsAndDigests() {
        assertTrue(appBuild.contains("tasks.register(\"generateReleaseRuntimeClasspathInventory\")"))
        assertTrue(appBuild.contains("getByName(\"releaseRuntimeClasspath\")"))
        assertTrue(appBuild.contains("resolutionResult.allComponents"))
        assertTrue(appBuild.contains("incoming.artifacts.artifacts"))
        assertTrue(appBuild.contains("Release runtime dependencies must be reviewed external Maven modules"))
        assertTrue(appBuild.contains("identifier is ProjectComponentIdentifier && identifier.projectPath == project.path"))
        assertTrue(appBuild.contains("filterNot { identifier -> identifier is ModuleComponentIdentifier }"))
        assertTrue(appBuild.contains("sha256Hex(artifact.file)"))
        assertTrue(appBuild.contains("schema=release-runtime-classpath-inventory-v1"))
        assertTrue(appBuild.contains("[components]"))
        assertTrue(appBuild.contains("[artifacts]"))
        assertTrue(appBuild.contains("osv-scanner-release-runtime.json"))
        assertTrue(appBuild.contains("JsonOutput.prettyPrint"))
        assertTrue(appBuild.contains("\"ecosystem\" to \"Maven\""))
        assertTrue(appBuild.contains("tasks.register(\"verifyReleaseOsvInventory\")"))
        assertTrue(appBuild.contains("tasks.register(\"updateReleaseOsvInventory\")"))
        assertTrue(appBuild.contains("Reviewed OSV inventory is stale"))
        assertTrue(appBuild.contains("configurations.configureEach"))
        assertTrue(appBuild.contains("module = \"analytics-appsetid\""))
        assertTrue(appBuild.contains("module = \"play-services-appset\""))
        assertTrue(appBuild.contains("implementation(\"io.appmetrica.analytics:analytics-core-api:8.3.0\")"))
        assertFalse(appBuild.contains("compileOnly(\"io.appmetrica.analytics:analytics-appsetid"))
        assertTrue(verificationMetadata.contains("name=\"analytics-core-api\" version=\"8.3.0\""))
        listOf(
            "play-services-appset",
            "analytics-ad-revenue",
            "analytics-appsetid",
            "analytics-billing",
            "analytics-id-sync",
            "analytics-identifiers",
            "analytics-location\"",
            "analytics-screenshot"
        ).forEach { excludedModule ->
            assertFalse(
                "Excluded module must not retain trusted verification metadata: $excludedModule",
                verificationMetadata.contains("name=\"$excludedModule")
            )
        }
    }

    @Test
    fun licenseGateRejectsMissingAndStaleCoordinatesAndWritesEvidence() {
        assertTrue(appBuild.contains("tasks.register(\"verifyReleaseDependencyLicenses\")"))
        assertTrue(appBuild.contains("externalArtifactCoordinates - packagedLicenses.keys"))
        assertTrue(appBuild.contains("packagedLicenses.keys - externalArtifactCoordinates"))
        assertTrue(appBuild.contains("repositoryLicenses != packagedLicenses"))
        assertTrue(appBuild.contains("schema=release-license-evidence-v1"))
        assertTrue(appBuild.contains("release-dependency-inventory-sha256="))
        assertTrue(appBuild.contains("packaged-notices-sha256="))
        assertTrue(appBuild.contains("repository-notices-sha256="))
        assertTrue(appBuild.contains("embedded-runtime-licenses-sha256="))
        assertTrue(appBuild.contains("generateEmbeddedThirdPartyLicenses"))
        assertTrue(appBuild.contains("inspectNestedArchive"))
        assertTrue(appBuild.contains("isEmbeddedLicenseEntry"))
        assertTrue(appBuild.contains("embeddedThirdPartyNoticesSha256"))
        assertTrue(appBuild.contains("dependsOn(verifyReleaseDependencyLicenses)"))
    }

    private fun parseManifest(text: String): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        text.substringAfter(MANIFEST_START)
            .substringBefore(MANIFEST_END)
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .forEach { row ->
                val fields = row.split('|').map(String::trim)
                assertEquals("Invalid manifest row: $row", 2, fields.size)
                assertTrue("Duplicate coordinate: ${fields[0]}", entries.put(fields[0], fields[1]) == null)
            }
        return entries
    }

    private companion object {
        const val MANIFEST_START = "[release-runtime-artifact-licenses-v1]"
        const val MANIFEST_END = "[/release-runtime-artifact-licenses-v1]"
    }
}
