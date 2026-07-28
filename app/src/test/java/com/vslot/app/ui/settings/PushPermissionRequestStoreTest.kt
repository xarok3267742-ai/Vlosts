package com.vslot.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PushPermissionRequestStoreTest {
    @Test
    fun `pending system result resumes until datastore records the answer`() {
        assertEquals(
            PushPermissionRecoveryAction.ResumeSystemRequest,
            pushPermissionRecoveryAction(
                PushPermissionRequestPhase.PendingSystemResult,
                permissionAsked = false
            )
        )
        assertEquals(
            PushPermissionRecoveryAction.MarkResolved,
            pushPermissionRecoveryAction(
                PushPermissionRequestPhase.PendingSystemResult,
                permissionAsked = true
            )
        )
        assertEquals(
            PushPermissionRecoveryAction.PersistGrantedResult,
            pushPermissionRecoveryAction(
                PushPermissionRequestPhase.SystemResultGranted,
                permissionAsked = false
            )
        )
        assertEquals(
            PushPermissionRecoveryAction.PersistDeniedResult,
            pushPermissionRecoveryAction(
                PushPermissionRequestPhase.SystemResultDenied,
                permissionAsked = false
            )
        )
    }

    @Test
    fun `non pending phases never relaunch the system prompt`() {
        assertEquals(
            PushPermissionRecoveryAction.None,
            pushPermissionRecoveryAction(
                PushPermissionRequestPhase.NeverStarted,
                permissionAsked = false
            )
        )
        assertEquals(
            PushPermissionRecoveryAction.MarkResolved,
            pushPermissionRecoveryAction(
                PushPermissionRequestPhase.NeverStarted,
                permissionAsked = true
            )
        )
        listOf(PushPermissionRequestPhase.Resolved).forEach { phase ->
            assertEquals(
                PushPermissionRecoveryAction.None,
                pushPermissionRecoveryAction(phase, permissionAsked = false)
            )
            assertEquals(
                PushPermissionRecoveryAction.None,
                pushPermissionRecoveryAction(phase, permissionAsked = true)
            )
        }
    }
}
