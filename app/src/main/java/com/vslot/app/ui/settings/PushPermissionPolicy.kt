package com.vslot.app.ui.settings

internal enum class PushPermissionAction {
    Disabled,
    ShowPrePrompt,
    OpenSystemSettings
}

internal fun pushPermissionAction(
    pushConfigured: Boolean,
    permissionAsked: Boolean
): PushPermissionAction {
    return when {
        !pushConfigured -> PushPermissionAction.Disabled
        permissionAsked -> PushPermissionAction.OpenSystemSettings
        else -> PushPermissionAction.ShowPrePrompt
    }
}
