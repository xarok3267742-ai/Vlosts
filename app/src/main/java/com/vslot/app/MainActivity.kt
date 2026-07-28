package com.vslot.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.databinding.ActivityMainBinding
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentDestinationId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareImmersiveWindow()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installSafeAreaInsets()
        hideSystemBars()
        recoverPendingSpinSettlement()
        if (savedInstanceState == null) {
            trackInitialOpenWhenConsentReady()
        }
    }

    private fun recoverPendingSpinSettlement(delayMs: Long = 0L) {
        lifecycleScope.launch {
            if (delayMs > 0L) delay(delayMs)
            runCatching {
                AppGraph.playerRepository.recoverPendingSpinSettlement(ProcessSession.id)
            }
                .onFailure { error ->
                    if (BuildConfig.QA_ENABLED) {
                        Log.e(TAG, "Pending spin recovery failed", error)
                    }
                }
        }
    }

    private fun trackAppOpen() {
        AppGraph.analyticsTracker.track(
            AnalyticsEvents.AppOpen,
            mapOf(
                "app_version" to BuildConfig.VERSION_NAME,
                "source" to "launcher"
            )
        )
    }

    private fun trackInitialOpenWhenConsentReady() {
        lifecycleScope.launch {
            try {
                applyPersistedAnalyticsConsent()
                trackAppOpen()
            } catch (error: IOException) {
                AppGraph.analyticsConsentController.setAnalyticsEnabled(false)
                if (BuildConfig.QA_ENABLED) {
                    Log.e(TAG, "Initial analytics consent read failed", error)
                }
            }
        }
    }

    private suspend fun applyPersistedAnalyticsConsent() {
        val enabled = AppGraph.playerRepository.playerState.first().analyticsEnabled
        AppGraph.analyticsConsentController.setAnalyticsEnabled(
            enabled && !AppGraph.analyticsRevocationGuard.isRevoked()
        )
    }

    override fun onResume() {
        super.onResume()
        (application as? VSlotApplication)?.refreshPushRegistration()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun installSafeAreaInsets() {
        val navHostView = binding.root
        val initialPadding = Insets.of(
            navHostView.paddingLeft,
            navHostView.paddingTop,
            navHostView.paddingRight,
            navHostView.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(navHostView) { view, insets ->
            // SlotFragment owns its cutout policy; keep its insets unconsumed to avoid double padding.
            val safeInsets = if (currentDestinationId == R.id.slotFragment) {
                Insets.NONE
            } else {
                insets.getInsets(
                    WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.mandatorySystemGestures() or
                        WindowInsetsCompat.Type.systemBars()
                )
            }
            view.updatePadding(
                left = initialPadding.left + safeInsets.left,
                top = initialPadding.top + safeInsets.top,
                right = initialPadding.right + safeInsets.right,
                bottom = initialPadding.bottom + safeInsets.bottom
            )
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHostFragment?.navController
        currentDestinationId = navController?.currentDestination?.id
        navController?.addOnDestinationChangedListener { _, destination, _ ->
            currentDestinationId = destination.id
            ViewCompat.requestApplyInsets(navHostView)
            if (destination.id == R.id.homeFragment) {
                recoverPendingSpinSettlement(HOME_SETTLEMENT_RECOVERY_DELAY_MS)
            }
        }
        ViewCompat.requestApplyInsets(navHostView)
    }

    @Suppress("DEPRECATION")
    private fun prepareImmersiveWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val HOME_SETTLEMENT_RECOVERY_DELAY_MS = 300L
    }
}
