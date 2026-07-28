package com.vslot.app

import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner

class VSlotTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        val commandOutput = uiAutomation.executeShellCommand(
            "settings put secure immersive_mode_confirmations confirmed"
        )
        ParcelFileDescriptor.AutoCloseInputStream(commandOutput).use { output ->
            output.readBytes()
        }
        super.onStart()
    }
}
