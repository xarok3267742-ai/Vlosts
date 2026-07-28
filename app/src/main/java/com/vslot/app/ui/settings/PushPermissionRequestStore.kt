package com.vslot.app.ui.settings

import android.annotation.SuppressLint
import android.content.Context

internal enum class PushPermissionRequestPhase {
    NeverStarted,
    PendingSystemResult,
    SystemResultGranted,
    SystemResultDenied,
    Resolved
}

internal enum class PushPermissionRecoveryAction {
    None,
    ResumeSystemRequest,
    PersistGrantedResult,
    PersistDeniedResult,
    MarkResolved
}

internal fun pushPermissionRecoveryAction(
    phase: PushPermissionRequestPhase,
    permissionAsked: Boolean
): PushPermissionRecoveryAction {
    if (permissionAsked && phase != PushPermissionRequestPhase.Resolved) {
        return PushPermissionRecoveryAction.MarkResolved
    }
    return when (phase) {
        PushPermissionRequestPhase.PendingSystemResult ->
            PushPermissionRecoveryAction.ResumeSystemRequest
        PushPermissionRequestPhase.SystemResultGranted ->
            PushPermissionRecoveryAction.PersistGrantedResult
        PushPermissionRequestPhase.SystemResultDenied ->
            PushPermissionRecoveryAction.PersistDeniedResult
        PushPermissionRequestPhase.NeverStarted,
        PushPermissionRequestPhase.Resolved -> PushPermissionRecoveryAction.None
    }
}

internal class PushPermissionRequestStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun phase(): PushPermissionRequestPhase {
        val persisted = preferences.getString(KEY_PHASE, null)
        return PushPermissionRequestPhase.entries.firstOrNull { it.name == persisted }
            ?: PushPermissionRequestPhase.NeverStarted
    }

    fun markPending(): Boolean = write(PushPermissionRequestPhase.PendingSystemResult)

    fun markSystemResult(granted: Boolean): Boolean {
        return write(
            if (granted) {
                PushPermissionRequestPhase.SystemResultGranted
            } else {
                PushPermissionRequestPhase.SystemResultDenied
            }
        )
    }

    fun markResolved(): Boolean = write(PushPermissionRequestPhase.Resolved)

    @SuppressLint("UseKtx")
    private fun write(phase: PushPermissionRequestPhase): Boolean {
        // KTX edit returns Unit; permission launch requires the synchronous commit result.
        return preferences.edit().putString(KEY_PHASE, phase.name).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "push_permission_request"
        const val KEY_PHASE = "phase"
    }
}
