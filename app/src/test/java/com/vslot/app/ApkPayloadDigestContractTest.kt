package com.vslot.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkPayloadDigestContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `canonical digest ignores packaging signing and version control metadata`() {
        val first = temporaryFolder.root.toPath().resolve("first.apk")
        val second = temporaryFolder.root.toPath().resolve("second.apk")
        val changed = temporaryFolder.root.toPath().resolve("changed.apk")
        val payload = listOf(
            "AndroidManifest.xml" to "manifest-v1",
            "classes.dex" to "dex-v1",
            "res/raw/config.txt" to "config-v1",
            "META-INF/com/android/build/gradle/app-metadata.properties" to "metadata-v1"
        )
        writeArchive(
            first,
            payload + listOf(
                "META-INF/CERT.SF" to "signature-a",
                "META-INF/CERT.RSA" to "block-a",
                "META-INF/version-control-info.textproto" to "revision-a"
            ),
            timestamp = 1_700_000_000_000L,
            compressionLevel = 1
        )
        writeArchive(
            second,
            payload.reversed() + listOf(
                "META-INF/CERT.SF" to "signature-b",
                "META-INF/CERT.RSA" to "block-b",
                "META-INF/version-control-info.textproto" to "revision-b"
            ),
            timestamp = 1_750_000_000_000L,
            compressionLevel = 9
        )
        writeArchive(
            changed,
            payload.map { (name, value) -> name to if (name == "classes.dex") "dex-v2" else value },
            timestamp = 1_700_000_000_000L,
            compressionLevel = 1
        )

        val firstDigest = payloadDigest(first)
        assertEquals(firstDigest, payloadDigest(second))
        assertNotEquals(firstDigest, payloadDigest(changed))
    }

    private fun payloadDigest(apk: Path): String {
        val script = Path.of("../tools/apk_payload_sha256.sh").toAbsolutePath().normalize()
        assertTrue("APK payload digest script must be executable.", Files.isExecutable(script))
        val process = ProcessBuilder(script.toString(), apk.toString())
            .redirectErrorStream(true)
            .apply {
                environment()["V_SLOT_JAVA"] = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    "java"
                ).toString()
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.matches(Regex("[0-9a-f]{64}")))
        return output
    }

    private fun writeArchive(
        target: Path,
        entries: List<Pair<String, String>>,
        timestamp: Long,
        compressionLevel: Int
    ) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.setLevel(compressionLevel)
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name).apply { time = timestamp })
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
