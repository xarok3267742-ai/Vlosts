package com.vslot.app

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentationRunnerContractTest {
    private val runner = Path.of(
        "src/androidTest/java/com/vslot/app/VSlotTestRunner.kt"
    ).readText()

    @Test
    fun `instrumentation keeps the display awake and restores the device setting`() {
        assertTrue(runner.contains("settings get global stay_on_while_plugged_in"))
        assertTrue(runner.contains("settings put global stay_on_while_plugged_in 7"))
        assertTrue(runner.contains("input keyevent KEYCODE_WAKEUP"))
        assertTrue(runner.contains("wm dismiss-keyguard"))
        assertTrue(runner.contains("override fun finish(resultCode: Int, results: Bundle?)"))
        assertTrue(runner.indexOf("restoreStayAwakeSafely()", runner.indexOf("override fun finish")) < runner.indexOf("super.finish(resultCode, results)"))
        assertTrue(runner.contains("try {\n            restoreStayAwakeSafely()\n        } finally"))
        assertTrue(runner.contains("runCatching"))
        assertTrue(runner.indexOf("stayAwakeRestored = true") > runner.indexOf("}.onSuccess"))
        assertTrue(runner.contains("settings delete global stay_on_while_plugged_in"))
        assertTrue(runner.contains("settings put global stay_on_while_plugged_in \$originalValue"))
    }
}
