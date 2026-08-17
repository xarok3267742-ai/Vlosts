package com.vslot.app.game

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasedSlotMathBuildGuardContractTest {
    private val repositoryRoot = Path.of("..").toAbsolutePath().normalize()
    private val appBuild = Path.of("build.gradle.kts").readText()
    private val manifestPath = Path.of("src/main/assets/released_math/v5/manifest.json")
    private val manifestBytes = manifestPath.readBytes()
    private val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))

    @Test
    fun `immutable V5 manifest binds exact evaluator models asset and goldens`() {
        val manifestSha256 = sha256(manifestBytes)
        assertTrue(appBuild.contains("5 to \"$manifestSha256\""))
        assertEquals("v-slot-released-math-manifest-v1", manifest.getString("schema"))
        assertEquals("immutable", manifest.getString("status"))
        assertEquals(5, manifest.getInt("mathVersion"))

        val sources = manifest.getJSONArray("descriptorSources")
        assertEquals(2, sources.length())
        val sourcePaths = buildSet {
            repeat(sources.length()) { index ->
                val source = sources.getJSONObject(index)
                val relativePath = source.getString("path")
                val bytes = repositoryRoot.resolve(relativePath).readBytes()
                assertEquals(relativePath, source.getLong("bytes"), bytes.size.toLong())
                assertEquals(relativePath, source.getString("sha256"), sha256(bytes))
                add(relativePath)
            }
        }
        assertEquals(
            setOf(
                "app/src/main/java/com/vslot/app/game/ReleasedSlotMathV5.kt",
                "app/src/main/java/com/vslot/app/game/SlotModels.kt"
            ),
            sourcePaths
        )

        val asset = manifest.getJSONObject("asset")
        val assetBytes = repositoryRoot.resolve(asset.getString("path")).readBytes()
        assertEquals(asset.getLong("bytes"), assetBytes.size.toLong())
        assertEquals(asset.getString("sha256"), sha256(assetBytes))
        assertEquals("assets/released_math/v5/slots_config.json", asset.getString("archivePath"))
        assertEquals(5, manifest.getJSONObject("goldenFingerprints").length())
        assertEquals(15, manifest.getJSONObject("goldenOutcomeDigests").length())
    }

    @Test
    fun `released math validator is mandatory for source and packaged release artifacts`() {
        val validator = repositoryRoot.resolve("tools/verify_released_slot_math.py").readText()
        val attributes = repositoryRoot.resolve(".gitattributes").readText()

        assertTrue(validator.contains("Duplicate JSON key"))
        assertTrue(validator.contains("external Gradle SHA-256 anchor"))
        assertTrue(validator.contains("Validator accepted a mutated released evaluator."))
        assertTrue(validator.contains("Validator accepted a tampered packaged asset."))
        assertTrue(appBuild.contains("verifyReleasedSlotMathValidatorContract"))
        assertTrue(appBuild.contains("verifyQaReleasedSlotMathV5"))
        assertTrue(appBuild.contains("verifyReleaseBundleReleasedSlotMathV5"))
        assertTrue(appBuild.contains("verifyReleaseUniversalApkReleasedSlotMathV5"))
        assertTrue(appBuild.contains("\"preBuild\" -> dependsOn(verifyReleasedSlotMathV5)"))
        assertTrue(appBuild.contains("\"assembleQa\" -> dependsOn(verifyQaReleasedSlotMathV5)"))
        assertTrue(appBuild.contains("\"bundleRelease\" -> finalizedBy(verifyReleaseBundleReleasedSlotMathV5)"))
        assertTrue(appBuild.contains("\":app:verifyReleasedSlotMathV5\""))
        assertTrue(appBuild.contains("\":app:verifyReleaseBundleReleasedSlotMathV5\""))
        assertTrue(appBuild.contains("\":app:verifyReleaseUniversalApkReleasedSlotMathV5\""))
        assertTrue(
            attributes.contains(
                "app/src/main/java/com/vslot/app/game/ReleasedSlotMathV5.kt text eol=lf"
            )
        )
        assertTrue(
            attributes.contains(
                "app/src/main/assets/released_math/v5/*.json text eol=lf"
            )
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
