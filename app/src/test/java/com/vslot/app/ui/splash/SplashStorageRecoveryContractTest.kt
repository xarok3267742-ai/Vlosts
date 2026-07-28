package com.vslot.app.ui.splash

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashStorageRecoveryContractTest {
    @Test
    fun `persistent player storage failure exposes retry and explicit system recovery`() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/splash/SplashFragment.kt").readText()
        val portrait = Path.of("src/main/res/layout/fragment_splash.xml").readText()
        val landscape = Path.of("src/main/res/layout-land/fragment_splash.xml").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()

        listOf(portrait, landscape).forEach { layout ->
            assertTrue(layout.contains("@+id/splashRetryButton"))
            assertTrue(layout.contains("@+id/splashAppSettingsButton"))
            assertTrue(layout.contains("@drawable/btn_settings_selector"))
            assertTrue(layout.contains("android:tooltipText=\"@string/open_app_settings\""))
        }
        assertTrue(fragment.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(fragment.contains("Uri.fromParts(\"package\", requireContext().packageName, null)"))
        assertTrue(fragment.contains("startActivity(Intent(Settings.ACTION_SETTINGS))"))
        assertTrue(strings.contains("<string name=\"open_app_settings\">"))
        assertTrue(strings.contains("откройте настройки приложения и очистите его данные"))
    }
}
