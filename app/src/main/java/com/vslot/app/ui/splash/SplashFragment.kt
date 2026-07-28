package com.vslot.app.ui.splash

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.vslot.app.AppGraph
import com.vslot.app.BuildConfig
import com.vslot.app.ProcessSession
import com.vslot.app.R
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.databinding.FragmentSplashBinding
import com.vslot.app.data.retryTransientPersistenceIo
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashFragment : Fragment() {
    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!
    private var splashAnimator: AnimatorSet? = null
    private val routeCoordinator = SplashRouteCoordinator<SplashRoute>()
    private val viewModel: SplashViewModel by viewModels {
        SplashViewModel.Factory(AppGraph.playerRepository)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityHeading(binding.splashStorageErrorMessage, true)
        animateLogo()
        binding.splashRetryButton.setOnClickListener {
            renderLoadFailure(false)
            viewModel.retry()
        }
        binding.splashAppSettingsButton.setOnClickListener {
            openApplicationSettings()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val routeDelayMs = SplashTiming.routeDelayMs(ValueAnimator.areAnimatorsEnabled())
            if (routeDelayMs > 0L) delay(routeDelayMs)
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loadState.collect { loadState ->
                    when (loadState) {
                        SplashLoadState.Loading -> renderLoadFailure(false)
                        SplashLoadState.Failed -> renderLoadFailure(true)
                        is SplashLoadState.Ready -> {
                            renderLoadFailure(false)
                            submitRoute(loadState.playerState.disclaimerAccepted)
                        }
                    }
                }
            }
        }
    }

    private suspend fun submitRoute(disclaimerAccepted: Boolean) {
        val route = if (disclaimerAccepted) {
            val pendingPresentationSlotId = recoverRoutablePendingPresentationSlotId()
            val debugSlotId = debugOpenSlotId()
            when {
                pendingPresentationSlotId != null -> {
                    SplashRoute.PendingPresentation(pendingPresentationSlotId)
                }
                debugSlotId != null -> SplashRoute.DebugSlot(debugSlotId)
                else -> SplashRoute.Home
            }
        } else {
            SplashRoute.Disclaimer
        }
        routeCoordinator.submit(route)
        dispatchPendingRoute()
    }

    private fun renderLoadFailure(failed: Boolean) {
        val binding = _binding ?: return
        binding.splashStorageErrorGroup.visibility = if (failed) View.VISIBLE else View.GONE
        binding.splashLoadingRail.visibility = if (failed) View.INVISIBLE else View.VISIBLE
        binding.splashLoadingScan.visibility = if (failed) View.INVISIBLE else View.VISIBLE
    }

    private fun openApplicationSettings() {
        val packageUri = Uri.fromParts("package", requireContext().packageName, null)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        runCatching { startActivity(appDetails) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private suspend fun recoverRoutablePendingPresentationSlotId(): String? {
        val pendingSlotId = try {
            retryTransientPersistenceIo {
                AppGraph.playerRepository.recoverPendingSpinSettlement(ProcessSession.id)
            }
            retryTransientPersistenceIo {
                AppGraph.playerRepository.pendingSpinPresentationSlotId()
            }
        } catch (_: IOException) {
            return null
        } ?: return null
        if (AppGraph.slotRepository.slots.any { it.id == pendingSlotId }) return pendingSlotId

        try {
            val stalePresentation = retryTransientPersistenceIo {
                AppGraph.playerRepository.claimSpinPresentation(pendingSlotId, ProcessSession.id)
            }
            stalePresentation?.let { presentation ->
                retryTransientPersistenceIo {
                    AppGraph.playerRepository.acknowledgeSpinPresentation(presentation.id)
                }
            }
        } catch (_: IOException) {
            return null
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        dispatchPendingRoute()
    }

    private fun debugOpenSlotId(): String? {
        if (!BuildConfig.QA_ENABLED) return null
        return requireActivity().intent
            ?.getStringExtra(EXTRA_DEBUG_OPEN_SLOT)
            .orEmpty()
            .takeIf { it.isNotBlank() }
    }

    private fun dispatchPendingRoute() {
        val canNavigate =
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !parentFragmentManager.isStateSaved
        routeCoordinator.dispatch(canNavigate) { route ->
            when (route) {
                is SplashRoute.DebugSlot -> openDebugSlot(route.slotId)
                is SplashRoute.PendingPresentation -> openPendingPresentationSlot(route.slotId)
                SplashRoute.Home -> navigateFromSplash(R.id.action_splash_to_home)
                SplashRoute.Disclaimer -> {
                    navigateFromSplash(R.id.action_splash_to_disclaimer).also { navigated ->
                        if (navigated) {
                            AppGraph.analyticsTracker.track(AnalyticsEvents.FirstLaunch)
                        }
                    }
                }
            }
        }
    }

    private fun openDebugSlot(slotId: String): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.splashFragment) return false
        navController.navigate(
            R.id.slotFragment,
            Bundle().apply { putString("slotId", slotId) },
            NavOptions.Builder()
                .setPopUpTo(R.id.splashFragment, true)
                .build()
        )
        return true
    }

    private fun openPendingPresentationSlot(slotId: String): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.splashFragment) return false
        navController.navigate(R.id.action_splash_to_home)
        if (navController.currentDestination?.id != R.id.homeFragment) return false
        navController.navigate(
            R.id.action_home_to_slot,
            Bundle().apply { putString("slotId", slotId) }
        )
        return true
    }

    private fun navigateFromSplash(actionId: Int): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.splashFragment) return false
        navController.navigate(actionId)
        return true
    }

    private fun animateLogo() {
        splashAnimator?.cancel()
        binding.logoGroup.alpha = 0f
        binding.logoGroup.scaleX = 0.86f
        binding.logoGroup.scaleY = 0.86f
        binding.splashLogoAura.alpha = 0f
        binding.splashLogoAura.scaleX = 0.9f
        binding.splashLogoAura.scaleY = 0.9f
        binding.splashIgnitionOverlay.visibility = View.VISIBLE
        binding.splashIgnitionOverlay.alpha = 0f
        binding.splashIgnitionOverlay.scaleX = 0.86f
        binding.splashIgnitionOverlay.scaleY = 0.86f
        binding.splashIgnitionOverlay.rotation = -6f
        binding.splashLoadingRail.alpha = 0f
        binding.splashLoadingRail.translationY = 8f
        binding.splashLoadingScan.visibility = View.VISIBLE
        binding.splashLoadingScan.alpha = 0f
        binding.splashLoadingScan.translationX = -18f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            binding.logoGroup.alpha = 1f
            binding.logoGroup.scaleX = 1f
            binding.logoGroup.scaleY = 1f
            binding.splashLogoAura.alpha = SPLASH_AURA_SETTLED_ALPHA
            binding.splashLogoAura.scaleX = 1f
            binding.splashLogoAura.scaleY = 1f
            binding.splashIgnitionOverlay.alpha = SPLASH_IGNITION_SETTLED_ALPHA
            binding.splashIgnitionOverlay.scaleX = 1f
            binding.splashIgnitionOverlay.scaleY = 1f
            binding.splashIgnitionOverlay.rotation = 0f
            binding.splashLoadingRail.alpha = SPLASH_RAIL_SETTLED_ALPHA
            binding.splashLoadingRail.translationY = 0f
            binding.splashLoadingScan.alpha = SPLASH_SCAN_SETTLED_ALPHA
            binding.splashLoadingScan.translationX = 0f
            return
        }
        splashAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.logoGroup, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.logoGroup, View.SCALE_X, 0.86f, 1f),
                ObjectAnimator.ofFloat(binding.logoGroup, View.SCALE_Y, 0.86f, 1f),
                ObjectAnimator.ofFloat(binding.splashLogoAura, View.ALPHA, 0f, SPLASH_AURA_PEAK_ALPHA, SPLASH_AURA_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.splashLogoAura, View.SCALE_X, 0.9f, 1.04f, 1f),
                ObjectAnimator.ofFloat(binding.splashLogoAura, View.SCALE_Y, 0.9f, 1.04f, 1f),
                ObjectAnimator.ofFloat(binding.splashIgnitionOverlay, View.ALPHA, 0f, SPLASH_IGNITION_PEAK_ALPHA, SPLASH_IGNITION_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.splashIgnitionOverlay, View.SCALE_X, 0.86f, 1.08f, 1f),
                ObjectAnimator.ofFloat(binding.splashIgnitionOverlay, View.SCALE_Y, 0.86f, 1.08f, 1f),
                ObjectAnimator.ofFloat(binding.splashIgnitionOverlay, View.ROTATION, -6f, 2.5f, 0f),
                ObjectAnimator.ofFloat(binding.splashLoadingRail, View.ALPHA, 0f, SPLASH_RAIL_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.splashLoadingRail, View.TRANSLATION_Y, 8f, 0f),
                ObjectAnimator.ofFloat(binding.splashLoadingScan, View.ALPHA, 0f, SPLASH_SCAN_PEAK_ALPHA, SPLASH_SCAN_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.splashLoadingScan, View.TRANSLATION_X, -18f, 18f, 0f)
            )
            duration = 760L
            start()
        }
    }

    override fun onDestroyView() {
        splashAnimator?.cancel()
        splashAnimator = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val EXTRA_DEBUG_OPEN_SLOT = "qa_open_slot"
        const val SPLASH_AURA_SETTLED_ALPHA = 0.34f
        const val SPLASH_AURA_PEAK_ALPHA = 0.58f
        const val SPLASH_IGNITION_SETTLED_ALPHA = 0.36f
        const val SPLASH_IGNITION_PEAK_ALPHA = 0.76f
        const val SPLASH_RAIL_SETTLED_ALPHA = 0.86f
        const val SPLASH_SCAN_SETTLED_ALPHA = 0.48f
        const val SPLASH_SCAN_PEAK_ALPHA = 0.92f
    }
}

internal class SplashRouteCoordinator<T : Any> {
    private var pendingRoute: T? = null
    private var completed = false

    fun submit(route: T) {
        if (!completed) pendingRoute = route
    }

    fun dispatch(canNavigate: Boolean, navigate: (T) -> Boolean): Boolean {
        if (completed || !canNavigate) return false
        val route = pendingRoute ?: return false
        if (!navigate(route)) return false
        pendingRoute = null
        completed = true
        return true
    }
}

private sealed interface SplashRoute {
    data object Home : SplashRoute
    data object Disclaimer : SplashRoute
    data class DebugSlot(val slotId: String) : SplashRoute
    data class PendingPresentation(val slotId: String) : SplashRoute
}
