package com.vslot.app.ui.settings

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PushPermissionDialogLifecycleContractTest {
    @Test
    fun `permission journal resolution survives settings view destruction`() {
        val settings = source("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt")
        val resolver = settings
            .substringAfter("private fun resolvePersistedPushPermissionRequest()")
            .substringBefore("private fun renderPushState")

        assertTrue(resolver.contains("lifecycleScope.launch(Dispatchers.IO)"))
        assertFalse(resolver.contains("viewLifecycleOwner.lifecycleScope"))
    }

    @Test
    fun `system cancellation defers pre prompt exactly once`() {
        val dialog = source("src/main/java/com/vslot/app/ui/dialog/PushPermissionDialogFragment.kt")
        val settings = source("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt")

        assertTrue(dialog.contains("private var resultSent = false"))
        assertTrue(dialog.contains("if (resultSent) return"))
        assertTrue(dialog.contains("override fun onCancel(dialog: DialogInterface)"))
        assertTrue(dialog.contains("dispatchResult(accepted = false)"))
        assertTrue(dialog.contains("setFragmentResult("))
        assertTrue(settings.contains("viewModel.onPushPermissionDeferred()"))
    }

    @Test
    fun `returning from system settings refreshes the current notification permission`() {
        val settings = source("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt")

        assertTrue(settings.contains("override fun onResume()"))
        assertTrue(settings.contains("application.refreshPushRegistration()"))
        assertTrue(settings.contains("application.pushRegistrationStatus.value"))
    }

    @Test
    fun `pre prompt discloses push identifiers and delivery telemetry before system prompt`() {
        val strings = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("src/main/res/values/strings.xml").toFile())
            .getElementsByTagName("string")
        val disclosure = (0 until strings.length)
            .map { strings.item(it) as Element }
            .first { it.getAttribute("name") == "push_prompt_body" }
            .textContent
        val dialog = source("src/main/java/com/vslot/app/ui/dialog/PushPermissionDialogFragment.kt")
        val settings = source("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt")

        assertTrue(disclosure.contains("Firebase (Google)"))
        assertTrue(disclosure.contains("AppMetrica (Яндекс)"))
        assertTrue(disclosure.contains("идентификаторы приложения и устройства"))
        assertTrue(disclosure.contains("телеметрию доставки уведомлений"))
        assertTrue(disclosure.contains("Далее Android покажет системный запрос"))
        assertTrue(dialog.contains("bindPushDisclosure(binding)"))
        assertTrue(dialog.contains("binding.pushPromptBody.visibility = View.GONE"))
        assertTrue(dialog.contains("binding.pushPromptBodyLargeText.visibility = View.VISIBLE"))
        assertTrue(settings.contains("ShowPrePrompt -> showPushPrePermission()"))
        assertTrue(settings.contains("notificationPermissionLauncher.launch("))
    }

    @Test
    fun `system permission request and result are journaled across process death`() {
        val settings = source("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt")
        val requestStore = source(
            "src/main/java/com/vslot/app/ui/settings/PushPermissionRequestStore.kt"
        )

        val pendingWrite = settings.indexOf("pushPermissionRequestStore.markPending()")
        val systemLaunch = settings.indexOf("notificationPermissionLauncher.launch(")
        val resultWrite = settings.indexOf("pushPermissionRequestStore.markSystemResult(granted)")
        val resultPersistence = settings.indexOf("viewModel.onPushPermissionResult(", resultWrite)
        assertTrue(pendingWrite >= 0 && systemLaunch > pendingWrite)
        assertTrue(resultWrite >= 0 && resultPersistence > resultWrite)
        assertTrue(settings.contains("recoverPersistedPushPermissionRequest(state)"))
        assertTrue(requestStore.contains("PendingSystemResult"))
        assertTrue(requestStore.contains("SystemResultGranted"))
        assertTrue(requestStore.contains("SystemResultDenied"))
        assertTrue(requestStore.contains(".commit()"))
    }

    private fun source(relativePath: String): String {
        return String(Files.readAllBytes(Path.of(relativePath)), Charsets.UTF_8)
    }
}
