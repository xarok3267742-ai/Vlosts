package com.vslot.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QaProcessDeathHookTest {
    @Test
    fun processDeathFixtureCanBePreparedTwiceInOneProcess() {
        try {
            val first = runQaCommand(COMMAND_PREPARE_PROCESS_DEATH)
            val second = runQaCommand(COMMAND_PREPARE_PROCESS_DEATH)

            assertTrue(first.contains("status=prepared"))
            assertTrue(first.contains("pending_settlement=true"))
            assertTrue(second.contains("status=prepared"))
            assertTrue(second.contains("pending_settlement=true"))
        } finally {
            runBlocking { AppGraph.playerRepository.resetForDebug() }
        }
    }

    private fun runQaCommand(command: String): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val completed = CountDownLatch(1)
        val response = AtomicReference<String>()
        val intent = Intent(QA_STATE_ACTION)
            .setClassName(context.packageName, QA_STATE_RECEIVER)
            .putExtra(QA_COMMAND_EXTRA, command)
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    response.set(resultData)
                    completed.countDown()
                }
            },
            null,
            Activity.RESULT_OK,
            null,
            null
        )
        assertTrue(
            "Timed out waiting for QA command $command.",
            completed.await(QA_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )
        return response.get().orEmpty()
    }

    private companion object {
        const val QA_STATE_ACTION = "com.vslot.app.debug.QA_STATE"
        const val QA_STATE_RECEIVER = "com.vslot.app.debug.QaStateReceiver"
        const val QA_COMMAND_EXTRA = "qa_command"
        const val COMMAND_PREPARE_PROCESS_DEATH = "prepare_process_death"
        const val QA_COMMAND_TIMEOUT_MS = 5_000L
    }
}
