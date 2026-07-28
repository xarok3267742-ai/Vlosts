package com.vslot.app

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessDeathRecoveryQaContractTest {
    @Test
    fun `qa hook persists a committed visual spin without settling it`() {
        val receiver = Path.of("src/debug/java/com/vslot/app/debug/QaStateReceiver.kt").readText()
        val resultDialog = Path.of("src/main/java/com/vslot/app/ui/dialog/ResultDialogFragment.kt").readText()
        val prepareBlock = receiver
            .substringAfter("private suspend fun prepareProcessDeathRecovery")
            .substringBefore("private suspend fun inspectProcessDeathRecovery")
        val inspectBlock = receiver
            .substringAfter("private suspend fun inspectProcessDeathRecovery")
            .substringBefore("private suspend fun acknowledgeProcessDeathPresentation")

        assertTrue(receiver.contains("COMMAND_PREPARE_PROCESS_DEATH = \"prepare_process_death\""))
        assertTrue(receiver.contains("COMMAND_INSPECT_PROCESS_DEATH = \"inspect_process_death\""))
        assertTrue(receiver.contains("COMMAND_ACK_PROCESS_DEATH = \"ack_process_death_presentation\""))
        assertTrue(prepareBlock.contains("repository.reserveSpin("))
        assertTrue(prepareBlock.contains("visualResult = visualResult"))
        assertTrue(prepareBlock.contains("ProcessSession.registerSpinSettlement(settlement.id)"))
        assertTrue(receiver.contains("DEBUG_PROCESS_DEATH_STOPS = intArrayOf(0, 0, 12, 4, 2)"))
        assertTrue(prepareBlock.contains("visualResult.freeSpinsAwarded == 0"))
        assertTrue(prepareBlock.contains("visualResult.winAmount >= visualResult.totalBet"))
        assertTrue(prepareBlock.contains("checkpoint.rawPendingSpinPresentation == null"))
        assertFalse(prepareBlock.contains("settleSpin("))
        assertFalse(prepareBlock.contains("recoverPendingSpinSettlement("))

        assertTrue(inspectBlock.contains("PlayerStateCheckpointStore") || receiver.contains("PlayerStateCheckpointStore"))
        assertTrue(inspectBlock.contains("pending_settlement_id"))
        assertTrue(inspectBlock.contains("pending_presentation_id"))
        assertTrue(inspectBlock.contains("presentation_claimed"))
        assertFalse(inspectBlock.contains("settleSpin("))
        assertFalse(inspectBlock.contains("recoverPendingSpinSettlement("))
        assertFalse(inspectBlock.contains("acknowledgeSpinPresentation("))
        val acknowledgeBlock = receiver
            .substringAfter("private suspend fun acknowledgeProcessDeathPresentation")
            .substringBefore("private fun readCheckpoint")
        assertTrue(acknowledgeBlock.contains("claimedByProcessSessionId != null"))
        assertTrue(acknowledgeBlock.contains("DEBUG_PROCESS_DEATH_SETTLEMENT_ID"))
        assertTrue(acknowledgeBlock.contains("acknowledgeSpinPresentation("))
        val firstDrawBlock = resultDialog
            .substringAfter("private fun notifyPresentationAfterFirstDraw")
            .substringBefore("private fun clearPresentationDrawListener")
        assertTrue(firstDrawBlock.contains("ViewTreeObserver.OnDrawListener"))
        assertTrue(firstDrawBlock.contains("Log.i(QA_PRESENTATION_TAG, QA_MODAL_FIRST_DRAW)"))
        assertTrue(firstDrawBlock.indexOf("Log.i(QA_PRESENTATION_TAG, QA_MODAL_FIRST_DRAW)") < firstDrawBlock.indexOf("PRESENTED_REQUEST_KEY"))
    }

    @Test
    fun `host script kills only the selected Samsung app pid and proves idempotence`() {
        val scriptPath = Path.of("../tools/qa_process_death_recovery.sh")
        val script = scriptPath.readText()
        val gitIgnore = Path.of("../.gitignore").readText()
        val syntaxCheck = ProcessBuilder("bash", "-n", scriptPath.toString())
            .redirectErrorStream(true)
            .start()
        val syntaxOutput = syntaxCheck.inputStream.bufferedReader().use { it.readText() }

        assertEquals(syntaxOutput, 0, syntaxCheck.waitFor())
        assertTrue(script.contains("serial=\"\${1:-\${ANDROID_SERIAL:-}}\""))
        assertTrue(script.contains("candidate_manufacturer"))
        assertTrue(script.contains("ro.kernel.qemu"))
        assertTrue(script.contains("manufacturer\" == \"samsung"))
        assertTrue(script.contains("V_SLOT_ALLOW_EMULATOR_QA"))
        assertTrue(script.contains("qa_profile=\"physical_samsung\""))
        assertTrue(script.contains("qa_profile=\"emulator\""))
        assertTrue(script.contains("for (field_index = 1; field_index <= NF; field_index += 1)"))
        assertFalse(script.contains("for (index = 1; index <= NF; index += 1)"))
        assertTrue(script.contains("v-slot-samsung-qa-\${lock_serial}.lock"))
        assertTrue(script.contains("PACKAGE=\"com.vslot.app.qa\""))
        assertTrue(script.contains("get_package_pid"))
        assertTrue(script.contains("shell am kill --user current \"\$PACKAGE\""))
        assertTrue(script.contains("while (( SECONDS < deadline ))"))
        assertTrue(script.contains("sleep 0.25"))
        assertTrue(script.contains("KEYCODE_HOME"))
        assertTrue(script.contains("Refusing to kill stale PID"))
        assertFalse(script.contains("shell kill -9"))
        assertFalse(script.contains("am force-stop"))
        assertEquals(2, script.countOccurrences("kill_exact_package_pid \"\$"))
        assertEquals(2, script.countOccurrences("shell am start --user current"))
        assertTrue(script.contains("pending_settlement_id \"\$settlement_id\""))
        assertTrue(script.contains("pending_presentation_id \"\$settlement_id\""))
        assertTrue(script.contains("observed_claim=\"\$(response_field \"\$observed\" presentation_claimed)\""))
        assertTrue(script.contains("\$observed_claim\" != \"true\" && \"\$observed_claim\" != \"false"))
        assertFalse(script.contains("run_qa_command ack_process_death_presentation"))
        assertTrue(script.contains("VSlotPresentation:I"))
        assertTrue(script.contains("modal_first_draw"))
        assertTrue(script.contains("first_draw_observed=true"))
        assertTrue(script.contains("\\\"first_draw_observed\\\""))
        assertTrue(script.contains("'  \"schema_version\": 5,'"))
        assertTrue(script.contains("\\\"git_commit\\\""))
        assertTrue(script.contains("\\\"payload_sha256\\\""))
        assertTrue(script.contains("V_SLOT_APK_PAYLOAD_DIGEST"))
        assertTrue(script.contains("presentation_observed=true"))
        assertTrue(script.contains("verify_final_state \"\$second_state_before_activity\""))
        assertTrue(script.contains("verify_final_state \"\$second_state_after_activity\""))
        assertTrue(script.contains("stay_on_while_plugged_in"))
        assertTrue(script.contains("restore_setting global stay_on_while_plugged_in"))
        assertTrue(script.contains("trap cleanup EXIT"))
        assertTrue(script.contains("serial_sha256=\"\$(hash_text \"\$serial\")\""))
        assertTrue(script.contains("\\\"serial_sha256\\\""))
        assertTrue(script.contains("\\\"qa_profile\\\""))
        assertFalse(script.contains("\\\"serial\\\":"))
        assertTrue(script.contains("pending_journal_cleared"))
        assertTrue(script.contains("second_restart_unchanged"))
        assertTrue(gitIgnore.lineSequence().any { it.trim() == "qa/process-death/evidence/" })
    }

    @Test
    fun `qa command surface stays dump protected and absent from release manifest`() {
        val mainManifest = Path.of("src/main/AndroidManifest.xml").readText()
        val debugManifest = Path.of("src/debug/AndroidManifest.xml").readText()

        assertFalse(mainManifest.contains("QaStateReceiver"))
        assertTrue(debugManifest.contains("com.vslot.app.debug.QaStateReceiver"))
        val receiverDeclaration = debugManifest
            .substringAfter("com.vslot.app.debug.QaStateReceiver")
            .substringBefore("</receiver>")
        assertTrue(receiverDeclaration.contains("android:exported=\"true\""))
        assertTrue(receiverDeclaration.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(receiverDeclaration.contains("com.vslot.app.debug.QA_STATE"))
    }

    private fun String.countOccurrences(token: String): Int {
        if (token.isEmpty()) return 0
        var count = 0
        var offset = 0
        while (true) {
            val index = indexOf(token, offset)
            if (index < 0) return count
            count += 1
            offset = index + token.length
        }
    }
}
