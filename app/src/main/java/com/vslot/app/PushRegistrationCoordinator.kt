package com.vslot.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal enum class PushRegistrationStatus {
    Unavailable,
    AwaitingPermission,
    Registering,
    Registered,
    Disabled,
    Failed
}

internal data class PushRegistrationCommand(
    val enabled: Boolean,
    val deleteToken: Boolean
) {
    val requiresRuntimeMutation: Boolean
        get() = enabled || deleteToken
}

internal fun pushRegistrationCommand(
    permissionAsked: Boolean,
    notificationsEnabled: Boolean,
    runtimePermissionGranted: Boolean
): PushRegistrationCommand {
    if (!permissionAsked) {
        return PushRegistrationCommand(enabled = false, deleteToken = false)
    }

    val enabled = notificationsEnabled && runtimePermissionGranted
    return PushRegistrationCommand(
        enabled = enabled,
        deleteToken = !enabled
    )
}

internal class PushRegistrationCoordinator {
    private val mutex = Mutex()
    private var appliedCommand: PushRegistrationCommand? = null
    private val mutableStatus = MutableStateFlow(PushRegistrationStatus.Unavailable)
    val status: StateFlow<PushRegistrationStatus> = mutableStatus.asStateFlow()

    fun markUnavailable() {
        mutableStatus.value = PushRegistrationStatus.Unavailable
    }

    suspend fun reconcile(
        desiredCommand: suspend () -> PushRegistrationCommand,
        applyCommand: suspend (PushRegistrationCommand) -> Unit
    ) {
        mutex.lock()
        try {
            val command = desiredCommand()
            if (appliedCommand == command) {
                mutableStatus.value = command.completedStatus()
                return
            }
            mutableStatus.value = if (command.requiresRuntimeMutation) {
                PushRegistrationStatus.Registering
            } else {
                command.completedStatus()
            }
            try {
                applyCommand(command)
                appliedCommand = command
                mutableStatus.value = command.completedStatus()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableStatus.value = PushRegistrationStatus.Failed
                throw error
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun PushRegistrationCommand.completedStatus(): PushRegistrationStatus {
        return when {
            enabled -> PushRegistrationStatus.Registered
            deleteToken -> PushRegistrationStatus.Disabled
            else -> PushRegistrationStatus.AwaitingPermission
        }
    }
}
