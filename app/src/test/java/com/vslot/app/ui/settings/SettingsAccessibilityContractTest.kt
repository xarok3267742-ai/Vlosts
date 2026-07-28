package com.vslot.app.ui.settings

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAccessibilityContractTest {
    @Test
    fun `feedback controls expose switch role and current checked state`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt").readText()

        assertTrue(source.contains("installSwitchSemantics(binding.soundToggleButton)"))
        assertTrue(source.contains("installSwitchSemantics(binding.hapticsToggleButton)"))
        assertTrue(source.contains("installSwitchSemantics(binding.analyticsToggleButton)"))
        assertTrue(source.contains("info.className = \"android.widget.Switch\""))
        assertTrue(source.contains("info.isCheckable = true"))
        assertTrue(source.contains("info.isChecked = host.isSelected"))
        assertTrue(source.contains("binding.soundToggleButton.contentDescription = getString(R.string.settings_sound)"))
        assertTrue(source.contains("binding.hapticsToggleButton.contentDescription = getString(R.string.settings_haptics)"))
        assertTrue(source.contains("binding.analyticsToggleButton.contentDescription = getString(R.string.settings_analytics)"))
        assertFalse(source.contains("binding.soundToggleButton.contentDescription = soundDescription"))
        assertFalse(source.contains("binding.hapticsToggleButton.contentDescription = hapticsDescription"))
        assertFalse(source.contains("binding.analyticsToggleButton.contentDescription = analyticsDescription"))
        assertFalse(source.contains("ViewCompat.setStateDescription"))
    }
}
