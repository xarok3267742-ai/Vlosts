package com.vslot.app.ui.slot

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.vslot.app.AppGraph
import com.vslot.app.BuildConfig
import com.vslot.app.R
import com.vslot.app.databinding.FragmentSlotBinding
import com.vslot.app.data.PlayerState
import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotConfig
import com.vslot.app.game.reelSymbolAt
import com.vslot.app.game.reelStripsFor
import com.vslot.app.game.SlotTheme
import com.vslot.app.game.SpinResult
import com.vslot.app.game.WinningLine
import com.vslot.app.ui.asCoins
import com.vslot.app.ui.dialog.AutoSpinCountDialogFragment
import com.vslot.app.ui.dialog.LowCoinsDialogFragment
import com.vslot.app.ui.dialog.PaytableDialogFragment
import com.vslot.app.ui.dialog.ResultDialogFragment
import com.vslot.app.ui.widget.ReelStripDrawableCache
import com.vslot.app.ui.widget.ReelStripView
import com.vslot.app.ui.widget.clearBoundImageResource
import com.vslot.app.ui.widget.setImageResourceIfChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

class SlotFragment : Fragment() {
    private var _binding: FragmentSlotBinding? = null
    private val binding get() = _binding!!
    private val reelCells = mutableListOf<ImageView>()
    private val reelCellBackdrops = mutableListOf<ImageView>()
    private val reelStopFlashViews = mutableListOf<ImageView>()
    private val reelBrakeViews = mutableListOf<ImageView>()
    private val symbolWinHalos = mutableListOf<ImageView>()
    private val bonusScatterHalos = mutableListOf<ImageView>()
    private val reelSpinColumns = mutableListOf<FrameLayout>()
    private val reelSpinStrips = mutableListOf<ReelStripView>()
    private val reelSpinSymbolViews: List<ReelStripView> get() = reelSpinStrips
    private var reelSpinDrawableCache: ReelStripDrawableCache? = null
    private var settlementRecoveryNoticeShown = false
    private val reelSpinResourceIds = Array(REEL_COUNT) {
        IntArray(REEL_SPIN_STRIP_SYMBOL_COUNT)
    }
    private val transientDrawablePreloads = mutableMapOf<Int, Drawable>()
    private var preloadedSpinConfigId: String? = null
    private var preloadedResultSignature: String? = null
    private var resultDrawablePreloadJob: Job? = null
    private var spinPerformanceHintSession: Closeable? = null
    private val reelMotionStreakViews = mutableListOf<ImageView>()
    private val reelMotionStreakModes = IntArray(REEL_COUNT) { NO_REEL_MOTION_STREAK_MODE }
    private val reelAnticipationBeamViews = mutableListOf<ImageView>()
    private val reelLandingSparkViews = mutableListOf<ImageView>()
    private val reelSpinStopAnimators = mutableMapOf<Int, AnimatorSet>()
    private val reelBrakeAnimators = mutableMapOf<Int, AnimatorSet>()
    private val reelAnticipationBeamAnimators = mutableMapOf<Int, AnimatorSet>()
    private val reelLandingSparkAnimators = mutableMapOf<Int, AnimatorSet>()
    private var reelBrakeSequenceAnimator: AnimatorSet? = null
    private var spinPreviewJob: Job? = null
    private var spinPreviewTargetResult: SpinResult? = null
    private var spinPreviewSlamStopRequested = false
    private var spinPreviewSlamStopRequestedAtMonotonicMs: Long? = null
    private var completedSpinPreviewPresentationId: String? = null
    private val reelSpinInterpolator = LinearInterpolator()
    private val reelAccelerationInterpolator = AccelerateInterpolator(1.32f)
    private val reelDecelerationInterpolator = DecelerateInterpolator(1.18f)
    private val reelStopInterpolator = OvershootInterpolator(0.72f)
    private var spinBlurTranslationAnimator: ObjectAnimator? = null
    private var spinBlurAlphaAnimator: ObjectAnimator? = null
    private var spinEnergyAnimator: AnimatorSet? = null
    private var themeSpinOverlayAnimator: AnimatorSet? = null
    private var cabinetLightsAnimator: AnimatorSet? = null
    private var slotMarqueeGlassAnimator: AnimatorSet? = null
    private var themeAmbientAnimator: AnimatorSet? = null
    private var themeAmbientSignature: String? = null
    private var cabinetLightMode: CabinetLightMode? = null
    private var reelStopAnimator: AnimatorSet? = null
    private var bigWinBannerAnimator: AnimatorSet? = null
    private var winBurstAnimator: AnimatorSet? = null
    private var winGlowAnimator: AnimatorSet? = null
    private var bonusEntryPortalAnimator: AnimatorSet? = null
    private var bonusEntryPortalStaticHideJob: Job? = null
    private var spinReadyGlowAnimator: AnimatorSet? = null
    private var spinImpactAnimator: AnimatorSet? = null
    private var slamStopCueAnimator: AnimatorSet? = null
    private var autoSpinHaloAnimator: AnimatorSet? = null
    private var autoSpinHaloGeneration = 0
    private var activeLinesPulseAnimator: AnimatorSet? = null
    private var balancePulseAnimator: AnimatorSet? = null
    private var slotLevelPulseAnimator: AnimatorSet? = null
    private var totalBetPulseAnimator: AnimatorSet? = null
    private var lastWinCountAnimator: ValueAnimator? = null
    private var lastWinCountRenderAtMs = Long.MIN_VALUE
    private var freeSpinsPulseAnimator: AnimatorSet? = null
    private var freeSpinsRailChargeAnimator: AnimatorSet? = null
    private var freeSpinsModeAnimator: AnimatorSet? = null
    private var freeSpinsStakeLockAnimator: AnimatorSet? = null
    private var freeSpinsStakeLockActive = false
    private var freeSpinsVisualModeActive = false
    private var symbolWinHaloAnimator: AnimatorSet? = null
    private var bonusScatterHaloAnimator: AnimatorSet? = null
    private var reelWindowDepthAnimator: AnimatorSet? = null
    private var reelAnticipationKickAnimator: AnimatorSet? = null
    private var winningPaylineCarouselJob: Job? = null
    private var autoSpinResultDismissJob: Job? = null
    private var inlinePresentationDrawObserver: ViewTreeObserver? = null
    private var inlinePresentationDrawListener: ViewTreeObserver.OnDrawListener? = null
    private var pendingInlinePresentationDrawId: String? = null
    private var pendingLandscapeStepperFallbackTarget: View? = null
    private var lastHighlightedCells: Set<Int> = emptySet()
    private var lastBonusScatterCells: Set<Int> = emptySet()
    private var lastAnimatedPresentationId: String? = null
    private var lastPresentedDialogResult: SpinResult? = null
    private var lastStopAnimatedPresentationId: String? = null
    private var lastStartedSpinFeedbackId: String? = null
    private var lastVisiblePaylineIndex: Int? = null
    private var lastWinningPaylineSignature: String? = null
    private var lastPresentedActiveLines: Int? = null
    private var lastPresentedBalance: Long? = null
    private var lastPresentedSlotLevel: Int? = null
    private var lastPresentedSlotLevelXp: Int? = null
    private var lastPresentedTotalBet: Int? = null
    private var lastPresentedFreeSpins: Int? = null
    private var lastCountedResult: SpinResult? = null
    private var restoredLastWinAmount: Int? = null
    private var slotSoundPlayer: SlotSoundPlayer? = null
    private var hapticsEnabled = true
    private var wasSpinning = false
    private var reducedMotionStopFeedbackPresentationId: String? = null
    private val slotId by lazy { arguments?.getString("slotId").orEmpty() }
    private val viewModel: SlotViewModel by viewModels {
        SlotViewModel.Factory(
            slotId = slotId,
            playerRepository = AppGraph.playerRepository,
            slotRepository = AppGraph.slotRepository,
            slotEngine = AppGraph.slotEngine,
            analyticsTracker = AppGraph.analyticsTracker
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSlotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoredLastWinAmount = savedInstanceState
            ?.takeIf { it.containsKey(KEY_LAST_WIN_AMOUNT) }
            ?.getInt(KEY_LAST_WIN_AMOUNT)
        lastAnimatedPresentationId = savedInstanceState?.getString(KEY_LAST_ANIMATED_PRESENTATION_ID)
        lastStopAnimatedPresentationId = savedInstanceState?.getString(KEY_LAST_STOP_PRESENTATION_ID)
        lastStartedSpinFeedbackId = savedInstanceState?.getString(KEY_LAST_STARTED_SPIN_FEEDBACK_ID)
        slotSoundPlayer = SlotSoundPlayer(requireContext())
        applySlotContentCutoutInsets()
        applyLandscapeReelWindowInsets()
        setupGrid()
        binding.reelSpinStripLayer.doOnLayout {
            prepareReelSpinStripDimensions()
        }
        binding.reelsGrid.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        animateReelWindowDepthMask()
        animateSlotMarqueeGlass()
        binding.activeLinesRailDigits.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        binding.activeLinesRail.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        binding.paylineMarkersOverlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleSlotExitRequest()
                }
            }
        )
        binding.backButton.setOnClickListener { handleSlotExitRequest() }
        binding.betMinusButton.setOnClickListener { viewModel.selectPreviousBet() }
        binding.betPlusButton.setOnClickListener { viewModel.selectNextBet() }
        binding.linesMinusButton.setOnClickListener { viewModel.selectPreviousLines() }
        binding.linesPlusButton.setOnClickListener { viewModel.selectNextLines() }
        binding.maxLinesButton.setOnClickListener { viewModel.selectMaxLines() }
        binding.autoSpinButton.setOnClickListener { handleAutoSpinClick() }
        setupLandscapeStepperTouchFallback()
        setFragmentResultListener(AutoSpinCountDialogFragment.REQUEST_KEY) { _, result ->
            val count = result.getInt(AutoSpinCountDialogFragment.KEY_COUNT)
            viewModel.startAutoSpin(count)
        }
        setFragmentResultListener(ResultDialogFragment.REQUEST_KEY) { _, result ->
            autoSpinResultDismissJob?.cancel()
            autoSpinResultDismissJob = null
            viewModel.onResultDialogDismissed(
                result.getString(ResultDialogFragment.KEY_PRESENTATION_ID).orEmpty()
            )
        }
        setFragmentResultListener(ResultDialogFragment.PRESENTED_REQUEST_KEY) { _, result ->
            viewModel.onResultDialogPresented(
                result.getString(ResultDialogFragment.KEY_PRESENTATION_ID).orEmpty()
            )
        }
        binding.spinButton.setOnClickListener {
            animateSpinButton()
            if (viewModel.uiState.value.isSpinning) {
                viewModel.requestSlamStop()
            } else {
                viewModel.spin()
            }
        }
        binding.paytableButton.setOnClickListener {
            if (showPaytable()) {
                viewModel.onPaytableOpen()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { collectState() }
                launch { collectEvents() }
            }
        }
        maybeStartQaAutoSpin(savedInstanceState)
    }

    override fun onStop() {
        if (activity?.isChangingConfigurations != true) {
            viewModel.pauseAutoSpin()
        }
        stopBackgroundFeedback()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        viewModel.retryPendingSettlementRecovery()
        viewModel.resumeFreeSpinsFeatureIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.uiState.value.lastResult?.let { result ->
            outState.putInt(KEY_LAST_WIN_AMOUNT, result.winAmount)
        }
        lastAnimatedPresentationId?.let { outState.putString(KEY_LAST_ANIMATED_PRESENTATION_ID, it) }
        lastStopAnimatedPresentationId?.let { outState.putString(KEY_LAST_STOP_PRESENTATION_ID, it) }
        lastStartedSpinFeedbackId?.let { outState.putString(KEY_LAST_STARTED_SPIN_FEEDBACK_ID, it) }
        super.onSaveInstanceState(outState)
    }

    private fun applyLandscapeReelWindowInsets() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        val horizontalInset = REEL_WINDOW_LANDSCAPE_HORIZONTAL_INSET_DP.dp()
        val reelWindowLayers = listOf(
            binding.reelCellBackdropLayer,
            binding.reelDepthDividers,
            binding.winGlowOverlay,
            binding.symbolWinHaloLayer,
            binding.bonusScatterHaloLayer,
            binding.reelsGrid,
            binding.reelSpinStripLayer,
            binding.reelMotionStreakLayer,
            binding.reelAnticipationBeamLayer,
            binding.reelLandingSparkLayer,
            binding.reelBrakeLayer,
            binding.reelWindowDepthMask,
            binding.reelApertureShadow,
            binding.slotThemeAmbientOverlay,
            binding.themeSpinOverlay,
            binding.freeSpinsModeOverlay,
            binding.spinEnergyOverlay,
            binding.winningPaylineOverlay,
            binding.spinBlurOverlay,
            binding.reelStopFlashLayer,
            binding.reelGlassOverlay,
            binding.coinBurstOverlay,
            binding.bonusEntryPortalOverlay
        )
        reelWindowLayers.forEach { layer ->
            val params = layer.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            if (params.marginStart == horizontalInset && params.marginEnd == horizontalInset) return@forEach
            params.marginStart = horizontalInset
            params.marginEnd = horizontalInset
            layer.layoutParams = params
        }
    }

    private fun applySlotContentCutoutInsets() {
        val content = binding.slotContent
        val initialLeft = content.paddingLeft
        val initialTop = content.paddingTop
        val initialRight = content.paddingRight
        val initialBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val cutoutInsets = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures() or
                    WindowInsetsCompat.Type.systemBars()
            )
            view.updatePadding(
                left = initialLeft + cutoutInsets.left,
                top = initialTop + cutoutInsets.top,
                right = initialRight + cutoutInsets.right,
                bottom = initialBottom + cutoutInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun popFromSlot(): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.slotFragment) return false
        return navController.popBackStack()
    }

    private fun handleSlotExitRequest() {
        val state = viewModel.uiState.value
        if (state.isSettlementRecoveryPending) {
            viewModel.pauseAutoSpin()
            popFromSlot()
            return
        }
        if (
            state.isSpinStartReserved ||
            state.isSpinning ||
            state.isResultPending ||
            state.pendingPresentationId != null
        ) {
            viewModel.pauseAutoSpin()
            viewModel.requestSlamStop()
            return
        }
        popFromSlot()
    }

    private fun handleAutoSpinClick() {
        val state = viewModel.uiState.value
        if (state.isAutoSpinEnabled) {
            viewModel.stopAutoSpin()
            return
        }
        val freeSpins = state.playerState.freeSpinsForSlot(state.config.id)
        if (freeSpins > 0) {
            viewModel.startAutoSpin(DEFAULT_AUTO_SPIN_COUNT)
            return
        }
        showAutoSpinCountDialog()
    }

    private fun maybeStartQaAutoSpin(savedInstanceState: Bundle?) {
        if (!BuildConfig.QA_ENABLED || savedInstanceState != null) return
        val activity = activity ?: return
        if (!activity.intent.getBooleanExtra(QA_AUTO_SPIN_EXTRA, false)) return
        activity.intent.removeExtra(QA_AUTO_SPIN_EXTRA)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(QA_AUTO_SPIN_START_DELAY_MS)
            if (_binding != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                viewModel.toggleAutoSpin()
            }
        }
    }

    private fun renderAutoSpinControl(state: SlotUiState, freeSpins: Int) {
        val remaining = if (state.isFreeSpinAutoPlay) {
            freeSpins
        } else {
            state.autoSpinsRemaining
        }
        val active = state.isAutoSpinEnabled
        binding.autoSpinStopOverlay.visibility = if (active) View.VISIBLE else View.INVISIBLE
        binding.autoSpinRemainingDigits.visibility = if (active && remaining != null) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        remaining?.let { binding.autoSpinRemainingDigits.setNumber(it.coerceAtLeast(0)) }
        binding.autoSpinButton.contentDescription = when {
            state.isFreeSpinAutoPlay -> getString(R.string.auto_spin_stop_free_spins, freeSpins)
            active && remaining != null -> getString(R.string.auto_spin_stop_remaining, remaining)
            active -> getString(R.string.auto_spin_stop)
            else -> getString(R.string.auto_spin_configure)
        }
        ViewCompat.setStateDescription(binding.autoSpinButton, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLandscapeStepperTouchFallback() {
        // Completed fallback gestures are forwarded to the actual stepper via performClick().
        val fallbackListener = View.OnTouchListener { _, event ->
            handleLandscapeStepperTouchFallback(event)
        }
        listOf(
            binding.root,
            binding.slotControlConsole,
            binding.betPanelImage,
            binding.betPanelMeterGlow,
            binding.lastWinPanelImage,
            binding.lastWinPanelMeterGlow
        ).forEach { view ->
            view.setOnTouchListener(fallbackListener)
        }
    }

    private fun handleLandscapeStepperTouchFallback(event: MotionEvent): Boolean {
        val target = resolveLandscapeStepperFallbackTarget(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingLandscapeStepperFallbackTarget = target
                target?.isPressed = true
                target != null
            }
            MotionEvent.ACTION_UP -> {
                val pendingTarget = pendingLandscapeStepperFallbackTarget
                pendingLandscapeStepperFallbackTarget = null
                pendingTarget?.isPressed = false
                if (pendingTarget != null && target === pendingTarget && pendingTarget.isEnabled) {
                    pendingTarget.performClick()
                    true
                } else {
                    pendingTarget != null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val hadPendingTarget = pendingLandscapeStepperFallbackTarget != null
                pendingLandscapeStepperFallbackTarget?.isPressed = false
                pendingLandscapeStepperFallbackTarget = null
                hadPendingTarget
            }
            else -> pendingLandscapeStepperFallbackTarget != null
        }
    }

    private fun resolveLandscapeStepperFallbackTarget(event: MotionEvent): View? {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return null
        val rootRect = Rect()
        if (!binding.root.getGlobalVisibleRect(rootRect)) return null
        val edgeHitWidth = LANDSCAPE_STEPPER_EDGE_HIT_WIDTH_DP.dp()
        val edgeRange = if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            rootRect.left..(rootRect.left + edgeHitWidth).coerceAtMost(rootRect.right)
        } else {
            (rootRect.right - edgeHitWidth).coerceAtLeast(rootRect.left)..rootRect.right
        }
        val rawX = event.rawX.toInt()
        if (rawX !in edgeRange) return null
        return when {
            binding.betPlusButton.isEnabled && event.isInsideVerticalBandOf(binding.betMinusButton) -> binding.betPlusButton
            binding.linesPlusButton.isEnabled && event.isInsideVerticalBandOf(binding.linesMinusButton) -> binding.linesPlusButton
            else -> null
        }
    }

    private fun MotionEvent.isInsideVerticalBandOf(view: View): Boolean {
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return false
        return rawY.toInt() in rect.top..rect.bottom
    }

    private suspend fun collectState() {
        viewModel.uiState.collect { state ->
            val theme = state.config.theme
            val freeSpins = state.playerState.freeSpinsForSlot(state.config.id)
            val freeSpinModeActive = freeSpins > 0 || (state.isSpinning && state.isCurrentSpinFreeSpin)
            val displayedLineBet = state.pendingResult
                ?.takeIf { state.isSpinning }
                ?.bet
                ?: state.playerState.displayedLineBet(state.config)
            val selectedLines = state.pendingResult
                ?.takeIf { state.isSpinning }
                ?.lines
                ?: state.playerState.displayedLines(state.config)
            preloadSpinPresentationResources(state.config, state.pendingResult)
            slotSoundPlayer?.enabled = state.playerState.soundEnabled
            hapticsEnabled = state.playerState.hapticsEnabled
            updateReelAccessibility(state.isSpinning)
            val spinStarted = state.isSpinning && !wasSpinning
            val spinEnded = !state.isSpinning && wasSpinning
            if (state.isSpinning && state.isReducedMotionStop) {
                reducedMotionStopFeedbackPresentationId = state.spinPresentationId
            }
            if (spinStarted) {
                startSpinPerformanceHint()
                if (state.spinPresentationId != lastStartedSpinFeedbackId) {
                    slotSoundPlayer?.play(SlotSoundCue.SpinStart)
                    lastStartedSpinFeedbackId = state.spinPresentationId
                }
                if (ValueAnimator.areAnimatorsEnabled()) {
                    slotSoundPlayer?.startReelSpinLoop()
                } else {
                    viewModel.requestReducedMotionStop()
                }
            } else if (spinEnded) {
                stopSpinPerformanceHint()
                slotSoundPlayer?.stopReelSpinLoop()
                reducedMotionStopFeedbackPresentationId?.let {
                    slotSoundPlayer?.play(SlotSoundCue.ReelStop, reelIndex = REEL_COUNT - 1)
                    if (hapticsEnabled) {
                        binding.reelsGrid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                reducedMotionStopFeedbackPresentationId = null
            }
            wasSpinning = state.isSpinning
            binding.slotBg.setImageResourceIfChanged(slotBackgroundDrawable(state.config.theme))
            binding.slotMachineFrame.setImageResourceIfChanged(slotMachineFrameDrawable(theme))
            binding.slotTitle.setImageResourceIfChanged(slotTitleDrawable(state.config.theme))
            binding.slotMarqueeGlass.setImageResourceIfChanged(slotMarqueeGlassDrawable(theme))
            binding.slotCabinetLights.setImageResourceIfChanged(slotCabinetLightsDrawable(theme))
            binding.slotCabinetChaseLights.setImageResourceIfChanged(slotCabinetChaseLightsDrawable(theme))
            binding.paylineMarkersOverlay.setImageResourceIfChanged(
                paylineMarkersOverlayDrawable(state.config.theme, selectedLines)
            )
            binding.reelDepthDividers.setImageResourceIfChanged(reelDepthDividersDrawable(theme))
            binding.reelWindowDepthMask.setImageResourceIfChanged(reelWindowDepthMaskDrawable(theme))
            binding.reelApertureShadow.setImageResourceIfChanged(reelApertureShadowDrawable(theme))
            binding.reelGlassOverlay.setImageResourceIfChanged(reelGlassOverlayDrawable(theme))
            binding.slotThemeAmbientOverlay.setImageResourceIfChanged(themeAmbientOverlayDrawable(theme))
            binding.spinDeckGlow.setImageResourceIfChanged(spinDeckGlowDrawable(theme))
            binding.spinButtonReadyGlow.setImageResourceIfChanged(spinButtonReadyGlowDrawable(theme))
            binding.paytableButtonDockGlow.setImageResourceIfChanged(paytableButtonDockGlowDrawable(theme))
            binding.paytableButtonIcon.setImageResourceIfChanged(paytableButtonDrawable(theme))
            binding.paytableButtonLabel.setImageResourceIfChanged(paytableButtonLabelDrawable(theme))
            binding.slotControlConsoleBackplane.setImageResourceIfChanged(slotControlConsoleBackplaneDrawable(theme))
            binding.betPanelImage.setImageResourceIfChanged(betPanelDrawable(theme))
            binding.lastWinPanelImage.setImageResourceIfChanged(betPanelDrawable(theme))
            binding.betPanelMeterGlow.setImageResourceIfChanged(slotControlMeterGlowDrawable(theme))
            binding.lastWinPanelMeterGlow.setImageResourceIfChanged(slotControlMeterGlowDrawable(theme))
            binding.activeLinesRailImage.setImageResourceIfChanged(activeLinesBadgeDrawable(theme))
            binding.freeSpinsRailImage.setImageResourceIfChanged(freeSpinsBadgeDrawable(theme))
            binding.betLabel.setImageResourceIfChanged(betLabelDrawable(theme))
            binding.linesLabel.setImageResourceIfChanged(linesLabelDrawable(theme))
            binding.activeLinesRailLabel.setImageResourceIfChanged(linesLabelDrawable(theme))
            binding.totalBetLabel.setImageResourceIfChanged(totalBetLabelDrawable(theme))
            binding.lastWinLabel.setImageResourceIfChanged(lastWinLabelDrawable(theme))
            val reelCellBackdrop = reelCellBackdropDrawable(theme)
            reelCellBackdrops.forEach { it.setImageResourceIfChanged(reelCellBackdrop) }
            binding.slotTitle.contentDescription = state.config.name
            binding.slotBalanceDigits.setNumber(state.playerState.coinsBalance)
            binding.slotBalanceDigits.contentDescription = "${getString(R.string.balance)} ${state.playerState.coinsBalance.asCoins()}"
            animateBalanceChangeIfNeeded(state.playerState.coinsBalance)
            bindSlotLevelState(state.playerState)
            binding.betDigits.setNumber(displayedLineBet)
            binding.betDigits.contentDescription = "${getString(R.string.line_bet)} ${displayedLineBet.asCoins()}"
            binding.linesDigits.setNumber(selectedLines)
            binding.linesDigits.contentDescription = activePaylinesDescription(selectedLines)
            binding.activeLinesRailDigits.setNumber(selectedLines)
            val totalBet = displayedLineBet * selectedLines
            binding.totalBetDigits.setNumber(totalBet)
            binding.totalBetDigits.contentDescription = "${getString(R.string.total_bet)} ${totalBet.asCoins()}"
            animateTotalBetChangeIfNeeded(totalBet)
            binding.freeSpinsDigits.setNumber(freeSpins)
            binding.freeSpinsRail.alpha = if (freeSpinModeActive) 1f else 0.72f
            binding.freeSpinsRail.contentDescription = getString(R.string.free_spins_remaining, freeSpins)
            binding.spinButton.setImageResourceIfChanged(spinButtonDrawable(state.config.theme, freeSpinModeActive))
            binding.spinButton.contentDescription = getString(
                when {
                    state.isSlamStopping -> R.string.spin_slam_stopping
                    state.isSpinning && !state.isAutoSpinEnabled -> R.string.spin_slam_stop
                    freeSpinModeActive -> R.string.spin_free_spins
                    else -> R.string.spin
                }
            )
            freeSpinsVisualModeActive = freeSpinModeActive
            animateFreeSpinsChangeIfNeeded(freeSpins)
            updateFreeSpinsRailCharge(freeSpinModeActive)
            updateFreeSpinsModeOverlay(freeSpinModeActive)
            updateFreeSpinsStakeLockOverlay(freeSpinModeActive)
            bindLastWin(state.lastResult, state.isSpinning)
            bindSettlementRecoveryNotice(state.isSettlementRecoveryPending)
            val controlsEnabled = !state.isSpinStartReserved &&
                !state.isSpinning &&
                !state.isResultPending &&
                !state.isSettlementRecoveryPending &&
                !state.isAutoSpinEnabled &&
                state.pendingPresentationId == null
            val autoSpinControlEnabled = !state.isSpinStartReserved &&
                !state.isResultPending &&
                !state.isSettlementRecoveryPending &&
                state.pendingPresentationId == null &&
                (state.isAutoSpinEnabled || !state.isSpinning)
            val stakeControlsEnabled = controlsEnabled && !freeSpinModeActive
            val selectedBetIndex = state.config.bets.indexOf(state.playerState.selectedBet).coerceAtLeast(0)
            binding.spinButton.isEnabled = !state.isSpinStartReserved &&
                !state.isResultPending &&
                !state.isSettlementRecoveryPending &&
                state.pendingPresentationId == null &&
                !state.isSlamStopping &&
                (!state.isSpinning || !state.isAutoSpinEnabled)
            binding.paytableButton.isEnabled = controlsEnabled
            val paytableAlpha = if (controlsEnabled) {
                PAYTABLE_CONTROL_ENABLED_ALPHA
            } else {
                PAYTABLE_CONTROL_DISABLED_ALPHA
            }
            binding.paytableButtonIcon.alpha = paytableAlpha
            binding.paytableButtonLabel.alpha = paytableAlpha
            binding.autoSpinButton.isEnabled = autoSpinControlEnabled
            binding.autoSpinButton.setImageResourceIfChanged(autoSpinButtonDrawable(theme, state.isAutoSpinEnabled))
            renderAutoSpinControl(state, freeSpins)
            updateAutoSpinActiveHalo(state.isAutoSpinEnabled)
            binding.maxLinesButtonIcon.setImageResourceIfChanged(maxLinesButtonDrawable(theme))
            binding.betMinusButton.setImageResourceIfChanged(betMinusButtonDrawable(theme))
            binding.betPlusButton.setImageResourceIfChanged(betPlusButtonDrawable(theme))
            binding.linesMinusButton.setImageResourceIfChanged(betMinusButtonDrawable(theme))
            binding.linesPlusButton.setImageResourceIfChanged(betPlusButtonDrawable(theme))
            binding.betMinusButton.isEnabled = stakeControlsEnabled && selectedBetIndex > 0
            binding.betPlusButton.isEnabled = stakeControlsEnabled && selectedBetIndex < state.config.bets.lastIndex
            binding.linesMinusButton.isEnabled = stakeControlsEnabled && selectedLines > PlayerState.MIN_LINES
            binding.linesPlusButton.isEnabled = stakeControlsEnabled && selectedLines < state.config.paylines
            binding.maxLinesButton.isEnabled = stakeControlsEnabled && selectedLines < state.config.paylines
            binding.maxLinesButtonIcon.isEnabled = binding.maxLinesButton.isEnabled
            animateActiveLinesChangeIfNeeded(selectedLines)
            updateSpinReadyGlow(controlsEnabled)
            updateSlamStopCue(
                state.isSpinning &&
                    !state.isSlamStopping &&
                    !state.isAutoSpinEnabled &&
                    !state.isResultPending
            )
            updateCabinetLights(if (state.isSpinning) CabinetLightMode.Spinning else CabinetLightMode.Idle)
            updateThemeAmbientOverlay(theme, isSpinning = state.isSpinning, freeSpinsActive = freeSpinModeActive)

        if (state.isSpinning) {
            lastHighlightedCells = emptySet()
            lastBonusScatterCells = emptySet()
            hideReelStopFlashLayer(immediate = true)
            hideReelBrakeLayer(immediate = true)
            hideBigWinBanner(immediate = true)
            hideThemeWinBurst(immediate = true)
            hideBonusEntryPortal(immediate = true)
            hideWinningPaylineOverlay(immediate = true)
            startSpinPreview(
                config = state.config,
                targetResult = state.pendingResult,
                presentationId = state.spinPresentationId,
                startedAtMonotonicMs = state.spinStartedAtMonotonicMs,
                stopRequestedAtMonotonicMs = state.spinStopRequestedAtMonotonicMs,
                slamStopping = state.isSlamStopping,
                reducedMotion = state.isReducedMotionStop
            )
        } else {
            stopSpinPreview()
            val reels = state.lastResult?.reels ?: initialSlotReels(state.config)
            val highlightedCells = state.lastResult?.let(::highlightedCellIndexes).orEmpty()
            val bonusScatterCells = state.lastResult?.let(::bonusScatterCellIndexes).orEmpty()
            renderReels(state.config.theme, reels, highlightedCells, bonusScatterCells)
            updateSpinResultAccessibility(state.lastResult, announce = spinEnded)
            animateReelStopIfNeeded(state.lastResult, state.lastResultPresentationId)
            renderWinningPaylineOverlay(state.config.theme, state.lastResult)
            animateWinResultIfNeeded(state.lastResult, state.lastResultPresentationId)
            if (state.lastResult?.let(SlotResultPresentationPolicy::shouldShowResultDialog) != true) {
                state.pendingPresentationId?.let(::acknowledgeInlinePresentationAfterNextDraw)
            }
            showPendingResultDialogIfNeeded(state)
            resumeRestoredResultDialogAutoDismissIfNeeded(state)
        }
        }
    }

    private fun bindSettlementRecoveryNotice(isPending: Boolean) {
        binding.settlementRecoveryNotice.isVisible = isPending
        if (!isPending) {
            settlementRecoveryNoticeShown = false
            return
        }
        if (settlementRecoveryNoticeShown) return
        settlementRecoveryNoticeShown = true
        Toast.makeText(
            requireContext(),
            R.string.slot_settlement_recovery_notice,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateReelAccessibility(isSpinning: Boolean) {
        binding.reelsGrid.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (isSpinning) {
            binding.reelsGrid.contentDescription = getString(R.string.slot_reels_accessibility)
            ViewCompat.setStateDescription(
                binding.reelsGrid,
                getString(R.string.slot_reels_spinning)
            )
        }
    }

    private fun updateSpinResultAccessibility(result: SpinResult?, announce: Boolean) {
        val resultDescription = result?.let { spinResult ->
            when (spinResult.resultType) {
                ResultType.Lose -> getString(R.string.slot_result_loss_announcement)
                ResultType.Win -> getString(
                    R.string.slot_result_win_announcement,
                    spinResult.winAmount.asCoins(),
                    spinResult.winningLines.size
                )
                ResultType.Bonus -> getString(
                    R.string.slot_result_bonus_announcement,
                    spinResult.winAmount.asCoins(),
                    spinResult.freeSpinsAwarded
                )
            }
        }
        ViewCompat.setStateDescription(binding.reelsGrid, resultDescription)
        if (announce && resultDescription != null) {
            binding.reelsGrid.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun paylineMarkersOverlayDrawable(theme: SlotTheme, selectedLines: Int): Int {
        val drawables = paylineMarkerDrawables(theme)
        val index = selectedLines.coerceIn(1, drawables.size) - 1
        return drawables[index]
    }

    private fun paylineMarkerDrawables(theme: SlotTheme): IntArray {
        return when (theme) {
            SlotTheme.Roman -> ROMAN_PAYLINE_MARKER_DRAWABLES
            SlotTheme.Neon -> NEON_PAYLINE_MARKER_DRAWABLES
            SlotTheme.Pharaoh -> PHARAOH_PAYLINE_MARKER_DRAWABLES
            SlotTheme.Ocean -> OCEAN_PAYLINE_MARKER_DRAWABLES
            SlotTheme.Violet -> VIOLET_PAYLINE_MARKER_DRAWABLES
        }
    }

    private fun slotBackgroundDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Roman -> R.drawable.rr_bg
            SlotTheme.Neon -> R.drawable.nn_bg
            SlotTheme.Pharaoh -> R.drawable.pg_bg
            SlotTheme.Ocean -> R.drawable.op_bg
            SlotTheme.Violet -> R.drawable.vf_bg
        }
    }

    private fun slotTitleDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Roman -> R.drawable.title_roman_reels
            SlotTheme.Neon -> R.drawable.title_neon_nights
            SlotTheme.Pharaoh -> R.drawable.title_pharaoh_gold
            SlotTheme.Ocean -> R.drawable.title_ocean_pearl
            SlotTheme.Violet -> R.drawable.title_violet_fortune
        }
    }

    private fun activePaylinesDescription(lines: Int): String {
        val normalizedLines = lines.coerceIn(PlayerState.MIN_LINES, VIOLET_PAYLINE_MARKER_DRAWABLES.size)
        val stringRes = when {
            normalizedLines % 100 in 11..14 -> R.string.slot_active_paylines_many
            normalizedLines % 10 == 1 -> R.string.slot_active_paylines_one
            normalizedLines % 10 in 2..4 -> R.string.slot_active_paylines_few
            else -> R.string.slot_active_paylines_many
        }
        return getString(stringRes, normalizedLines)
    }

    private fun PlayerState.displayedLineBet(config: SlotConfig): Int {
        val rawBet = if (freeSpinsForSlot(config.id) > 0) {
            freeSpinBetForSlot(config.id).takeIf { it in config.bets } ?: selectedBet
        } else {
            selectedBet
        }
        return if (rawBet in config.bets) rawBet else config.bets.first()
    }

    private fun PlayerState.displayedLines(config: SlotConfig): Int {
        val rawLines = if (freeSpinsForSlot(config.id) > 0) {
            freeSpinLinesForSlot(config.id).takeIf { it in PlayerState.MIN_LINES..config.paylines } ?: selectedLines
        } else {
            selectedLines
        }
        return rawLines.coerceIn(PlayerState.MIN_LINES, config.paylines)
    }

    private fun spinButtonDrawable(theme: SlotTheme, hasFreeSpins: Boolean): Int {
        return when {
            theme == SlotTheme.Roman && hasFreeSpins -> R.drawable.spin_button_roman_free_spins_selector
            theme == SlotTheme.Roman -> R.drawable.spin_button_roman_selector
            theme == SlotTheme.Neon && hasFreeSpins -> R.drawable.spin_button_neon_free_spins_selector
            theme == SlotTheme.Neon -> R.drawable.spin_button_neon_selector
            theme == SlotTheme.Pharaoh && hasFreeSpins -> R.drawable.spin_button_pharaoh_free_spins_selector
            theme == SlotTheme.Pharaoh -> R.drawable.spin_button_pharaoh_selector
            theme == SlotTheme.Ocean && hasFreeSpins -> R.drawable.spin_button_ocean_free_spins_selector
            theme == SlotTheme.Ocean -> R.drawable.spin_button_ocean_selector
            hasFreeSpins -> R.drawable.spin_button_violet_free_spins_selector
            else -> R.drawable.spin_button_violet_selector
        }
    }

    private fun themedChromeDrawable(
        theme: SlotTheme,
        violet: Int,
        roman: Int,
        neon: Int,
        pharaoh: Int,
        ocean: Int
    ): Int {
        return when (theme) {
            SlotTheme.Violet -> violet
            SlotTheme.Roman -> roman
            SlotTheme.Neon -> neon
            SlotTheme.Pharaoh -> pharaoh
            SlotTheme.Ocean -> ocean
        }
    }

    private fun slotMachineFrameDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_machine_frame_violet,
            R.drawable.slot_machine_frame_roman,
            R.drawable.slot_machine_frame_neon,
            R.drawable.slot_machine_frame_pharaoh,
            R.drawable.slot_machine_frame_ocean
        )
    }

    private fun slotMarqueeGlassDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_marquee_glass,
            R.drawable.slot_marquee_glass_roman,
            R.drawable.slot_marquee_glass_neon,
            R.drawable.slot_marquee_glass_pharaoh,
            R.drawable.slot_marquee_glass_ocean
        )
    }

    private fun slotCabinetLightsDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_cabinet_lights,
            R.drawable.slot_cabinet_lights_roman,
            R.drawable.slot_cabinet_lights_neon,
            R.drawable.slot_cabinet_lights_pharaoh,
            R.drawable.slot_cabinet_lights_ocean
        )
    }

    private fun slotCabinetChaseLightsDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_cabinet_chase_lights,
            R.drawable.slot_cabinet_chase_lights_roman,
            R.drawable.slot_cabinet_chase_lights_neon,
            R.drawable.slot_cabinet_chase_lights_pharaoh,
            R.drawable.slot_cabinet_chase_lights_ocean
        )
    }

    private fun reelDepthDividersDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_depth_dividers,
            R.drawable.reel_depth_dividers_roman,
            R.drawable.reel_depth_dividers_neon,
            R.drawable.reel_depth_dividers_pharaoh,
            R.drawable.reel_depth_dividers_ocean
        )
    }

    private fun reelWindowDepthMaskDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_window_depth_mask,
            R.drawable.reel_window_depth_mask_roman,
            R.drawable.reel_window_depth_mask_neon,
            R.drawable.reel_window_depth_mask_pharaoh,
            R.drawable.reel_window_depth_mask_ocean
        )
    }

    private fun reelApertureShadowDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_aperture_shadow,
            R.drawable.reel_aperture_shadow_roman,
            R.drawable.reel_aperture_shadow_neon,
            R.drawable.reel_aperture_shadow_pharaoh,
            R.drawable.reel_aperture_shadow_ocean
        )
    }

    private fun reelGlassOverlayDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_glass_overlay_violet,
            R.drawable.reel_glass_overlay_roman,
            R.drawable.reel_glass_overlay_neon,
            R.drawable.reel_glass_overlay_pharaoh,
            R.drawable.reel_glass_overlay_ocean
        )
    }

    private fun reelSpinBlurDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_spin_blur_violet,
            R.drawable.reel_spin_blur_roman,
            R.drawable.reel_spin_blur_neon,
            R.drawable.reel_spin_blur_pharaoh,
            R.drawable.reel_spin_blur_ocean
        )
    }

    private fun spinEnergyOverlayDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_spin_energy_rim_violet,
            R.drawable.reel_spin_energy_rim_roman,
            R.drawable.reel_spin_energy_rim_neon,
            R.drawable.reel_spin_energy_rim_pharaoh,
            R.drawable.reel_spin_energy_rim_ocean
        )
    }

    private fun winGlowSpriteDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.win_glow_sprite_violet,
            R.drawable.win_glow_sprite_roman,
            R.drawable.win_glow_sprite_neon,
            R.drawable.win_glow_sprite_pharaoh,
            R.drawable.win_glow_sprite_ocean
        )
    }

    private fun bigWinBannerDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_big_win_banner_violet,
            R.drawable.slot_big_win_banner_roman,
            R.drawable.slot_big_win_banner_neon,
            R.drawable.slot_big_win_banner_pharaoh,
            R.drawable.slot_big_win_banner_ocean
        )
    }

    private fun bonusFreeSpinsBannerDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_bonus_free_spins_banner_violet,
            R.drawable.slot_bonus_free_spins_banner_roman,
            R.drawable.slot_bonus_free_spins_banner_neon,
            R.drawable.slot_bonus_free_spins_banner_pharaoh,
            R.drawable.slot_bonus_free_spins_banner_ocean
        )
    }

    private fun bonusEntryPortalDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.bonus_entry_portal_violet,
            R.drawable.bonus_entry_portal_roman,
            R.drawable.bonus_entry_portal_neon,
            R.drawable.bonus_entry_portal_pharaoh,
            R.drawable.bonus_entry_portal_ocean
        )
    }

    private fun themeAmbientOverlayDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Violet -> R.drawable.theme_ambient_overlay_violet
            SlotTheme.Roman -> R.drawable.theme_ambient_overlay_roman
            SlotTheme.Neon -> R.drawable.theme_ambient_overlay_neon
            SlotTheme.Pharaoh -> R.drawable.theme_ambient_overlay_pharaoh
            SlotTheme.Ocean -> R.drawable.theme_ambient_overlay_ocean
        }
    }

    private fun themeWinBurstDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Violet -> R.drawable.theme_win_burst_violet
            SlotTheme.Roman -> R.drawable.theme_win_burst_roman
            SlotTheme.Neon -> R.drawable.theme_win_burst_neon
            SlotTheme.Pharaoh -> R.drawable.theme_win_burst_pharaoh
            SlotTheme.Ocean -> R.drawable.theme_win_burst_ocean
        }
    }

    private fun themeSpinOverlayDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.theme_spin_overlay_violet,
            R.drawable.theme_spin_overlay_roman,
            R.drawable.theme_spin_overlay_neon,
            R.drawable.theme_spin_overlay_pharaoh,
            R.drawable.theme_spin_overlay_ocean
        )
    }

    private fun reelBrakeClampDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_brake_clamp,
            R.drawable.reel_brake_clamp_roman,
            R.drawable.reel_brake_clamp_neon,
            R.drawable.reel_brake_clamp_pharaoh,
            R.drawable.reel_brake_clamp_ocean
        )
    }

    private fun reelStopFlashDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_stop_flash_violet,
            R.drawable.reel_stop_flash_roman,
            R.drawable.reel_stop_flash_neon,
            R.drawable.reel_stop_flash_pharaoh,
            R.drawable.reel_stop_flash_ocean
        )
    }

    private fun symbolWinHaloDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.symbol_win_halo_violet,
            R.drawable.symbol_win_halo_roman,
            R.drawable.symbol_win_halo_neon,
            R.drawable.symbol_win_halo_pharaoh,
            R.drawable.symbol_win_halo_ocean
        )
    }

    private fun bonusScatterHaloDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.symbol_bonus_scatter_halo_violet,
            R.drawable.symbol_bonus_scatter_halo_roman,
            R.drawable.symbol_bonus_scatter_halo_neon,
            R.drawable.symbol_bonus_scatter_halo_pharaoh,
            R.drawable.symbol_bonus_scatter_halo_ocean
        )
    }

    private fun reelMotionStreakDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_motion_streak,
            R.drawable.reel_motion_streak_roman,
            R.drawable.reel_motion_streak_neon,
            R.drawable.reel_motion_streak_pharaoh,
            R.drawable.reel_motion_streak_ocean
        )
    }

    private fun reelAnticipationBeamDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_anticipation_beam_violet,
            R.drawable.reel_anticipation_beam_roman,
            R.drawable.reel_anticipation_beam_neon,
            R.drawable.reel_anticipation_beam_pharaoh,
            R.drawable.reel_anticipation_beam_ocean
        )
    }

    private fun reelLandingSparkDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_landing_spark_violet,
            R.drawable.reel_landing_spark_roman,
            R.drawable.reel_landing_spark_neon,
            R.drawable.reel_landing_spark_pharaoh,
            R.drawable.reel_landing_spark_ocean
        )
    }

    private fun freeSpinsModeOverlayDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.free_spins_mode_overlay_violet,
            R.drawable.free_spins_mode_overlay_roman,
            R.drawable.free_spins_mode_overlay_neon,
            R.drawable.free_spins_mode_overlay_pharaoh,
            R.drawable.free_spins_mode_overlay_ocean
        )
    }

    private fun spinDeckGlowDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_spin_deck_glow_violet,
            R.drawable.slot_spin_deck_glow_roman,
            R.drawable.slot_spin_deck_glow_neon,
            R.drawable.slot_spin_deck_glow_pharaoh,
            R.drawable.slot_spin_deck_glow_ocean
        )
    }

    private fun spinButtonReadyGlowDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.spin_button_ready_glow_violet,
            R.drawable.spin_button_ready_glow_roman,
            R.drawable.spin_button_ready_glow_neon,
            R.drawable.spin_button_ready_glow_pharaoh,
            R.drawable.spin_button_ready_glow_ocean
        )
    }

    private fun spinImpactFlashDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.spin_impact_flash,
            R.drawable.spin_impact_flash_roman,
            R.drawable.spin_impact_flash_neon,
            R.drawable.spin_impact_flash_pharaoh,
            R.drawable.spin_impact_flash_ocean
        )
    }

    private fun slamStopCueDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slam_stop_cue_violet,
            R.drawable.slam_stop_cue_roman,
            R.drawable.slam_stop_cue_neon,
            R.drawable.slam_stop_cue_pharaoh,
            R.drawable.slam_stop_cue_ocean
        )
    }

    private fun paytableButtonDockGlowDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_paytable_dock_glow_violet,
            R.drawable.slot_paytable_dock_glow_roman,
            R.drawable.slot_paytable_dock_glow_neon,
            R.drawable.slot_paytable_dock_glow_pharaoh,
            R.drawable.slot_paytable_dock_glow_ocean
        )
    }

    private fun paytableButtonDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.paytable_button,
            R.drawable.paytable_button_roman,
            R.drawable.paytable_button_neon,
            R.drawable.paytable_button_pharaoh,
            R.drawable.paytable_button_ocean
        )
    }

    private fun paytableButtonLabelDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.label_paytable_button,
            R.drawable.label_paytable_button_roman,
            R.drawable.label_paytable_button_neon,
            R.drawable.label_paytable_button_pharaoh,
            R.drawable.label_paytable_button_ocean
        )
    }

    private fun slotControlConsoleBackplaneDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_control_console_backplane_violet,
            R.drawable.slot_control_console_backplane_roman,
            R.drawable.slot_control_console_backplane_neon,
            R.drawable.slot_control_console_backplane_pharaoh,
            R.drawable.slot_control_console_backplane_ocean
        )
    }

    private fun totalBetLinkPulseDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.total_bet_link_pulse,
            R.drawable.total_bet_link_pulse_roman,
            R.drawable.total_bet_link_pulse_neon,
            R.drawable.total_bet_link_pulse_pharaoh,
            R.drawable.total_bet_link_pulse_ocean
        )
    }

    private fun betPanelDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.bet_panel,
            R.drawable.bet_panel_roman,
            R.drawable.bet_panel_neon,
            R.drawable.bet_panel_pharaoh,
            R.drawable.bet_panel_ocean
        )
    }

    private fun betLabelDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.label_bet,
            R.drawable.label_bet_roman,
            R.drawable.label_bet_neon,
            R.drawable.label_bet_pharaoh,
            R.drawable.label_bet_ocean
        )
    }

    private fun linesLabelDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.label_lines,
            R.drawable.label_lines_roman,
            R.drawable.label_lines_neon,
            R.drawable.label_lines_pharaoh,
            R.drawable.label_lines_ocean
        )
    }

    private fun totalBetLabelDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.label_total_bet,
            R.drawable.label_total_bet_roman,
            R.drawable.label_total_bet_neon,
            R.drawable.label_total_bet_pharaoh,
            R.drawable.label_total_bet_ocean
        )
    }

    private fun lastWinLabelDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.label_last_win,
            R.drawable.label_last_win_roman,
            R.drawable.label_last_win_neon,
            R.drawable.label_last_win_pharaoh,
            R.drawable.label_last_win_ocean
        )
    }

    private fun slotControlMeterGlowDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.slot_control_meter_glow,
            R.drawable.slot_control_meter_glow_roman,
            R.drawable.slot_control_meter_glow_neon,
            R.drawable.slot_control_meter_glow_pharaoh,
            R.drawable.slot_control_meter_glow_ocean
        )
    }

    private fun activeLinesBadgeDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.active_lines_badge,
            R.drawable.active_lines_badge_roman,
            R.drawable.active_lines_badge_neon,
            R.drawable.active_lines_badge_pharaoh,
            R.drawable.active_lines_badge_ocean
        )
    }

    private fun freeSpinsBadgeDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.free_spins_badge,
            R.drawable.free_spins_badge_roman,
            R.drawable.free_spins_badge_neon,
            R.drawable.free_spins_badge_pharaoh,
            R.drawable.free_spins_badge_ocean
        )
    }

    private fun freeSpinsRailChargeDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.free_spins_rail_charge,
            R.drawable.free_spins_rail_charge_roman,
            R.drawable.free_spins_rail_charge_neon,
            R.drawable.free_spins_rail_charge_pharaoh,
            R.drawable.free_spins_rail_charge_ocean
        )
    }

    private fun freeSpinsStakeLockOverlayDrawable(theme: SlotTheme): Int {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) {
            themedChromeDrawable(
                theme,
                R.drawable.free_spins_stake_lock_overlay_violet_land,
                R.drawable.free_spins_stake_lock_overlay_roman_land,
                R.drawable.free_spins_stake_lock_overlay_neon_land,
                R.drawable.free_spins_stake_lock_overlay_pharaoh_land,
                R.drawable.free_spins_stake_lock_overlay_ocean_land
            )
        } else {
            themedChromeDrawable(
                theme,
                R.drawable.free_spins_stake_lock_overlay_violet,
                R.drawable.free_spins_stake_lock_overlay_roman,
                R.drawable.free_spins_stake_lock_overlay_neon,
                R.drawable.free_spins_stake_lock_overlay_pharaoh,
                R.drawable.free_spins_stake_lock_overlay_ocean
            )
        }
    }

    private fun reelCellBackdropDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.reel_cell_backdrop,
            R.drawable.reel_cell_backdrop_roman,
            R.drawable.reel_cell_backdrop_neon,
            R.drawable.reel_cell_backdrop_pharaoh,
            R.drawable.reel_cell_backdrop_ocean
        )
    }

    private fun autoSpinActiveHaloDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.auto_spin_active_halo,
            R.drawable.auto_spin_active_halo_roman,
            R.drawable.auto_spin_active_halo_neon,
            R.drawable.auto_spin_active_halo_pharaoh,
            R.drawable.auto_spin_active_halo_ocean
        )
    }

    private fun autoSpinButtonDrawable(theme: SlotTheme, active: Boolean): Int {
        return when {
            theme == SlotTheme.Roman && active -> R.drawable.btn_autospin_roman_active_selector
            theme == SlotTheme.Roman -> R.drawable.btn_autospin_roman_selector
            theme == SlotTheme.Neon && active -> R.drawable.btn_autospin_neon_active_selector
            theme == SlotTheme.Neon -> R.drawable.btn_autospin_neon_selector
            theme == SlotTheme.Pharaoh && active -> R.drawable.btn_autospin_pharaoh_active_selector
            theme == SlotTheme.Pharaoh -> R.drawable.btn_autospin_pharaoh_selector
            theme == SlotTheme.Ocean && active -> R.drawable.btn_autospin_ocean_active_selector
            theme == SlotTheme.Ocean -> R.drawable.btn_autospin_ocean_selector
            active -> R.drawable.btn_autospin_active_selector
            else -> R.drawable.btn_autospin_selector
        }
    }

    private fun maxLinesButtonDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.btn_max_lines_selector,
            R.drawable.btn_max_lines_roman_selector,
            R.drawable.btn_max_lines_neon_selector,
            R.drawable.btn_max_lines_pharaoh_selector,
            R.drawable.btn_max_lines_ocean_selector
        )
    }

    private fun betMinusButtonDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.btn_bet_minus_selector,
            R.drawable.btn_bet_minus_roman_selector,
            R.drawable.btn_bet_minus_neon_selector,
            R.drawable.btn_bet_minus_pharaoh_selector,
            R.drawable.btn_bet_minus_ocean_selector
        )
    }

    private fun betPlusButtonDrawable(theme: SlotTheme): Int {
        return themedChromeDrawable(
            theme,
            R.drawable.btn_bet_plus_selector,
            R.drawable.btn_bet_plus_roman_selector,
            R.drawable.btn_bet_plus_neon_selector,
            R.drawable.btn_bet_plus_pharaoh_selector,
            R.drawable.btn_bet_plus_ocean_selector
        )
    }

    private fun animateActiveLinesChangeIfNeeded(selectedLines: Int) {
        val previousLines = lastPresentedActiveLines
        lastPresentedActiveLines = selectedLines
        if (previousLines == null || previousLines == selectedLines) {
            settleActiveLinesPulseTargets()
            return
        }

        activeLinesPulseAnimator?.cancel()
        settleActiveLinesPulseTargets()
        if (!ValueAnimator.areAnimatorsEnabled()) return

        activeLinesPulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.paylineMarkersOverlay, View.ALPHA, 0.54f, 1f, 0.84f, 1f),
                ObjectAnimator.ofFloat(binding.activeLinesRail, View.SCALE_X, 1f, 1.1f, 1f),
                ObjectAnimator.ofFloat(binding.activeLinesRail, View.SCALE_Y, 1f, 1.1f, 1f),
                ObjectAnimator.ofFloat(binding.linesDigits, View.SCALE_X, 1f, 1.08f, 1f),
                ObjectAnimator.ofFloat(binding.linesDigits, View.SCALE_Y, 1f, 1.08f, 1f),
                ObjectAnimator.ofFloat(binding.activeLinesRailDigits, View.SCALE_X, 1f, 1.08f, 1f),
                ObjectAnimator.ofFloat(binding.activeLinesRailDigits, View.SCALE_Y, 1f, 1.08f, 1f)
            )
            duration = ACTIVE_LINES_PULSE_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleActiveLinesPulseTargets()
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleActiveLinesPulseTargets()
                }
            })
            start()
        }
    }

    private fun settleActiveLinesPulseTargets() {
        val binding = _binding ?: return
        binding.paylineMarkersOverlay.alpha = 1f
        binding.activeLinesRail.scaleX = 1f
        binding.activeLinesRail.scaleY = 1f
        binding.linesDigits.scaleX = 1f
        binding.linesDigits.scaleY = 1f
        binding.activeLinesRailDigits.scaleX = 1f
        binding.activeLinesRailDigits.scaleY = 1f
    }

    private fun animateBalanceChangeIfNeeded(balance: Long) {
        val previousBalance = lastPresentedBalance
        lastPresentedBalance = balance
        if (previousBalance == null || previousBalance == balance) {
            settleBalancePulseTargets()
            return
        }

        balancePulseAnimator?.cancel()
        settleBalancePulseTargets()
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val increased = balance > previousBalance
        val panelPeakScale = if (increased) 1.045f else 0.982f
        val digitPeakScale = if (increased) 1.1f else 0.965f
        val coinPeakScale = if (increased) 1.16f else 0.94f
        val glowDipAlpha = if (increased) 0.52f else 0.64f

        balancePulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.slotBalancePanel, View.SCALE_X, 1f, panelPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotBalancePanel, View.SCALE_Y, 1f, panelPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotBalanceDigits, View.SCALE_X, 1f, digitPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotBalanceDigits, View.SCALE_Y, 1f, digitPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotBalanceCoin, View.SCALE_X, 1f, coinPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotBalanceCoin, View.SCALE_Y, 1f, coinPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotBalanceMeterGlow, View.ALPHA, 1f, glowDipAlpha, 1f)
            )
            duration = BALANCE_CHANGE_PULSE_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleBalancePulseTargets()
                    if (balancePulseAnimator === animation) balancePulseAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleBalancePulseTargets()
                    if (balancePulseAnimator === animation) balancePulseAnimator = null
                }
            })
            start()
        }
    }

    private fun settleBalancePulseTargets() {
        val binding = _binding ?: return
        binding.slotBalancePanel.scaleX = 1f
        binding.slotBalancePanel.scaleY = 1f
        binding.slotBalanceDigits.scaleX = 1f
        binding.slotBalanceDigits.scaleY = 1f
        binding.slotBalanceCoin.scaleX = 1f
        binding.slotBalanceCoin.scaleY = 1f
        binding.slotBalanceMeterGlow.alpha = 1f
    }

    private fun bindSlotLevelState(state: PlayerState) {
        val requiredXp = state.xpForCurrentLevel
        val progress = if (requiredXp <= 0) {
            1f
        } else {
            state.xpInCurrentLevel.toFloat()
                .div(requiredXp)
                .coerceIn(0f, 1f)
        }
        binding.slotLevelDigits.setNumber(state.playerLevel)
        binding.slotLevelPanel.contentDescription = getString(
            R.string.player_level_accessibility,
            state.playerLevel,
            state.xpInCurrentLevel,
            state.xpForCurrentLevel
        )
        binding.slotLevelXpProgressFill.pivotX = 0f
        binding.slotLevelXpProgressFill.pivotY = binding.slotLevelXpProgressFill.height / 2f
        binding.slotLevelXpProgressFill.scaleX = progress
        bindSlotLevelProgressMarker(progress)
        animateSlotLevelChangeIfNeeded(state.playerLevel, state.levelXp)
    }

    private fun bindSlotLevelProgressMarker(progress: Float) {
        val trackWidth = binding.slotLevelXpTrack.usableWidth()
        val capWidth = binding.slotLevelXpProgressCap.usableWidth()
        val pulseWidth = binding.slotLevelXpProgressPulse.usableWidth()
        val minCenter = capWidth / 2f
        val maxCenter = (trackWidth - capWidth / 2f).coerceAtLeast(minCenter)
        val centerX = (trackWidth * progress).coerceIn(minCenter, maxCenter)

        binding.slotLevelXpProgressCap.translationX = centerX - capWidth / 2f
        binding.slotLevelXpProgressPulse.translationX = centerX - pulseWidth / 2f
        binding.slotLevelXpProgressPulse.alpha = 0.24f + progress * 0.34f
        binding.slotLevelXpProgressCap.alpha = if (progress <= 0f) 0.74f else 1f
    }

    private fun animateSlotLevelChangeIfNeeded(level: Int, levelXp: Int) {
        val previousLevel = lastPresentedSlotLevel
        val previousLevelXp = lastPresentedSlotLevelXp
        lastPresentedSlotLevel = level
        lastPresentedSlotLevelXp = levelXp
        if (previousLevel == null || previousLevelXp == null || (previousLevel == level && previousLevelXp == levelXp)) {
            settleSlotLevelPulseTargets()
            return
        }

        slotLevelPulseAnimator?.cancel()
        settleSlotLevelPulseTargets()
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val leveledUp = level > previousLevel
        val panelPeakScale = if (leveledUp) 1.08f else 1.035f
        val digitPeakScale = if (leveledUp) 1.18f else 1.08f
        val pulsePeakAlpha = if (leveledUp) 0.94f else 0.68f

        slotLevelPulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.slotLevelPanel, View.SCALE_X, 1f, panelPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotLevelPanel, View.SCALE_Y, 1f, panelPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotLevelDigits, View.SCALE_X, 1f, digitPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotLevelDigits, View.SCALE_Y, 1f, digitPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.slotLevelXpProgressPulse, View.ALPHA, 0.42f, pulsePeakAlpha, 0.42f),
                ObjectAnimator.ofFloat(binding.slotLevelXpProgressCap, View.SCALE_X, 1f, if (leveledUp) 1.42f else 1.18f, 1f),
                ObjectAnimator.ofFloat(binding.slotLevelXpProgressCap, View.SCALE_Y, 1f, if (leveledUp) 1.42f else 1.18f, 1f)
            )
            duration = SLOT_LEVEL_CHANGE_PULSE_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleSlotLevelPulseTargets()
                    if (slotLevelPulseAnimator === animation) slotLevelPulseAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleSlotLevelPulseTargets()
                    if (slotLevelPulseAnimator === animation) slotLevelPulseAnimator = null
                }
            })
            start()
        }
    }

    private fun settleSlotLevelPulseTargets() {
        val binding = _binding ?: return
        binding.slotLevelPanel.scaleX = 1f
        binding.slotLevelPanel.scaleY = 1f
        binding.slotLevelDigits.scaleX = 1f
        binding.slotLevelDigits.scaleY = 1f
        binding.slotLevelXpProgressCap.scaleX = 1f
        binding.slotLevelXpProgressCap.scaleY = 1f
    }

    private fun View.usableWidth(): Float {
        val measured = width
            .takeIf { it > 0 }
            ?: measuredWidth.takeIf { it > 0 }
            ?: layoutParams.width.takeIf { it > 0 }
            ?: 1
        return measured.toFloat()
    }

    private fun animateTotalBetChangeIfNeeded(totalBet: Int) {
        val previousTotalBet = lastPresentedTotalBet
        lastPresentedTotalBet = totalBet
        if (previousTotalBet == null || previousTotalBet == totalBet) {
            settleTotalBetPulseTargets()
            return
        }

        totalBetPulseAnimator?.cancel()
        settleTotalBetPulseTargets()
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val increased = totalBet > previousTotalBet
        val digitPeakScale = if (increased) 1.16f else 0.94f
        val glowPeakAlpha = if (increased) 0.78f else 0.46f
        binding.totalBetLinkPulse.setImageResourceIfChanged(
            totalBetLinkPulseDrawable(viewModel.uiState.value.config.theme)
        )
        binding.totalBetLinkPulse.visibility = View.VISIBLE
        binding.totalBetLinkPulse.alpha = 0f
        binding.totalBetLinkPulse.scaleX = 0.982f
        binding.totalBetLinkPulse.scaleY = 0.965f

        totalBetPulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.totalBetDigits, View.SCALE_X, 1f, digitPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.totalBetDigits, View.SCALE_Y, 1f, digitPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.totalBetLinkPulse, View.ALPHA, 0f, TOTAL_BET_LINK_PEAK_ALPHA, 0f),
                ObjectAnimator.ofFloat(binding.totalBetLinkPulse, View.SCALE_X, 0.982f, 1.016f, 1f),
                ObjectAnimator.ofFloat(binding.totalBetLinkPulse, View.SCALE_Y, 0.965f, 1.025f, 1f),
                ObjectAnimator.ofFloat(
                    binding.lastWinPanelMeterGlow,
                    View.ALPHA,
                    CONTROL_METER_SETTLED_ALPHA,
                    glowPeakAlpha,
                    CONTROL_METER_SETTLED_ALPHA
                )
            )
            duration = TOTAL_BET_CHANGE_PULSE_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleTotalBetPulseTargets()
                    if (totalBetPulseAnimator === animation) totalBetPulseAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleTotalBetPulseTargets()
                    if (totalBetPulseAnimator === animation) totalBetPulseAnimator = null
                }
            })
            start()
        }
    }

    private fun bindLastWin(result: SpinResult?, isSpinning: Boolean) {
        if (isSpinning) {
            restoredLastWinAmount = null
            cancelLastWinCountUp()
            renderLastWinValue(0, accessibilityValue = 0)
            return
        }

        val finalValue = result?.winAmount?.coerceAtLeast(0) ?: 0
        if (result === lastCountedResult) {
            if (lastWinCountAnimator == null) {
                renderLastWinValue(finalValue, accessibilityValue = finalValue)
            }
            return
        }

        lastCountedResult = result
        cancelLastWinCountUp()
        val restoredValue = restoredLastWinAmount
        restoredLastWinAmount = null
        if (finalValue <= 0 || restoredValue == finalValue || !ValueAnimator.areAnimatorsEnabled()) {
            renderLastWinValue(finalValue, accessibilityValue = finalValue)
            return
        }

        binding.lastWinDigits.setVisualNumber(0)
        binding.lastWinDigits.contentDescription =
            "${getString(R.string.last_win)} ${finalValue.asCoins()}"
        lastWinCountRenderAtMs = Long.MIN_VALUE
        val animator = ValueAnimator.ofInt(0, finalValue).apply {
            duration = SlotWinCountUpTiming.durationMs(requireNotNull(result))
            interpolator = DecelerateInterpolator(1.25f)
            addUpdateListener { valueAnimator ->
                if (_binding == null || lastWinCountAnimator !== valueAnimator) return@addUpdateListener
                val nowMs = SystemClock.uptimeMillis()
                if (
                    valueAnimator.animatedFraction < 1f &&
                    lastWinCountRenderAtMs != Long.MIN_VALUE &&
                    nowMs - lastWinCountRenderAtMs < LAST_WIN_COUNT_RENDER_INTERVAL_MS
                ) {
                    return@addUpdateListener
                }
                lastWinCountRenderAtMs = nowMs
                binding.lastWinDigits.setVisualNumber(valueAnimator.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (lastWinCountAnimator === animation) {
                        lastWinCountAnimator = null
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (lastWinCountAnimator === animation) {
                        lastWinCountAnimator = null
                        if (_binding != null) {
                            renderLastWinValue(finalValue, accessibilityValue = finalValue)
                        }
                    }
                }
            })
        }
        lastWinCountAnimator = animator
        animator.start()
    }

    private fun renderLastWinValue(value: Int, accessibilityValue: Int) {
        binding.lastWinDigits.setNumber(value)
        binding.lastWinDigits.contentDescription =
            "${getString(R.string.last_win)} ${accessibilityValue.asCoins()}"
    }

    private fun cancelLastWinCountUp() {
        val animator = lastWinCountAnimator
        lastWinCountAnimator = null
        lastWinCountRenderAtMs = Long.MIN_VALUE
        animator?.cancel()
    }

    private fun settleTotalBetPulseTargets() {
        val binding = _binding ?: return
        binding.totalBetDigits.scaleX = 1f
        binding.totalBetDigits.scaleY = 1f
        binding.lastWinPanelMeterGlow.alpha = CONTROL_METER_SETTLED_ALPHA
        binding.totalBetLinkPulse.alpha = 0f
        binding.totalBetLinkPulse.scaleX = 1f
        binding.totalBetLinkPulse.scaleY = 1f
        binding.totalBetLinkPulse.visibility = View.INVISIBLE
    }

    private fun animateFreeSpinsChangeIfNeeded(freeSpins: Int) {
        val previousFreeSpins = lastPresentedFreeSpins
        lastPresentedFreeSpins = freeSpins
        if (previousFreeSpins == null || previousFreeSpins == freeSpins) {
            settleFreeSpinsPulseTargets(freeSpins)
            return
        }

        freeSpinsPulseAnimator?.cancel()
        settleFreeSpinsPulseTargets(freeSpins)
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val increased = freeSpins > previousFreeSpins
        val baseAlpha = freeSpinsRailAlpha(freeSpins)
        val pulseAlpha = if (increased) 1f else (baseAlpha * 0.72f).coerceAtLeast(0.46f)
        val railPeakScale = if (increased) 1.12f else 0.965f
        val digitPeakScale = if (increased) 1.18f else 0.92f

        freeSpinsPulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.freeSpinsRail, View.ALPHA, baseAlpha, pulseAlpha, baseAlpha),
                ObjectAnimator.ofFloat(binding.freeSpinsRail, View.SCALE_X, 1f, railPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.freeSpinsRail, View.SCALE_Y, 1f, railPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.freeSpinsDigits, View.SCALE_X, 1f, digitPeakScale, 1f),
                ObjectAnimator.ofFloat(binding.freeSpinsDigits, View.SCALE_Y, 1f, digitPeakScale, 1f)
            )
            duration = FREE_SPINS_PULSE_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleFreeSpinsPulseTargets(freeSpins)
                    if (freeSpinsPulseAnimator === animation) freeSpinsPulseAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleFreeSpinsPulseTargets(freeSpins)
                    if (freeSpinsPulseAnimator === animation) freeSpinsPulseAnimator = null
                }
            })
            start()
        }
    }

    private fun settleFreeSpinsPulseTargets(freeSpins: Int = lastPresentedFreeSpins ?: 0) {
        val binding = _binding ?: return
        binding.freeSpinsRail.alpha = freeSpinsRailAlpha(freeSpins)
        binding.freeSpinsRail.scaleX = 1f
        binding.freeSpinsRail.scaleY = 1f
        binding.freeSpinsDigits.scaleX = 1f
        binding.freeSpinsDigits.scaleY = 1f
        if (freeSpins <= 0 && !freeSpinsVisualModeActive && freeSpinsRailChargeAnimator == null) {
            binding.freeSpinsRailCharge.alpha = 0f
            binding.freeSpinsRailCharge.scaleX = 1f
            binding.freeSpinsRailCharge.scaleY = 1f
            binding.freeSpinsRailCharge.visibility = View.INVISIBLE
        }
    }

    private fun freeSpinsRailAlpha(freeSpins: Int): Float {
        return if (freeSpins > 0 || freeSpinsVisualModeActive) 1f else 0.72f
    }

    private fun updateFreeSpinsRailCharge(active: Boolean) {
        if (active) {
            startFreeSpinsRailCharge()
        } else {
            stopFreeSpinsRailCharge()
        }
    }

    private fun startFreeSpinsRailCharge() {
        val binding = _binding ?: return
        if (freeSpinsRailChargeAnimator != null) return
        val theme = viewModel.uiState.value.config.theme
        binding.freeSpinsRailCharge.setImageResourceIfChanged(freeSpinsRailChargeDrawable(theme))
        binding.freeSpinsRailCharge.visibility = View.VISIBLE
        binding.freeSpinsRailCharge.alpha = FREE_SPINS_RAIL_CHARGE_LOW_ALPHA
        binding.freeSpinsRailCharge.scaleX = 1f
        binding.freeSpinsRailCharge.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            binding.freeSpinsRailCharge.alpha = FREE_SPINS_RAIL_CHARGE_HIGH_ALPHA
            return
        }

        val alphaPulse = ObjectAnimator.ofFloat(
            binding.freeSpinsRailCharge,
            View.ALPHA,
            FREE_SPINS_RAIL_CHARGE_LOW_ALPHA,
            FREE_SPINS_RAIL_CHARGE_HIGH_ALPHA
        ).apply {
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
            duration = FREE_SPINS_RAIL_CHARGE_PULSE_DURATION_MS
        }
        val scaleXPulse = ObjectAnimator.ofFloat(binding.freeSpinsRailCharge, View.SCALE_X, 0.985f, 1.035f).apply {
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
            duration = FREE_SPINS_RAIL_CHARGE_PULSE_DURATION_MS
        }
        val scaleYPulse = ObjectAnimator.ofFloat(binding.freeSpinsRailCharge, View.SCALE_Y, 0.985f, 1.035f).apply {
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
            duration = FREE_SPINS_RAIL_CHARGE_PULSE_DURATION_MS
        }
        freeSpinsRailChargeAnimator = AnimatorSet().apply {
            playTogether(alphaPulse, scaleXPulse, scaleYPulse)
            start()
        }
    }

    private fun stopFreeSpinsRailCharge(immediate: Boolean = false) {
        val binding = _binding ?: return
        freeSpinsRailChargeAnimator?.cancel()
        freeSpinsRailChargeAnimator = null
        binding.freeSpinsRailCharge.scaleX = 1f
        binding.freeSpinsRailCharge.scaleY = 1f
        if (immediate || !ValueAnimator.areAnimatorsEnabled()) {
            binding.freeSpinsRailCharge.alpha = 0f
            binding.freeSpinsRailCharge.visibility = View.INVISIBLE
            return
        }
        binding.freeSpinsRailCharge.animate()
            .alpha(0f)
            .setDuration(FREE_SPINS_RAIL_CHARGE_FADE_DURATION_MS)
            .withEndAction {
                binding.freeSpinsRailCharge.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun updateFreeSpinsModeOverlay(active: Boolean) {
        if (active) {
            startFreeSpinsModeOverlay()
        } else {
            stopFreeSpinsModeOverlay()
        }
    }

    private fun startFreeSpinsModeOverlay() {
        val overlay = binding.freeSpinsModeOverlay
        if (freeSpinsModeAnimator != null) return
        binding.freeSpinsModeOverlay.setImageResourceIfChanged(
            freeSpinsModeOverlayDrawable(viewModel.uiState.value.config.theme)
        )
        overlay.visibility = View.VISIBLE
        overlay.alpha = FREE_SPINS_MODE_LOW_ALPHA
        overlay.scaleX = 0.992f
        overlay.scaleY = 0.992f
        overlay.translationY = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        freeSpinsModeAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    overlay,
                    View.ALPHA,
                    FREE_SPINS_MODE_LOW_ALPHA,
                    FREE_SPINS_MODE_HIGH_ALPHA,
                    FREE_SPINS_MODE_LOW_ALPHA
                ).apply {
                    duration = FREE_SPINS_MODE_PULSE_DURATION_MS
                    repeatCount = 0
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.992f, 1.012f, 0.992f).apply {
                    duration = FREE_SPINS_MODE_PULSE_DURATION_MS
                    repeatCount = 0
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.992f, 1.012f, 0.992f).apply {
                    duration = FREE_SPINS_MODE_PULSE_DURATION_MS
                    repeatCount = 0
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, -3f, 3f, -3f).apply {
                    duration = FREE_SPINS_MODE_DRIFT_DURATION_MS
                    repeatCount = 0
                    repeatMode = ValueAnimator.RESTART
                }
            )
            start()
        }
    }

    private fun stopFreeSpinsModeOverlay(immediate: Boolean = false) {
        val binding = _binding ?: return
        val overlay = binding.freeSpinsModeOverlay
        freeSpinsModeAnimator?.cancel()
        freeSpinsModeAnimator = null
        overlay.animate().cancel()
        if (immediate) {
            overlay.visibility = View.INVISIBLE
            overlay.alpha = 0f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            overlay.translationY = 0f
            return
        }
        if (overlay.visibility != View.VISIBLE && overlay.alpha == 0f) return
        overlay.animate()
            .alpha(0f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(160L)
            .withEndAction { overlay.visibility = View.INVISIBLE }
            .start()
    }

    private fun updateFreeSpinsStakeLockOverlay(active: Boolean) {
        if (active == freeSpinsStakeLockActive) return
        freeSpinsStakeLockActive = active
        if (active) {
            setStakeControlsVisible(false)
            startFreeSpinsStakeLockOverlay()
        } else {
            stopFreeSpinsStakeLockOverlay()
        }
    }

    private fun setStakeControlsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        binding.betStepperGroup.visibility = visibility
        binding.linesStepperGroup.visibility = visibility
    }

    private fun startFreeSpinsStakeLockOverlay() {
        val overlay = binding.freeSpinsStakeLockOverlay
        freeSpinsStakeLockAnimator?.cancel()
        overlay.animate().cancel()
        val theme = viewModel.uiState.value.config.theme
        binding.freeSpinsStakeLockOverlay.setImageResourceIfChanged(freeSpinsStakeLockOverlayDrawable(theme))
        overlay.visibility = View.VISIBLE
        overlay.alpha = FREE_SPINS_STAKE_LOCK_SETTLED_ALPHA
        overlay.scaleX = 0.988f
        overlay.scaleY = 0.96f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            return
        }

        freeSpinsStakeLockAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    overlay,
                    View.ALPHA,
                    0f,
                    FREE_SPINS_STAKE_LOCK_PEAK_ALPHA,
                    FREE_SPINS_STAKE_LOCK_SETTLED_ALPHA
                ),
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.988f, 1.012f, 1f),
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.96f, 1.02f, 1f)
            )
            duration = FREE_SPINS_STAKE_LOCK_ENTER_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleFreeSpinsStakeLockOverlay()
                    if (freeSpinsStakeLockAnimator === animation) freeSpinsStakeLockAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleFreeSpinsStakeLockOverlay()
                    if (freeSpinsStakeLockAnimator === animation) freeSpinsStakeLockAnimator = null
                }
            })
            start()
        }
    }

    private fun settleFreeSpinsStakeLockOverlay() {
        val binding = _binding ?: return
        val overlay = binding.freeSpinsStakeLockOverlay
        if (!freeSpinsStakeLockActive) return
        overlay.visibility = View.VISIBLE
        overlay.alpha = FREE_SPINS_STAKE_LOCK_SETTLED_ALPHA
        overlay.scaleX = 1f
        overlay.scaleY = 1f
    }

    private fun stopFreeSpinsStakeLockOverlay(immediate: Boolean = false) {
        val binding = _binding ?: return
        val overlay = binding.freeSpinsStakeLockOverlay
        freeSpinsStakeLockActive = false
        freeSpinsStakeLockAnimator?.cancel()
        freeSpinsStakeLockAnimator = null
        overlay.animate().cancel()
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        if (immediate || !ValueAnimator.areAnimatorsEnabled()) {
            overlay.alpha = 0f
            overlay.visibility = View.INVISIBLE
            setStakeControlsVisible(true)
            return
        }
        if (overlay.visibility != View.VISIBLE && overlay.alpha == 0f) {
            setStakeControlsVisible(true)
            return
        }
        overlay.animate()
            .alpha(0f)
            .setDuration(FREE_SPINS_STAKE_LOCK_FADE_DURATION_MS)
            .withEndAction {
                overlay.visibility = View.INVISIBLE
                setStakeControlsVisible(true)
            }
            .start()
    }

    private suspend fun collectEvents() {
        viewModel.events.collect { event ->
            when (event) {
                is SlotEvent.LowCoins -> showLowCoinsDialog(event.bonusAvailable)
                is SlotEvent.ResultReady -> showResultDialog(
                    event.result,
                    event.freeSpinsAwarded,
                    event.presentationId
                )
                is SlotEvent.PendingPresentation -> openPendingPresentationSlot(event.slotId)
            }
        }
    }

    private fun acknowledgeInlinePresentationAfterNextDraw(presentationId: String) {
        if (presentationId.isBlank() || pendingInlinePresentationDrawId == presentationId) return
        clearInlinePresentationDrawListener()
        pendingInlinePresentationDrawId = presentationId
        val target = binding.reelsGrid
        val observer = target.viewTreeObserver
        var drawObserved = false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (!drawObserved) {
                drawObserved = true
                target.post {
                    if (observer.isAlive) observer.removeOnDrawListener(listener)
                    inlinePresentationDrawObserver = null
                    inlinePresentationDrawListener = null
                    if (_binding == null || pendingInlinePresentationDrawId != presentationId) return@post
                    pendingInlinePresentationDrawId = null
                    if (BuildConfig.QA_ENABLED) {
                        Log.i(QA_PRESENTATION_TAG, QA_INLINE_FIRST_DRAW)
                    }
                    viewModel.onSpinPresentationRendered(presentationId)
                }
            }
        }
        inlinePresentationDrawObserver = observer
        inlinePresentationDrawListener = listener
        observer.addOnDrawListener(listener)
        target.invalidate()
    }

    private fun clearInlinePresentationDrawListener() {
        val observer = inlinePresentationDrawObserver
        val listener = inlinePresentationDrawListener
        if (observer?.isAlive == true && listener != null) {
            observer.removeOnDrawListener(listener)
        }
        inlinePresentationDrawObserver = null
        inlinePresentationDrawListener = null
        pendingInlinePresentationDrawId = null
    }

    private fun openPendingPresentationSlot(pendingSlotId: String) {
        if (pendingSlotId.isBlank() || pendingSlotId == slotId) return
        if (parentFragmentManager.isStateSaved) return
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.slotFragment) return
        navController.navigate(
            R.id.action_global_slot,
            Bundle().apply { putString("slotId", pendingSlotId) }
        )
    }

    private fun setupGrid() {
        if (reelCells.isNotEmpty()) return
        setupReelCellBackdropLayer()
        setupSymbolWinHaloLayer()
        setupBonusScatterHaloLayer()
        setupReelSpinStripLayer()
        setupReelMotionStreakLayer()
        setupReelAnticipationBeamLayer()
        setupReelLandingSparkLayer()
        setupReelBrakeLayer()
        val cellPadding = 2.dp()
        for (row in 0 until 3) {
            for (column in 0 until 5) {
                val imageView = ImageView(requireContext()).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    contentDescription = null
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                }
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(column, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(4, 4, 4, 4)
                }
                binding.reelsGrid.addView(imageView, params)
                reelCells += imageView
            }
        }
        setupReelStopFlashLayer()
    }

    private fun setupReelSpinStripLayer() {
        if (reelSpinColumns.isNotEmpty()) return
        val drawableCache = ReelStripDrawableCache(requireContext()).also {
            reelSpinDrawableCache = it
        }
        for (column in 0 until REEL_COUNT) {
            val columnFrame = FrameLayout(requireContext()).apply {
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                clipChildren = true
                clipToPadding = true
            }
            val strip = ReelStripView(requireContext(), drawableCache).apply {
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setDrawnSymbolRange(
                    ReelSpinTrajectory.SETTLED_CELL_OFFSET.toInt(),
                    ReelSpinTrajectory.SETTLED_CELL_OFFSET.toInt() + REEL_VISIBLE_ROWS
                )
            }
            columnFrame.addView(
                strip,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    REEL_SPIN_FALLBACK_CELL_HEIGHT_DP.dp() * REEL_SPIN_STRIP_SYMBOL_COUNT
                )
            )
            val params = GridLayout.LayoutParams(
                GridLayout.spec(0, 1f),
                GridLayout.spec(column, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(4, 4, 4, 4)
            }
            binding.reelSpinStripLayer.addView(columnFrame, params)
            reelSpinColumns += columnFrame
            reelSpinStrips += strip
        }
    }

    private fun setupReelBrakeLayer() {
        if (reelBrakeViews.isNotEmpty()) return
        for (column in 0 until REEL_COUNT) {
            val imageView = ImageView(requireContext()).apply {
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
                visibility = View.INVISIBLE
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(0, 1f),
                GridLayout.spec(column, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(1, 0, 1, 0)
            }
            binding.reelBrakeLayer.addView(imageView, params)
            reelBrakeViews += imageView
        }
    }

    private fun prepareReelSpinStripDimensions() {
        reelSpinColumns.indices.forEach(::reelStripCellHeightPx)
    }

    private fun preloadSpinPresentationResources(config: SlotConfig, result: SpinResult?) {
        if (preloadedSpinConfigId != config.id) {
            val symbolResources = config.symbols
                .flatMap { symbol ->
                    if (shouldUseRichSpinEffects()) {
                        listOf(
                            SlotSymbolResources.image(config.theme, symbol),
                            SlotSymbolResources.spinImage(config.theme, symbol)
                        )
                    } else {
                        listOf(SlotSymbolResources.image(config.theme, symbol))
                    }
                }
                .distinct()
                .toIntArray()
            reelSpinDrawableCache?.preload(symbolResources)
            preloadDrawables(
                reelSpinBlurDrawable(config.theme),
                spinEnergyOverlayDrawable(config.theme),
                themeSpinOverlayDrawable(config.theme),
                reelMotionStreakDrawable(config.theme),
                reelAnticipationBeamDrawable(config.theme),
                reelLandingSparkDrawable(config.theme),
                reelBrakeClampDrawable(config.theme),
                reelStopFlashDrawable(config.theme)
            )
            preloadedSpinConfigId = config.id
        }

        result ?: return
        val resultSignature = buildString {
            append(config.id)
            append(':')
            append(result.resultType.name)
            append(':')
            result.winningLines
                .map { it.paylineIndex }
                .distinct()
                .sorted()
                .joinTo(this, separator = ",")
        }
        if (preloadedResultSignature == resultSignature) return

        val winningPaylineResourceIds = result.winningLines
            .map { it.paylineIndex }
            .distinct()
            .map { lineIndex -> paylineWinDrawable(config.theme, lineIndex) }
        val resultResourceIds = (
            if (shouldUseRichSpinEffects()) {
                winningPaylineResourceIds
            } else {
                winningPaylineResourceIds.take(1)
            }
            ).toMutableList()
        resultResourceIds += symbolWinHaloDrawable(config.theme)
        when {
            result.resultType == ResultType.Bonus -> {
                resultResourceIds += listOf(
                    winGlowSpriteDrawable(config.theme),
                    themeWinBurstDrawable(config.theme),
                    bonusScatterHaloDrawable(config.theme),
                    bonusEntryPortalDrawable(config.theme),
                    bonusFreeSpinsBannerDrawable(config.theme)
                )
            }
            shouldShowBigWinBanner(result) -> {
                resultResourceIds += listOf(
                    winGlowSpriteDrawable(config.theme),
                    themeWinBurstDrawable(config.theme),
                    bigWinBannerDrawable(config.theme)
                )
            }
            SlotResultPresentationPolicy.isPartialReturn(result) -> {
                resultResourceIds += winGlowSpriteDrawable(config.theme)
            }
        }
        preloadResultDrawables(resultSignature, resultResourceIds.distinct())
        preloadedResultSignature = resultSignature
    }

    private fun preloadDrawables(@DrawableRes vararg resourceIds: Int) {
        resourceIds.forEach { resourceId ->
            if (resourceId !in transientDrawablePreloads) {
                AppCompatResources.getDrawable(requireContext(), resourceId)?.let { drawable ->
                    transientDrawablePreloads[resourceId] = drawable
                }
            }
        }
    }

    private fun preloadResultDrawables(signature: String, @DrawableRes resourceIds: List<Int>) {
        resultDrawablePreloadJob?.cancel()
        val context = requireContext().applicationContext
        resultDrawablePreloadJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            delay(RESULT_DRAWABLE_PRELOAD_DELAY_MS)
            val loaded = resourceIds.mapNotNull { resourceId ->
                AppCompatResources.getDrawable(context, resourceId)?.let { resourceId to it }
            }
            withContext(Dispatchers.Main.immediate) {
                if (preloadedResultSignature == signature) {
                    transientDrawablePreloads.putAll(loaded)
                }
                resultDrawablePreloadJob = null
            }
        }
    }

    private fun startSpinPerformanceHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || spinPerformanceHintSession != null) return
        spinPerformanceHintSession = runCatching {
            PerformanceHintApi31.createSession(
                context = requireContext(),
                threadId = Process.myTid(),
                targetWorkDurationNanos = SPIN_PERFORMANCE_TARGET_NANOS
            )
        }.getOrNull()
    }

    private fun reportSpinPerformanceWork(workDurationNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            spinPerformanceHintSession?.let { session ->
                PerformanceHintApi31.reportActualWorkDuration(
                    session,
                    workDurationNanos.coerceAtLeast(1L)
                )
            }
        }
    }

    private fun stopSpinPerformanceHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        spinPerformanceHintSession?.close()
        spinPerformanceHintSession = null
    }

    private fun setupReelMotionStreakLayer() {
        if (reelMotionStreakViews.isNotEmpty()) return
        for (column in 0 until REEL_COUNT) {
            val imageView = ImageView(requireContext()).apply {
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
                visibility = View.INVISIBLE
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(0, 1f),
                GridLayout.spec(column, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(2, 0, 2, 0)
            }
            binding.reelMotionStreakLayer.addView(imageView, params)
            reelMotionStreakViews += imageView
        }
    }

    private fun setupReelAnticipationBeamLayer() {
        if (reelAnticipationBeamViews.isNotEmpty()) return
        for (column in 0 until REEL_COUNT) {
            val imageView = ImageView(requireContext()).apply {
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
                visibility = View.INVISIBLE
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(0, 1f),
                GridLayout.spec(column, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(0, 0, 0, 0)
            }
            binding.reelAnticipationBeamLayer.addView(imageView, params)
            reelAnticipationBeamViews += imageView
        }
    }

    private fun setupReelLandingSparkLayer() {
        if (reelLandingSparkViews.isNotEmpty()) return
        for (column in 0 until REEL_COUNT) {
            val imageView = ImageView(requireContext()).apply {
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
                visibility = View.INVISIBLE
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(0, 1f),
                GridLayout.spec(column, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(0, 0, 0, 0)
            }
            binding.reelLandingSparkLayer.addView(imageView, params)
            reelLandingSparkViews += imageView
        }
    }

    private fun setupSymbolWinHaloLayer() {
        if (symbolWinHalos.isNotEmpty()) return
        for (row in 0 until 3) {
            for (column in 0 until 5) {
                val imageView = ImageView(requireContext()).apply {
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_XY
                    alpha = 0f
                    visibility = View.INVISIBLE
                }
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(column, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(0, 0, 0, 0)
                }
                binding.symbolWinHaloLayer.addView(imageView, params)
                symbolWinHalos += imageView
            }
        }
    }

    private fun setupBonusScatterHaloLayer() {
        if (bonusScatterHalos.isNotEmpty()) return
        for (row in 0 until REEL_VISIBLE_ROWS) {
            for (column in 0 until REEL_COUNT) {
                val imageView = ImageView(requireContext()).apply {
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_XY
                    alpha = 0f
                    visibility = View.INVISIBLE
                }
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(column, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(0, 0, 0, 0)
                }
                binding.bonusScatterHaloLayer.addView(imageView, params)
                bonusScatterHalos += imageView
            }
        }
    }

    private fun setupReelCellBackdropLayer() {
        if (reelCellBackdrops.isNotEmpty()) return
        for (row in 0 until 3) {
            for (column in 0 until 5) {
                val imageView = ImageView(requireContext()).apply {
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageResourceIfChanged(R.drawable.reel_cell_backdrop)
                }
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(column, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(4, 4, 4, 4)
                }
                binding.reelCellBackdropLayer.addView(imageView, params)
                reelCellBackdrops += imageView
            }
        }
    }

    private fun setupReelStopFlashLayer() {
        if (reelStopFlashViews.isNotEmpty()) return
        val cellPadding = 1.dp()
        for (column in 0 until 5) {
            val imageView = ImageView(requireContext()).apply {
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
                visibility = View.INVISIBLE
                setPadding(cellPadding, 0, cellPadding, 0)
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(0, 1f),
                GridLayout.spec(column, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(1, 0, 1, 0)
            }
            binding.reelStopFlashLayer.addView(imageView, params)
            reelStopFlashViews += imageView
        }
    }

    private fun animateReelWindowDepthMask() {
        val mask = binding.reelWindowDepthMask
        val aperture = binding.reelApertureShadow
        reelWindowDepthAnimator?.cancel()
        reelWindowDepthAnimator = null
        mask.animate().cancel()
        aperture.animate().cancel()
        mask.visibility = View.VISIBLE
        mask.alpha = REEL_WINDOW_DEPTH_SETTLED_ALPHA
        mask.scaleX = 1f
        mask.scaleY = 1f
        aperture.visibility = View.VISIBLE
        aperture.alpha = REEL_APERTURE_SETTLED_ALPHA
        aperture.scaleX = 1f
        aperture.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        mask.alpha = 0.58f
        mask.scaleX = 0.992f
        mask.scaleY = 0.992f
        aperture.alpha = 0.64f
        aperture.scaleX = 0.988f
        aperture.scaleY = 0.988f

        reelWindowDepthAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(mask, View.ALPHA, 0.58f, 1f, REEL_WINDOW_DEPTH_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(mask, View.SCALE_X, 0.992f, 1.006f, 1f),
                ObjectAnimator.ofFloat(mask, View.SCALE_Y, 0.992f, 1.006f, 1f),
                ObjectAnimator.ofFloat(aperture, View.ALPHA, 0.64f, 1f, REEL_APERTURE_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(aperture, View.SCALE_X, 0.988f, 1.008f, 1f),
                ObjectAnimator.ofFloat(aperture, View.SCALE_Y, 0.988f, 1.008f, 1f)
            )
            duration = REEL_WINDOW_DEPTH_POLISH_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (reelWindowDepthAnimator === animation) {
                        reelWindowDepthAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun animateSlotMarqueeGlass() {
        val glass = binding.slotMarqueeGlass
        slotMarqueeGlassAnimator?.cancel()
        slotMarqueeGlassAnimator = null
        glass.animate().cancel()
        glass.visibility = View.VISIBLE
        glass.alpha = SLOT_MARQUEE_GLASS_SETTLED_ALPHA
        glass.scaleX = 1f
        glass.scaleY = 1f
        glass.translationY = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        glass.alpha = 0.54f
        glass.scaleX = 0.986f
        glass.scaleY = 0.986f
        glass.translationY = -5f

        slotMarqueeGlassAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glass, View.ALPHA, 0.54f, 1f, SLOT_MARQUEE_GLASS_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(glass, View.SCALE_X, 0.986f, 1.006f, 1f),
                ObjectAnimator.ofFloat(glass, View.SCALE_Y, 0.986f, 1.006f, 1f),
                ObjectAnimator.ofFloat(glass, View.TRANSLATION_Y, -5f, 1f, 0f)
            )
            duration = SLOT_MARQUEE_GLASS_POLISH_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (slotMarqueeGlassAnimator === animation) {
                        slotMarqueeGlassAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun renderReels(
        theme: SlotTheme,
        reels: List<List<String>>,
        highlightedCells: Set<Int> = emptySet(),
        bonusScatterCells: Set<Int> = emptySet()
    ) {
        for (row in 0 until REEL_VISIBLE_ROWS) {
            for (column in 0 until REEL_COUNT) {
                val cellIndex = row * REEL_COUNT + column
                val symbol = reels[column][row]
                val imageView = reelCells[cellIndex]
                imageView.setImageResourceIfChanged(SlotSymbolResources.image(theme, symbol))
                imageView.background = null
            }
        }
        updateReelsContentDescription(theme, reels)
        renderReelHighlights(highlightedCells, bonusScatterCells)
    }

    private fun renderReelHighlights(
        highlightedCells: Set<Int>,
        bonusScatterCells: Set<Int>
    ) {
        val shouldAnimateHighlights = shouldUseRichSpinEffects() &&
            highlightedCells.isNotEmpty() &&
            highlightedCells != lastHighlightedCells
        val shouldAnimateBonusScatter = shouldUseRichSpinEffects() &&
            bonusScatterCells.isNotEmpty() &&
            bonusScatterCells != lastBonusScatterCells
        if (shouldUseRichSpinEffects()) {
            renderSymbolWinHalos(highlightedCells, shouldAnimateHighlights)
            renderBonusScatterHalos(bonusScatterCells, shouldAnimateBonusScatter)
        } else {
            hideSymbolWinHalos(immediate = true)
            hideBonusScatterHalos(immediate = true)
        }
        for (row in 0 until REEL_VISIBLE_ROWS) {
            for (column in 0 until REEL_COUNT) {
                val cellIndex = row * REEL_COUNT + column
                val imageView = reelCells[cellIndex]
                val highlighted = cellIndex in highlightedCells
                val bonusScatter = cellIndex in bonusScatterCells
                val emphasized = highlighted || bonusScatter

                imageView.animate().cancel()
                imageView.animate().setStartDelay(0L)
                imageView.alpha = if (
                    (highlightedCells.isNotEmpty() || bonusScatterCells.isNotEmpty()) && !emphasized
                ) {
                    0.56f
                } else {
                    1f
                }
                imageView.scaleX = if (bonusScatter) 1.07f else if (highlighted) 1.03f else 1f
                imageView.scaleY = if (bonusScatter) 1.07f else if (highlighted) 1.03f else 1f
            }
        }
        if (shouldAnimateHighlights) {
            animateHighlightedCells(highlightedCells)
        }
        lastHighlightedCells = highlightedCells
        lastBonusScatterCells = bonusScatterCells
    }

    private fun updateReelsContentDescription(theme: SlotTheme, reels: List<List<String>>) {
        val rowDescriptions = (0 until REEL_VISIBLE_ROWS).map { row ->
            (0 until REEL_COUNT).joinToString(", ") { column ->
                SlotSymbolResources.label(theme, reels[column][row])
            }
        }
        binding.reelsGrid.contentDescription = getString(
            R.string.slot_reels_rows_accessibility,
            rowDescriptions[0],
            rowDescriptions[1],
            rowDescriptions[2]
        )
    }

    private fun renderSymbolWinHalos(highlightedCells: Set<Int>, animate: Boolean) {
        if (highlightedCells.isEmpty()) {
            hideSymbolWinHalos(immediate = true)
            return
        }
        val haloDrawable = symbolWinHaloDrawable(viewModel.uiState.value.config.theme)
        symbolWinHalos.forEach { it.setImageResourceIfChanged(haloDrawable) }
        symbolWinHaloAnimator?.cancel()
        symbolWinHaloAnimator = null
        symbolWinHalos.forEachIndexed { cellIndex, halo ->
            halo.animate().cancel()
            val highlighted = cellIndex in highlightedCells
            halo.visibility = if (highlighted) View.VISIBLE else View.INVISIBLE
            halo.alpha = if (highlighted) 0.82f else 0f
            halo.scaleX = if (highlighted) 1.02f else 1f
            halo.scaleY = if (highlighted) 1.02f else 1f
            halo.translationY = 0f
        }
        if (animate) {
            animateSymbolWinHalos(highlightedCells)
        }
    }

    private fun animateSymbolWinHalos(highlightedCells: Set<Int>) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val haloAnimators = highlightedCells.mapNotNull { cellIndex ->
            val halo = symbolWinHalos.getOrNull(cellIndex) ?: return@mapNotNull null
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(halo, View.ALPHA, 0f, 1f, 0.82f),
                    ObjectAnimator.ofFloat(halo, View.SCALE_X, 0.88f, 1.12f, 1.02f),
                    ObjectAnimator.ofFloat(halo, View.SCALE_Y, 0.88f, 1.12f, 1.02f),
                    ObjectAnimator.ofFloat(halo, View.TRANSLATION_Y, 8f, 0f)
                )
                duration = 430L
            }
        }
        if (haloAnimators.isEmpty()) return
        symbolWinHaloAnimator = AnimatorSet().apply {
            playTogether(haloAnimators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (symbolWinHaloAnimator === animation) {
                        symbolWinHaloAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun hideSymbolWinHalos(immediate: Boolean = false) {
        symbolWinHaloAnimator?.cancel()
        symbolWinHaloAnimator = null
        symbolWinHalos.forEach { halo ->
            halo.animate().cancel()
            if (immediate) {
                halo.visibility = View.INVISIBLE
                halo.alpha = 0f
                halo.scaleX = 1f
                halo.scaleY = 1f
                halo.translationY = 0f
                halo.clearBoundImageResource()
                return@forEach
            }
            halo.animate()
                .alpha(0f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(120L)
                .withEndAction {
                    halo.visibility = View.INVISIBLE
                    halo.clearBoundImageResource()
                }
                .start()
        }
    }

    private fun renderBonusScatterHalos(bonusScatterCells: Set<Int>, animate: Boolean) {
        if (bonusScatterCells.isEmpty()) {
            hideBonusScatterHalos(immediate = true)
            return
        }
        val haloDrawable = bonusScatterHaloDrawable(viewModel.uiState.value.config.theme)
        bonusScatterHalos.forEach { it.setImageResourceIfChanged(haloDrawable) }
        bonusScatterHaloAnimator?.cancel()
        bonusScatterHaloAnimator = null
        bonusScatterHalos.forEachIndexed { cellIndex, halo ->
            halo.animate().cancel()
            val highlighted = cellIndex in bonusScatterCells
            halo.visibility = if (highlighted) View.VISIBLE else View.INVISIBLE
            halo.alpha = if (highlighted) BONUS_SCATTER_HALO_SETTLED_ALPHA else 0f
            halo.scaleX = if (highlighted) 1.06f else 1f
            halo.scaleY = if (highlighted) 1.06f else 1f
            halo.rotation = 0f
            halo.translationY = 0f
        }
        if (animate) {
            animateBonusScatterHalos(bonusScatterCells)
        }
    }

    private fun animateBonusScatterHalos(bonusScatterCells: Set<Int>) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val haloAnimators = bonusScatterCells.sorted().mapIndexedNotNull { index, cellIndex ->
            val halo = bonusScatterHalos.getOrNull(cellIndex) ?: return@mapIndexedNotNull null
            AnimatorSet().apply {
                startDelay = index * BONUS_SCATTER_HALO_STAGGER_MS
                playTogether(
                    ObjectAnimator.ofFloat(halo, View.ALPHA, 0f, 1f, BONUS_SCATTER_HALO_SETTLED_ALPHA),
                    ObjectAnimator.ofFloat(halo, View.SCALE_X, 0.66f, 1.28f, 1.06f),
                    ObjectAnimator.ofFloat(halo, View.SCALE_Y, 0.66f, 1.28f, 1.06f),
                    ObjectAnimator.ofFloat(halo, View.ROTATION, -9f, 8f, 0f),
                    ObjectAnimator.ofFloat(halo, View.TRANSLATION_Y, 10f, -4f, 0f)
                )
                duration = BONUS_SCATTER_HALO_TRIGGER_MS
            }
        }
        if (haloAnimators.isEmpty()) return
        bonusScatterHaloAnimator = AnimatorSet().apply {
            playTogether(haloAnimators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bonusScatterHaloAnimator === animation) {
                        bonusScatterHaloAnimator = null
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (bonusScatterHaloAnimator === animation) {
                        bonusScatterHaloAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun hideBonusScatterHalos(immediate: Boolean = false) {
        bonusScatterHaloAnimator?.cancel()
        bonusScatterHaloAnimator = null
        bonusScatterHalos.forEach { halo ->
            halo.animate().cancel()
            if (immediate) {
                halo.visibility = View.INVISIBLE
                halo.alpha = 0f
                halo.scaleX = 1f
                halo.scaleY = 1f
                halo.rotation = 0f
                halo.translationY = 0f
                halo.clearBoundImageResource()
                return@forEach
            }
            halo.animate()
                .alpha(0f)
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .translationY(0f)
                .setDuration(140L)
                .withEndAction {
                    halo.visibility = View.INVISIBLE
                    halo.clearBoundImageResource()
                }
                .start()
        }
    }

    private fun animateSpinButton() {
        if (hapticsEnabled) {
            binding.spinButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        if (shouldUseRichSpinEffects()) {
            animateSpinImpactFlash()
        } else {
            hideSpinImpactFlash(immediate = true)
        }
        ObjectAnimator.ofFloat(binding.spinButton, View.SCALE_X, 1f, 0.94f, 1f).apply { duration = 180L }.start()
        ObjectAnimator.ofFloat(binding.spinButton, View.SCALE_Y, 1f, 0.94f, 1f).apply { duration = 180L }.start()
    }

    private fun animateSpinImpactFlash() {
        val flash = binding.spinButtonImpactFlash
        spinImpactAnimator?.cancel()
        spinImpactAnimator = null
        flash.animate().cancel()
        binding.spinButtonImpactFlash.setImageResourceIfChanged(
            spinImpactFlashDrawable(viewModel.uiState.value.config.theme)
        )
        flash.bringToFront()
        flash.visibility = View.VISIBLE
        flash.alpha = 0f
        flash.scaleX = 0.82f
        flash.scaleY = 0.84f
        flash.rotation = -2.5f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            hideSpinImpactFlash(immediate = true)
            return
        }

        spinImpactAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(flash, View.ALPHA, 0f, SPIN_IMPACT_HIGH_ALPHA, 0.54f, 0f),
                ObjectAnimator.ofFloat(flash, View.SCALE_X, 0.82f, 1.18f, 1.04f, 1f),
                ObjectAnimator.ofFloat(flash, View.SCALE_Y, 0.84f, 1.16f, 1.04f, 1f),
                ObjectAnimator.ofFloat(flash, View.TRANSLATION_Y, 6f, -3f, 0f),
                ObjectAnimator.ofFloat(flash, View.ROTATION, -2.5f, 1.4f, -0.4f, 0f)
            )
            duration = SPIN_IMPACT_FLASH_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleSpinImpactFlash(animation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleSpinImpactFlash(animation)
                }
            })
            start()
        }
    }

    private fun settleSpinImpactFlash(animation: Animator) {
        if (spinImpactAnimator !== animation) return
        spinImpactAnimator = null
        hideSpinImpactFlash(immediate = true)
    }

    private fun hideSpinImpactFlash(immediate: Boolean = false) {
        val binding = _binding ?: return
        val flash = binding.spinButtonImpactFlash
        spinImpactAnimator?.cancel()
        spinImpactAnimator = null
        flash.animate().cancel()
        if (immediate) {
            flash.visibility = View.INVISIBLE
            flash.alpha = 0f
            flash.scaleX = 1f
            flash.scaleY = 1f
            flash.translationY = 0f
            flash.rotation = 0f
            return
        }
        flash.animate()
            .alpha(0f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .rotation(0f)
            .setDuration(110L)
            .withEndAction { flash.visibility = View.INVISIBLE }
            .start()
    }

    private fun updateSlamStopCue(active: Boolean) {
        if (active) {
            startSlamStopCue()
        } else {
            stopSlamStopCue()
        }
    }

    private fun startSlamStopCue() {
        val cue = binding.slamStopCue
        if (slamStopCueAnimator != null) {
            cue.visibility = View.VISIBLE
            return
        }
        cue.animate().cancel()
        val theme = viewModel.uiState.value.config.theme
        binding.slamStopCue.setImageResourceIfChanged(slamStopCueDrawable(theme))
        cue.visibility = View.VISIBLE
        cue.alpha = SLAM_STOP_CUE_LOW_ALPHA
        cue.scaleX = 0.86f
        cue.scaleY = 0.86f
        cue.rotation = -8f
        if (!ValueAnimator.areAnimatorsEnabled() || !shouldUseRichSpinEffects()) {
            cue.alpha = SLAM_STOP_CUE_HIGH_ALPHA
            cue.scaleX = 1f
            cue.scaleY = 1f
            cue.rotation = 0f
            return
        }

        slamStopCueAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(cue, View.ALPHA, SLAM_STOP_CUE_LOW_ALPHA, SLAM_STOP_CUE_HIGH_ALPHA, SLAM_STOP_CUE_LOW_ALPHA).apply {
                    duration = SLAM_STOP_CUE_PULSE_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(cue, View.SCALE_X, 0.86f, 1.08f, 0.86f).apply {
                    duration = SLAM_STOP_CUE_PULSE_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(cue, View.SCALE_Y, 0.86f, 1.08f, 0.86f).apply {
                    duration = SLAM_STOP_CUE_PULSE_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(cue, View.ROTATION, -8f, 8f, -8f).apply {
                    duration = SLAM_STOP_CUE_ROTATION_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                }
            )
            start()
        }
    }

    private fun stopSlamStopCue(immediate: Boolean = false) {
        val binding = _binding ?: return
        val cue = binding.slamStopCue
        slamStopCueAnimator?.cancel()
        slamStopCueAnimator = null
        cue.animate().cancel()
        if (immediate) {
            cue.visibility = View.INVISIBLE
            cue.alpha = 0f
            cue.scaleX = 1f
            cue.scaleY = 1f
            cue.rotation = 0f
            return
        }
        cue.animate()
            .alpha(0f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setDuration(SLAM_STOP_CUE_FADE_DURATION_MS)
            .withEndAction { cue.visibility = View.INVISIBLE }
            .start()
    }

    private fun updateSpinReadyGlow(enabled: Boolean) {
        if (enabled) {
            startSpinReadyGlow()
        } else {
            stopSpinReadyGlow(immediate = !shouldUseRichSpinEffects())
        }
    }

    private fun startSpinReadyGlow() {
        val glow = binding.spinButtonReadyGlow
        if (spinReadyGlowAnimator != null) return
        glow.visibility = View.VISIBLE
        glow.alpha = 0.78f
        glow.scaleX = 0.98f
        glow.scaleY = 0.98f
        if (!ValueAnimator.areAnimatorsEnabled() || !shouldUseRichSpinEffects()) return

        val alpha = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.68f, 1f, 0.68f).apply {
            duration = 1_250L
        }
        val scaleX = ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.98f, 1.045f, 0.98f).apply {
            duration = 1_250L
        }
        val scaleY = ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.98f, 1.045f, 0.98f).apply {
            duration = 1_250L
        }
        spinReadyGlowAnimator = AnimatorSet().apply {
            playTogether(alpha, scaleX, scaleY)
            start()
        }
    }

    private fun stopSpinReadyGlow(immediate: Boolean = false) {
        val glow = binding.spinButtonReadyGlow
        spinReadyGlowAnimator?.cancel()
        spinReadyGlowAnimator = null
        glow.animate().cancel()
        if (immediate) {
            glow.visibility = View.INVISIBLE
            glow.alpha = 0f
            glow.scaleX = 1f
            glow.scaleY = 1f
            return
        }
        glow.animate()
            .alpha(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160L)
            .withEndAction { glow.visibility = View.INVISIBLE }
            .start()
    }

    private fun updateAutoSpinActiveHalo(active: Boolean) {
        if (active) {
            startAutoSpinActiveHalo()
        } else {
            stopAutoSpinActiveHalo()
        }
    }

    private fun startAutoSpinActiveHalo() {
        val halo = binding.autoSpinActiveHalo
        autoSpinHaloGeneration += 1
        halo.animate().cancel()
        binding.autoSpinActiveHalo.setImageResourceIfChanged(
            autoSpinActiveHaloDrawable(viewModel.uiState.value.config.theme)
        )
        halo.visibility = View.VISIBLE
        if (autoSpinHaloAnimator != null) return
        halo.alpha = AUTO_SPIN_HALO_LOW_ALPHA
        halo.scaleX = 0.9f
        halo.scaleY = 0.9f
        halo.rotation = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        autoSpinHaloAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(halo, View.ALPHA, AUTO_SPIN_HALO_LOW_ALPHA, AUTO_SPIN_HALO_HIGH_ALPHA, AUTO_SPIN_HALO_LOW_ALPHA).apply {
                    duration = AUTO_SPIN_HALO_PULSE_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(halo, View.SCALE_X, 0.9f, 1.18f, 0.9f).apply {
                    duration = AUTO_SPIN_HALO_PULSE_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(halo, View.SCALE_Y, 0.9f, 1.18f, 0.9f).apply {
                    duration = AUTO_SPIN_HALO_PULSE_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                },
                ObjectAnimator.ofFloat(halo, View.ROTATION, 0f, 360f).apply {
                    duration = AUTO_SPIN_HALO_ROTATION_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                }
            )
            start()
        }
    }

    private fun stopAutoSpinActiveHalo(immediate: Boolean = false) {
        val halo = binding.autoSpinActiveHalo
        autoSpinHaloGeneration += 1
        val generation = autoSpinHaloGeneration
        autoSpinHaloAnimator?.cancel()
        autoSpinHaloAnimator = null
        halo.animate().cancel()
        if (immediate) {
            halo.visibility = View.INVISIBLE
            halo.alpha = 0f
            halo.scaleX = 1f
            halo.scaleY = 1f
            halo.rotation = 0f
            return
        }
        halo.animate()
            .alpha(0f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setDuration(160L)
            .withEndAction {
                if (generation == autoSpinHaloGeneration) {
                    halo.visibility = View.INVISIBLE
                }
            }
            .start()
    }

    private fun reelColumnCells(column: Int): List<ImageView> {
        return (0 until REEL_VISIBLE_ROWS).mapNotNull { row ->
            reelCells.getOrNull(row * REEL_COUNT + column)
        }
    }

    private fun renderReelColumn(theme: SlotTheme, column: Int, symbols: List<String>) {
        reelColumnCells(column).forEachIndexed { row, imageView ->
            val symbol = symbols.getOrNull(row) ?: return@forEachIndexed

            imageView.animate().cancel()
            imageView.animate().setStartDelay(0L)
            imageView.setImageResourceIfChanged(SlotSymbolResources.image(theme, symbol))
            imageView.alpha = 1f
            imageView.scaleX = 1f
            imageView.scaleY = 1f
            imageView.background = null
        }
    }

    private fun spinningColumnSymbols(config: SlotConfig, column: Int, offset: Int): List<String> {
        val symbols = config.reelStripsFor(viewModel.uiState.value.isCurrentSpinFreeSpin).getOrNull(column)
            ?.takeIf { it.isNotEmpty() }
            ?: config.symbols
        if (symbols.isEmpty()) return emptyList()
        return List(REEL_VISIBLE_ROWS) { row ->
            val symbolIndex = wrappedStripIndex(offset + column * REEL_COLUMN_OFFSET + row, symbols.size)
            symbols[symbolIndex]
        }
    }

    private fun spinningStripSymbols(config: SlotConfig, column: Int, offset: Int): List<String> {
        val useFreeSpinReels = viewModel.uiState.value.isCurrentSpinFreeSpin
        val activeReelStrips = config.reelStripsFor(useFreeSpinReels)
        val symbols = activeReelStrips.getOrNull(column)
            ?.takeIf { it.isNotEmpty() }
            ?: config.symbols
        if (symbols.isEmpty()) return emptyList()
        return List(REEL_SPIN_STRIP_SYMBOL_COUNT) { stripRow ->
            val symbolIndex = wrappedStripIndex(offset + column * REEL_COLUMN_OFFSET + stripRow, symbols.size)
            if (activeReelStrips.getOrNull(column) === symbols) {
                config.reelSymbolAt(column, symbolIndex, useFreeSpinReels)
            } else {
                symbols[symbolIndex]
            }
        }
    }

    private fun alignedStoppingStep(
        currentOffset: Int,
        targetOffset: Int,
        framesRemaining: Int,
        maximumStep: Int
    ): Int {
        return ReelStopAlignment.step(
            currentOffset,
            targetOffset,
            framesRemaining,
            maximumStep
        )
    }

    private fun wrappedStripIndex(index: Int, size: Int): Int {
        return ((index % size) + size) % size
    }

    private fun renderSpinStripColumn(
        theme: SlotTheme,
        column: Int,
        symbols: List<String>,
        motionBlurred: Boolean
    ) {
        val strip = reelSpinStrips.getOrNull(column) ?: return
        if (symbols.size < REEL_SPIN_STRIP_SYMBOL_COUNT) return
        val resourceIds = reelSpinResourceIds[column]
        val useBlurTextures = motionBlurred && shouldUseRichSpinEffects()
        for (index in 0 until REEL_SPIN_STRIP_SYMBOL_COUNT) {
            resourceIds[index] = if (useBlurTextures) {
                SlotSymbolResources.spinImage(theme, symbols[index])
            } else {
                SlotSymbolResources.image(theme, symbols[index])
            }
        }
        strip.setSymbols(
            resourceIds = resourceIds,
            symbolAlpha = if (motionBlurred) REEL_SPIN_SYMBOL_BLUR_ALPHA else 1f,
            symbolScaleY = if (motionBlurred) REEL_SPIN_SYMBOL_BLUR_SCALE_Y else 1f
        )
    }

    private fun animateReelColumnSpin(column: Int) {
        val travelPx = reelColumnTravelPx()
        reelColumnCells(column).forEach { imageView ->
            imageView.animate().cancel()
            imageView.animate().setStartDelay(0L)
            imageView.translationY = -travelPx
            imageView.alpha = 0.58f
            imageView.scaleY = 1.16f
            if (!ValueAnimator.areAnimatorsEnabled()) {
                imageView.translationY = 0f
                imageView.alpha = 1f
                imageView.scaleY = 1f
                return@forEach
            }
            imageView.animate()
                .translationY(0f)
                .alpha(0.94f)
                .scaleY(1f)
                .setInterpolator(reelSpinInterpolator)
                .setDuration(REEL_SPIN_TICK_MS + REEL_SPIN_OVERLAP_MS)
                .start()
        }
    }

    private fun reelColumnTravelPx(): Float {
        val measuredCellHeight = reelCells.firstOrNull { it.height > 0 }?.height ?: 0
        return if (measuredCellHeight > 0) {
            (measuredCellHeight + REEL_SPIN_CELL_GAP_DP.dp()).toFloat()
        } else {
            REEL_SPIN_FALLBACK_TRAVEL_DP.dp().toFloat()
        }
    }

    private fun animateReelColumnStop(column: Int) {
        val settleStartPx = REEL_STOP_SETTLE_TRAVEL_DP.dp().toFloat()
        reelColumnCells(column).forEachIndexed { row, imageView ->
            imageView.animate().cancel()
            imageView.animate().setStartDelay(0L)
            imageView.translationY = -settleStartPx
            imageView.alpha = 1f
            imageView.scaleY = 0.94f
            if (!ValueAnimator.areAnimatorsEnabled()) {
                imageView.translationY = 0f
                imageView.scaleY = 1f
                return@forEachIndexed
            }
            AnimatorSet().apply {
                startDelay = row * REEL_STOP_ROW_DELAY_MS
                interpolator = reelStopInterpolator
                playTogether(
                    ObjectAnimator.ofFloat(imageView, View.TRANSLATION_Y, -settleStartPx, 9f, -3f, 0f),
                    ObjectAnimator.ofFloat(imageView, View.SCALE_Y, 0.94f, 1.04f, 0.99f, 1f),
                    ObjectAnimator.ofFloat(imageView, View.ALPHA, 0.86f, 1f)
                )
                duration = REEL_STOP_BOUNCE_DURATION_MS
                start()
            }
        }
    }

    private fun animateSpinStripColumn(
        column: Int,
        phase: ReelSpinPhase,
        scatterChase: Boolean,
        step: Int,
        durationMs: Long
    ) {
        if (!ReelSpinTrajectory.shouldAnimate(step)) return
        val strip = reelSpinStrips.getOrNull(column) ?: return
        val cellHeight = reelStripCellHeightPx(column)
        strip.setDrawnSymbolRange(
            ReelSpinTrajectory.SETTLED_CELL_OFFSET.toInt(),
            (
                ReelSpinTrajectory.animationStartCellOffset(step).toInt() +
                    REEL_VISIBLE_ROWS -
                    1
                ).coerceAtMost(REEL_SPIN_STRIP_SYMBOL_COUNT - 1)
        )
        strip.animate().cancel()
        reelSpinStopAnimators.remove(column)?.cancel()
        animateReelMotionStreak(column, phase, scatterChase, durationMs)
        strip.visibility = View.VISIBLE
        val richEffects = shouldUseRichSpinEffects()
        strip.alpha = if (richEffects) reelSpinStartAlpha(phase) else REEL_SPIN_SYMBOL_BLUR_ALPHA
        strip.scaleY = if (richEffects) reelSpinStartScaleY(phase) else REEL_SPIN_SYMBOL_BLUR_SCALE_Y
        strip.scaleX = 1f
        strip.translationX = 0f
        strip.translationY = -cellHeight * ReelSpinTrajectory.animationStartCellOffset(step)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            strip.translationY = -cellHeight
            strip.scaleX = 1f
            strip.translationX = 0f
            strip.scaleY = 1f
            strip.alpha = 1f
            return
        }
        val motion = strip.animate()
            .translationY(-cellHeight * ReelSpinTrajectory.SETTLED_CELL_OFFSET)
            .setInterpolator(reelSpinInterpolatorFor(phase, scatterChase))
            .setDuration(durationMs + REEL_SPIN_OVERLAP_MS)
        if (richEffects) {
            motion
                .alpha(reelSpinEndAlpha(phase))
                .scaleY(reelSpinEndScaleY(phase))
        }
        motion.start()
    }

    private fun animateSpinStripColumnStop(column: Int, slamStopping: Boolean = false) {
        val strip = reelSpinStrips.getOrNull(column) ?: return
        val cellHeight = reelStripCellHeightPx(column)
        strip.setDrawnSymbolRange(
            firstIndex = 0,
            lastIndex = (REEL_VISIBLE_ROWS + 1).coerceAtMost(REEL_SPIN_STRIP_SYMBOL_COUNT - 1)
        )
        val columnFrame = reelSpinColumns.getOrNull(column)
        strip.animate().cancel()
        val stopStartCellOffset = if (cellHeight > 0f) {
            -strip.translationY / cellHeight
        } else {
            ReelSpinTrajectory.SETTLED_CELL_OFFSET
        }
        val stopStartScaleY = strip.scaleY
        val stopStartAlpha = strip.alpha
        val stopBounceOffsets = ReelSpinTrajectory.stopBounceCellOffsets(stopStartCellOffset)
            .map { offset -> -cellHeight * offset }
            .toFloatArray()
        columnFrame?.animate()?.cancel()
        reelSpinStopAnimators.remove(column)?.cancel()
        if (shouldUseRichSpinEffects()) {
            settleReelMotionStreakColumn(column)
            pulseReelBrakeColumn(column, scatterChase = false, finalStop = true)
        } else {
            hideReelMotionStreakLayer(immediate = true)
            hideReelBrakeLayer(immediate = true)
        }
        columnFrame?.visibility = View.VISIBLE
        columnFrame?.alpha = 1f
        strip.visibility = View.VISIBLE
        strip.scaleX = 1f
        strip.translationX = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            strip.translationY = -cellHeight
            strip.translationX = 0f
            strip.scaleX = 1f
            strip.scaleY = 1f
            columnFrame?.alpha = 0f
            columnFrame?.visibility = View.INVISIBLE
            return
        }

        reelSpinStopAnimators[column] = AnimatorSet().apply {
            interpolator = reelStopInterpolator
            playTogether(
                ObjectAnimator.ofFloat(
                    strip,
                    View.TRANSLATION_Y,
                    *stopBounceOffsets
                ),
                ObjectAnimator.ofFloat(strip, View.SCALE_X, 1f, 0.985f, 1.01f, 1f),
                ObjectAnimator.ofFloat(strip, View.SCALE_Y, stopStartScaleY, 0.92f, 1.035f, 0.99f, 1f),
                ObjectAnimator.ofFloat(strip, View.ALPHA, stopStartAlpha, 1f)
            )
            duration = if (slamStopping) {
                SlotSpinTimeline.SLAM_STOP_BOUNCE_DURATION_MS
            } else {
                REEL_STOP_STRIP_BOUNCE_DURATION_MS
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (reelSpinStopAnimators[column] === animation) {
                        reelSpinStopAnimators.remove(column)
                    }
                    fadeStoppedSpinColumn(column)
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (reelSpinStopAnimators[column] === animation) {
                        reelSpinStopAnimators.remove(column)
                    }
                }
            })
            start()
        }
    }

    private fun revealStoppedReelColumn(theme: SlotTheme, column: Int, symbols: List<String>) {
        reelColumnCells(column).forEachIndexed { row, imageView ->
            val symbol = symbols.getOrNull(row) ?: return@forEachIndexed
            imageView.animate().cancel()
            imageView.animate().setStartDelay(0L)
            imageView.setImageResourceIfChanged(SlotSymbolResources.image(theme, symbol))
            imageView.alpha = 1f
            imageView.scaleX = 1f
            imageView.scaleY = 1f
            imageView.translationY = 0f
        }
    }

    private fun fadeStoppedSpinColumn(column: Int) {
        val columnFrame = reelSpinColumns.getOrNull(column) ?: return
        columnFrame.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            columnFrame.alpha = 0f
            columnFrame.visibility = View.INVISIBLE
            return
        }
        columnFrame.animate()
            .alpha(0f)
            .setDuration(REEL_STOPPED_COLUMN_FADE_MS)
            .withEndAction {
                columnFrame.visibility = View.INVISIBLE
                columnFrame.alpha = 0f
            }
            .start()
    }

    private fun animateSpinStripColumnAnticipation(column: Int, scatterChase: Boolean) {
        if (!scatterChase) return
        val strip = reelSpinStrips.getOrNull(column) ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val nudgePx = REEL_SCATTER_ANTICIPATION_NUDGE_DP.dp().toFloat()
        animateReelAnticipationKick(column, scatterChase)
        pulseReelBrakeColumn(column, scatterChase = scatterChase)
        pulseReelLandingSparkColumn(column)
        pulseReelMotionStreakColumn(column, scatterChase)
        pulseReelAnticipationBeamColumn(column, scatterChase)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(strip, View.SCALE_X, 1f, 0.972f, 1.018f, 1f),
                ObjectAnimator.ofFloat(strip, View.TRANSLATION_X, 0f, -nudgePx, nudgePx, 0f)
            )
            duration = REEL_SCATTER_ANTICIPATION_DURATION_MS
            start()
        }
    }

    private fun animateReelAnticipationKick(column: Int, scatterChase: Boolean) {
        val mask = binding.reelWindowDepthMask
        val aperture = binding.reelApertureShadow
        reelAnticipationKickAnimator?.cancel()
        reelAnticipationKickAnimator = null
        mask.animate().cancel()
        aperture.animate().cancel()
        mask.visibility = View.VISIBLE
        mask.alpha = REEL_WINDOW_DEPTH_SETTLED_ALPHA
        mask.scaleX = 1f
        mask.scaleY = 1f
        mask.translationY = 0f
        aperture.visibility = View.VISIBLE
        aperture.alpha = REEL_APERTURE_SETTLED_ALPHA
        aperture.scaleX = 1f
        aperture.scaleY = 1f
        aperture.translationY = 0f
        pulseReelAnticipationColumn(column, scatterChase)
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val kickTravel = (if (scatterChase) REEL_SCATTER_WINDOW_KICK_TRAVEL_DP else REEL_STOP_WINDOW_KICK_TRAVEL_DP).dp().toFloat()
        val highAlpha = if (scatterChase) 1f else 0.96f
        val settleAlpha = if (scatterChase) 0.82f else 0.84f
        val duration = if (scatterChase) REEL_SCATTER_WINDOW_KICK_DURATION_MS else REEL_STOP_WINDOW_KICK_DURATION_MS
        reelAnticipationKickAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    mask,
                    View.ALPHA,
                    REEL_WINDOW_DEPTH_SETTLED_ALPHA,
                    highAlpha,
                    settleAlpha,
                    REEL_WINDOW_DEPTH_SETTLED_ALPHA
                ),
                ObjectAnimator.ofFloat(mask, View.SCALE_X, 1f, if (scatterChase) 1.018f else 1.012f, 0.996f, 1f),
                ObjectAnimator.ofFloat(mask, View.SCALE_Y, 1f, if (scatterChase) 1.028f else 1.02f, 0.99f, 1f),
                ObjectAnimator.ofFloat(mask, View.TRANSLATION_Y, 0f, -kickTravel, kickTravel * 0.55f, 0f),
                ObjectAnimator.ofFloat(aperture, View.ALPHA, REEL_APERTURE_SETTLED_ALPHA, 1f, REEL_APERTURE_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(aperture, View.SCALE_X, 1f, if (scatterChase) 1.014f else 1.008f, 0.998f, 1f),
                ObjectAnimator.ofFloat(aperture, View.SCALE_Y, 1f, if (scatterChase) 1.022f else 1.014f, 0.996f, 1f),
                ObjectAnimator.ofFloat(aperture, View.TRANSLATION_Y, 0f, -kickTravel * 0.72f, kickTravel * 0.35f, 0f)
            )
            this.duration = duration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleReelAnticipationKick(animation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleReelAnticipationKick(animation)
                }
            })
            start()
        }
    }

    private fun settleReelAnticipationKick(animation: Animator? = reelAnticipationKickAnimator) {
        if (animation != null && reelAnticipationKickAnimator !== animation) return
        reelAnticipationKickAnimator = null
        if (_binding == null) return
        binding.reelWindowDepthMask.alpha = REEL_WINDOW_DEPTH_SETTLED_ALPHA
        binding.reelWindowDepthMask.scaleX = 1f
        binding.reelWindowDepthMask.scaleY = 1f
        binding.reelWindowDepthMask.translationY = 0f
        binding.reelApertureShadow.alpha = REEL_APERTURE_SETTLED_ALPHA
        binding.reelApertureShadow.scaleX = 1f
        binding.reelApertureShadow.scaleY = 1f
        binding.reelApertureShadow.translationY = 0f
    }

    private fun pulseReelAnticipationColumn(column: Int, scatterChase: Boolean) {
        val flash = reelStopFlashViews.getOrNull(column) ?: return
        flash.setImageResourceIfChanged(reelStopFlashDrawable(viewModel.uiState.value.config.theme))
        val layer = binding.reelStopFlashLayer
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 1f
        flash.animate().cancel()
        flash.visibility = View.VISIBLE
        flash.alpha = 0f
        flash.scaleX = 0.86f
        flash.scaleY = 0.96f
        flash.translationY = -10f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            flash.visibility = View.INVISIBLE
            flash.alpha = 0f
            flash.clearBoundImageResource()
            return
        }
        val flashAlpha = if (scatterChase) REEL_SCATTER_ANTICIPATION_FLASH_ALPHA else REEL_STOP_ANTICIPATION_FLASH_ALPHA
        val flashScale = if (scatterChase) 1.1f else 1.02f
        val introDuration = if (scatterChase) 130L else 90L
        val outroDuration = if (scatterChase) 190L else 120L
        flash.animate()
            .alpha(flashAlpha)
            .scaleX(flashScale)
            .scaleY(flashScale)
            .translationY(-2f)
            .setDuration(introDuration)
            .withEndAction {
                flash.animate()
                    .alpha(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(outroDuration)
                    .withEndAction {
                        flash.visibility = View.INVISIBLE
                        flash.clearBoundImageResource()
                    }
                    .start()
            }
            .start()
    }

    private fun reelSpinPhase(elapsedMs: Long, stopAtMs: Long): ReelSpinPhase {
        return when {
            elapsedMs < REEL_SPIN_ACCELERATION_MS -> ReelSpinPhase.Acceleration
            stopAtMs - elapsedMs <= REEL_SPIN_DECELERATION_MS -> ReelSpinPhase.Deceleration
            else -> ReelSpinPhase.Cruise
        }
    }

    private fun reelSpinFrameDurationMs(phase: ReelSpinPhase, column: Int, scatterChase: Boolean): Long {
        val columnLag = (column * REEL_SPIN_COLUMN_DURATION_OFFSET_MS).toLong()
        if (scatterChase && phase == ReelSpinPhase.Deceleration) {
            return REEL_SCATTER_ANTICIPATION_FRAME_MS + columnLag
        }
        return when (phase) {
            ReelSpinPhase.Acceleration -> REEL_SPIN_ACCEL_FRAME_MS + columnLag
            ReelSpinPhase.Cruise -> REEL_SPIN_CRUISE_FRAME_MS
            ReelSpinPhase.Deceleration -> REEL_SPIN_DECEL_FRAME_MS + columnLag
        }
    }

    private fun reelSpinSymbolStep(phase: ReelSpinPhase, column: Int, scatterChase: Boolean): Int {
        if (scatterChase && phase == ReelSpinPhase.Deceleration) return REEL_SCATTER_ANTICIPATION_STEP_SYMBOLS
        return when (phase) {
            ReelSpinPhase.Acceleration -> REEL_SPIN_ACCEL_STEP_SYMBOLS
            ReelSpinPhase.Cruise -> REEL_SPIN_CRUISE_STEP_SYMBOLS
            ReelSpinPhase.Deceleration -> REEL_SPIN_DECEL_STEP_SYMBOLS
        }
    }

    private fun reelStopAtMs(config: SlotConfig, targetResult: SpinResult?, column: Int): Long {
        return SlotSpinTimeline.stopAtMs(config, targetResult, column)
    }

    private fun hasScatterChaseForColumn(config: SlotConfig, targetResult: SpinResult?, column: Int): Boolean {
        return SlotSpinTimeline.hasScatterChase(config, targetResult, column)
    }

    private fun reelSpinStartAlpha(phase: ReelSpinPhase): Float {
        return when (phase) {
            ReelSpinPhase.Acceleration -> 0.72f
            ReelSpinPhase.Cruise -> 0.96f
            ReelSpinPhase.Deceleration -> 0.9f
        }
    }

    private fun reelSpinEndAlpha(phase: ReelSpinPhase): Float {
        return when (phase) {
            ReelSpinPhase.Acceleration -> 0.9f
            ReelSpinPhase.Cruise -> 0.88f
            ReelSpinPhase.Deceleration -> 0.98f
        }
    }

    private fun reelSpinStartScaleY(phase: ReelSpinPhase): Float {
        return when (phase) {
            ReelSpinPhase.Acceleration -> 0.98f
            ReelSpinPhase.Cruise -> 1.06f
            ReelSpinPhase.Deceleration -> 1.1f
        }
    }

    private fun reelSpinEndScaleY(phase: ReelSpinPhase): Float {
        return when (phase) {
            ReelSpinPhase.Acceleration -> 1.08f
            ReelSpinPhase.Cruise -> 1.1f
            ReelSpinPhase.Deceleration -> 1.03f
        }
    }

    private fun reelSpinInterpolatorFor(
        phase: ReelSpinPhase,
        scatterChase: Boolean
    ): android.animation.TimeInterpolator {
        return when {
            scatterChase && phase == ReelSpinPhase.Deceleration -> reelDecelerationInterpolator
            phase == ReelSpinPhase.Acceleration -> reelAccelerationInterpolator
            phase == ReelSpinPhase.Deceleration -> reelDecelerationInterpolator
            else -> reelSpinInterpolator
        }
    }

    private fun animateReelMotionStreak(column: Int, phase: ReelSpinPhase, scatterChase: Boolean, durationMs: Long) {
        val streak = reelMotionStreakViews.getOrNull(column) ?: return
        val mode = phase.ordinal * REEL_MOTION_STREAK_MODE_VARIANTS + if (scatterChase) 1 else 0
        if (reelMotionStreakModes[column] == mode) return
        reelMotionStreakModes[column] = mode
        if (
            !scatterChase &&
            (!shouldUseRichSpinEffects() || phase != ReelSpinPhase.Acceleration)
        ) {
            streak.animate().cancel()
            resetReelMotionStreakView(streak)
            return
        }
        streak.setImageResourceIfChanged(reelMotionStreakDrawable(viewModel.uiState.value.config.theme))
        val layer = binding.reelMotionStreakLayer
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 1f
        streak.animate().cancel()
        streak.visibility = View.VISIBLE
        streak.alpha = reelMotionStreakStartAlpha(phase, scatterChase)
        streak.scaleX = 1f
        streak.scaleY = reelMotionStreakStartScaleY(phase, scatterChase)
        streak.translationY = -reelMotionStreakTravelPx(column, phase) * 0.38f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            streak.alpha = reelMotionStreakEndAlpha(phase, scatterChase)
            streak.scaleY = 1f
            streak.translationY = 0f
            return
        }
        streak.animate()
            .alpha(reelMotionStreakEndAlpha(phase, scatterChase))
            .scaleY(reelMotionStreakEndScaleY(phase, scatterChase))
            .translationY(reelMotionStreakTravelPx(column, phase) * 0.42f)
            .setInterpolator(reelSpinInterpolator)
            .setDuration(durationMs)
            .start()
    }

    private fun pulseReelMotionStreakColumn(column: Int, scatterChase: Boolean) {
        val streak = reelMotionStreakViews.getOrNull(column) ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        streak.animate().cancel()
        streak.visibility = View.VISIBLE
        streak.alpha = if (scatterChase) REEL_MOTION_STREAK_SCATTER_ALPHA else REEL_MOTION_STREAK_ANTICIPATION_ALPHA
        streak.scaleX = if (scatterChase) 1.08f else 1.04f
        streak.scaleY = if (scatterChase) 1.2f else 1.12f
        streak.translationY = -reelMotionStreakTravelPx(column, ReelSpinPhase.Deceleration) * 0.28f
        streak.animate()
            .alpha(if (scatterChase) 0.58f else 0.34f)
            .scaleX(1f)
            .scaleY(1.04f)
            .translationY(0f)
            .setDuration(if (scatterChase) REEL_MOTION_STREAK_SCATTER_PULSE_MS else REEL_MOTION_STREAK_ANTICIPATION_PULSE_MS)
            .start()
    }

    private fun settleReelMotionStreakColumn(column: Int) {
        val streak = reelMotionStreakViews.getOrNull(column) ?: return
        reelMotionStreakModes[column] = NO_REEL_MOTION_STREAK_MODE
        streak.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetReelMotionStreakView(streak)
            return
        }
        streak.animate()
            .alpha(0f)
            .scaleY(0.92f)
            .translationY(reelMotionStreakTravelPx(column, ReelSpinPhase.Deceleration) * 0.18f)
            .setDuration(REEL_MOTION_STREAK_SETTLE_MS)
            .withEndAction { resetReelMotionStreakView(streak) }
            .start()
    }

    private fun reelMotionStreakTravelPx(column: Int, phase: ReelSpinPhase): Float {
        val multiplier = when (phase) {
            ReelSpinPhase.Acceleration -> 0.78f
            ReelSpinPhase.Cruise -> 1.12f
            ReelSpinPhase.Deceleration -> 0.58f
        }
        return reelStripCellHeightPx(column) * multiplier
    }

    private fun reelMotionStreakStartAlpha(phase: ReelSpinPhase, scatterChase: Boolean): Float {
        if (scatterChase) return REEL_MOTION_STREAK_SCATTER_ALPHA
        return when (phase) {
            ReelSpinPhase.Acceleration -> 0.36f
            ReelSpinPhase.Cruise -> 0.58f
            ReelSpinPhase.Deceleration -> 0.48f
        }
    }

    private fun reelMotionStreakEndAlpha(phase: ReelSpinPhase, scatterChase: Boolean): Float {
        if (scatterChase) return 0.62f
        return when (phase) {
            ReelSpinPhase.Acceleration -> 0.56f
            ReelSpinPhase.Cruise -> 0.68f
            ReelSpinPhase.Deceleration -> 0.28f
        }
    }

    private fun reelMotionStreakStartScaleY(phase: ReelSpinPhase, scatterChase: Boolean): Float {
        if (scatterChase) return 1.18f
        return when (phase) {
            ReelSpinPhase.Acceleration -> 1.04f
            ReelSpinPhase.Cruise -> 1.14f
            ReelSpinPhase.Deceleration -> 1.1f
        }
    }

    private fun reelMotionStreakEndScaleY(phase: ReelSpinPhase, scatterChase: Boolean): Float {
        if (scatterChase) return 1.12f
        return when (phase) {
            ReelSpinPhase.Acceleration -> 1.12f
            ReelSpinPhase.Cruise -> 1.18f
            ReelSpinPhase.Deceleration -> 0.96f
        }
    }

    private fun resetReelMotionStreakView(streak: ImageView) {
        streak.visibility = View.INVISIBLE
        streak.alpha = 0f
        streak.scaleX = 1f
        streak.scaleY = 1f
        streak.translationY = 0f
    }

    private fun reelStripCellHeightPx(column: Int): Float {
        val columnHeight = reelSpinColumns.getOrNull(column)?.height ?: 0
        val measuredColumnCellHeight = if (columnHeight > 0) columnHeight / REEL_VISIBLE_ROWS else 0
        val measuredGridCellHeight = reelCells.firstOrNull { it.height > 0 }?.height ?: 0
        val cellHeight = when {
            measuredColumnCellHeight > 0 -> measuredColumnCellHeight
            measuredGridCellHeight > 0 -> measuredGridCellHeight
            else -> REEL_SPIN_FALLBACK_CELL_HEIGHT_DP.dp()
        }.coerceAtLeast(1)
        val stripHeight = cellHeight * REEL_SPIN_STRIP_SYMBOL_COUNT
        val strip = reelSpinStrips.getOrNull(column)
        if (strip != null && strip.layoutParams.height != stripHeight) {
            strip.layoutParams = (strip.layoutParams as FrameLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = stripHeight
            }
        }
        return cellHeight.toFloat()
    }

    private fun showReelSpinStripLayer() {
        binding.reelSpinStripLayer.visibility = View.VISIBLE
        binding.reelSpinStripLayer.alpha = 1f
        reelSpinColumns.forEach { columnFrame ->
            columnFrame.animate().cancel()
            columnFrame.visibility = View.VISIBLE
            columnFrame.alpha = 1f
        }
        reelSpinSymbolViews.forEach { strip ->
            strip.animate().cancel()
            strip.animate().setStartDelay(0L)
        }
        reelCells.forEach { cell ->
            cell.animate().cancel()
            cell.animate().setStartDelay(0L)
            cell.alpha = 0f
            cell.scaleX = 1f
            cell.scaleY = 1f
            cell.translationY = 0f
        }
        binding.reelMotionStreakLayer.visibility = View.VISIBLE
        binding.reelMotionStreakLayer.alpha = 1f
        hideReelAnticipationBeamLayer(immediate = true)
        hideReelLandingSparkLayer(immediate = true)
        binding.reelsGrid.visibility = View.VISIBLE
        binding.reelsGrid.alpha = 1f
        hideReelBrakeLayer(immediate = true)
        hideSymbolWinHalos(immediate = true)
        hideBonusScatterHalos(immediate = true)
    }

    private fun hideReelSpinStripLayer() {
        reelSpinStopAnimators.values.toList().forEach { it.cancel() }
        reelSpinStopAnimators.clear()
        reelSpinStrips.forEachIndexed { column, strip ->
            val cellHeight = reelStripCellHeightPx(column)
            reelSpinColumns.getOrNull(column)?.let { columnFrame ->
                columnFrame.animate().cancel()
                columnFrame.visibility = View.VISIBLE
                columnFrame.alpha = 1f
            }
            strip.animate().cancel()
            strip.translationY = -cellHeight
            strip.translationX = 0f
            strip.alpha = 1f
            strip.scaleX = 1f
            strip.scaleY = 1f
        }
        hideReelMotionStreakLayer(immediate = false)
        hideReelAnticipationBeamLayer(immediate = false)
        hideReelLandingSparkLayer(immediate = false)
        binding.reelSpinStripLayer.visibility = View.INVISIBLE
        binding.reelSpinStripLayer.alpha = 0f
        binding.reelsGrid.visibility = View.VISIBLE
        binding.reelsGrid.alpha = 1f
    }

    private fun hideReelMotionStreakLayer(immediate: Boolean) {
        val layer = binding.reelMotionStreakLayer
        layer.animate().cancel()
        reelMotionStreakModes.fill(NO_REEL_MOTION_STREAK_MODE)
        reelMotionStreakViews.forEach { streak ->
            streak.animate().cancel()
            if (immediate) {
                resetReelMotionStreakView(streak)
            }
        }
        if (immediate) {
            reelMotionStreakViews.forEach(ImageView::clearBoundImageResource)
            layer.visibility = View.INVISIBLE
            layer.alpha = 0f
            return
        }
        if (layer.visibility != View.VISIBLE && layer.alpha == 0f) return
        layer.animate()
            .alpha(0f)
            .setDuration(REEL_MOTION_STREAK_LAYER_FADE_MS)
            .withEndAction {
                reelMotionStreakViews.forEach(::resetReelMotionStreakView)
                reelMotionStreakViews.forEach(ImageView::clearBoundImageResource)
                layer.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun pulseReelAnticipationBeamColumn(column: Int, scatterChase: Boolean) {
        if (!scatterChase) return
        val binding = _binding ?: return
        val beam = reelAnticipationBeamViews.getOrNull(column) ?: return
        beam.setImageResourceIfChanged(reelAnticipationBeamDrawable(viewModel.uiState.value.config.theme))
        reelAnticipationBeamAnimators.remove(column)?.cancel()
        val layer = binding.reelAnticipationBeamLayer
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 1f
        beam.animate().cancel()
        beam.visibility = View.VISIBLE
        beam.alpha = 0f
        beam.scaleX = 0.86f
        beam.scaleY = 0.94f
        beam.translationY = REEL_SCATTER_BEAM_ENTER_TRAVEL_DP.dp().toFloat()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetReelAnticipationBeamView(beam)
            beam.clearBoundImageResource()
            if (reelAnticipationBeamAnimators.isEmpty()) {
                layer.visibility = View.INVISIBLE
                layer.alpha = 0f
            }
            return
        }

        reelAnticipationBeamAnimators[column] = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(beam, View.ALPHA, 0f, REEL_SCATTER_BEAM_PEAK_ALPHA, REEL_SCATTER_BEAM_SETTLED_ALPHA, 0f),
                ObjectAnimator.ofFloat(beam, View.SCALE_X, 0.86f, 1.08f, 1.02f, 0.98f),
                ObjectAnimator.ofFloat(beam, View.SCALE_Y, 0.94f, 1.14f, 1.04f, 0.98f),
                ObjectAnimator.ofFloat(
                    beam,
                    View.TRANSLATION_Y,
                    REEL_SCATTER_BEAM_ENTER_TRAVEL_DP.dp().toFloat(),
                    -REEL_SCATTER_BEAM_LIFT_DP.dp().toFloat(),
                    0f
                )
            )
            duration = REEL_SCATTER_BEAM_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleReelAnticipationBeamColumn(column, beam, animation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleReelAnticipationBeamColumn(column, beam, animation)
                }
            })
            start()
        }
    }

    private fun settleReelAnticipationBeamColumn(column: Int, beam: ImageView, animation: Animator) {
        if (reelAnticipationBeamAnimators[column] === animation) {
            reelAnticipationBeamAnimators.remove(column)
        }
        resetReelAnticipationBeamView(beam)
        beam.clearBoundImageResource()
        val binding = _binding ?: return
        if (reelAnticipationBeamAnimators.isEmpty()) {
            binding.reelAnticipationBeamLayer.visibility = View.INVISIBLE
            binding.reelAnticipationBeamLayer.alpha = 0f
        }
    }

    private fun resetReelAnticipationBeamView(beam: ImageView) {
        beam.visibility = View.INVISIBLE
        beam.alpha = 0f
        beam.scaleX = 1f
        beam.scaleY = 1f
        beam.translationY = 0f
    }

    private fun hideReelAnticipationBeamLayer(immediate: Boolean) {
        val binding = _binding ?: return
        val layer = binding.reelAnticipationBeamLayer
        reelAnticipationBeamAnimators.values.toList().forEach { it.cancel() }
        reelAnticipationBeamAnimators.clear()
        layer.animate().cancel()
        reelAnticipationBeamViews.forEach { beam ->
            beam.animate().cancel()
            resetReelAnticipationBeamView(beam)
        }
        if (immediate) {
            reelAnticipationBeamViews.forEach(ImageView::clearBoundImageResource)
            layer.visibility = View.INVISIBLE
            layer.alpha = 0f
            return
        }
        if (layer.visibility != View.VISIBLE && layer.alpha == 0f) return
        layer.animate()
            .alpha(0f)
            .setDuration(REEL_SCATTER_BEAM_FADE_MS)
            .withEndAction {
                reelAnticipationBeamViews.forEach(ImageView::clearBoundImageResource)
                layer.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun pulseReelLandingSparkColumn(column: Int) {
        val binding = _binding ?: return
        val spark = reelLandingSparkViews.getOrNull(column) ?: return
        spark.setImageResourceIfChanged(reelLandingSparkDrawable(viewModel.uiState.value.config.theme))
        reelLandingSparkAnimators.remove(column)?.cancel()
        val layer = binding.reelLandingSparkLayer
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 1f
        spark.animate().cancel()
        spark.visibility = View.VISIBLE
        spark.alpha = 0f
        spark.scaleX = 0.72f
        spark.scaleY = 0.82f
        spark.translationY = REEL_LANDING_SPARK_ENTER_TRAVEL_DP.dp().toFloat()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetReelLandingSparkView(spark)
            spark.clearBoundImageResource()
            if (reelLandingSparkAnimators.isEmpty()) {
                layer.visibility = View.INVISIBLE
                layer.alpha = 0f
            }
            return
        }

        reelLandingSparkAnimators[column] = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(spark, View.ALPHA, 0f, REEL_LANDING_SPARK_PEAK_ALPHA, REEL_LANDING_SPARK_SETTLE_ALPHA, 0f),
                ObjectAnimator.ofFloat(spark, View.SCALE_X, 0.72f, 1.16f, 1.02f, 0.96f),
                ObjectAnimator.ofFloat(spark, View.SCALE_Y, 0.82f, 1.1f, 1.02f, 0.98f),
                ObjectAnimator.ofFloat(
                    spark,
                    View.TRANSLATION_Y,
                    REEL_LANDING_SPARK_ENTER_TRAVEL_DP.dp().toFloat(),
                    -REEL_LANDING_SPARK_LIFT_DP.dp().toFloat(),
                    0f
                )
            )
            duration = REEL_LANDING_SPARK_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleReelLandingSparkColumn(column, spark, animation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleReelLandingSparkColumn(column, spark, animation)
                }
            })
            start()
        }
    }

    private fun settleReelLandingSparkColumn(column: Int, spark: ImageView, animation: Animator) {
        if (reelLandingSparkAnimators[column] === animation) {
            reelLandingSparkAnimators.remove(column)
        }
        resetReelLandingSparkView(spark)
        spark.clearBoundImageResource()
        val binding = _binding ?: return
        if (reelLandingSparkAnimators.isEmpty()) {
            binding.reelLandingSparkLayer.visibility = View.INVISIBLE
            binding.reelLandingSparkLayer.alpha = 0f
        }
    }

    private fun resetReelLandingSparkView(spark: ImageView) {
        spark.visibility = View.INVISIBLE
        spark.alpha = 0f
        spark.scaleX = 1f
        spark.scaleY = 1f
        spark.translationY = 0f
    }

    private fun hideReelLandingSparkLayer(immediate: Boolean) {
        val binding = _binding ?: return
        val layer = binding.reelLandingSparkLayer
        reelLandingSparkAnimators.values.toList().forEach { it.cancel() }
        reelLandingSparkAnimators.clear()
        layer.animate().cancel()
        reelLandingSparkViews.forEach { spark ->
            spark.animate().cancel()
            resetReelLandingSparkView(spark)
        }
        if (immediate) {
            reelLandingSparkViews.forEach(ImageView::clearBoundImageResource)
            layer.visibility = View.INVISIBLE
            layer.alpha = 0f
            return
        }
        if (layer.visibility != View.VISIBLE && layer.alpha == 0f) return
        layer.animate()
            .alpha(0f)
            .setDuration(REEL_LANDING_SPARK_FADE_MS)
            .withEndAction {
                reelLandingSparkViews.forEach(ImageView::clearBoundImageResource)
                layer.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun pulseReelStopColumn(column: Int) {
        slotSoundPlayer?.play(SlotSoundCue.ReelStop, reelIndex = column)
        if (hapticsEnabled) {
            binding.reelsGrid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun pulseReelBrakeColumn(column: Int, scatterChase: Boolean, finalStop: Boolean = false) {
        val binding = _binding ?: return
        val brake = reelBrakeViews.getOrNull(column) ?: return
        brake.setImageResourceIfChanged(reelBrakeClampDrawable(viewModel.uiState.value.config.theme))
        reelBrakeAnimators.remove(column)?.cancel()
        val layer = binding.reelBrakeLayer
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 1f
        brake.animate().cancel()
        brake.visibility = View.VISIBLE
        brake.alpha = 0f
        brake.scaleX = if (finalStop) 0.9f else 0.94f
        brake.scaleY = if (finalStop) 1.14f else 1.05f
        brake.translationY = if (finalStop) -18f else -8f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetReelBrakeView(brake)
            brake.clearBoundImageResource()
            if (reelBrakeAnimators.isEmpty() && reelBrakeSequenceAnimator == null) {
                layer.visibility = View.INVISIBLE
                layer.alpha = 0f
            }
            return
        }

        val highAlpha = when {
            scatterChase -> REEL_BRAKE_SCATTER_HIGH_ALPHA
            finalStop -> REEL_BRAKE_FINAL_HIGH_ALPHA
            else -> REEL_BRAKE_ANTICIPATION_HIGH_ALPHA
        }
        val duration = when {
            scatterChase -> REEL_BRAKE_SCATTER_PULSE_DURATION_MS
            finalStop -> REEL_BRAKE_FINAL_PULSE_DURATION_MS
            else -> REEL_BRAKE_ANTICIPATION_PULSE_DURATION_MS
        }
        reelBrakeAnimators[column] = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(brake, View.ALPHA, 0f, highAlpha, highAlpha * 0.45f, 0f),
                ObjectAnimator.ofFloat(brake, View.SCALE_X, brake.scaleX, 1.06f, 0.98f, 1f),
                ObjectAnimator.ofFloat(brake, View.SCALE_Y, brake.scaleY, 0.92f, 1.04f, 1f),
                ObjectAnimator.ofFloat(brake, View.TRANSLATION_Y, brake.translationY, 6f, -2f, 0f)
            )
            this.duration = duration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleReelBrakeColumn(column, brake, animation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleReelBrakeColumn(column, brake, animation)
                }
            })
            start()
        }
    }

    private fun settleReelBrakeColumn(column: Int, brake: ImageView, animation: Animator) {
        if (reelBrakeAnimators[column] === animation) {
            reelBrakeAnimators.remove(column)
        }
        resetReelBrakeView(brake)
        brake.clearBoundImageResource()
        val binding = _binding ?: return
        if (reelBrakeAnimators.isEmpty() && reelBrakeSequenceAnimator == null) {
            binding.reelBrakeLayer.visibility = View.INVISIBLE
            binding.reelBrakeLayer.alpha = 0f
        }
    }

    private fun resetReelBrakeView(brake: ImageView) {
        brake.visibility = View.INVISIBLE
        brake.alpha = 0f
        brake.scaleX = 1f
        brake.scaleY = 1f
        brake.translationY = 0f
    }

    private fun startSpinPreview(
        config: SlotConfig,
        targetResult: SpinResult?,
        presentationId: String?,
        startedAtMonotonicMs: Long?,
        stopRequestedAtMonotonicMs: Long?,
        slamStopping: Boolean,
        reducedMotion: Boolean
    ) {
        if (slamStopping) {
            spinPreviewSlamStopRequested = true
            spinPreviewSlamStopRequestedAtMonotonicMs = stopRequestedAtMonotonicMs
        }
        if (reducedMotion || !ValueAnimator.areAnimatorsEnabled()) {
            if (spinPreviewJob?.isActive == true) stopSpinPreview()
            spinPreviewTargetResult = targetResult
            for (column in 0 until REEL_COUNT) {
                renderSpinStripColumn(
                    config.theme,
                    column,
                    spinningStripSymbols(config, column, -column * REEL_COLUMN_OFFSET),
                    motionBlurred = true
                )
            }
            showReelSpinStripLayer()
            return
        }
        if (spinPreviewJob?.isActive == true) {
            if (spinPreviewTargetResult == null && targetResult != null) {
                stopSpinPreview()
            } else {
                return
            }
        }
        spinPreviewTargetResult = targetResult
        spinPreviewSlamStopRequested = slamStopping
        spinPreviewSlamStopRequestedAtMonotonicMs = stopRequestedAtMonotonicMs
        val motionStreakDrawable = reelMotionStreakDrawable(config.theme)
        reelMotionStreakViews.forEach { it.setImageResourceIfChanged(motionStreakDrawable) }
        if (shouldUseRichSpinEffects()) {
            startSpinBlurOverlay()
            startSpinEnergyOverlay()
            startThemeSpinOverlay(config.theme)
        }
        spinPreviewJob = viewLifecycleOwner.lifecycleScope.launch {
            val stoppedColumns = BooleanArray(REEL_COUNT)
            val anticipationColumns = BooleanArray(REEL_COUNT)
            val targetReels = targetResult?.reels
            val targetStopIndexes = targetResult?.stopIndexes
            val normalStopTimesMs = LongArray(REEL_COUNT) { column ->
                reelStopAtMs(config, targetResult, column)
            }
            val scatterChaseColumns = BooleanArray(REEL_COUNT) { column ->
                hasScatterChaseForColumn(config, targetResult, column)
            }
            val anticipationStartTimesMs = LongArray(REEL_COUNT) { column ->
                SlotSpinTimeline.scatterAnticipationStartAtMs(
                    config,
                    targetResult,
                    column,
                    REEL_SCATTER_ANTICIPATION_WINDOW_MS
                ) ?: Long.MAX_VALUE
            }
            val elapsedSinceStartMs = startedAtMonotonicMs
                ?.let { startedAt -> (monotonicTimeMs() - startedAt).coerceAtLeast(0L) }
                ?: 0L
            val maximumRevealMs = if (targetResult == null) {
                normalStopTimesMs.last()
            } else {
                (normalStopTimesMs.maxOrNull() ?: 0L) +
                    SlotSpinTimeline.REEL_STOP_BOUNCE_DURATION_MS +
                    SlotSpinTimeline.REVEAL_SETTLE_MS
            }
            var elapsedMs = elapsedSinceStartMs.coerceAtMost(maximumRevealMs)
            val previewTimelineStartedAtMs = monotonicTimeMs() - elapsedMs
            val columnOffsets = IntArray(REEL_COUNT) { column ->
                -((elapsedMs / REEL_SPIN_CRUISE_FRAME_MS).toInt() * REEL_SPIN_CRUISE_STEP_SYMBOLS) -
                    column * REEL_COLUMN_OFFSET
            }
            val alignedStopOffsets = IntArray(REEL_COUNT) { Int.MAX_VALUE }
            val nextFrameAtMs = LongArray(REEL_COUNT) { column ->
                elapsedMs + reelSpinPreviewFrameDelayMs(
                    reelSpinPhase(elapsedMs, SlotSpinTimeline.baseStopAtMs(config, column)),
                    column,
                    scatterChase = false
                )
            }
            val slamStopAtMs = LongArray(REEL_COUNT) { Long.MAX_VALUE }

            fun slamRequestElapsedMs(defaultElapsedMs: Long): Long {
                val requestAtMs = spinPreviewSlamStopRequestedAtMonotonicMs
                    ?: return defaultElapsedMs
                val spinStartedAtMs = startedAtMonotonicMs ?: return defaultElapsedMs
                return (requestAtMs - spinStartedAtMs).coerceAtLeast(0L)
            }

            fun applySlamStopSchedule(requestedElapsedMs: Long) {
                val slamStopStartAtMs = SlotSpinTimeline.slamStopStartAtMs(requestedElapsedMs)
                var remainingIndex = 0
                for (column in 0 until REEL_COUNT) {
                    val normalStopAtMs = normalStopTimesMs[column]
                    if (stoppedColumns[column] || normalStopAtMs <= requestedElapsedMs) {
                        slamStopAtMs[column] = normalStopAtMs
                        continue
                    }
                    val acceleratedStopAtMs = slamStopStartAtMs +
                        remainingIndex * SlotSpinTimeline.SLAM_STOP_COLUMN_STAGGER_MS
                    slamStopAtMs[column] = acceleratedStopAtMs
                    if (acceleratedStopAtMs < normalStopAtMs) {
                        alignedStopOffsets[column] = Int.MAX_VALUE
                    }
                    remainingIndex += 1
                }
            }

            if (slamStopping) {
                applySlamStopSchedule(slamRequestElapsedMs(elapsedMs))
            }

            targetReels?.forEachIndexed { column, targetColumn ->
                val stopAtMs = minOf(normalStopTimesMs[column], slamStopAtMs[column])
                if (elapsedMs >= stopAtMs) {
                    renderReelColumn(config.theme, column, targetColumn)
                    reelSpinColumns.getOrNull(column)?.visibility = View.INVISIBLE
                    stoppedColumns[column] = true
                }
            }
            for (column in 0 until REEL_COUNT) {
                if (stoppedColumns[column]) continue
                renderSpinStripColumn(
                    config.theme,
                    column,
                    spinningStripSymbols(config, column, columnOffsets[column]),
                    motionBlurred = true
                )
                reelSpinStrips.getOrNull(column)?.translationY =
                    -reelStripCellHeightPx(column) * ReelSpinTrajectory.SETTLED_CELL_OFFSET
            }
            showReelSpinStripLayer()
            stoppedColumns.forEachIndexed { column, stopped ->
                if (stopped) reelSpinColumns.getOrNull(column)?.visibility = View.INVISIBLE
            }
            var columnScanStart = 0
            while (stoppedColumns.any { !it }) {
                val spinWorkStartedAtNanos = System.nanoTime()
                if (spinPreviewSlamStopRequested && slamStopAtMs.all { it == Long.MAX_VALUE }) {
                    applySlamStopSchedule(slamRequestElapsedMs(elapsedMs))
                }
                var remainingColumnWorkBudget = REEL_SPIN_COLUMNS_PER_TICK
                for (columnOffset in 0 until REEL_COUNT) {
                    val column = (columnScanStart + columnOffset) % REEL_COUNT
                    if (stoppedColumns[column]) continue

                    val normalStopAtMs = normalStopTimesMs[column]
                    val stopAtMs = minOf(normalStopAtMs, slamStopAtMs[column])
                    val isSlamStop = slamStopAtMs[column] < normalStopAtMs
                    val targetColumn = targetReels?.getOrNull(column)
                    val targetStopIndex = targetStopIndexes?.getOrNull(column)
                    val scatterChase = scatterChaseColumns[column]
                    val scatterChaseActive = scatterChase &&
                        elapsedMs >= anticipationStartTimesMs[column]
                    val visualStopAtMs = if (scatterChaseActive) {
                        stopAtMs
                    } else {
                        SlotSpinTimeline.baseStopAtMs(config, column)
                    }
                    val phase = reelSpinPhase(elapsedMs, visualStopAtMs)
                    val baseFrameDurationMs = reelSpinPreviewFrameDelayMs(
                        phase,
                        column,
                        scatterChaseActive
                    )
                    val remainingToStopMs = (stopAtMs - elapsedMs).coerceAtLeast(0L)
                    val alignmentFrameCadenceMs = if (isSlamStop) {
                        ReelSpinTrajectory.SLAM_ALIGNMENT_FRAME_MS
                    } else {
                        ReelSpinTrajectory.ALIGNMENT_FRAME_MS
                    }
                    val plannedAlignmentFrames = ReelSpinTrajectory.framesUntilStop(
                        remainingToStopMs,
                        alignmentFrameCadenceMs
                    )
                    val alignmentMaximumStep = if (isSlamStop) {
                        ReelSpinTrajectory.MAX_ALIGNMENT_STEP
                    } else {
                        ReelSpinTrajectory.NORMAL_ALIGNMENT_MAX_STEP
                    }
                    val stripSize = config.reelStripsFor(
                        viewModel.uiState.value.isCurrentSpinFreeSpin
                    ).getOrNull(column)
                        ?.takeIf { it.isNotEmpty() }
                        ?.size
                        ?: config.symbols.size
                    if (
                        targetStopIndex != null &&
                        alignedStopOffsets[column] == Int.MAX_VALUE &&
                        ReelSpinTrajectory.shouldBeginAlignment(remainingToStopMs)
                    ) {
                        alignedStopOffsets[column] = ReelStopAlignment.targetOffsetWithMinimumTravel(
                            currentOffset = columnOffsets[column],
                            targetStopIndex = targetStopIndex,
                            column = column,
                            stripSize = stripSize,
                            columnOffset = REEL_COLUMN_OFFSET,
                            minimumTravel = if (isSlamStop) 1 else plannedAlignmentFrames,
                            maximumTravel = plannedAlignmentFrames *
                                alignmentMaximumStep
                        )
                    }
                    if (alignedStopOffsets[column] != Int.MAX_VALUE) {
                        val maximumAvailableTravel = ReelSpinTrajectory.framesUntilStop(
                            remainingToStopMs,
                            REEL_SPIN_TICK_MS
                        ) * alignmentMaximumStep
                        alignedStopOffsets[column] = ReelStopAlignment.targetOffsetWithinCapacity(
                            currentOffset = columnOffsets[column],
                            targetOffset = alignedStopOffsets[column],
                            stripSize = stripSize,
                            maximumTravel = maximumAvailableTravel
                        )
                    }
                    val alignedStopOffset = alignedStopOffsets[column]
                    val remainingAlignmentDistance = if (alignedStopOffset == Int.MAX_VALUE) {
                        0
                    } else {
                        (columnOffsets[column] - alignedStopOffset).coerceAtLeast(0)
                    }
                    val framesRemaining = ReelSpinTrajectory.alignmentFramesUntilStop(
                        remainingToStopMs,
                        remainingAlignmentDistance,
                        alignmentFrameCadenceMs,
                        REEL_SPIN_TICK_MS,
                        alignmentMaximumStep
                    )
                    val targetAligned = alignedStopOffset == Int.MAX_VALUE ||
                        columnOffsets[column] == alignedStopOffset
                    val finalAlignmentFrameSettled = elapsedMs >= nextFrameAtMs[column]
                    if (
                        targetColumn != null &&
                        elapsedMs >= stopAtMs &&
                        targetAligned &&
                        finalAlignmentFrameSettled
                    ) {
                        if (remainingColumnWorkBudget == 0) continue
                        remainingColumnWorkBudget -= 1
                        renderSpinStripColumn(
                            config.theme,
                            column,
                            spinningStripSymbols(config, column, columnOffsets[column]),
                            motionBlurred = false
                        )
                        revealStoppedReelColumn(config.theme, column, targetColumn)
                        animateSpinStripColumnStop(column, slamStopping = isSlamStop)
                        if (shouldUseRichSpinEffects()) {
                            pulseReelStopColumn(column)
                        }
                        stoppedColumns[column] = true
                    } else {
                        if (elapsedMs < nextFrameAtMs[column] || remainingColumnWorkBudget == 0) continue
                        remainingColumnWorkBudget -= 1
                        val step = if (alignedStopOffset != Int.MAX_VALUE) {
                            alignedStoppingStep(
                                columnOffsets[column],
                                alignedStopOffset,
                                framesRemaining,
                                alignmentMaximumStep
                            )
                        } else {
                            reelSpinSymbolStep(phase, column, scatterChaseActive)
                        }
                        columnOffsets[column] -= step
                        renderSpinStripColumn(
                            config.theme,
                            column,
                            spinningStripSymbols(config, column, columnOffsets[column]),
                            motionBlurred = true
                        )
                        val frameDurationMs = if (alignedStopOffset != Int.MAX_VALUE) {
                            ReelSpinTrajectory.alignmentFrameDurationMs(
                                remainingToStopMs,
                                framesRemaining,
                                alignmentFrameCadenceMs
                            )
                        } else {
                            ReelSpinTrajectory.frameDurationUntilStop(
                                baseFrameDurationMs,
                                remainingToStopMs
                            )
                        }
                        animateSpinStripColumn(
                            column,
                            phase,
                            scatterChaseActive,
                            step,
                            frameDurationMs
                        )
                        if (targetColumn != null &&
                            !anticipationColumns[column] &&
                            elapsedMs >= anticipationStartTimesMs[column]
                        ) {
                            animateSpinStripColumnAnticipation(column, scatterChaseActive)
                            anticipationColumns[column] = true
                        }
                        nextFrameAtMs[column] = elapsedMs + frameDurationMs
                    }
                }
                columnScanStart = (columnScanStart + 1) % REEL_COUNT
                reportSpinPerformanceWork(System.nanoTime() - spinWorkStartedAtNanos)
                delay(REEL_SPIN_TICK_MS)
                elapsedMs = (monotonicTimeMs() - previewTimelineStartedAtMs)
                    .coerceAtLeast(elapsedMs)
            }
            completedSpinPreviewPresentationId = presentationId
        }
    }

    private fun reelSpinPreviewFrameDelayMs(
        phase: ReelSpinPhase,
        column: Int,
        scatterChase: Boolean
    ): Long {
        return maxOf(
            REEL_SPIN_MIN_RENDER_INTERVAL_MS,
            reelSpinFrameDurationMs(phase, column, scatterChase)
        )
    }

    private fun stopSpinPreview() {
        spinPreviewJob?.cancel()
        spinPreviewJob = null
        spinPreviewTargetResult = null
        spinPreviewSlamStopRequested = false
        spinPreviewSlamStopRequestedAtMonotonicMs = null
        reelAnticipationKickAnimator?.cancel()
        reelAnticipationKickAnimator = null
        settleReelAnticipationKick()
        stopSpinBlurOverlay()
        stopSpinEnergyOverlay()
        stopThemeSpinOverlay()
        hideReelBrakeLayer(immediate = true)
        hideReelMotionStreakLayer(immediate = true)
        hideReelAnticipationBeamLayer(immediate = true)
        hideReelLandingSparkLayer(immediate = true)
        hideReelSpinStripLayer()
        reelCells.forEach { it.translationY = 0f }
    }

    private fun highlightedCellIndexes(result: SpinResult): Set<Int> {
        return WinningPaylineHighlights.cellIndexes(
            orderedWinningLines(result).firstOrNull(),
            REEL_COUNT
        )
    }

    private fun highlightedCellIndexes(result: SpinResult, paylineIndex: Int): Set<Int> {
        return WinningPaylineHighlights.cellIndexes(
            result.winningLines.firstOrNull { line -> line.paylineIndex == paylineIndex },
            REEL_COUNT
        )
    }

    private fun bonusScatterCellIndexes(result: SpinResult): Set<Int> {
        if (result.resultType != ResultType.Bonus) return emptySet()
        return result.scatterPositions
            .map { position -> position.row * REEL_COUNT + position.reel }
            .toSet()
    }

    private fun renderWinningPaylineOverlay(theme: SlotTheme, result: SpinResult?) {
        if (result == null) {
            hideWinningPaylineOverlay()
            return
        }
        val winningLines = orderedWinningLines(result)
        if (winningLines.isEmpty()) {
            hideWinningPaylineOverlay()
            return
        }

        val signature = winningLines.joinToString(separator = "|") {
            "${theme.name}:${it.paylineIndex}:${it.amount}:${it.count}"
        }
        if (signature == lastWinningPaylineSignature && binding.winningPaylineOverlay.isVisible) {
            return
        }

        winningPaylineCarouselJob?.cancel()
        winningPaylineCarouselJob = null
        lastWinningPaylineSignature = signature

        val lineIndexes = winningLines
            .distinctBy { it.paylineIndex }
            .map { it.paylineIndex }
        if (
            lineIndexes.size == 1 ||
            !ValueAnimator.areAnimatorsEnabled() ||
            !shouldUseRichSpinEffects()
        ) {
            showWinningPaylineOverlay(theme, result, lineIndexes.first(), animate = true)
            return
        }

        startWinningPaylineCarousel(theme, result, lineIndexes)
    }

    private fun orderedWinningLines(result: SpinResult): List<WinningLine> {
        return result.winningLines.sortedWith(
            compareByDescending<WinningLine> { it.amount }
                .thenByDescending { it.count }
                .thenBy { it.paylineIndex }
        )
    }

    private fun startWinningPaylineCarousel(
        theme: SlotTheme,
        result: SpinResult,
        lineIndexes: List<Int>
    ) {
        val rounds = if (lineIndexes.size <= SlotWinFeedbackTiming.PAYLINE_CAROUSEL_REPEAT_LIMIT) {
            SlotWinFeedbackTiming.PAYLINE_CAROUSEL_REPEAT_ROUNDS
        } else {
            1
        }
        winningPaylineCarouselJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(rounds) {
                lineIndexes.forEach { lineIndex ->
                    if (_binding == null) return@launch
                    showWinningPaylineOverlay(theme, result, lineIndex, animate = true)
                    delay(SlotWinFeedbackTiming.PAYLINE_CAROUSEL_STEP_MS)
                }
            }
            if (_binding != null) {
                showWinningPaylineOverlay(theme, result, lineIndexes.first(), animate = true)
            }
            winningPaylineCarouselJob = null
        }
    }

    private fun showWinningPaylineOverlay(
        theme: SlotTheme,
        result: SpinResult,
        lineIndex: Int,
        animate: Boolean
    ) {
        val binding = _binding ?: return
        val overlay = binding.winningPaylineOverlay
        val shouldAnimate = animate &&
            shouldUseRichSpinEffects() &&
            (overlay.visibility != View.VISIBLE || lastVisiblePaylineIndex != lineIndex)
        renderReelHighlights(
            highlightedCellIndexes(result, lineIndex),
            bonusScatterCellIndexes(result)
        )
        overlay.animate().cancel()
        overlay.setImageResourceIfChanged(paylineWinDrawable(theme, lineIndex))
        overlay.contentDescription = getString(R.string.slot_winning_payline, lineIndex + 1)
        overlay.visibility = View.VISIBLE

        if (shouldAnimate) {
            overlay.alpha = 0f
            overlay.scaleX = 0.96f
            overlay.scaleY = 0.96f
            overlay.animate()
                .alpha(0.92f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260L)
                .start()
        } else {
            overlay.alpha = 0.92f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
        }
        lastVisiblePaylineIndex = lineIndex
    }

    private fun hideWinningPaylineOverlay(immediate: Boolean = false) {
        val binding = _binding ?: return
        val overlay = binding.winningPaylineOverlay
        winningPaylineCarouselJob?.cancel()
        winningPaylineCarouselJob = null
        overlay.animate().cancel()
        lastVisiblePaylineIndex = null
        lastWinningPaylineSignature = null
        overlay.contentDescription = null
        if (overlay.visibility != View.VISIBLE && overlay.alpha == 0f) return
        if (immediate) {
            overlay.alpha = 0f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            overlay.visibility = View.INVISIBLE
            return
        }
        overlay.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                overlay.visibility = View.INVISIBLE
                overlay.scaleX = 1f
                overlay.scaleY = 1f
            }
            .start()
    }

    private fun paylineWinDrawable(theme: SlotTheme, paylineIndex: Int): Int {
        val drawables = paylineWinDrawables(theme)
        return drawables[paylineIndex.coerceIn(0, drawables.lastIndex)]
    }

    private fun paylineWinDrawables(theme: SlotTheme): IntArray {
        return when (theme) {
            SlotTheme.Roman -> ROMAN_PAYLINE_WIN_DRAWABLES
            SlotTheme.Neon -> NEON_PAYLINE_WIN_DRAWABLES
            SlotTheme.Pharaoh -> PHARAOH_PAYLINE_WIN_DRAWABLES
            SlotTheme.Ocean -> OCEAN_PAYLINE_WIN_DRAWABLES
            SlotTheme.Violet -> VIOLET_PAYLINE_WIN_DRAWABLES
        }
    }

    private fun animateHighlightedCells(highlightedCells: Set<Int>) {
        highlightedCells.forEach { cellIndex ->
            val imageView = reelCells.getOrNull(cellIndex) ?: return@forEach
            imageView.scaleX = 0.92f
            imageView.scaleY = 0.92f
            imageView.alpha = 1f
            imageView.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(180L)
                .withEndAction {
                    imageView.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .setDuration(280L)
                        .start()
                }
                .start()
        }
    }

    private fun animateWinResultIfNeeded(result: SpinResult?, presentationId: String?) {
        if (result == null || presentationId.isNullOrBlank()) return
        if (presentationId == lastAnimatedPresentationId) return
        lastAnimatedPresentationId = presentationId
        if (result.resultType != ResultType.Lose) {
            if (SlotResultPresentationPolicy.isPartialReturn(result)) {
                slotSoundPlayer?.play(SlotSoundCue.Payout)
                animatePartialReturnOverlay()
            } else {
                slotSoundPlayer?.play(
                    if (result.resultType == ResultType.Bonus) SlotSoundCue.Bonus else SlotSoundCue.Win
                )
                if (hapticsEnabled) {
                    binding.reelsGrid.performHapticFeedback(
                        if (result.resultType == ResultType.Bonus) {
                            HapticFeedbackConstants.LONG_PRESS
                        } else {
                            HapticFeedbackConstants.CLOCK_TICK
                        }
                    )
                }
                animateWinOverlay(result)
            }
        }
    }

    private fun animatePartialReturnOverlay() {
        val glow = binding.winGlowOverlay
        val theme = viewModel.uiState.value.config.theme
        stopWinGlowOverlay()
        glow.setImageResourceIfChanged(winGlowSpriteDrawable(theme))
        glow.visibility = View.VISIBLE
        hideBigWinBanner(immediate = true)
        hideThemeWinBurst(immediate = true)
        hideBonusEntryPortal(immediate = true)
        glow.alpha = 0f
        glow.scaleX = 0.86f
        glow.scaleY = 0.86f
        glow.animate()
            .alpha(PARTIAL_RETURN_GLOW_ALPHA)
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(PARTIAL_RETURN_GLOW_IN_MS)
            .withEndAction {
                glow.animate()
                    .alpha(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(PARTIAL_RETURN_GLOW_OUT_MS)
                    .start()
            }
            .start()
    }

    private fun showResultDialog(
        result: SpinResult,
        freeSpinsAwarded: Int,
        presentationId: String
    ) {
        if (result === lastPresentedDialogResult) return
        if (parentFragmentManager.isStateSaved) {
            return
        }
        if (parentFragmentManager.findFragmentByTag(SPIN_RESULT_DIALOG_TAG) != null) return
        lastPresentedDialogResult = result
        val dialogResultType = if (result.resultType == ResultType.Bonus && freeSpinsAwarded <= 0) {
            ResultType.Win
        } else {
            result.resultType
        }
        ResultDialogFragment.newInstance(
            dialogResultType,
            result.winAmount,
            freeSpinsAwarded,
            viewModel.uiState.value.config.theme,
            presentationId
        )
            .show(parentFragmentManager, SPIN_RESULT_DIALOG_TAG)
        scheduleAutoSpinResultDismiss(result, freeSpinsAwarded)
    }

    private fun showPendingResultDialogIfNeeded(state: SlotUiState) {
        val result = state.lastResult ?: return
        val presentationId = state.pendingPresentationId ?: return
        if (state.isSpinning || !state.isResultPending) return
        if (!SlotResultPresentationPolicy.shouldShowResultDialog(result)) return
        showResultDialog(result, result.freeSpinsAwarded, presentationId)
    }

    private fun showLowCoinsDialog(bonusAvailable: Boolean) {
        if (parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(LOW_COINS_DIALOG_TAG) != null) return
        LowCoinsDialogFragment.newInstance(bonusAvailable)
            .show(parentFragmentManager, LOW_COINS_DIALOG_TAG)
    }

    private fun scheduleAutoSpinResultDismiss(result: SpinResult, freeSpinsAwarded: Int) {
        autoSpinResultDismissJob?.cancel()
        autoSpinResultDismissJob = null
        if (!viewModel.uiState.value.isAutoSpinEnabled) return
        if (isTouchExplorationEnabled()) {
            viewModel.stopAutoSpin()
            return
        }

        val dismissDelayMs = if (result.resultType == ResultType.Bonus || freeSpinsAwarded > 0) {
            AUTO_SPIN_BONUS_RESULT_DISMISS_DELAY_MS
        } else {
            AUTO_SPIN_RESULT_DISMISS_DELAY_MS
        }
        autoSpinResultDismissJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(dismissDelayMs)
            if (!viewModel.uiState.value.isAutoSpinEnabled) return@launch
            val dialog = parentFragmentManager.findFragmentByTag(SPIN_RESULT_DIALOG_TAG) as? ResultDialogFragment
                ?: return@launch
            dialog.dismissAllowingStateLoss()
        }
    }

    private fun isTouchExplorationEnabled(): Boolean {
        val manager = requireContext().getSystemService(AccessibilityManager::class.java)
        return manager?.isEnabled == true && manager.isTouchExplorationEnabled
    }

    private fun resumeRestoredResultDialogAutoDismissIfNeeded(state: SlotUiState) {
        if (
            autoSpinResultDismissJob != null ||
            !state.isAutoSpinEnabled ||
            state.isSpinning ||
            !state.isResultPending
        ) return
        val result = state.lastResult ?: return
        val restoredDialog = parentFragmentManager.findFragmentByTag(SPIN_RESULT_DIALOG_TAG)
        if (restoredDialog !is ResultDialogFragment) return
        scheduleAutoSpinResultDismiss(result, result.freeSpinsAwarded)
    }

    private fun animateWinOverlay(result: SpinResult) {
        val theme = viewModel.uiState.value.config.theme
        stopWinGlowOverlay()
        val showBigWinBanner = shouldShowBigWinBanner(result)
        if (result.resultType == ResultType.Win && !showBigWinBanner) {
            hideBigWinBanner(immediate = true)
            hideThemeWinBurst(immediate = true)
            hideBonusEntryPortal(immediate = true)
            binding.winGlowOverlay.visibility = View.INVISIBLE
            return
        }
        binding.winGlowOverlay.setImageResourceIfChanged(winGlowSpriteDrawable(theme))
        binding.winGlowOverlay.visibility = View.VISIBLE
        binding.winGlowOverlay.scaleX = 0.72f
        binding.winGlowOverlay.scaleY = 0.72f
        binding.coinBurstOverlay.setImageResourceIfChanged(themeWinBurstDrawable(theme))
        if (showBigWinBanner) {
            animateBigWinBanner(result)
        } else {
            hideBigWinBanner(immediate = true)
        }

        val animation = AnimatorSet()
        winGlowAnimator = animation
        animation.apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.winGlowOverlay, View.ALPHA, 0f, 1f, 0f),
                ObjectAnimator.ofFloat(binding.winGlowOverlay, View.SCALE_X, 0.72f, 1.18f),
                ObjectAnimator.ofFloat(binding.winGlowOverlay, View.SCALE_Y, 0.72f, 1.18f)
            )
            duration = 1_050L
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(endedAnimation: Animator) {
                    if (winGlowAnimator === endedAnimation) winGlowAnimator = null
                }

                override fun onAnimationCancel(cancelledAnimation: Animator) {
                    if (winGlowAnimator === cancelledAnimation) winGlowAnimator = null
                }
            })
            start()
        }
        animateThemeWinBurst(theme, result)
        if (result.resultType == ResultType.Bonus) {
            animateBonusEntryPortal(theme)
        } else {
            hideBonusEntryPortal(immediate = true)
        }
        if (showBigWinBanner) {
            binding.bigWinBannerOverlay.bringToFront()
        }
    }

    private fun animateBonusEntryPortal(theme: SlotTheme) {
        val binding = _binding ?: return
        val portal = binding.bonusEntryPortalOverlay
        bonusEntryPortalAnimator?.cancel()
        bonusEntryPortalAnimator = null
        bonusEntryPortalStaticHideJob?.cancel()
        bonusEntryPortalStaticHideJob = null
        portal.animate().cancel()
        binding.bonusEntryPortalOverlay.setImageResourceIfChanged(bonusEntryPortalDrawable(theme))
        portal.bringToFront()
        portal.visibility = View.VISIBLE
        portal.alpha = 0f
        portal.translationX = 0f
        portal.translationY = 28f
        portal.scaleX = 0.64f
        portal.scaleY = 0.64f
        portal.rotation = -2.4f

        if (!ValueAnimator.areAnimatorsEnabled()) {
            portal.alpha = BONUS_ENTRY_PORTAL_PEAK_ALPHA
            bonusEntryPortalStaticHideJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(BONUS_ENTRY_PORTAL_STATIC_HOLD_MS)
                bonusEntryPortalStaticHideJob = null
                if (_binding != null && bonusEntryPortalAnimator == null) {
                    hideBonusEntryPortal(immediate = true)
                }
            }
            return
        }

        bonusEntryPortalAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(portal, View.ALPHA, 0f, BONUS_ENTRY_PORTAL_PEAK_ALPHA, 0.72f, 0f),
                ObjectAnimator.ofFloat(portal, View.SCALE_X, 0.64f, 1.08f, 1.16f),
                ObjectAnimator.ofFloat(portal, View.SCALE_Y, 0.64f, 1.08f, 1.16f),
                ObjectAnimator.ofFloat(portal, View.TRANSLATION_Y, 28f, -8f, -38f),
                ObjectAnimator.ofFloat(portal, View.ROTATION, -2.4f, 1.2f, 3.2f)
            )
            duration = BONUS_ENTRY_PORTAL_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bonusEntryPortalAnimator === animation) {
                        bonusEntryPortalAnimator = null
                    }
                    hideBonusEntryPortal(immediate = true)
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (bonusEntryPortalAnimator === animation) {
                        bonusEntryPortalAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun hideBonusEntryPortal(immediate: Boolean = false) {
        val binding = _binding
        bonusEntryPortalAnimator?.cancel()
        bonusEntryPortalAnimator = null
        bonusEntryPortalStaticHideJob?.cancel()
        bonusEntryPortalStaticHideJob = null
        val portal = binding?.bonusEntryPortalOverlay ?: return
        portal.animate().cancel()
        if (immediate) {
            portal.visibility = View.INVISIBLE
            portal.alpha = 0f
            portal.translationX = 0f
            portal.translationY = 0f
            portal.scaleX = 1f
            portal.scaleY = 1f
            portal.rotation = 0f
            return
        }
        portal.animate()
            .alpha(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setDuration(160L)
            .withEndAction {
                portal.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun animateThemeWinBurst(theme: SlotTheme, result: SpinResult) {
        val burst = binding.coinBurstOverlay
        val motion = themeWinBurstMotion(theme)
        val bonusBoost = if (result.resultType == ResultType.Bonus) 1.12f else 1f
        val peakAlpha = (motion.peakAlpha * bonusBoost).coerceAtMost(THEME_WIN_BURST_MAX_ALPHA)
        val peakScale = motion.peakScale * if (result.resultType == ResultType.Bonus) 1.04f else 1f
        val duration = if (result.resultType == ResultType.Bonus) {
            (motion.durationMs * 1.18f).toLong()
        } else {
            motion.durationMs
        }

        winBurstAnimator?.cancel()
        winBurstAnimator = null
        burst.animate().cancel()
        burst.bringToFront()
        burst.visibility = View.VISIBLE
        burst.alpha = 0f
        burst.translationX = motion.startX
        burst.translationY = motion.startY
        burst.scaleX = motion.startScale
        burst.scaleY = motion.startScale
        burst.rotation = motion.startRotation

        winBurstAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(burst, View.ALPHA, 0f, peakAlpha, peakAlpha, 0f),
                ObjectAnimator.ofFloat(burst, View.TRANSLATION_X, motion.startX, motion.endX),
                ObjectAnimator.ofFloat(burst, View.TRANSLATION_Y, motion.startY, motion.endY),
                ObjectAnimator.ofFloat(burst, View.SCALE_X, motion.startScale, peakScale, peakScale * 0.96f),
                ObjectAnimator.ofFloat(burst, View.SCALE_Y, motion.startScale, peakScale, peakScale * 0.96f),
                ObjectAnimator.ofFloat(burst, View.ROTATION, motion.startRotation, motion.endRotation)
            )
            this.duration = duration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (winBurstAnimator === animation) {
                        winBurstAnimator = null
                    }
                    hideThemeWinBurst(immediate = true)
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (winBurstAnimator === animation) {
                        winBurstAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun hideThemeWinBurst(immediate: Boolean = false) {
        val burst = binding.coinBurstOverlay
        winBurstAnimator?.cancel()
        winBurstAnimator = null
        burst.animate().cancel()
        if (immediate) {
            burst.visibility = View.INVISIBLE
            burst.alpha = 0f
            burst.translationX = 0f
            burst.translationY = 0f
            burst.scaleX = 1f
            burst.scaleY = 1f
            burst.rotation = 0f
            return
        }
        burst.animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                burst.visibility = View.INVISIBLE
                burst.translationX = 0f
                burst.translationY = 0f
                burst.scaleX = 1f
                burst.scaleY = 1f
                burst.rotation = 0f
            }
            .start()
    }

    private fun themeWinBurstMotion(theme: SlotTheme): ThemeWinBurstMotion {
        return when (theme) {
            SlotTheme.Violet -> ThemeWinBurstMotion(
                startScale = 0.9f,
                peakScale = 1.36f,
                peakAlpha = 1f,
                startX = -16f,
                endX = 12f,
                startY = 42f,
                endY = -46f,
                startRotation = -4.5f,
                endRotation = 5.5f,
                durationMs = 2_420L
            )
            SlotTheme.Roman -> ThemeWinBurstMotion(
                startScale = 0.92f,
                peakScale = 1.28f,
                peakAlpha = 0.96f,
                startX = 4f,
                endX = -10f,
                startY = 34f,
                endY = -36f,
                startRotation = 1.5f,
                endRotation = -3.5f,
                durationMs = 2_700L
            )
            SlotTheme.Neon -> ThemeWinBurstMotion(
                startScale = 0.84f,
                peakScale = 1.42f,
                peakAlpha = 1f,
                startX = 22f,
                endX = -18f,
                startY = 28f,
                endY = -52f,
                startRotation = -7f,
                endRotation = 8f,
                durationMs = 2_100L
            )
            SlotTheme.Pharaoh -> ThemeWinBurstMotion(
                startScale = 0.9f,
                peakScale = 1.34f,
                peakAlpha = 1f,
                startX = -8f,
                endX = 14f,
                startY = 40f,
                endY = -44f,
                startRotation = 2.5f,
                endRotation = -4.5f,
                durationMs = 2_520L
            )
            SlotTheme.Ocean -> ThemeWinBurstMotion(
                startScale = 0.9f,
                peakScale = 1.32f,
                peakAlpha = 0.98f,
                startX = 10f,
                endX = -14f,
                startY = 46f,
                endY = -50f,
                startRotation = -1.5f,
                endRotation = 3.5f,
                durationMs = 2_820L
            )
        }
    }

    private fun shouldShowBigWinBanner(result: SpinResult): Boolean {
        return SlotResultPresentationPolicy.shouldShowResultDialog(result)
    }

    private fun animateBigWinBanner(result: SpinResult) {
        val banner = binding.bigWinBannerOverlay
        bigWinBannerAnimator?.cancel()
        bigWinBannerAnimator = null
        banner.animate().cancel()
        val theme = viewModel.uiState.value.config.theme
        val imageRes = if (result.resultType == ResultType.Bonus) {
            bonusFreeSpinsBannerDrawable(theme)
        } else {
            bigWinBannerDrawable(theme)
        }
        val descriptionRes = if (result.resultType == ResultType.Bonus) {
            R.string.slot_bonus_free_spins_banner
        } else {
            R.string.slot_big_win_banner
        }
        binding.bigWinBannerOverlay.setImageResourceIfChanged(imageRes)
        banner.contentDescription = getString(descriptionRes)
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.scaleX = 0.82f
        banner.scaleY = 0.82f
        banner.translationY = 16f

        val intro = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(banner, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(banner, View.SCALE_X, 0.82f, 1.04f),
                ObjectAnimator.ofFloat(banner, View.SCALE_Y, 0.82f, 1.04f),
                ObjectAnimator.ofFloat(banner, View.TRANSLATION_Y, 16f, -8f)
            )
            duration = 260L
        }
        val outro = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(banner, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(banner, View.SCALE_X, 1.04f, 0.98f),
                ObjectAnimator.ofFloat(banner, View.SCALE_Y, 1.04f, 0.98f),
                ObjectAnimator.ofFloat(banner, View.TRANSLATION_Y, -8f, -42f)
            )
            startDelay = 760L
            duration = 360L
        }
        var canceled = false
        bigWinBannerAnimator = AnimatorSet().apply {
            playSequentially(intro, outro)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        banner.visibility = View.INVISIBLE
                        banner.translationY = 0f
                        banner.scaleX = 1f
                        banner.scaleY = 1f
                    }
                    if (bigWinBannerAnimator === animation) {
                        bigWinBannerAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun hideBigWinBanner(immediate: Boolean = false) {
        val banner = binding.bigWinBannerOverlay
        bigWinBannerAnimator?.cancel()
        bigWinBannerAnimator = null
        banner.animate().cancel()
        if (immediate) {
            banner.visibility = View.INVISIBLE
            banner.alpha = 0f
            banner.translationY = 0f
            banner.scaleX = 1f
            banner.scaleY = 1f
            return
        }
        banner.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                banner.visibility = View.INVISIBLE
                banner.translationY = 0f
                banner.scaleX = 1f
                banner.scaleY = 1f
            }
            .start()
    }

    private fun startSpinBlurOverlay() {
        val overlay = binding.spinBlurOverlay
        spinBlurTranslationAnimator?.cancel()
        spinBlurAlphaAnimator?.cancel()
        overlay.animate().cancel()
        val theme = viewModel.uiState.value.config.theme
        binding.spinBlurOverlay.setImageResourceIfChanged(reelSpinBlurDrawable(theme))
        overlay.visibility = View.VISIBLE
        overlay.translationY = -34f
        overlay.alpha = 0.48f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        spinBlurTranslationAnimator = ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, -34f, 34f).apply {
            duration = SPIN_BLUR_INTRO_DURATION_MS
            start()
        }
        spinBlurAlphaAnimator = ObjectAnimator.ofFloat(overlay, View.ALPHA, 0.42f, 0.62f, 0.42f).apply {
            setFloatValues(0.42f, 0.62f, 0f)
            duration = SPIN_BLUR_INTRO_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(endedAnimation: Animator) {
                    if (spinBlurAlphaAnimator !== endedAnimation) return
                    spinBlurTranslationAnimator = null
                    spinBlurAlphaAnimator = null
                    overlay.visibility = View.INVISIBLE
                    overlay.alpha = 0f
                    overlay.translationY = 0f
                }
            })
            start()
        }
    }

    private fun stopSpinBlurOverlay() {
        val overlay = binding.spinBlurOverlay
        spinBlurTranslationAnimator?.cancel()
        spinBlurAlphaAnimator?.cancel()
        spinBlurTranslationAnimator = null
        spinBlurAlphaAnimator = null
        if (overlay.visibility != View.VISIBLE && overlay.alpha == 0f) return
        overlay.animate()
            .alpha(0f)
            .translationY(0f)
            .setDuration(180L)
            .withEndAction {
                overlay.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun startSpinEnergyOverlay() {
        val overlay = binding.spinEnergyOverlay
        val theme = viewModel.uiState.value.config.theme
        binding.spinEnergyOverlay.setImageResourceIfChanged(spinEnergyOverlayDrawable(theme))
        spinEnergyAnimator?.cancel()
        spinEnergyAnimator = null
        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        overlay.alpha = SPIN_ENERGY_LOW_ALPHA
        overlay.translationY = -18f
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val animation = AnimatorSet()
        spinEnergyAnimator = animation
        animation.apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    overlay,
                    View.ALPHA,
                    SPIN_ENERGY_LOW_ALPHA,
                    SPIN_ENERGY_HIGH_ALPHA,
                    0f
                ),
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, -18f, 10f),
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.996f, 1.008f),
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.998f, 1.01f)
            )
            duration = SPIN_ENERGY_PULSE_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(endedAnimation: Animator) {
                    if (spinEnergyAnimator !== endedAnimation) return
                    spinEnergyAnimator = null
                    overlay.visibility = View.INVISIBLE
                    overlay.alpha = 0f
                    overlay.translationY = 0f
                    overlay.scaleX = 1f
                    overlay.scaleY = 1f
                }
            })
            start()
        }
    }

    private fun stopSpinEnergyOverlay(immediate: Boolean = false) {
        val overlay = binding.spinEnergyOverlay
        spinEnergyAnimator?.cancel()
        spinEnergyAnimator = null
        overlay.animate().cancel()
        if (overlay.visibility != View.VISIBLE && overlay.alpha == 0f) return
        if (immediate) {
            overlay.visibility = View.INVISIBLE
            overlay.alpha = 0f
            overlay.translationY = 0f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            return
        }
        overlay.animate()
            .alpha(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .withEndAction { overlay.visibility = View.INVISIBLE }
            .start()
    }

    private fun startThemeSpinOverlay(theme: SlotTheme) {
        val overlay = binding.themeSpinOverlay
        val motion = themeSpinOverlayMotion(theme)
        themeSpinOverlayAnimator?.cancel()
        themeSpinOverlayAnimator = null
        overlay.animate().cancel()
        binding.themeSpinOverlay.setImageResourceIfChanged(themeSpinOverlayDrawable(theme))
        overlay.visibility = View.VISIBLE
        overlay.alpha = motion.lowAlpha
        overlay.translationX = -motion.driftX
        overlay.translationY = motion.startY
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        overlay.rotation = -motion.rotationDegrees
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val animation = AnimatorSet()
        themeSpinOverlayAnimator = animation
        animation.apply {
            playTogether(
                ObjectAnimator.ofFloat(overlay, View.ALPHA, motion.lowAlpha, motion.highAlpha, 0f),
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_X, -motion.driftX, motion.driftX),
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, motion.startY, motion.endY),
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 1f, motion.scalePeak),
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 1f, motion.scalePeak),
                ObjectAnimator.ofFloat(overlay, View.ROTATION, -motion.rotationDegrees, motion.rotationDegrees)
            )
            duration = THEME_SPIN_INTRO_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(endedAnimation: Animator) {
                    if (themeSpinOverlayAnimator !== endedAnimation) return
                    themeSpinOverlayAnimator = null
                    overlay.visibility = View.INVISIBLE
                    overlay.alpha = 0f
                    overlay.translationX = 0f
                    overlay.translationY = 0f
                    overlay.scaleX = 1f
                    overlay.scaleY = 1f
                    overlay.rotation = 0f
                }
            })
            start()
        }
    }

    private fun stopThemeSpinOverlay(immediate: Boolean = false) {
        val overlay = binding.themeSpinOverlay
        themeSpinOverlayAnimator?.cancel()
        themeSpinOverlayAnimator = null
        overlay.animate().cancel()
        if (immediate) {
            overlay.visibility = View.INVISIBLE
            overlay.alpha = 0f
            overlay.translationX = 0f
            overlay.translationY = 0f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            overlay.rotation = 0f
            return
        }
        if (overlay.visibility != View.VISIBLE && overlay.alpha == 0f) return
        overlay.animate()
            .alpha(0f)
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setDuration(180L)
            .withEndAction { overlay.visibility = View.INVISIBLE }
            .start()
    }

    private fun themeSpinOverlayMotion(theme: SlotTheme): ThemeSpinOverlayMotion {
        return when (theme) {
            SlotTheme.Violet -> ThemeSpinOverlayMotion(
                lowAlpha = 0.18f,
                highAlpha = 0.58f,
                pulseDurationMs = 520L,
                driftDurationMs = 720L,
                driftX = 10f,
                startY = -20f,
                endY = 22f,
                scalePeak = 1.028f,
                rotationDegrees = 0.35f
            )
            SlotTheme.Roman -> ThemeSpinOverlayMotion(
                lowAlpha = 0.16f,
                highAlpha = 0.52f,
                pulseDurationMs = 680L,
                driftDurationMs = 860L,
                driftX = 14f,
                startY = -16f,
                endY = 18f,
                scalePeak = 1.018f,
                rotationDegrees = 0.22f
            )
            SlotTheme.Neon -> ThemeSpinOverlayMotion(
                lowAlpha = 0.22f,
                highAlpha = 0.64f,
                pulseDurationMs = 360L,
                driftDurationMs = 470L,
                driftX = 28f,
                startY = -10f,
                endY = 18f,
                scalePeak = 1.032f,
                rotationDegrees = 0.12f
            )
            SlotTheme.Pharaoh -> ThemeSpinOverlayMotion(
                lowAlpha = 0.2f,
                highAlpha = 0.6f,
                pulseDurationMs = 600L,
                driftDurationMs = 780L,
                driftX = 18f,
                startY = -24f,
                endY = 20f,
                scalePeak = 1.03f,
                rotationDegrees = 0.32f
            )
            SlotTheme.Ocean -> ThemeSpinOverlayMotion(
                lowAlpha = 0.18f,
                highAlpha = 0.54f,
                pulseDurationMs = 760L,
                driftDurationMs = 980L,
                driftX = 12f,
                startY = 22f,
                endY = -20f,
                scalePeak = 1.022f,
                rotationDegrees = 0.18f
            )
        }
    }

    private fun updateThemeAmbientOverlay(theme: SlotTheme, isSpinning: Boolean, freeSpinsActive: Boolean) {
        val signature = "${theme.name}:$isSpinning:$freeSpinsActive"
        if (isSpinning) {
            if (binding.slotThemeAmbientOverlay.isVisible || themeAmbientAnimator != null) {
                stopThemeAmbientOverlay()
            }
            themeAmbientSignature = signature
            return
        }
        if (themeAmbientSignature == signature) return
        themeAmbientSignature = signature
        themeAmbientAnimator?.cancel()
        themeAmbientAnimator = null

        val overlay = binding.slotThemeAmbientOverlay
        val motion = themeAmbientMotion(theme)
        val lowAlpha = if (isSpinning) motion.spinLowAlpha else motion.idleLowAlpha
        val highAlpha = if (isSpinning) motion.spinHighAlpha else motion.idleHighAlpha
        val freeSpinBoost = if (freeSpinsActive) 1.12f else 1f
        val settledLowAlpha = (lowAlpha * freeSpinBoost).coerceAtMost(THEME_AMBIENT_MAX_ALPHA)
        val settledHighAlpha = (highAlpha * freeSpinBoost).coerceAtMost(THEME_AMBIENT_MAX_ALPHA)
        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        overlay.translationX = 0f
        overlay.translationY = 0f
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        overlay.rotation = 0f
        overlay.alpha = settledLowAlpha

        if (!ValueAnimator.areAnimatorsEnabled() || !shouldUseRichSpinEffects()) {
            overlay.alpha = settledLowAlpha
            return
        }

        val duration = if (isSpinning) motion.spinDurationMs else motion.idleDurationMs
        val driftMultiplier = if (isSpinning) 1.65f else 1f
        themeAmbientAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(overlay, View.ALPHA, settledLowAlpha, settledHighAlpha, settledLowAlpha).apply {
                    this.duration = duration
                },
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_X, -motion.driftX * driftMultiplier, motion.driftX * driftMultiplier, -motion.driftX * driftMultiplier).apply {
                    this.duration = duration + motion.phaseOffsetMs
                },
                ObjectAnimator.ofFloat(overlay, View.TRANSLATION_Y, motion.driftY * driftMultiplier, -motion.driftY * driftMultiplier, motion.driftY * driftMultiplier).apply {
                    this.duration = (duration + motion.phaseOffsetMs / 2).coerceAtLeast(1L)
                },
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 1f, motion.scalePeak, 1f).apply {
                    this.duration = duration + 220L
                },
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 1f, motion.scalePeak, 1f).apply {
                    this.duration = duration + 220L
                },
                ObjectAnimator.ofFloat(overlay, View.ROTATION, -motion.rotationDegrees, motion.rotationDegrees, -motion.rotationDegrees).apply {
                    this.duration = duration + motion.phaseOffsetMs + 480L
                }
            )
            start()
        }
    }

    private fun stopThemeAmbientOverlay() {
        themeAmbientAnimator?.cancel()
        themeAmbientAnimator = null
        themeAmbientSignature = null
        binding.slotThemeAmbientOverlay.animate().cancel()
        binding.slotThemeAmbientOverlay.visibility = View.INVISIBLE
        binding.slotThemeAmbientOverlay.alpha = 0f
        binding.slotThemeAmbientOverlay.translationX = 0f
        binding.slotThemeAmbientOverlay.translationY = 0f
        binding.slotThemeAmbientOverlay.scaleX = 1f
        binding.slotThemeAmbientOverlay.scaleY = 1f
        binding.slotThemeAmbientOverlay.rotation = 0f
    }

    private fun themeAmbientMotion(theme: SlotTheme): ThemeAmbientMotion {
        return when (theme) {
            SlotTheme.Violet -> ThemeAmbientMotion(
                idleLowAlpha = 0.10f,
                idleHighAlpha = 0.22f,
                spinLowAlpha = 0.17f,
                spinHighAlpha = 0.34f,
                idleDurationMs = 2_500L,
                spinDurationMs = 1_350L,
                phaseOffsetMs = 420L,
                driftX = 4.5f,
                driftY = -3.5f,
                scalePeak = 1.014f,
                rotationDegrees = 0.55f
            )
            SlotTheme.Roman -> ThemeAmbientMotion(
                idleLowAlpha = 0.09f,
                idleHighAlpha = 0.2f,
                spinLowAlpha = 0.14f,
                spinHighAlpha = 0.3f,
                idleDurationMs = 3_300L,
                spinDurationMs = 1_780L,
                phaseOffsetMs = 720L,
                driftX = 1.5f,
                driftY = 4f,
                scalePeak = 1.009f,
                rotationDegrees = 0.28f
            )
            SlotTheme.Neon -> ThemeAmbientMotion(
                idleLowAlpha = 0.12f,
                idleHighAlpha = 0.27f,
                spinLowAlpha = 0.2f,
                spinHighAlpha = 0.39f,
                idleDurationMs = 1_540L,
                spinDurationMs = 780L,
                phaseOffsetMs = 260L,
                driftX = 9f,
                driftY = 1.5f,
                scalePeak = 1.006f,
                rotationDegrees = 0.12f
            )
            SlotTheme.Pharaoh -> ThemeAmbientMotion(
                idleLowAlpha = 0.1f,
                idleHighAlpha = 0.22f,
                spinLowAlpha = 0.17f,
                spinHighAlpha = 0.35f,
                idleDurationMs = 2_680L,
                spinDurationMs = 1_320L,
                phaseOffsetMs = 560L,
                driftX = -3.5f,
                driftY = -7f,
                scalePeak = 1.012f,
                rotationDegrees = -0.38f
            )
            SlotTheme.Ocean -> ThemeAmbientMotion(
                idleLowAlpha = 0.11f,
                idleHighAlpha = 0.24f,
                spinLowAlpha = 0.16f,
                spinHighAlpha = 0.34f,
                idleDurationMs = 3_050L,
                spinDurationMs = 1_620L,
                phaseOffsetMs = 640L,
                driftX = 3f,
                driftY = -8f,
                scalePeak = 1.016f,
                rotationDegrees = 0.2f
            )
        }
    }

    private fun updateCabinetLights(mode: CabinetLightMode) {
        if (cabinetLightMode == mode) return
        cabinetLightMode = mode
        cabinetLightsAnimator?.cancel()
        cabinetLightsAnimator = null

        val base = binding.slotCabinetLights
        val chase = binding.slotCabinetChaseLights
        base.animate().cancel()
        chase.animate().cancel()
        chase.visibility = View.VISIBLE
        chase.translationX = 0f
        chase.translationY = 0f
        chase.scaleX = 1f
        chase.scaleY = 1f

        if (mode == CabinetLightMode.Idle) {
            base.alpha = 1f
            chase.alpha = 0.18f
            return
        }

        base.alpha = 1f
        chase.alpha = CABINET_SPIN_CHASE_ALPHA
    }

    private fun stopCabinetLights() {
        cabinetLightsAnimator?.cancel()
        cabinetLightsAnimator = null
        cabinetLightMode = null
        binding.slotCabinetLights.animate().cancel()
        binding.slotCabinetChaseLights.animate().cancel()
        binding.slotCabinetLights.alpha = 1f
        binding.slotCabinetChaseLights.alpha = 0f
        binding.slotCabinetChaseLights.translationX = 0f
        binding.slotCabinetChaseLights.translationY = 0f
        binding.slotCabinetChaseLights.scaleX = 1f
        binding.slotCabinetChaseLights.scaleY = 1f
        binding.slotCabinetChaseLights.visibility = View.INVISIBLE
    }

    private fun animateReelStopIfNeeded(result: SpinResult?, presentationId: String?) {
        if (result == null || presentationId.isNullOrBlank()) return
        if (presentationId == lastStopAnimatedPresentationId) return
        lastStopAnimatedPresentationId = presentationId
        if (presentationId == completedSpinPreviewPresentationId) return
        animateReelBrakeSequence()
        animateReelStopFlashLayer()
    }

    private fun animateReelBrakeSequence() {
        val binding = _binding ?: return
        val brakeDrawable = reelBrakeClampDrawable(viewModel.uiState.value.config.theme)
        reelBrakeViews.forEach { it.setImageResourceIfChanged(brakeDrawable) }
        val layer = binding.reelBrakeLayer
        reelBrakeSequenceAnimator?.cancel()
        reelBrakeSequenceAnimator = null
        reelBrakeAnimators.values.toList().forEach { it.cancel() }
        reelBrakeAnimators.clear()
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 1f
        reelBrakeViews.forEach(::resetReelBrakeView)
        if (reelBrakeViews.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            hideReelBrakeLayer(immediate = true)
            return
        }

        val columnAnimators = reelBrakeViews.mapIndexed { column, brake ->
            AnimatorSet().apply {
                startDelay = column * REEL_BRAKE_COLUMN_STAGGER_MS
                playTogether(
                    ObjectAnimator.ofFloat(brake, View.ALPHA, 0f, REEL_BRAKE_SEQUENCE_HIGH_ALPHA, 0f),
                    ObjectAnimator.ofFloat(brake, View.SCALE_X, 0.88f, 1.08f, 0.98f, 1f),
                    ObjectAnimator.ofFloat(brake, View.SCALE_Y, 1.16f, 0.9f, 1.05f, 1f),
                    ObjectAnimator.ofFloat(brake, View.TRANSLATION_Y, -20f, 10f, -3f, 0f)
                )
                duration = REEL_BRAKE_SEQUENCE_DURATION_MS
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        brake.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        resetReelBrakeView(brake)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        resetReelBrakeView(brake)
                    }
                })
            }
        }
        reelBrakeSequenceAnimator = AnimatorSet().apply {
            playTogether(columnAnimators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleReelBrakeSequence(animation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleReelBrakeSequence(animation)
                }
            })
            start()
        }
    }

    private fun settleReelBrakeSequence(animation: Animator) {
        if (reelBrakeSequenceAnimator !== animation) return
        reelBrakeSequenceAnimator = null
        val binding = _binding ?: return
        reelBrakeViews.forEach(::resetReelBrakeView)
        if (reelBrakeAnimators.isEmpty()) {
            reelBrakeViews.forEach(ImageView::clearBoundImageResource)
            binding.reelBrakeLayer.visibility = View.INVISIBLE
            binding.reelBrakeLayer.alpha = 0f
        }
    }

    private fun animateReelStopFlashLayer() {
        val flashDrawable = reelStopFlashDrawable(viewModel.uiState.value.config.theme)
        reelStopFlashViews.forEach { it.setImageResourceIfChanged(flashDrawable) }
        val layer = binding.reelStopFlashLayer
        reelStopAnimator?.cancel()
        reelStopAnimator = null
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 1f
        reelStopFlashViews.forEach { flash ->
            flash.animate().cancel()
            flash.visibility = View.INVISIBLE
            flash.alpha = 0f
            flash.scaleX = 0.78f
            flash.scaleY = 0.98f
            flash.translationY = -18f
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            hideReelStopFlashLayer(immediate = true)
            return
        }

        val columnAnimators = reelStopFlashViews.mapIndexed { column, flash ->
            AnimatorSet().apply {
                startDelay = column * 95L
                playTogether(
                    ObjectAnimator.ofFloat(flash, View.ALPHA, 0f, 0.86f, 0f),
                    ObjectAnimator.ofFloat(flash, View.SCALE_X, 0.78f, 1.08f, 0.9f),
                    ObjectAnimator.ofFloat(flash, View.TRANSLATION_Y, -18f, 8f)
                )
                duration = 280L
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        flash.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        flash.visibility = View.INVISIBLE
                        flash.alpha = 0f
                        flash.scaleX = 1f
                        flash.translationY = 0f
                    }
                })
            }
        }
        reelStopAnimator = AnimatorSet().apply {
            playTogether(columnAnimators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    layer.visibility = View.INVISIBLE
                    layer.alpha = 0f
                    if (reelStopAnimator === animation) {
                        reelStopAnimator = null
                        reelStopFlashViews.forEach(ImageView::clearBoundImageResource)
                    }
                }
            })
            start()
        }
    }

    private fun hideReelStopFlashLayer(immediate: Boolean = false) {
        val layer = binding.reelStopFlashLayer
        reelStopAnimator?.cancel()
        reelStopAnimator = null
        layer.animate().cancel()
        reelStopFlashViews.forEach { flash ->
            flash.animate().cancel()
            flash.visibility = View.INVISIBLE
            flash.alpha = 0f
            flash.scaleX = 1f
            flash.scaleY = 1f
            flash.translationY = 0f
        }
        if (immediate) {
            reelStopFlashViews.forEach(ImageView::clearBoundImageResource)
            layer.visibility = View.INVISIBLE
            layer.alpha = 0f
            return
        }
        layer.animate()
            .alpha(0f)
            .setDuration(120L)
            .withEndAction {
                reelStopFlashViews.forEach(ImageView::clearBoundImageResource)
                layer.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun hideReelBrakeLayer(immediate: Boolean = false) {
        val binding = _binding ?: return
        val layer = binding.reelBrakeLayer
        reelBrakeSequenceAnimator?.cancel()
        reelBrakeSequenceAnimator = null
        reelBrakeAnimators.values.toList().forEach { it.cancel() }
        reelBrakeAnimators.clear()
        layer.animate().cancel()
        reelBrakeViews.forEach { brake ->
            brake.animate().cancel()
            resetReelBrakeView(brake)
        }
        if (immediate) {
            reelBrakeViews.forEach(ImageView::clearBoundImageResource)
            layer.visibility = View.INVISIBLE
            layer.alpha = 0f
            return
        }
        if (layer.visibility != View.VISIBLE && layer.alpha == 0f) return
        layer.animate()
            .alpha(0f)
            .setDuration(120L)
            .withEndAction {
                reelBrakeViews.forEach(ImageView::clearBoundImageResource)
                layer.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun showPaytable(): Boolean {
        val state = viewModel.uiState.value
        if (
            state.isSpinStartReserved ||
            state.isSpinning ||
            state.isResultPending ||
            state.isSettlementRecoveryPending ||
            state.pendingPresentationId != null ||
            state.isAutoSpinEnabled
        ) {
            return false
        }
        if (parentFragmentManager.isStateSaved) return false
        if (parentFragmentManager.findFragmentByTag(PAYTABLE_DIALOG_TAG) != null) return false
        PaytableDialogFragment.newInstance(state.config.id).show(parentFragmentManager, PAYTABLE_DIALOG_TAG)
        return true
    }

    private fun showAutoSpinCountDialog(): Boolean {
        val state = viewModel.uiState.value
        if (
            state.isSpinStartReserved ||
            state.isSpinning ||
            state.isResultPending ||
            state.isSettlementRecoveryPending ||
            state.pendingPresentationId != null ||
            state.isAutoSpinEnabled
        ) {
            return false
        }
        if (parentFragmentManager.isStateSaved) return false
        if (parentFragmentManager.findFragmentByTag(AutoSpinCountDialogFragment.TAG) != null) return false
        AutoSpinCountDialogFragment().show(
            parentFragmentManager,
            AutoSpinCountDialogFragment.TAG
        )
        return true
    }

    private fun stopBackgroundFeedback() {
        slotSoundPlayer?.stopAll()
        wasSpinning = false
        stopSpinPerformanceHint()
        cancelLastWinCountUp()
        stopSpinPreview()
        stopSpinEnergyOverlay(immediate = true)
        stopThemeSpinOverlay(immediate = true)
        stopSpinReadyGlow(immediate = true)
        stopSlamStopCue(immediate = true)
        stopAutoSpinActiveHalo(immediate = true)
        stopCabinetLights()
        stopThemeAmbientOverlay()
        stopWinGlowOverlay()
        stopFreeSpinsRailCharge(immediate = true)
        stopFreeSpinsModeOverlay(immediate = true)
        stopFreeSpinsStakeLockOverlay(immediate = true)
        winningPaylineCarouselJob?.cancel()
        winningPaylineCarouselJob = null
        lastWinningPaylineSignature = null
        autoSpinResultDismissJob?.cancel()
        autoSpinResultDismissJob = null
    }

    override fun onDestroyView() {
        clearInlinePresentationDrawListener()
        stopSpinPreview()
        stopSpinEnergyOverlay(immediate = true)
        stopThemeSpinOverlay(immediate = true)
        stopSpinReadyGlow(immediate = true)
        hideSpinImpactFlash(immediate = true)
        stopSlamStopCue(immediate = true)
        stopAutoSpinActiveHalo(immediate = true)
        stopCabinetLights()
        stopThemeAmbientOverlay()
        stopWinGlowOverlay()
        hideThemeWinBurst(immediate = true)
        hideBonusEntryPortal(immediate = true)
        hideReelStopFlashLayer(immediate = true)
        hideReelBrakeLayer(immediate = true)
        hideReelMotionStreakLayer(immediate = true)
        hideReelAnticipationBeamLayer(immediate = true)
        hideSymbolWinHalos(immediate = true)
        hideBonusScatterHalos(immediate = true)
        winningPaylineCarouselJob?.cancel()
        winningPaylineCarouselJob = null
        autoSpinResultDismissJob?.cancel()
        autoSpinResultDismissJob = null
        lastWinningPaylineSignature = null
        activeLinesPulseAnimator?.cancel()
        activeLinesPulseAnimator = null
        balancePulseAnimator?.cancel()
        balancePulseAnimator = null
        slotLevelPulseAnimator?.cancel()
        slotLevelPulseAnimator = null
        totalBetPulseAnimator?.cancel()
        totalBetPulseAnimator = null
        cancelLastWinCountUp()
        freeSpinsPulseAnimator?.cancel()
        freeSpinsPulseAnimator = null
        stopFreeSpinsRailCharge(immediate = true)
        stopFreeSpinsModeOverlay(immediate = true)
        stopFreeSpinsStakeLockOverlay(immediate = true)
        lastPresentedBalance = null
        lastPresentedSlotLevel = null
        lastPresentedSlotLevelXp = null
        lastPresentedTotalBet = null
        lastPresentedFreeSpins = null
        lastCountedResult = null
        restoredLastWinAmount = null
        slotMarqueeGlassAnimator?.cancel()
        slotMarqueeGlassAnimator = null
        reelWindowDepthAnimator?.cancel()
        reelWindowDepthAnimator = null
        reelAnticipationKickAnimator?.cancel()
        reelAnticipationKickAnimator = null
        reelCells.clear()
        reelCellBackdrops.clear()
        reelStopFlashViews.clear()
        reelBrakeViews.clear()
        reelBrakeAnimators.clear()
        reelBrakeSequenceAnimator = null
        symbolWinHalos.clear()
        bonusScatterHalos.clear()
        reelSpinStopAnimators.clear()
        reelSpinStrips.forEach(ReelStripView::clearSymbols)
        reelSpinDrawableCache?.clear()
        reelSpinDrawableCache = null
        resultDrawablePreloadJob?.cancel()
        resultDrawablePreloadJob = null
        stopSpinPerformanceHint()
        transientDrawablePreloads.clear()
        preloadedSpinConfigId = null
        preloadedResultSignature = null
        reelSpinColumns.clear()
        reelSpinStrips.clear()
        reelMotionStreakViews.clear()
        reelAnticipationBeamViews.clear()
        reelAnticipationBeamAnimators.clear()
        reelLandingSparkViews.clear()
        reelLandingSparkAnimators.clear()
        slotSoundPlayer?.release()
        slotSoundPlayer = null
        hapticsEnabled = true
        wasSpinning = false
        _binding = null
        super.onDestroyView()
    }

    private fun stopWinGlowOverlay() {
        winGlowAnimator?.cancel()
        winGlowAnimator = null
        val glow = _binding?.winGlowOverlay ?: return
        glow.animate().cancel()
        glow.alpha = 0f
        glow.scaleX = 1f
        glow.scaleY = 1f
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L

    private fun shouldUseRichSpinEffects(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    private enum class CabinetLightMode {
        Idle,
        Spinning
    }

    private data class ThemeAmbientMotion(
        val idleLowAlpha: Float,
        val idleHighAlpha: Float,
        val spinLowAlpha: Float,
        val spinHighAlpha: Float,
        val idleDurationMs: Long,
        val spinDurationMs: Long,
        val phaseOffsetMs: Long,
        val driftX: Float,
        val driftY: Float,
        val scalePeak: Float,
        val rotationDegrees: Float
    )

    @RequiresApi(Build.VERSION_CODES.S)
    private object PerformanceHintApi31 {
        fun createSession(
            context: android.content.Context,
            threadId: Int,
            targetWorkDurationNanos: Long
        ): Closeable? {
            return context
                .getSystemService(PerformanceHintManager::class.java)
                ?.createHintSession(intArrayOf(threadId), targetWorkDurationNanos)
        }

        fun reportActualWorkDuration(session: Closeable, workDurationNanos: Long) {
            (session as PerformanceHintManager.Session).reportActualWorkDuration(workDurationNanos)
        }
    }

    private data class ThemeWinBurstMotion(
        val startScale: Float,
        val peakScale: Float,
        val peakAlpha: Float,
        val startX: Float,
        val endX: Float,
        val startY: Float,
        val endY: Float,
        val startRotation: Float,
        val endRotation: Float,
        val durationMs: Long
    )

    private data class ThemeSpinOverlayMotion(
        val lowAlpha: Float,
        val highAlpha: Float,
        val pulseDurationMs: Long,
        val driftDurationMs: Long,
        val driftX: Float,
        val startY: Float,
        val endY: Float,
        val scalePeak: Float,
        val rotationDegrees: Float
    )

    private enum class ReelSpinPhase {
        Acceleration,
        Cruise,
        Deceleration
    }

    private companion object {
        const val QA_PRESENTATION_TAG = "VSlotPresentation"
        const val QA_INLINE_FIRST_DRAW = "inline_first_draw"
        const val KEY_LAST_WIN_AMOUNT = "lastWinAmount"
        const val KEY_LAST_ANIMATED_PRESENTATION_ID = "lastAnimatedPresentationId"
        const val KEY_LAST_STOP_PRESENTATION_ID = "lastStopPresentationId"
        const val KEY_LAST_STARTED_SPIN_FEEDBACK_ID = "lastStartedSpinFeedbackId"
        const val REEL_WINDOW_DEPTH_POLISH_DURATION_MS = 720L
        const val REEL_WINDOW_LANDSCAPE_HORIZONTAL_INSET_DP = 30
        const val LANDSCAPE_STEPPER_EDGE_HIT_WIDTH_DP = 74
        const val PAYTABLE_CONTROL_ENABLED_ALPHA = 1f
        const val PAYTABLE_CONTROL_DISABLED_ALPHA = 0.42f
        const val REEL_WINDOW_DEPTH_SETTLED_ALPHA = 0.9f
        const val REEL_APERTURE_SETTLED_ALPHA = 0.92f
        const val SLOT_MARQUEE_GLASS_POLISH_DURATION_MS = 760L
        const val SLOT_MARQUEE_GLASS_SETTLED_ALPHA = 0.94f
        const val SPIN_ENERGY_PULSE_DURATION_MS = 420L
        const val THEME_SPIN_INTRO_DURATION_MS = 420L
        const val SPIN_BLUR_INTRO_DURATION_MS = 360L
        const val CABINET_SPIN_CHASE_ALPHA = 0.42f
        const val RESULT_DRAWABLE_PRELOAD_DELAY_MS = 500L
        const val SPIN_PERFORMANCE_TARGET_NANOS = 16_666_667L
        const val SPIN_ENERGY_LOW_ALPHA = 0.36f
        const val SPIN_ENERGY_HIGH_ALPHA = 0.72f
        const val THEME_AMBIENT_MAX_ALPHA = 0.44f
        const val THEME_WIN_BURST_MAX_ALPHA = 1f
        const val PARTIAL_RETURN_GLOW_ALPHA = 0.42f
        const val PARTIAL_RETURN_GLOW_IN_MS = 180L
        const val PARTIAL_RETURN_GLOW_OUT_MS = 320L
        const val BONUS_ENTRY_PORTAL_DURATION_MS = 1_560L
        const val BONUS_ENTRY_PORTAL_STATIC_HOLD_MS = 620L
        const val BONUS_ENTRY_PORTAL_PEAK_ALPHA = 0.96f
        const val SPIN_IMPACT_FLASH_DURATION_MS = 700L
        const val SPIN_IMPACT_HIGH_ALPHA = 1f
        const val SLAM_STOP_CUE_PULSE_DURATION_MS = 860L
        const val SLAM_STOP_CUE_ROTATION_DURATION_MS = 1_240L
        const val SLAM_STOP_CUE_FADE_DURATION_MS = 140L
        const val SLAM_STOP_CUE_LOW_ALPHA = 0.44f
        const val SLAM_STOP_CUE_HIGH_ALPHA = 0.92f
        const val AUTO_SPIN_HALO_PULSE_DURATION_MS = 980L
        const val AUTO_SPIN_HALO_ROTATION_DURATION_MS = 1_900L
        const val AUTO_SPIN_HALO_LOW_ALPHA = 0.62f
        const val AUTO_SPIN_HALO_HIGH_ALPHA = 1f
        const val AUTO_SPIN_RESULT_DISMISS_DELAY_MS = 1_850L
        const val AUTO_SPIN_BONUS_RESULT_DISMISS_DELAY_MS = 2_450L
        const val QA_AUTO_SPIN_EXTRA = "qa_auto_spin"
        const val QA_AUTO_SPIN_START_DELAY_MS = 850L
        const val DEFAULT_AUTO_SPIN_COUNT = 10
        const val SPIN_RESULT_DIALOG_TAG = "spin_result"
        const val LOW_COINS_DIALOG_TAG = "low_coins_bonus"
        const val PAYTABLE_DIALOG_TAG = "paytable"
        const val ACTIVE_LINES_PULSE_DURATION_MS = 360L
        const val BALANCE_CHANGE_PULSE_DURATION_MS = 320L
        const val SLOT_LEVEL_CHANGE_PULSE_DURATION_MS = 420L
        const val TOTAL_BET_CHANGE_PULSE_DURATION_MS = 340L
        const val TOTAL_BET_LINK_PEAK_ALPHA = 0.86f
        const val CONTROL_METER_SETTLED_ALPHA = 0.62f
        const val FREE_SPINS_PULSE_DURATION_MS = 360L
        const val FREE_SPINS_RAIL_CHARGE_PULSE_DURATION_MS = 1_080L
        const val FREE_SPINS_RAIL_CHARGE_FADE_DURATION_MS = 180L
        const val FREE_SPINS_RAIL_CHARGE_LOW_ALPHA = 0.52f
        const val FREE_SPINS_RAIL_CHARGE_HIGH_ALPHA = 0.94f
        const val FREE_SPINS_MODE_PULSE_DURATION_MS = 1_420L
        const val FREE_SPINS_MODE_DRIFT_DURATION_MS = 1_920L
        const val FREE_SPINS_MODE_LOW_ALPHA = 0.5f
        const val FREE_SPINS_MODE_HIGH_ALPHA = 0.86f
        const val FREE_SPINS_STAKE_LOCK_ENTER_DURATION_MS = 360L
        const val FREE_SPINS_STAKE_LOCK_FADE_DURATION_MS = 160L
        const val FREE_SPINS_STAKE_LOCK_SETTLED_ALPHA = 0.72f
        const val FREE_SPINS_STAKE_LOCK_PEAK_ALPHA = 0.96f
        const val BONUS_SCATTER_HALO_TRIGGER_MS = 700L
        const val BONUS_SCATTER_HALO_STAGGER_MS = 95L
        const val BONUS_SCATTER_HALO_SETTLED_ALPHA = 0.92f
        const val REEL_COUNT = 5
        const val REEL_VISIBLE_ROWS = 3
        const val REEL_COLUMN_OFFSET = 3
        const val REEL_SPIN_TICK_MS = 20L
        const val REEL_SPIN_COLUMNS_PER_TICK = 1
        const val NO_REEL_MOTION_STREAK_MODE = -1
        const val REEL_MOTION_STREAK_MODE_VARIANTS = 2
        const val REEL_SPIN_MIN_RENDER_INTERVAL_MS = 80L
        const val REEL_SPIN_OVERLAP_MS = 0L
        const val REEL_SPIN_ACCELERATION_MS = 420L
        const val REEL_SPIN_DECELERATION_MS = 620L
        const val REEL_SPIN_ACCEL_FRAME_MS = 96L
        const val REEL_SPIN_CRUISE_FRAME_MS = 42L
        const val REEL_SPIN_DECEL_FRAME_MS = 136L
        const val REEL_SPIN_ACCEL_STEP_SYMBOLS = 1
        const val REEL_SPIN_CRUISE_STEP_SYMBOLS = 1
        const val REEL_SPIN_DECEL_STEP_SYMBOLS = 1
        const val REEL_SCATTER_ANTICIPATION_STEP_SYMBOLS = 1
        const val REEL_SPIN_COLUMN_DURATION_OFFSET_MS = 6
        const val REEL_SPIN_SYMBOL_BLUR_ALPHA = 0.94f
        const val REEL_SPIN_SYMBOL_BLUR_SCALE_Y = 1.12f
        const val REEL_STOP_ANTICIPATION_MS = 300L
        const val REEL_STOP_WINDOW_KICK_DURATION_MS = 240L
        const val REEL_STOP_WINDOW_KICK_TRAVEL_DP = 5
        const val REEL_STOP_ANTICIPATION_FLASH_ALPHA = 0.5f
        const val REEL_SCATTER_ANTICIPATION_WINDOW_MS = 1_040L
        const val REEL_SCATTER_ANTICIPATION_DURATION_MS = 560L
        const val REEL_SCATTER_ANTICIPATION_FRAME_MS = 176L
        const val REEL_SCATTER_ANTICIPATION_NUDGE_DP = 7
        const val REEL_SCATTER_WINDOW_KICK_DURATION_MS = 560L
        const val REEL_SCATTER_WINDOW_KICK_TRAVEL_DP = 11
        const val REEL_SCATTER_ANTICIPATION_FLASH_ALPHA = 0.86f
        const val LAST_WIN_COUNT_RENDER_INTERVAL_MS = 33L
        const val REEL_SCATTER_BEAM_DURATION_MS = 940L
        const val REEL_SCATTER_BEAM_FADE_MS = 160L
        const val REEL_SCATTER_BEAM_ENTER_TRAVEL_DP = 24
        const val REEL_SCATTER_BEAM_LIFT_DP = 10
        const val REEL_SCATTER_BEAM_PEAK_ALPHA = 0.9f
        const val REEL_SCATTER_BEAM_SETTLED_ALPHA = 0.56f
        const val REEL_LANDING_SPARK_DURATION_MS = 420L
        const val REEL_LANDING_SPARK_FADE_MS = 150L
        const val REEL_LANDING_SPARK_ENTER_TRAVEL_DP = 18
        const val REEL_LANDING_SPARK_LIFT_DP = 12
        const val REEL_LANDING_SPARK_PEAK_ALPHA = 0.74f
        const val REEL_LANDING_SPARK_SETTLE_ALPHA = 0.46f
        const val REEL_SPIN_CELL_GAP_DP = 10
        const val REEL_SPIN_FALLBACK_TRAVEL_DP = 118
        const val REEL_STOP_SETTLE_TRAVEL_DP = 46
        const val REEL_STOP_ROW_DELAY_MS = 18L
        const val REEL_STOP_BOUNCE_DURATION_MS = 320L
        const val REEL_SPIN_STRIP_SYMBOL_COUNT = 8
        const val REEL_SPIN_FALLBACK_CELL_HEIGHT_DP = 118
        const val REEL_STOP_STRIP_BOUNCE_DURATION_MS = 300L
        const val REEL_STOPPED_COLUMN_FADE_MS = 170L
        const val REEL_MOTION_STREAK_LAYER_FADE_MS = 160L
        const val REEL_MOTION_STREAK_SETTLE_MS = 210L
        const val REEL_MOTION_STREAK_ANTICIPATION_PULSE_MS = 210L
        const val REEL_MOTION_STREAK_SCATTER_PULSE_MS = 320L
        const val REEL_MOTION_STREAK_ANTICIPATION_ALPHA = 0.66f
        const val REEL_MOTION_STREAK_SCATTER_ALPHA = 0.82f
        const val REEL_BRAKE_COLUMN_STAGGER_MS = 95L
        const val REEL_BRAKE_ANTICIPATION_PULSE_DURATION_MS = 230L
        const val REEL_BRAKE_FINAL_PULSE_DURATION_MS = 320L
        const val REEL_BRAKE_SCATTER_PULSE_DURATION_MS = 360L
        const val REEL_BRAKE_SEQUENCE_DURATION_MS = 330L
        const val REEL_BRAKE_ANTICIPATION_HIGH_ALPHA = 0.42f
        const val REEL_BRAKE_FINAL_HIGH_ALPHA = 0.76f
        const val REEL_BRAKE_SCATTER_HIGH_ALPHA = 0.86f
        const val REEL_BRAKE_SEQUENCE_HIGH_ALPHA = 0.7f
        val VIOLET_PAYLINE_MARKER_DRAWABLES = intArrayOf(
            R.drawable.payline_markers_overlay_active_1,
            R.drawable.payline_markers_overlay_active_2,
            R.drawable.payline_markers_overlay_active_3,
            R.drawable.payline_markers_overlay_active_4,
            R.drawable.payline_markers_overlay_active_5,
            R.drawable.payline_markers_overlay_active_6,
            R.drawable.payline_markers_overlay_active_7,
            R.drawable.payline_markers_overlay_active_8,
            R.drawable.payline_markers_overlay_active_9,
            R.drawable.payline_markers_overlay_active_10
        )
        val ROMAN_PAYLINE_MARKER_DRAWABLES = intArrayOf(
            R.drawable.payline_markers_overlay_roman_active_1,
            R.drawable.payline_markers_overlay_roman_active_2,
            R.drawable.payline_markers_overlay_roman_active_3,
            R.drawable.payline_markers_overlay_roman_active_4,
            R.drawable.payline_markers_overlay_roman_active_5,
            R.drawable.payline_markers_overlay_roman_active_6,
            R.drawable.payline_markers_overlay_roman_active_7,
            R.drawable.payline_markers_overlay_roman_active_8,
            R.drawable.payline_markers_overlay_roman_active_9,
            R.drawable.payline_markers_overlay_roman_active_10
        )
        val NEON_PAYLINE_MARKER_DRAWABLES = intArrayOf(
            R.drawable.payline_markers_overlay_neon_active_1,
            R.drawable.payline_markers_overlay_neon_active_2,
            R.drawable.payline_markers_overlay_neon_active_3,
            R.drawable.payline_markers_overlay_neon_active_4,
            R.drawable.payline_markers_overlay_neon_active_5,
            R.drawable.payline_markers_overlay_neon_active_6,
            R.drawable.payline_markers_overlay_neon_active_7,
            R.drawable.payline_markers_overlay_neon_active_8,
            R.drawable.payline_markers_overlay_neon_active_9,
            R.drawable.payline_markers_overlay_neon_active_10
        )
        val PHARAOH_PAYLINE_MARKER_DRAWABLES = intArrayOf(
            R.drawable.payline_markers_overlay_pharaoh_active_1,
            R.drawable.payline_markers_overlay_pharaoh_active_2,
            R.drawable.payline_markers_overlay_pharaoh_active_3,
            R.drawable.payline_markers_overlay_pharaoh_active_4,
            R.drawable.payline_markers_overlay_pharaoh_active_5,
            R.drawable.payline_markers_overlay_pharaoh_active_6,
            R.drawable.payline_markers_overlay_pharaoh_active_7,
            R.drawable.payline_markers_overlay_pharaoh_active_8,
            R.drawable.payline_markers_overlay_pharaoh_active_9,
            R.drawable.payline_markers_overlay_pharaoh_active_10
        )
        val OCEAN_PAYLINE_MARKER_DRAWABLES = intArrayOf(
            R.drawable.payline_markers_overlay_ocean_active_1,
            R.drawable.payline_markers_overlay_ocean_active_2,
            R.drawable.payline_markers_overlay_ocean_active_3,
            R.drawable.payline_markers_overlay_ocean_active_4,
            R.drawable.payline_markers_overlay_ocean_active_5,
            R.drawable.payline_markers_overlay_ocean_active_6,
            R.drawable.payline_markers_overlay_ocean_active_7,
            R.drawable.payline_markers_overlay_ocean_active_8,
            R.drawable.payline_markers_overlay_ocean_active_9,
            R.drawable.payline_markers_overlay_ocean_active_10
        )
        val VIOLET_PAYLINE_WIN_DRAWABLES = intArrayOf(
            R.drawable.payline_win_1,
            R.drawable.payline_win_2,
            R.drawable.payline_win_3,
            R.drawable.payline_win_4,
            R.drawable.payline_win_5,
            R.drawable.payline_win_6,
            R.drawable.payline_win_7,
            R.drawable.payline_win_8,
            R.drawable.payline_win_9,
            R.drawable.payline_win_10
        )
        val ROMAN_PAYLINE_WIN_DRAWABLES = intArrayOf(
            R.drawable.payline_win_roman_1,
            R.drawable.payline_win_roman_2,
            R.drawable.payline_win_roman_3,
            R.drawable.payline_win_roman_4,
            R.drawable.payline_win_roman_5,
            R.drawable.payline_win_roman_6,
            R.drawable.payline_win_roman_7,
            R.drawable.payline_win_roman_8,
            R.drawable.payline_win_roman_9,
            R.drawable.payline_win_roman_10
        )
        val NEON_PAYLINE_WIN_DRAWABLES = intArrayOf(
            R.drawable.payline_win_neon_1,
            R.drawable.payline_win_neon_2,
            R.drawable.payline_win_neon_3,
            R.drawable.payline_win_neon_4,
            R.drawable.payline_win_neon_5,
            R.drawable.payline_win_neon_6,
            R.drawable.payline_win_neon_7,
            R.drawable.payline_win_neon_8,
            R.drawable.payline_win_neon_9,
            R.drawable.payline_win_neon_10
        )
        val PHARAOH_PAYLINE_WIN_DRAWABLES = intArrayOf(
            R.drawable.payline_win_pharaoh_1,
            R.drawable.payline_win_pharaoh_2,
            R.drawable.payline_win_pharaoh_3,
            R.drawable.payline_win_pharaoh_4,
            R.drawable.payline_win_pharaoh_5,
            R.drawable.payline_win_pharaoh_6,
            R.drawable.payline_win_pharaoh_7,
            R.drawable.payline_win_pharaoh_8,
            R.drawable.payline_win_pharaoh_9,
            R.drawable.payline_win_pharaoh_10
        )
        val OCEAN_PAYLINE_WIN_DRAWABLES = intArrayOf(
            R.drawable.payline_win_ocean_1,
            R.drawable.payline_win_ocean_2,
            R.drawable.payline_win_ocean_3,
            R.drawable.payline_win_ocean_4,
            R.drawable.payline_win_ocean_5,
            R.drawable.payline_win_ocean_6,
            R.drawable.payline_win_ocean_7,
            R.drawable.payline_win_ocean_8,
            R.drawable.payline_win_ocean_9,
            R.drawable.payline_win_ocean_10
        )
    }
}
