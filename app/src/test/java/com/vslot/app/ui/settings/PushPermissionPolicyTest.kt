package com.vslot.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PushPermissionPolicyTest {
    @Test
    fun `unconfigured push action stays disabled`() {
        assertEquals(
            PushPermissionAction.Disabled,
            pushPermissionAction(
                pushConfigured = false,
                permissionAsked = false
            )
        )
    }

    @Test
    fun `first permission attempt starts with explanatory prompt`() {
        assertEquals(
            PushPermissionAction.ShowPrePrompt,
            pushPermissionAction(
                pushConfigured = true,
                permissionAsked = false
            )
        )
    }

    @Test
    fun `completed permission attempt routes to system settings`() {
        assertEquals(
            PushPermissionAction.OpenSystemSettings,
            pushPermissionAction(
                pushConfigured = true,
                permissionAsked = true
            )
        )
    }

    @Test
    fun `system permission alone still starts the explicit consent flow`() {
        assertEquals(
            PushPermissionAction.ShowPrePrompt,
            pushPermissionAction(
                pushConfigured = true,
                permissionAsked = false
            )
        )
    }
}
