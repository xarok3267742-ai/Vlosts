package com.vslot.app.ui.slot

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotAutoSpinContractTest {
    @Test
    fun `autospin picker offers only supported bounded batches`() {
        val dialog = Path.of(
            "src/main/java/com/vslot/app/ui/dialog/AutoSpinCountDialogFragment.kt"
        ).readText()
        val viewModel = Path.of(
            "src/main/java/com/vslot/app/ui/slot/SlotViewModel.kt"
        ).readText()

        assertTrue(dialog.contains("bindOption(binding.autoSpinOption10, 10)"))
        assertTrue(dialog.contains("bindOption(binding.autoSpinOption25, 25)"))
        assertTrue(dialog.contains("bindOption(binding.autoSpinOption50, 50)"))
        assertTrue(dialog.contains("parentFragmentManager.setFragmentResult("))
        assertTrue(viewModel.contains("SUPPORTED_AUTO_SPIN_COUNTS = setOf(10, 25, 50)"))
        assertTrue(viewModel.contains("if (count !in SUPPORTED_AUTO_SPIN_COUNTS) return"))
    }

    @Test
    fun `autospin picker uses accessible image controls in both orientations`() {
        val option = Path.of("src/main/res/layout/item_auto_spin_count_option.xml").readText()
        val portrait = Path.of("src/main/res/layout/dialog_auto_spin_count.xml").readText()
        val landscape = Path.of("src/main/res/layout-land/dialog_auto_spin_count.xml").readText()
        val strings = Path.of("src/main/res/values/strings.xml").readText()

        assertTrue(option.contains("android:layout_height=\"60dp\""))
        assertTrue(option.contains("<ImageButton"))
        assertTrue(option.contains("<com.vslot.app.ui.widget.BitmapNumberView"))
        assertTrue(!option.contains("<TextView") && !option.contains("android:text="))
        listOf(portrait, landscape).forEach { layout ->
            assertTrue(layout.contains("android:id=\"@+id/autoSpinTitle\""))
            assertTrue(layout.contains("android:focusable=\"true\""))
            assertTrue(layout.contains("@+id/autoSpinOption10"))
            assertTrue(layout.contains("@+id/autoSpinOption25"))
            assertTrue(layout.contains("@+id/autoSpinOption50"))
            assertTrue(layout.contains("@+id/autoSpinSafeguards"))
            assertTrue(layout.contains("@string/auto_spin_safeguards"))
        }
        assertTrue(strings.contains("auto_spin_count_action\">Запустить %1\$d автоспинов"))
        assertTrue(strings.contains("auto_spin_stop_remaining\">Остановить автоспин, осталось %1\$d"))
        assertTrue(strings.contains("Лимит потерь — 10 ставок"))
        assertTrue(strings.contains("остановится при выплате 10× общей ставки или больше"))
        assertTrue(strings.contains("при запуске бонуса"))
    }

    @Test
    fun `slot surface exposes remaining count and stop affordance in both orientations`() {
        val portrait = Path.of("src/main/res/layout/fragment_slot.xml").readText()
        val landscape = Path.of("src/main/res/layout-land/fragment_slot.xml").readText()
        val drawableRoot = Path.of("src/main/res/drawable-nodpi")

        listOf(portrait, landscape).forEach { layout ->
            assertTrue(layout.contains("@+id/autoSpinRemainingDigits"))
            assertTrue(layout.contains("@+id/autoSpinStopOverlay"))
            assertTrue(layout.contains("@+id/autoSpinButton"))
        }
        assertTrue(Files.exists(drawableRoot.resolve("title_auto_spin.webp")))
        assertTrue(Files.exists(drawableRoot.resolve("label_auto_spin_choose.webp")))
        assertTrue(Files.exists(drawableRoot.resolve("label_auto_spin_stop.webp")))
    }

    @Test
    fun `autospin exposes current action without deprecated announcements`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val autoSpinRenderer = source
            .substringAfter("private fun renderAutoSpinControl")
            .substringBefore("private fun setupLandscapeStepperTouchFallback")

        assertTrue(autoSpinRenderer.contains("binding.autoSpinButton.contentDescription = when"))
        assertTrue(autoSpinRenderer.contains("R.string.auto_spin_stop_remaining"))
        assertTrue(autoSpinRenderer.contains("ViewCompat.setStateDescription(binding.autoSpinButton, null)"))
        assertFalse(source.contains("announceAutoSpin"))
        assertFalse(source.contains("AccessibilityEvent.TYPE_ANNOUNCEMENT"))
    }

    @Test
    fun `active autospin remains stoppable while the next spin is reserved`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/slot/SlotFragment.kt").readText()
        val controls = source
            .substringAfter("val autoSpinControlEnabled")
            .substringBefore("val stakeControlsEnabled")

        assertTrue(controls.contains("state.isAutoSpinEnabled ||"))
        assertTrue(controls.contains("!state.isSpinStartReserved && !state.isSpinning"))
        assertFalse(controls.startsWith(" = !state.isSpinStartReserved"))
    }

    private fun Path.readText(): String = String(Files.readAllBytes(this), Charsets.UTF_8)
}
