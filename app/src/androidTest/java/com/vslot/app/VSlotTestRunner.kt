package com.vslot.app

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

class VSlotTestRunner : AndroidJUnitRunner() {
    private var originalStayAwake: String? = null
    private var stayAwakeRestored = false

    override fun onStart() {
        try {
            originalStayAwake = runShellCommand(
                "settings get global stay_on_while_plugged_in"
            ).trim()
            runShellCommand("settings put global stay_on_while_plugged_in 7")
            runShellCommand("input keyevent KEYCODE_WAKEUP")
            runShellCommand("wm dismiss-keyguard")
            runShellCommand("settings put secure immersive_mode_confirmations confirmed")
            if (isEmulator()) {
                runShellCommand("settings put global hide_error_dialogs 1")
                runShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            }
            super.onStart()
        } catch (error: Throwable) {
            restoreStayAwakeSafely()
            throw error
        }
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        try {
            restoreStayAwakeSafely()
        } finally {
            super.finish(resultCode, results)
        }
    }

    private fun restoreStayAwakeSafely() {
        if (stayAwakeRestored) return
        val originalValue = originalStayAwake
        if (originalValue == null) return
        runCatching {
            if (originalValue.isBlank() || originalValue == "null") {
                runShellCommand("settings delete global stay_on_while_plugged_in")
            } else {
                runShellCommand("settings put global stay_on_while_plugged_in $originalValue")
            }
        }.onSuccess {
            stayAwakeRestored = true
        }.onFailure { error ->
            Log.e(TAG, "Unable to restore stay-awake setting after instrumentation.", error)
        }
    }

    private fun runShellCommand(command: String): String {
        val commandOutput = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(commandOutput).use { output ->
            output.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.PRODUCT.startsWith("sdk")
    }

    private companion object {
        const val TAG = "VSlotTestRunner"
    }
}
