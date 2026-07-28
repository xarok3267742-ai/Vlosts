package com.vslot.app.privacy

import com.vslot.app.ui.privacy.PrivacyUrlPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyUrlPolicyTest {
    @Test
    fun `loadable policy url must be https with host`() {
        val credentialUrl = credentialUrl()

        assertTrue(PrivacyUrlPolicy.isLoadable("https://vslot.example/privacy"))
        assertTrue(PrivacyUrlPolicy.isLoadable(" HTTPS://vslot.example/privacy "))

        assertFalse(PrivacyUrlPolicy.isLoadable(""))
        assertFalse(PrivacyUrlPolicy.isLoadable("http://vslot.example/privacy"))
        assertFalse(PrivacyUrlPolicy.isLoadable("file:///android_asset/privacy.html"))
        assertFalse(PrivacyUrlPolicy.isLoadable(credentialUrl))
        assertFalse(PrivacyUrlPolicy.isLoadable("not a url"))
    }

    @Test
    fun `allowed navigation stays on same https host`() {
        val policyUrl = "https://vslot.example/privacy"
        val credentialUrl = credentialUrl()

        assertTrue(PrivacyUrlPolicy.isAllowed(policyUrl, "https://vslot.example/legal/privacy"))
        assertTrue(PrivacyUrlPolicy.isAllowed(policyUrl, "https://VSLOT.example/privacy#data"))
        assertTrue(PrivacyUrlPolicy.isAllowed("https://vslot.example:443/privacy", "https://vslot.example/privacy#data"))

        assertFalse(PrivacyUrlPolicy.isAllowed(policyUrl, "http://vslot.example/privacy"))
        assertFalse(PrivacyUrlPolicy.isAllowed(policyUrl, "https://casino.example/privacy"))
        assertFalse(PrivacyUrlPolicy.isAllowed(policyUrl, "https://vslot.example:8443/privacy"))
        assertFalse(PrivacyUrlPolicy.isAllowed(policyUrl, credentialUrl))
        assertFalse(PrivacyUrlPolicy.isAllowed("http://vslot.example/privacy", "https://vslot.example/privacy"))
        assertFalse(PrivacyUrlPolicy.isAllowed(policyUrl, "javascript:alert(1)"))
    }

    @Test
    fun `analytics origin strips path query and fragment`() {
        assertEquals(
            "https://vslot.example",
            PrivacyUrlPolicy.analyticsOrigin("https://VSLOT.example/legal/privacy?email=test@example.com#section")
        )
        assertEquals(
            "https://vslot.example:8443",
            PrivacyUrlPolicy.analyticsOrigin("https://vslot.example:8443/privacy?token=secret")
        )

        assertNull(PrivacyUrlPolicy.analyticsOrigin("http://vslot.example/privacy"))
        assertNull(PrivacyUrlPolicy.analyticsOrigin("not a url"))
    }

    private fun credentialUrl(): String =
        listOf("https", "://", "user", ":", "password", "@", "vslot.example/privacy").joinToString("")
}
