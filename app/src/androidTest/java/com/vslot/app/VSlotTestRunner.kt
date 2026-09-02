package com.vslot.app

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner

class VSlotTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        runShellCommand("settings put secure immersive_mode_confirmations confirmed")
        if (isEmulator()) {
            runShellCommand("settings put global hide_error_dialogs 1")
            runShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        }
        super.onStart()
    }

    private fun runShellCommand(command: String) {
        val commandOutput = uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(commandOutput).use { output ->
            output.readBytes()
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.PRODUCT.startsWith("sdk")
    }
}
