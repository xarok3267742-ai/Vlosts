package com.vslot.app

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalSamsungRawEvidencePackageContractTest {
    @Test
    fun `packager requires every raw connected stage and device log`() {
        val script = Path.of("../tools/package_physical_samsung_evidence.sh")
            .toAbsolutePath()
            .normalize()
            .readText()

        listOf(
            "portrait_smoke",
            "font_scale_2_0_first_launch_legal_notices",
            "compact_portrait_settings",
            "compact_landscape_rotation_1",
            "compact_landscape_rotation_3",
            "landscape_rotation_1",
            "landscape_rotation_3"
        ).forEach { stage -> assertTrue(script.contains(stage)) }
        assertTrue(script.contains("process-death.log"))
        assertTrue(script.contains("frame-metrics.log"))
        assertTrue(script.contains("TEST-*.xml"))
        assertTrue(script.contains("zip -q -X -r"))
    }
}
