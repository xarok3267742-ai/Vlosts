package com.vslot.app.ui.settings

import android.animation.AnimatorSet
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.vslot.app.AppGraph
import com.vslot.app.BuildConfig
import com.vslot.app.R
import com.vslot.app.PushRegistrationStatus
import com.vslot.app.VSlotApplication
import com.vslot.app.areNotificationsDeliverable
import com.vslot.app.data.PlayerState
import com.vslot.app.databinding.FragmentSettingsBinding
import com.vslot.app.ui.dialog.PushPermissionDialogFragment
import com.vslot.app.ui.dialog.AnalyticsConsentDialogFragment
import com.vslot.app.ui.dialog.SocialRulesDialogFragment
import com.vslot.app.ui.dialog.ThirdPartyNoticesDialogFragment
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val application get() = requireContext().applicationContext as VSlotApplication
    private var settingsConsoleAnimator: AnimatorSet? = null
    private var pushStatusAnimator: AnimatorSet? = null
    private var pushStatusSignature: String? = null
    private var notificationPermissionLaunchActive = false
    private var pushPermissionResultPersistenceActive = false
    private val pushPermissionRequestStore by lazy {
        PushPermissionRequestStore(requireContext().applicationContext)
    }
    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            AppGraph.playerRepository,
            AppGraph.analyticsTracker,
            AppGraph.analyticsConsentController,
            AppGraph.analyticsRevocationGuard
        )
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionLaunchActive = false
        pushPermissionRequestStore.markSystemResult(granted)
        pushPermissionResultPersistenceActive = true
        viewModel.onPushPermissionResult(
            granted = granted,
            onPersisted = {
                pushPermissionResultPersistenceActive = false
                resolvePersistedPushPermissionRequest()
            },
            onFailure = { pushPermissionResultPersistenceActive = false }
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.onVisible()
        parentFragmentManager.setFragmentResultListener(PushPermissionDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(PushPermissionDialogFragment.KEY_ACCEPTED)) {
                requestNotificationPermission()
            } else {
                viewModel.onPushPermissionDeferred()
            }
        }
        parentFragmentManager.setFragmentResultListener(
            AnalyticsConsentDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            viewModel.setAnalyticsEnabled(
                bundle.getBoolean(AnalyticsConsentDialogFragment.KEY_ACCEPTED)
            )
        }
        val visibleVersionName = BuildConfig.VERSION_NAME
            .removeSuffix("-debug")
            .removeSuffix("-qa")
        binding.versionImage.setImageResource(R.drawable.label_version_current)
        binding.versionImage.contentDescription = getString(R.string.version_format, visibleVersionName)
        bindScalableCopy(visibleVersionName)
        binding.backButton.setOnClickListener { popFromSettings() }
        binding.privacyButton.setOnClickListener { navigateFromSettings(R.id.action_settings_to_privacy) }
        binding.noticesButton.setOnClickListener { showThirdPartyNoticesDialog() }
        binding.rulesButton.setOnClickListener {
            showSocialRulesDialog()
        }
        binding.pushButton.setOnClickListener { handlePushButtonClick() }
        installSwitchSemantics(binding.soundToggleButton)
        installSwitchSemantics(binding.hapticsToggleButton)
        installSwitchSemantics(binding.analyticsToggleButton)
        binding.soundToggleButton.setOnClickListener {
            viewModel.setSoundEnabled(!binding.soundToggleButton.isSelected)
        }
        binding.hapticsToggleButton.setOnClickListener {
            viewModel.setHapticsEnabled(!binding.hapticsToggleButton.isSelected)
        }
        binding.analyticsToggleButton.setOnClickListener {
            if (binding.analyticsToggleButton.isSelected) {
                viewModel.setAnalyticsEnabled(false)
            } else {
                showAnalyticsConsentDialog()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.playerState,
                    application.pushRegistrationStatus
                ) { state, pushRegistrationStatus -> state to pushRegistrationStatus }
                    .collect { (state, pushRegistrationStatus) ->
                        recoverPersistedPushPermissionRequest(state)
                        renderPushState(state, pushRegistrationStatus)
                        renderFeedbackState(state)
                    }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    if (event == SettingsEvent.SaveFailed) {
                        val message = getString(R.string.persistence_save_error_retry)
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun bindScalableCopy(visibleVersionName: String) {
        val useScalableCopy = resources.configuration.fontScale > DEFAULT_FONT_SCALE
        binding.versionLargeText.text = getString(R.string.version_format, visibleVersionName)
        binding.versionImage.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.versionLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.socialDisclaimerImage.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.socialDisclaimerLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.privacyButtonLabel.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.privacyButtonLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.noticesButtonLabel.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.noticesButtonLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.rulesButtonLabel.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.rulesButtonLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.pushButtonLabel.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.pushButtonLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.pushStatusText.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.pushStatusLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.settingsSafetyPanel.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.settingsSafetyLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        if (
            useScalableCopy &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            binding.pushStatusStage.layoutParams = binding.pushStatusStage.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        binding.settingsBadge.visibility = if (
            useScalableCopy && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            View.GONE
        } else {
            View.VISIBLE
        }

        binding.versionImage.importantForAccessibility = accessibilityImportance(!useScalableCopy)
        binding.versionLargeText.importantForAccessibility = accessibilityImportance(useScalableCopy)
        binding.socialDisclaimerImage.importantForAccessibility = accessibilityImportance(!useScalableCopy)
        binding.socialDisclaimerLargeText.importantForAccessibility = accessibilityImportance(useScalableCopy)
        binding.settingsSafetyPanel.importantForAccessibility = accessibilityImportance(!useScalableCopy)
        binding.settingsSafetyLargeText.importantForAccessibility = accessibilityImportance(useScalableCopy)
    }

    private fun accessibilityImportance(enabled: Boolean): Int = if (enabled) {
        View.IMPORTANT_FOR_ACCESSIBILITY_YES
    } else {
        View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onStart() {
        super.onStart()
        animateSettingsConsolePolish()
    }

    override fun onResume() {
        super.onResume()
        application.refreshPushRegistration()
        recoverPersistedPushPermissionRequest(viewModel.playerState.value)
        renderPushState(viewModel.playerState.value, application.pushRegistrationStatus.value)
    }

    override fun onStop() {
        stopSettingsConsolePolish()
        stopPushStatusPolish()
        super.onStop()
    }

    private fun handlePushButtonClick() {
        when (
            pushPermissionRecoveryAction(
                pushPermissionRequestStore.phase(),
                viewModel.playerState.value.pushPermissionAsked
            )
        ) {
            PushPermissionRecoveryAction.ResumeSystemRequest -> {
                launchSystemNotificationPermission()
                return
            }
            PushPermissionRecoveryAction.PersistGrantedResult -> {
                persistRecoveredPushPermissionResult(true)
                return
            }
            PushPermissionRecoveryAction.PersistDeniedResult -> {
                persistRecoveredPushPermissionResult(false)
                return
            }
            PushPermissionRecoveryAction.MarkResolved -> resolvePersistedPushPermissionRequest()
            PushPermissionRecoveryAction.None -> Unit
        }
        when (application.pushRegistrationStatus.value) {
            PushRegistrationStatus.Failed -> {
                application.refreshPushRegistration()
                return
            }
            PushRegistrationStatus.Registering -> return
            else -> Unit
        }
        when (
            pushPermissionAction(
                pushConfigured = arePushNotificationsConfigured(),
                permissionAsked = viewModel.playerState.value.pushPermissionAsked
            )
        ) {
            PushPermissionAction.Disabled -> Unit
            PushPermissionAction.ShowPrePrompt -> showPushPrePermission()
            PushPermissionAction.OpenSystemSettings -> openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val context = requireContext()
        val appNotificationSettings = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        }
        val appDetailsSettings = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        val settingsIntent = listOf(appNotificationSettings, appDetailsSettings)
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: return
        startActivity(settingsIntent)
    }

    private fun showPushPrePermission() {
        if (!arePushNotificationsConfigured()) {
            return
        }
        if (parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(PUSH_PERMISSION_DIALOG_TAG) != null) return
        PushPermissionDialogFragment().show(parentFragmentManager, PUSH_PERMISSION_DIALOG_TAG)
        viewModel.onPushPermissionShown(Build.VERSION.SDK_INT)
    }

    private fun showSocialRulesDialog() {
        if (parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(SOCIAL_RULES_DIALOG_TAG) != null) return
        SocialRulesDialogFragment().show(parentFragmentManager, SOCIAL_RULES_DIALOG_TAG)
    }

    private fun showAnalyticsConsentDialog() {
        if (parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(ANALYTICS_CONSENT_DIALOG_TAG) != null) return
        AnalyticsConsentDialogFragment().show(
            parentFragmentManager,
            ANALYTICS_CONSENT_DIALOG_TAG
        )
    }

    private fun showThirdPartyNoticesDialog() {
        if (parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(THIRD_PARTY_NOTICES_DIALOG_TAG) != null) return
        ThirdPartyNoticesDialogFragment().show(
            parentFragmentManager,
            THIRD_PARTY_NOTICES_DIALOG_TAG
        )
    }

    private fun navigateFromSettings(actionId: Int): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.settingsFragment) return false
        navController.navigate(actionId)
        return true
    }

    private fun popFromSettings(): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.settingsFragment) return false
        return navController.popBackStack()
    }

    private fun requestNotificationPermission() {
        if (!arePushNotificationsConfigured()) {
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            viewModel.onPushPermissionResult(true)
            return
        }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (isNotificationPermissionGranted()) {
            viewModel.onPushPermissionResult(true)
        } else {
            persistPendingPermissionRequestAndLaunch()
        }
    }

    private fun persistPendingPermissionRequestAndLaunch() {
        if (notificationPermissionLaunchActive) return
        viewLifecycleOwner.lifecycleScope.launch {
            val persisted = withContext(Dispatchers.IO) {
                pushPermissionRequestStore.markPending()
            }
            if (persisted && _binding != null) {
                launchSystemNotificationPermission()
            }
        }
    }

    private fun launchSystemNotificationPermission() {
        if (notificationPermissionLaunchActive || !arePushNotificationsConfigured()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isNotificationPermissionGranted()) {
            viewModel.onPushPermissionResult(true) {
                resolvePersistedPushPermissionRequest()
            }
            return
        }
        notificationPermissionLaunchActive = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun recoverPersistedPushPermissionRequest(state: PlayerState) {
        when (pushPermissionRecoveryAction(pushPermissionRequestStore.phase(), state.pushPermissionAsked)) {
            PushPermissionRecoveryAction.None -> Unit
            PushPermissionRecoveryAction.ResumeSystemRequest -> launchSystemNotificationPermission()
            PushPermissionRecoveryAction.PersistGrantedResult -> persistRecoveredPushPermissionResult(true)
            PushPermissionRecoveryAction.PersistDeniedResult -> persistRecoveredPushPermissionResult(false)
            PushPermissionRecoveryAction.MarkResolved -> resolvePersistedPushPermissionRequest()
        }
    }

    private fun persistRecoveredPushPermissionResult(granted: Boolean) {
        if (pushPermissionResultPersistenceActive) return
        pushPermissionResultPersistenceActive = true
        viewModel.onPushPermissionResult(
            granted = granted,
            onPersisted = {
                pushPermissionResultPersistenceActive = false
                resolvePersistedPushPermissionRequest()
            },
            onFailure = { pushPermissionResultPersistenceActive = false }
        )
    }

    private fun resolvePersistedPushPermissionRequest() {
        // Permission persistence can complete after this fragment's view has left the back stack.
        lifecycleScope.launch(Dispatchers.IO) {
            pushPermissionRequestStore.markResolved()
        }
    }

    private fun renderPushState(state: PlayerState, registrationStatus: PushRegistrationStatus) {
        val pushConfigured = arePushNotificationsConfigured()
        val systemPermissionGranted = pushConfigured &&
            state.pushPermissionAsked &&
            isNotificationPermissionGranted()
        val registered = systemPermissionGranted && registrationStatus == PushRegistrationStatus.Registered
        val registrationPending = systemPermissionGranted && registrationStatus == PushRegistrationStatus.Registering
        val registrationFailed = systemPermissionGranted && registrationStatus == PushRegistrationStatus.Failed
        val pushActionEnabled = pushConfigured && !registrationPending
        binding.pushButton.isEnabled = pushActionEnabled
        binding.pushButton.alpha = if (pushActionEnabled) PUSH_BUTTON_ENABLED_ALPHA else PUSH_BUTTON_UNCONFIGURED_ALPHA
        binding.pushButtonLabel.alpha = if (pushActionEnabled) PUSH_BUTTON_ENABLED_ALPHA else PUSH_BUTTON_UNCONFIGURED_LABEL_ALPHA
        binding.pushButtonLargeText.alpha = if (pushActionEnabled) PUSH_BUTTON_ENABLED_ALPHA else PUSH_BUTTON_UNCONFIGURED_LABEL_ALPHA
        binding.pushButton.setImageResource(
            if (!pushConfigured || registered || registrationPending) {
                R.drawable.btn_privacy_selector
            } else {
                R.drawable.btn_play_selector
            }
        )
        val pushButtonLabel = when {
            !pushConfigured -> getString(R.string.push_unconfigured_status)
            registered -> getString(R.string.push_enabled_status)
            registrationPending -> getString(R.string.push_registration_pending_status)
            registrationFailed -> getString(R.string.push_registration_retry_action)
            else -> getString(R.string.push_reminders_action)
        }
        binding.pushButtonLabel.setImageResource(
            when {
                !pushConfigured -> R.drawable.label_push_unconfigured_action
                registered -> R.drawable.label_push_enabled
                registrationPending -> R.drawable.label_push_unconfigured_action
                else -> R.drawable.label_push_reminders
            }
        )
        binding.pushButtonLargeText.text = pushButtonLabel
        val pushStatus = when {
            !pushConfigured -> getString(R.string.push_unconfigured_status) to R.drawable.label_push_status_unconfigured
            registered -> getString(R.string.push_enabled_status) to R.drawable.label_push_status_enabled
            registrationPending -> getString(R.string.push_registration_pending_status) to R.drawable.label_push_status_asked
            registrationFailed -> getString(R.string.push_registration_error_status) to R.drawable.label_push_status_off
            state.pushPermissionAsked -> getString(R.string.push_asked_status) to R.drawable.label_push_status_asked
            else -> getString(R.string.push_not_enabled_status) to R.drawable.label_push_status_off
        }
        binding.pushStatusText.setImageResource(pushStatus.second)
        binding.pushStatusLargeText.text = pushStatus.first
        binding.pushStatusStage.contentDescription = pushStatus.first
        binding.pushButton.contentDescription = pushButtonLabel
        updatePushStatusPolish(pushConfigured = pushConfigured, granted = registered, asked = state.pushPermissionAsked)
    }

    private fun renderFeedbackState(state: PlayerState) {
        binding.soundToggleButton.isSelected = state.soundEnabled
        binding.soundToggleIcon.isSelected = state.soundEnabled
        binding.soundToggleButton.contentDescription = getString(R.string.settings_sound)
        binding.hapticsToggleButton.isSelected = state.hapticsEnabled
        binding.hapticsToggleIcon.isSelected = state.hapticsEnabled
        binding.hapticsToggleButton.contentDescription = getString(R.string.settings_haptics)
        binding.analyticsToggleButton.isSelected = state.analyticsEnabled
        binding.analyticsToggleIcon.isSelected = state.analyticsEnabled
        binding.analyticsToggleButton.contentDescription = getString(R.string.settings_analytics)
        ViewCompat.setTooltipText(
            binding.analyticsToggleButton,
            getString(
                if (state.analyticsEnabled) {
                    R.string.settings_analytics_on
                } else {
                    R.string.settings_analytics_off
                }
            )
        )
    }

    private fun installSwitchSemantics(toggle: View) {
        ViewCompat.setAccessibilityDelegate(
            toggle,
            object : AccessibilityDelegateCompat() {
                @Suppress("DEPRECATION")
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            }
        )
    }

    private fun arePushNotificationsConfigured(): Boolean {
        return BuildConfig.FIREBASE_CONFIGURED && BuildConfig.APP_METRICA_API_KEY.isNotBlank()
    }

    private fun isNotificationPermissionGranted(): Boolean {
        val context = requireContext()
        if (!context.areNotificationsDeliverable()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun animateSettingsConsolePolish() {
        stopSettingsConsolePolish(reset = false)
        binding.settingsControlGlow.visibility = View.VISIBLE
        binding.settingsControlGlow.alpha = SETTINGS_CONSOLE_SETTLED_ALPHA
        binding.settingsControlGlow.scaleX = 1f
        binding.settingsControlGlow.scaleY = 1f
        binding.settingsSafetyAnchor.alpha = SETTINGS_SAFETY_ANCHOR_SETTLED_ALPHA
        binding.settingsSafetyAnchor.scaleX = 1f
        binding.settingsSafetyAnchor.scaleY = 1f
        binding.settingsSafetyPanel.scaleX = 1f
        binding.settingsSafetyPanel.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        binding.settingsControlGlow.alpha = 0.08f
        binding.settingsControlGlow.scaleX = 0.985f
        binding.settingsControlGlow.scaleY = 0.985f
        binding.settingsSafetyAnchor.alpha = SETTINGS_SAFETY_ANCHOR_LOW_ALPHA
        binding.settingsSafetyAnchor.scaleX = 0.99f
        binding.settingsSafetyAnchor.scaleY = 0.985f
        settingsConsoleAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.settingsControlGlow, View.ALPHA, 0.08f, SETTINGS_CONSOLE_PEAK_ALPHA, SETTINGS_CONSOLE_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.settingsControlGlow, View.SCALE_X, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(binding.settingsControlGlow, View.SCALE_Y, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(binding.settingsSafetyAnchor, View.ALPHA, SETTINGS_SAFETY_ANCHOR_LOW_ALPHA, SETTINGS_SAFETY_ANCHOR_PEAK_ALPHA, SETTINGS_SAFETY_ANCHOR_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.settingsSafetyAnchor, View.SCALE_X, 0.99f, 1.012f, 1f),
                ObjectAnimator.ofFloat(binding.settingsSafetyAnchor, View.SCALE_Y, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(binding.settingsSafetyPanel, View.SCALE_X, 0.99f, 1.012f, 1f),
                ObjectAnimator.ofFloat(binding.settingsSafetyPanel, View.SCALE_Y, 0.99f, 1.012f, 1f)
            )
            duration = SETTINGS_CONSOLE_POLISH_DURATION_MS
            start()
        }
    }

    private fun stopSettingsConsolePolish(reset: Boolean = true) {
        settingsConsoleAnimator?.cancel()
        settingsConsoleAnimator = null
        if (_binding == null || !reset) return
        binding.settingsControlGlow.alpha = SETTINGS_CONSOLE_SETTLED_ALPHA
        binding.settingsControlGlow.scaleX = 1f
        binding.settingsControlGlow.scaleY = 1f
        binding.settingsSafetyAnchor.alpha = SETTINGS_SAFETY_ANCHOR_SETTLED_ALPHA
        binding.settingsSafetyAnchor.scaleX = 1f
        binding.settingsSafetyAnchor.scaleY = 1f
        binding.settingsSafetyPanel.scaleX = 1f
        binding.settingsSafetyPanel.scaleY = 1f
    }

    private fun updatePushStatusPolish(pushConfigured: Boolean, granted: Boolean, asked: Boolean) {
        val signature = when {
            !pushConfigured -> "unconfigured"
            granted -> "enabled"
            asked -> "asked"
            else -> "off"
        }
        if (pushStatusSignature == signature) {
            if (pushStatusAnimator != null) return
            settlePushStatusPolish(signature)
            return
        }
        pushStatusSignature = signature
        pushStatusAnimator?.cancel()
        settlePushStatusPolish(signature)
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val peakAlpha = when (signature) {
            "enabled" -> 1f
            "asked" -> 0.78f
            "off" -> 0.68f
            else -> 0.52f
        }
        val peakScale = if (signature == "enabled") 1.16f else 1.08f
        pushStatusAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.pushStatusConsole, View.ALPHA, binding.pushStatusConsole.alpha, 1f, binding.pushStatusConsole.alpha),
                ObjectAnimator.ofFloat(binding.pushStatusSignalPulse, View.ALPHA, binding.pushStatusSignalPulse.alpha, peakAlpha, binding.pushStatusSignalPulse.alpha),
                ObjectAnimator.ofFloat(binding.pushStatusSignalPulse, View.SCALE_X, 0.96f, peakScale, 1f),
                ObjectAnimator.ofFloat(binding.pushStatusSignalPulse, View.SCALE_Y, 0.96f, peakScale, 1f),
                ObjectAnimator.ofFloat(binding.pushStatusStage, View.SCALE_X, 0.994f, 1.012f, 1f),
                ObjectAnimator.ofFloat(binding.pushStatusStage, View.SCALE_Y, 0.994f, 1.012f, 1f)
            )
            duration = PUSH_STATUS_POLISH_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settlePushStatusPolish(signature)
                    if (pushStatusAnimator === animation) pushStatusAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    settlePushStatusPolish(signature)
                    if (pushStatusAnimator === animation) pushStatusAnimator = null
                }
            })
            start()
        }
    }

    private fun settlePushStatusPolish(signature: String? = pushStatusSignature) {
        if (_binding == null) return
        binding.pushStatusStage.scaleX = 1f
        binding.pushStatusStage.scaleY = 1f
        binding.pushStatusConsole.scaleX = 1f
        binding.pushStatusConsole.scaleY = 1f
        binding.pushStatusSignalPulse.scaleX = 1f
        binding.pushStatusSignalPulse.scaleY = 1f
        binding.pushStatusConsole.alpha = when (signature) {
            "unconfigured" -> 0.84f
            else -> 1f
        }
        binding.pushStatusSignalPulse.alpha = when (signature) {
            "enabled" -> 0.82f
            "asked" -> 0.58f
            "off" -> 0.5f
            else -> 0.36f
        }
    }

    private fun stopPushStatusPolish(reset: Boolean = true) {
        pushStatusAnimator?.cancel()
        pushStatusAnimator = null
        if (reset) {
            settlePushStatusPolish()
        }
    }

    override fun onDestroyView() {
        stopSettingsConsolePolish()
        stopPushStatusPolish()
        pushStatusSignature = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val SETTINGS_CONSOLE_POLISH_DURATION_MS = 760L
        const val SETTINGS_CONSOLE_SETTLED_ALPHA = 0.28f
        const val SETTINGS_CONSOLE_PEAK_ALPHA = 0.52f
        const val SETTINGS_SAFETY_ANCHOR_LOW_ALPHA = 0.54f
        const val SETTINGS_SAFETY_ANCHOR_SETTLED_ALPHA = 0.86f
        const val SETTINGS_SAFETY_ANCHOR_PEAK_ALPHA = 0.96f
        const val PUSH_STATUS_POLISH_DURATION_MS = 620L
        const val PUSH_BUTTON_ENABLED_ALPHA = 1f
        const val PUSH_BUTTON_UNCONFIGURED_ALPHA = 0.88f
        const val PUSH_BUTTON_UNCONFIGURED_LABEL_ALPHA = 1f
        const val DEFAULT_FONT_SCALE = 1.0f
        const val SOCIAL_RULES_DIALOG_TAG = "social_casino_rules"
        const val PUSH_PERMISSION_DIALOG_TAG = "push_permission_pre_prompt"
        const val ANALYTICS_CONSENT_DIALOG_TAG = "analytics_consent"
        const val THIRD_PARTY_NOTICES_DIALOG_TAG = "third_party_notices"
    }
}
