package com.vslot.app.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class PrivacyWebViewLifecycleContractTest {
    @Test
    fun `loading and error transitions use semantic live regions`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()

        assertTrue(source.contains("binding.privacyLoadingGroup.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(source.contains("binding.errorImage.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertFalse(source.contains("announceForAccessibility"))
        assertFalse(source.contains("announcePrivacyStatus"))
    }

    @Test
    fun `toolbar and system back consume webview history before fragment navigation`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()
        val viewSetup = source
            .substringAfter("override fun onViewCreated")
            .substringBefore("private fun navigateBack")
        val backNavigation = source
            .substringAfter("private fun navigateBack")
            .substringBefore("private fun popFromPrivacy")

        assertTrue(viewSetup.contains("onBackPressedDispatcher.addCallback"))
        assertTrue(viewSetup.contains("override fun handleOnBackPressed()"))
        assertTrue(viewSetup.contains("binding.backButton.setOnClickListener { navigateBack() }"))
        assertTrue(viewSetup.contains("navigateBack()"))

        val canGoBack = backNavigation.indexOf("canGoBack()")
        val goBack = backNavigation.indexOf("goBack()")
        val popFragment = backNavigation.indexOf("popFromPrivacy()")
        assertTrue(
            "WebView history must be consumed before the privacy destination is popped",
            canGoBack >= 0 && goBack > canGoBack && popFragment > goBack
        )
    }

    @Test
    fun `webview state is restored before using same origin process fallback`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()
        val viewSetup = source
            .substringAfter("override fun onViewCreated")
            .substringBefore("private fun navigateBack")
        val restoreOrLoad = source
            .substringAfter("private fun restoreOrLoadPolicy")
            .substringBefore("private fun restorePolicyState")
        val restoreState = source
            .substringAfter("private fun restorePolicyState")
            .substringBefore("private fun loadPolicy")
        val saveState = source
            .substringAfter("override fun onSaveInstanceState")
            .substringBefore("private fun captureWebViewState")
        val captureState = source
            .substringAfter("private fun captureWebViewState")
            .substringBefore("private fun clearRetainedWebViewState")

        assertTrue(viewSetup.contains("restoreOrLoadPolicy(savedInstanceState)"))
        assertFalse(
            "View creation must not unconditionally reload the root policy URL",
            viewSetup.lines().any { it.trim() == "loadPolicy()" }
        )
        assertTrue(saveState.contains("KEY_WEB_VIEW_STATE"))
        assertTrue(saveState.contains("KEY_FALLBACK_URL"))
        assertTrue(saveState.contains("KEY_FALLBACK_SCROLL_Y"))
        assertTrue(captureState.contains("webView.saveState(webViewState)"))
        assertTrue(captureState.contains("webView.url"))
        assertTrue(captureState.contains("webView.scrollY"))

        val restoreAttempt = restoreOrLoad.indexOf("restorePolicyState(savedWebViewState)")
        val fallbackLoad = restoreOrLoad.indexOf("loadPolicy(")
        assertTrue(
            "Saved WebView history must be attempted before loading a fallback URL",
            restoreAttempt >= 0 && fallbackLoad > restoreAttempt
        )
        assertTrue(restoreState.contains("activeWebView?.restoreState(savedWebViewState)"))
        assertTrue(restoreState.contains("0 until history.size"))
        assertTrue(restoreState.contains("PrivacyUrlPolicy.isAllowed(policyUrl, it)"))
        assertTrue(restoreOrLoad.contains("PrivacyUrlPolicy.isAllowed(policyUrl, it)"))
        assertTrue(source.contains("view.scrollTo(0, scrollY)"))
    }

    @Test
    fun `privacy webview is hardened and destroyed with its fragment view`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()

        assertTrue(
            "Privacy WebView must keep active content isolated",
            source.contains("javaScriptEnabled = false") &&
                source.contains("domStorageEnabled = false") &&
                source.contains("allowFileAccess = false") &&
                source.contains("allowContentAccess = false") &&
                source.contains("mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW") &&
                source.contains("safeBrowsingEnabled = true")
        )
        assertTrue(
            "Privacy WebView must reject cross-origin subresources as well as navigation",
            source.contains("shouldOverrideUrlLoading") &&
                source.contains("shouldInterceptRequest") &&
                source.contains("Blocked by privacy origin policy")
        )

        val destroyView = source.indexOf("override fun onDestroyView()")
        val captureState = source.indexOf("captureWebViewState", destroyView)
        val stopLoading = source.indexOf("stopLoading()", destroyView)
        val detachClient = source.indexOf("webViewClient = WebViewClient()", destroyView)
        val detachChromeClient = source.indexOf("webChromeClient = null", destroyView)
        val clearHistory = source.indexOf("clearHistory()", destroyView)
        val removeChildren = source.indexOf("removeAllViews()", destroyView)
        val destroyWebView = source.indexOf("destroy()", destroyView)
        val clearBinding = source.indexOf("_binding = null", destroyView)
        assertTrue(
            "WebView teardown must stop work, detach callbacks and destroy the renderer before clearing binding",
            destroyView >= 0 &&
                captureState > destroyView &&
                stopLoading > captureState &&
                detachClient > stopLoading &&
                detachChromeClient > detachClient &&
                clearHistory > detachChromeClient &&
                removeChildren > clearHistory &&
                destroyWebView > removeChildren &&
                clearBinding > destroyWebView
        )
    }

    @Test
    fun `main frame http failures cannot be reported as successful policy loads`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()
        val httpError = source
            .substringAfter("override fun onReceivedHttpError")
            .substringBefore("override fun onPageFinished")

        assertTrue(httpError.contains("request.isForMainFrame"))
        assertTrue(httpError.contains("errorResponse.statusCode >= 400"))
        assertTrue(httpError.contains("view.stopLoading()"))
        assertTrue(httpError.contains("showError("))
        assertTrue(source.contains("if (!loadFailed)"))
    }

    @Test
    fun `terminated renderer is consumed and replaced with a hardened webview`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()
        val rendererFailure = source
            .substringAfter("override fun onRenderProcessGone")
            .substringBefore("override fun onPageFinished")
        val replacement = source
            .substringAfter("private fun replaceTerminatedWebView")
            .substringBefore("private fun restoreOrLoadPolicy")

        assertTrue(rendererFailure.contains("replaceTerminatedWebView(view, detail.didCrash())"))
        assertTrue(rendererFailure.contains("return true"))
        assertTrue(replacement.contains("parent?.removeView(failedWebView)"))
        assertTrue(replacement.contains("failedWebView.destroy()"))
        assertTrue(replacement.contains("val replacement = ensurePrivacyWebView() ?: return"))
        assertTrue(replacement.contains("showError("))
    }
}
