package com.vslot.app

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class VSlotTestRunnerContractTest {
    @Test
    fun `managed emulators suppress unrelated system error dialogs`() {
        val source = Path.of("src/androidTest/java/com/vslot/app/VSlotTestRunner.kt").readText()

        assertTrue(source.contains("if (isEmulator())"))
        assertTrue(source.contains("settings put global hide_error_dialogs 1"))
        assertTrue(source.contains("android.intent.action.CLOSE_SYSTEM_DIALOGS"))
        assertTrue(source.contains("Build.FINGERPRINT.startsWith(\"generic\")"))
    }
}
