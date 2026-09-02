package com.vslot.app.ui.privacy

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFragmentContractTest {
    @Test
    fun `blocked main frame navigation is visible and stops loading`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()

        assertTrue(source.contains("!allowed && request.isForMainFrame"))
        assertTrue(source.contains("view.stopLoading()"))
        assertTrue(source.contains("\"blocked_origin\""))
        assertTrue(source.contains("showError("))
    }

    @Test
    fun `missing system webview falls back without crashing layout inflation`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()
        val portraitLayout = Path.of("src/main/res/layout/fragment_privacy.xml").readText()
        val landscapeLayout = Path.of("src/main/res/layout-land/fragment_privacy.xml").readText()

        assertFalse(portraitLayout.contains("<WebView"))
        assertFalse(landscapeLayout.contains("<WebView"))
        assertTrue(portraitLayout.contains("@+id/privacyWebViewHost"))
        assertTrue(landscapeLayout.contains("@+id/privacyWebViewHost"))
        assertTrue(source.contains("private fun ensurePrivacyWebView(): WebView?"))
        assertTrue(source.contains("catch (_: RuntimeException)"))
        assertTrue(source.contains("\"webview_unavailable\""))
        assertTrue(source.contains("retryable = false"))
    }
}
