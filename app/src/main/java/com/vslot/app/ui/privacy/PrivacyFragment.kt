package com.vslot.app.ui.privacy

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.OnBackPressedCallback
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.vslot.app.AppGraph
import com.vslot.app.BuildConfig
import com.vslot.app.R
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.databinding.FragmentPrivacyBinding
import java.io.ByteArrayInputStream

class PrivacyFragment : Fragment() {
    private var _binding: FragmentPrivacyBinding? = null
    private val binding get() = _binding!!
    private var privacyErrorAnimator: AnimatorSet? = null
    private var privacyLoadingAnimator: AnimatorSet? = null
    private var loadFailed = false
    private var retainedWebViewState: Bundle? = null
    private var retainedFallbackUrl: String? = null
    private var retainedFallbackScrollY = 0
    private var pendingFallbackScrollY: Int? = null
    private var activeWebView: WebView? = null
    private val policyUrl: String get() = BuildConfig.PRIVACY_POLICY_URL

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrivacyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.privacyLoadingGroup.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        binding.errorImage.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateBack()
                }
            }
        )
        binding.backButton.setOnClickListener { navigateBack() }
        binding.retryButton.setOnClickListener { loadPolicy() }
        activeWebView = binding.privacyWebView
        configureWebView(binding.privacyWebView)
        AppGraph.analyticsTracker.track(AnalyticsEvents.PrivacyOpen, mapOf("source" to "app"))
        restoreOrLoadPolicy(savedInstanceState)
    }

    private fun navigateBack(): Boolean {
        val webView = activeWebView
        if (webView?.canGoBack() == true) {
            prepareForPageLoad()
            webView.goBack()
            return true
        }
        return popFromPrivacy()
    }

    private fun popFromPrivacy(): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.privacyFragment) return false
        return navController.popBackStack()
    }

    private fun configureWebView(webView: WebView) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().setAcceptCookie(false)
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = PrivacyUrlPolicy.isAllowed(policyUrl, request.url.toString())
                if (!allowed && request.isForMainFrame) {
                    view.stopLoading()
                    showError(
                        R.drawable.label_privacy_error_load,
                        R.string.privacy_load_error,
                        "blocked_origin"
                    )
                }
                return !allowed
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                if (PrivacyUrlPolicy.isAllowed(policyUrl, request.url.toString())) return null
                return android.webkit.WebResourceResponse(
                    "text/plain",
                    Charsets.UTF_8.name(),
                    403,
                    "Blocked by privacy origin policy",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showError(R.drawable.label_privacy_error_load, R.string.privacy_load_error, "ssl")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showError(R.drawable.label_privacy_error_load, R.string.privacy_load_error, error.errorCode.toString())
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    view.stopLoading()
                    showError(
                        R.drawable.label_privacy_error_load,
                        R.string.privacy_load_error,
                        "http_${errorResponse.statusCode}"
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                replaceTerminatedWebView(view, detail.didCrash())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                val binding = _binding ?: return
                if (!loadFailed) {
                    hidePrivacyLoading()
                    hidePrivacyErrorPolish()
                    binding.errorGroup.visibility = View.GONE
                    activeWebView?.visibility = View.VISIBLE
                    pendingFallbackScrollY?.let { scrollY ->
                        pendingFallbackScrollY = null
                        view.post {
                            if (activeWebView === view) {
                                view.scrollTo(0, scrollY)
                            }
                        }
                    }
                    AppGraph.analyticsTracker.track(
                        AnalyticsEvents.PrivacyLoadSuccess,
                        mapOf("policy_origin" to PrivacyUrlPolicy.analyticsOrigin(url))
                    )
                }
            }
        }
    }

    private fun replaceTerminatedWebView(failedWebView: WebView, didCrash: Boolean) {
        val parent = failedWebView.parent as? ViewGroup
        val childIndex = parent?.indexOfChild(failedWebView) ?: -1
        val layoutParams = failedWebView.layoutParams
        if (activeWebView === failedWebView) activeWebView = null
        parent?.removeView(failedWebView)
        failedWebView.webViewClient = WebViewClient()
        failedWebView.webChromeClient = null
        failedWebView.removeAllViews()
        failedWebView.destroy()

        val context = context
        if (_binding == null || context == null || parent == null || childIndex < 0) return
        val replacement = WebView(context).apply {
            id = R.id.privacyWebView
            this.layoutParams = layoutParams
            setBackgroundColor(requireContext().getColor(R.color.input_dark))
            visibility = View.GONE
        }
        parent.addView(replacement, childIndex)
        activeWebView = replacement
        configureWebView(replacement)
        showError(
            R.drawable.label_privacy_error_load,
            R.string.privacy_load_error,
            if (didCrash) "renderer_crash" else "renderer_killed"
        )
    }

    private fun restoreOrLoadPolicy(savedInstanceState: Bundle?) {
        val savedWebViewState = savedInstanceState?.getBundle(KEY_WEB_VIEW_STATE)
            ?: retainedWebViewState
        val savedFallbackUrl = savedInstanceState
            ?.getString(KEY_FALLBACK_URL)
            ?.takeIf { PrivacyUrlPolicy.isAllowed(policyUrl, it) }
            ?: retainedFallbackUrl?.takeIf { PrivacyUrlPolicy.isAllowed(policyUrl, it) }
        val savedFallbackScrollY = if (
            savedFallbackUrl != null && savedInstanceState?.containsKey(KEY_FALLBACK_SCROLL_Y) == true
        ) {
            savedInstanceState.getInt(KEY_FALLBACK_SCROLL_Y).coerceAtLeast(0)
        } else {
            retainedFallbackScrollY.coerceAtLeast(0)
        }

        if (savedWebViewState != null && restorePolicyState(savedWebViewState)) {
            clearRetainedWebViewState()
            return
        }

        clearRetainedWebViewState()
        loadPolicy(
            url = savedFallbackUrl ?: policyUrl,
            fallbackScrollY = savedFallbackScrollY.takeIf { savedFallbackUrl != null }
        )
    }

    private fun restorePolicyState(savedWebViewState: Bundle): Boolean {
        if (!PrivacyUrlPolicy.isLoadable(policyUrl)) return false

        prepareForPageLoad()
        val history = runCatching {
            activeWebView?.restoreState(savedWebViewState)
        }.getOrNull() ?: return false
        val hasOnlyAllowedHistory = history.size > 0 && (0 until history.size).all { index ->
            history.getItemAtIndex(index).url
                ?.let { PrivacyUrlPolicy.isAllowed(policyUrl, it) } == true
        }
        if (!hasOnlyAllowedHistory) {
            activeWebView?.stopLoading()
            activeWebView?.clearHistory()
            return false
        }
        return true
    }

    private fun loadPolicy(url: String = policyUrl, fallbackScrollY: Int? = null) {
        if (_binding == null) return
        loadFailed = false
        pendingFallbackScrollY = null
        if (policyUrl.isBlank()) {
            showError(
                R.drawable.label_privacy_error_not_configured,
                R.string.privacy_not_configured,
                "not_configured",
                retryable = false
            )
            return
        }
        if (!PrivacyUrlPolicy.isLoadable(policyUrl)) {
            showError(
                R.drawable.label_privacy_error_invalid_url,
                R.string.privacy_invalid_url,
                "invalid_url",
                retryable = false
            )
            return
        }
        if (!hasInternetConnection()) {
            showError(R.drawable.label_privacy_error_offline, R.string.no_internet, "offline")
            return
        }
        val loadUrl = url.takeIf { PrivacyUrlPolicy.isAllowed(policyUrl, it) } ?: policyUrl
        prepareForPageLoad()
        pendingFallbackScrollY = fallbackScrollY?.coerceAtLeast(0)
        activeWebView?.loadUrl(loadUrl)
    }

    private fun prepareForPageLoad() {
        val binding = _binding ?: return
        loadFailed = false
        pendingFallbackScrollY = null
        binding.errorGroup.visibility = View.GONE
        hidePrivacyErrorPolish()
        activeWebView?.visibility = View.VISIBLE
        showPrivacyLoading()
    }

    private fun showError(
        @DrawableRes imageRes: Int,
        @StringRes contentDescriptionRes: Int,
        code: String,
        retryable: Boolean = true
    ) {
        val binding = _binding ?: return
        loadFailed = true
        hidePrivacyLoading()
        activeWebView?.visibility = View.GONE
        binding.errorGroup.visibility = View.VISIBLE
        binding.errorImage.setImageResource(imageRes)
        binding.errorImage.contentDescription = getString(contentDescriptionRes)
        binding.retryButtonGroup.visibility = if (retryable) View.VISIBLE else View.GONE
        binding.retryButton.isEnabled = retryable
        animatePrivacyErrorPolish()
        AppGraph.analyticsTracker.track(
            AnalyticsEvents.PrivacyLoadError,
            mapOf("error_code" to code)
        )
    }

    private fun showPrivacyLoading() {
        privacyLoadingAnimator?.cancel()
        privacyLoadingAnimator = null
        val binding = _binding ?: return
        binding.privacyLoadingGroup.visibility = View.VISIBLE
        binding.privacyLoadingGroup.alpha = 1f
        binding.privacyLoadingSweep.visibility = View.VISIBLE
        binding.privacyLoadingSweep.alpha = PRIVACY_LOADING_SWEEP_SETTLED_ALPHA
        binding.privacyLoadingSweep.scaleX = 1f
        binding.privacyLoadingSweep.scaleY = 1f
        binding.privacyLoadingSweep.translationY = 0f
        binding.privacyLoadingShield.scaleX = 1f
        binding.privacyLoadingShield.scaleY = 1f
        binding.privacyLoadingScanRail.alpha = PRIVACY_LOADING_RAIL_SETTLED_ALPHA
        binding.privacyLoadingScanRail.translationX = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val sweepTravel = PRIVACY_LOADING_SWEEP_TRAVEL_DP.dp().toFloat()
        binding.privacyLoadingGroup.alpha = 0f
        binding.privacyLoadingSweep.alpha = 0f
        binding.privacyLoadingSweep.scaleX = 0.94f
        binding.privacyLoadingSweep.scaleY = 0.98f
        binding.privacyLoadingSweep.translationY = sweepTravel
        binding.privacyLoadingShield.scaleX = 0.94f
        binding.privacyLoadingShield.scaleY = 0.94f
        binding.privacyLoadingScanRail.translationX = -18f
        binding.privacyLoadingScanRail.alpha = 0.24f
        privacyLoadingAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.privacyLoadingGroup, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.privacyLoadingSweep, View.ALPHA, 0f, PRIVACY_LOADING_SWEEP_PEAK_ALPHA, PRIVACY_LOADING_SWEEP_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.privacyLoadingSweep, View.TRANSLATION_Y, sweepTravel, -sweepTravel * 0.45f, 0f),
                ObjectAnimator.ofFloat(binding.privacyLoadingSweep, View.SCALE_X, 0.94f, 1.035f, 1f),
                ObjectAnimator.ofFloat(binding.privacyLoadingSweep, View.SCALE_Y, 0.98f, 1.04f, 1f),
                ObjectAnimator.ofFloat(binding.privacyLoadingShield, View.SCALE_X, 0.94f, 1.035f, 1f),
                ObjectAnimator.ofFloat(binding.privacyLoadingShield, View.SCALE_Y, 0.94f, 1.035f, 1f),
                ObjectAnimator.ofFloat(binding.privacyLoadingScanRail, View.TRANSLATION_X, -18f, 18f, 0f),
                ObjectAnimator.ofFloat(binding.privacyLoadingScanRail, View.ALPHA, 0.24f, PRIVACY_LOADING_RAIL_PEAK_ALPHA, PRIVACY_LOADING_RAIL_SETTLED_ALPHA)
            )
            duration = PRIVACY_LOADING_POLISH_DURATION_MS
            start()
        }
    }

    private fun hidePrivacyLoading() {
        privacyLoadingAnimator?.cancel()
        privacyLoadingAnimator = null
        val binding = _binding ?: return
        binding.privacyLoadingGroup.visibility = View.GONE
        binding.privacyLoadingGroup.alpha = 0f
        binding.privacyLoadingSweep.visibility = View.INVISIBLE
        binding.privacyLoadingSweep.alpha = 0f
        binding.privacyLoadingSweep.scaleX = 1f
        binding.privacyLoadingSweep.scaleY = 1f
        binding.privacyLoadingSweep.translationY = 0f
        binding.privacyLoadingShield.scaleX = 1f
        binding.privacyLoadingShield.scaleY = 1f
        binding.privacyLoadingScanRail.translationX = 0f
        binding.privacyLoadingScanRail.alpha = PRIVACY_LOADING_RAIL_SETTLED_ALPHA
    }

    private fun hasInternetConnection(): Boolean {
        val manager = context?.getSystemService<ConnectivityManager>() ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun animatePrivacyErrorPolish() {
        privacyErrorAnimator?.cancel()
        privacyErrorAnimator = null
        val binding = _binding ?: return
        val overlay = binding.privacyGuardDocumentGlow
        overlay.visibility = View.VISIBLE
        overlay.alpha = PRIVACY_ERROR_SETTLED_ALPHA
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        binding.privacyGuardBadge.scaleX = 1f
        binding.privacyGuardBadge.scaleY = 1f
        binding.retryButton.scaleX = 1f
        binding.retryButton.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        overlay.alpha = 0.08f
        overlay.scaleX = 0.985f
        overlay.scaleY = 0.985f
        val polishAnimators = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(overlay, View.ALPHA, 0.08f, PRIVACY_ERROR_PEAK_ALPHA, PRIVACY_ERROR_SETTLED_ALPHA),
            ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.985f, 1.018f, 1f),
            ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.985f, 1.018f, 1f),
            ObjectAnimator.ofFloat(binding.privacyGuardBadge, View.SCALE_X, 0.94f, 1.045f, 1f),
            ObjectAnimator.ofFloat(binding.privacyGuardBadge, View.SCALE_Y, 0.94f, 1.045f, 1f)
        )
        if (binding.retryButtonGroup.isVisible) {
            polishAnimators += listOf(
                ObjectAnimator.ofFloat(binding.retryButton, View.SCALE_X, 0.985f, 1.025f, 1f),
                ObjectAnimator.ofFloat(binding.retryButton, View.SCALE_Y, 0.985f, 1.025f, 1f)
            )
        }
        privacyErrorAnimator = AnimatorSet().apply {
            playTogether(polishAnimators)
            duration = PRIVACY_ERROR_POLISH_DURATION_MS
            start()
        }
    }

    private fun hidePrivacyErrorPolish() {
        privacyErrorAnimator?.cancel()
        privacyErrorAnimator = null
        val binding = _binding ?: return
        binding.privacyGuardDocumentGlow.alpha = 0f
        binding.privacyGuardDocumentGlow.scaleX = 1f
        binding.privacyGuardDocumentGlow.scaleY = 1f
        binding.privacyGuardDocumentGlow.visibility = View.INVISIBLE
        binding.privacyGuardBadge.scaleX = 1f
        binding.privacyGuardBadge.scaleY = 1f
        binding.retryButton.scaleX = 1f
        binding.retryButton.scaleY = 1f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        activeWebView?.let(::captureWebViewState)
        retainedWebViewState?.let { outState.putBundle(KEY_WEB_VIEW_STATE, it) }
        retainedFallbackUrl?.let { url ->
            outState.putString(KEY_FALLBACK_URL, url)
            outState.putInt(KEY_FALLBACK_SCROLL_Y, retainedFallbackScrollY)
        }
        super.onSaveInstanceState(outState)
    }

    private fun captureWebViewState(webView: WebView) {
        val webViewState = Bundle()
        retainedWebViewState = runCatching { webView.saveState(webViewState) }
            .getOrNull()
            ?.let { webViewState }
        retainedFallbackUrl = webView.url
            ?.takeIf { PrivacyUrlPolicy.isAllowed(policyUrl, it) }
        retainedFallbackScrollY = if (retainedFallbackUrl != null) {
            webView.scrollY.coerceAtLeast(0)
        } else {
            0
        }
    }

    private fun clearRetainedWebViewState() {
        retainedWebViewState = null
        retainedFallbackUrl = null
        retainedFallbackScrollY = 0
    }

    override fun onDestroyView() {
        val binding = _binding
        activeWebView?.let(::captureWebViewState)
        hidePrivacyLoading()
        privacyErrorAnimator?.cancel()
        privacyErrorAnimator = null
        activeWebView?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            webChromeClient = null
            clearHistory()
            removeAllViews()
            destroy()
        }
        activeWebView = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_WEB_VIEW_STATE = "privacy.web_view_state"
        const val KEY_FALLBACK_URL = "privacy.fallback_url"
        const val KEY_FALLBACK_SCROLL_Y = "privacy.fallback_scroll_y"
        const val PRIVACY_LOADING_POLISH_DURATION_MS = 900L
        const val PRIVACY_LOADING_RAIL_SETTLED_ALPHA = 0.74f
        const val PRIVACY_LOADING_RAIL_PEAK_ALPHA = 0.96f
        const val PRIVACY_LOADING_SWEEP_TRAVEL_DP = 34
        const val PRIVACY_LOADING_SWEEP_SETTLED_ALPHA = 0.32f
        const val PRIVACY_LOADING_SWEEP_PEAK_ALPHA = 0.62f
        const val PRIVACY_ERROR_POLISH_DURATION_MS = 820L
        const val PRIVACY_ERROR_SETTLED_ALPHA = 0.32f
        const val PRIVACY_ERROR_PEAK_ALPHA = 0.58f
    }
}
