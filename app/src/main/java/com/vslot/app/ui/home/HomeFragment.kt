package com.vslot.app.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.vslot.app.AppGraph
import com.vslot.app.R
import com.vslot.app.databinding.FragmentHomeBinding
import com.vslot.app.data.PlayerState
import com.vslot.app.ui.DailyBonusCountdownFormatter
import com.vslot.app.ui.asCoins
import com.vslot.app.ui.dialog.DailyBonusDialogFragment
import com.vslot.app.ui.widget.clearImageResourcesRecursively
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val shineAnimators = mutableListOf<ObjectAnimator>()
    private val auraAnimators = mutableListOf<ObjectAnimator>()
    private val unlockBurstAnimators = mutableMapOf<String, AnimatorSet>()
    private var shineJob: Job? = null
    private var auraJob: Job? = null
    private var dailyBonusCountdownJob: Job? = null
    private var dailyBonusCountdownTimestamp: Long? = null
    private var dailyBonusAccessibilityBucket: Int? = null
    private var lastObservedPlayerLevel: Int? = null
    private var latestPlayerState = PlayerState()
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(AppGraph.playerRepository, AppGraph.analyticsTracker)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.onHomeVisible()
        binding.violetCard.setOnClickListener {
            openSlotIfUnlocked(SlotUnlockRules.VIOLET_FORTUNE, getString(R.string.slot_violet_fortune))
        }
        binding.romanCard.setOnClickListener {
            openSlotIfUnlocked(SlotUnlockRules.ROMAN_REELS, getString(R.string.slot_roman_reels))
        }
        binding.neonCard.setOnClickListener {
            openSlotIfUnlocked(SlotUnlockRules.NEON_NIGHTS, getString(R.string.slot_neon_nights))
        }
        binding.pharaohCard.setOnClickListener {
            openSlotIfUnlocked(SlotUnlockRules.PHARAOH_GOLD, getString(R.string.slot_pharaoh_gold))
        }
        binding.oceanCard.setOnClickListener {
            openSlotIfUnlocked(SlotUnlockRules.OCEAN_PEARL, getString(R.string.slot_ocean_pearl))
        }
        installLockedSlotAccessibility(
            binding.neonCard,
            SlotUnlockRules.NEON_NIGHTS,
            getString(R.string.slot_neon_nights)
        )
        installLockedSlotAccessibility(
            binding.pharaohCard,
            SlotUnlockRules.PHARAOH_GOLD,
            getString(R.string.slot_pharaoh_gold)
        )
        installLockedSlotAccessibility(
            binding.oceanCard,
            SlotUnlockRules.OCEAN_PEARL,
            getString(R.string.slot_ocean_pearl)
        )
        binding.settingsButton.setOnClickListener { navigateFromHome(R.id.action_home_to_settings) }
        binding.privacyButton.setOnClickListener { navigateFromHome(R.id.action_home_to_privacy) }
        binding.dailyBonusButton.setOnClickListener {
            openDailyBonusFromUserAction()
        }
        binding.homeSlotScrollView?.let { scrollView ->
            val bottomVeil = binding.homeScrollBottomVeil ?: return@let
            scrollView.setOnScrollChangeListener { view, _, _, _, _ ->
                bottomVeil.isVisible = view.canScrollVertically(1)
            }
            scrollView.post {
                bottomVeil.isVisible = scrollView.canScrollVertically(1)
            }
        }
        binding.homeXpLabel?.isVisible = resources.configuration.screenWidthDp >= XP_LABEL_MIN_WIDTH_DP

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state ->
                    latestPlayerState = state
                    binding.balanceDigits.setNumber(state.coinsBalance)
                    binding.balanceDigits.contentDescription = "${getString(R.string.balance)} ${state.coinsBalance.asCoins()}"
                    bindLevelState(state)
                    bindSlotUnlockState(state)
                    renderDailyBonusState(state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startHomeAuraAnimations()
        startHomeShineAnimations()
    }

    override fun onStop() {
        stopLockedSlotPulseAnimations()
        stopUnlockBurstAnimations()
        stopHomeShineAnimations()
        stopHomeAuraAnimations()
        stopDailyBonusCountdown()
        super.onStop()
    }

    private fun renderDailyBonusState(state: PlayerState) {
        val available = state.isDailyBonusAvailable()
        binding.dailyBonusImage.setImageResource(
            if (available) R.drawable.daily_bonus_ready_imagegen else R.drawable.daily_bonus_wait_imagegen
        )
        binding.dailyBonusStatusText.visibility = if (available) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.dailyBonusClaimPlate.visibility = binding.dailyBonusStatusText.visibility
        binding.dailyBonusStatusText.setImageResource(R.drawable.label_claim_bonus)
        binding.dailyBonusCountdownRail.visibility = if (available) {
            View.GONE
        } else {
            View.VISIBLE
        }
        if (available) {
            binding.dailyBonusButton.contentDescription = getString(R.string.daily_bonus_ready_action)
            resetDailyBonusCountdownCharge()
            stopDailyBonusCountdown()
        } else {
            startDailyBonusCountdown(state.lastDailyBonusTimestamp)
        }
    }

    private fun bindLevelState(state: PlayerState) {
        val xpText = "${state.xpInCurrentLevel}/${state.xpForCurrentLevel}"
        binding.homeLevelDigits.setNumber(state.playerLevel)
        binding.homeXpDigits.setCharacters(
            xpText,
            spacingPx = 0,
            compactSeparators = true,
            fixedGlyphBaseWidthDp = homeXpGlyphBaseWidthDp(xpText)
        )
        binding.homeLevelPanel.contentDescription = getString(
            R.string.player_level_accessibility,
            state.playerLevel,
            state.xpInCurrentLevel,
            state.xpForCurrentLevel
        )
        bindLevelProgressFill(state)
    }

    private fun homeXpGlyphBaseWidthDp(value: String): Float {
        val glyphWeight = value.sumOf { character ->
            if (character == '/') HOME_XP_SLASH_WEIGHT else 1.0
        }.coerceAtLeast(1.0)
        return (HOME_XP_CONTENT_WIDTH_DP / glyphWeight.toFloat())
            .coerceAtMost(HOME_XP_MAX_GLYPH_BASE_WIDTH_DP)
    }

    private fun bindSlotUnlockState(state: PlayerState) {
        bindSlotGate(
            card = binding.violetCard,
            lockOverlay = null,
            lockPulse = null,
            slotId = SlotUnlockRules.VIOLET_FORTUNE,
            slotName = getString(R.string.slot_violet_fortune),
            playDescriptionRes = R.string.home_play_violet_slot,
            playerLevel = state.playerLevel
        )
        bindSlotGate(
            card = binding.romanCard,
            lockOverlay = null,
            lockPulse = null,
            slotId = SlotUnlockRules.ROMAN_REELS,
            slotName = getString(R.string.slot_roman_reels),
            playDescriptionRes = R.string.home_play_roman_slot,
            playerLevel = state.playerLevel
        )
        bindSlotGate(
            card = binding.neonCard,
            lockOverlay = binding.neonLockedOverlay,
            lockPulse = binding.neonLockedPulse,
            slotId = SlotUnlockRules.NEON_NIGHTS,
            slotName = getString(R.string.slot_neon_nights),
            playDescriptionRes = R.string.home_play_neon_slot,
            playerLevel = state.playerLevel
        )
        bindSlotGate(
            card = binding.pharaohCard,
            lockOverlay = binding.pharaohLockedOverlay,
            lockPulse = binding.pharaohLockedPulse,
            slotId = SlotUnlockRules.PHARAOH_GOLD,
            slotName = getString(R.string.slot_pharaoh_gold),
            playDescriptionRes = R.string.home_play_pharaoh_slot,
            playerLevel = state.playerLevel
        )
        bindSlotGate(
            card = binding.oceanCard,
            lockOverlay = binding.oceanLockedOverlay,
            lockPulse = binding.oceanLockedPulse,
            slotId = SlotUnlockRules.OCEAN_PEARL,
            slotName = getString(R.string.slot_ocean_pearl),
            playDescriptionRes = R.string.home_play_ocean_slot,
            playerLevel = state.playerLevel
        )
        animateNewlyUnlockedSlots(state.playerLevel)
    }

    private fun bindSlotGate(
        card: View,
        lockOverlay: ImageView?,
        lockPulse: ImageView?,
        slotId: String,
        slotName: String,
        playDescriptionRes: Int,
        playerLevel: Int
    ) {
        val requiredLevel = SlotUnlockRules.requiredLevel(slotId)
        val unlocked = SlotUnlockRules.isUnlocked(slotId, playerLevel)
        card.contentDescription = if (unlocked) {
            getString(playDescriptionRes)
        } else {
            getString(R.string.slot_locked_until_level, slotName, requiredLevel)
        }

        if (lockOverlay == null && lockPulse == null) return

        bindLockOverlay(lockOverlay, requiredLevel, unlocked)
        bindLockPulse(lockPulse, unlocked)
    }

    private fun installLockedSlotAccessibility(card: View, slotId: String, slotName: String) {
        val requiredLevel = SlotUnlockRules.requiredLevel(slotId)
        ViewCompat.setAccessibilityDelegate(
            card,
            object : AccessibilityDelegateCompat() {
                @Suppress("DEPRECATION")
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (SlotUnlockRules.isUnlocked(slotId, latestPlayerState.playerLevel)) return
                    val lockedDescription = getString(
                        R.string.slot_locked_until_level,
                        slotName,
                        requiredLevel
                    )
                    info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
                    info.isEnabled = false
                    info.contentDescription = lockedDescription
                }
            }
        )
    }

    private fun bindLockOverlay(lockOverlay: ImageView?, requiredLevel: Int, unlocked: Boolean) {
        if (lockOverlay == null) return
        lockOverlay.animate().cancel()
        lockOverlay.scaleX = 1f
        lockOverlay.scaleY = 1f
        lockOverlay.alpha = 1f
        if (unlocked) {
            lockOverlay.visibility = View.GONE
        } else {
            lockOverlay.setImageResource(slotLockOverlayDrawable(requiredLevel))
            lockOverlay.visibility = View.VISIBLE
        }
    }

    private fun bindLockPulse(lockPulse: ImageView?, unlocked: Boolean) {
        if (lockPulse == null) return
        lockPulse.animate().cancel()
        lockPulse.scaleX = 1f
        lockPulse.scaleY = 1f
        if (unlocked) {
            lockPulse.alpha = 0f
            lockPulse.visibility = View.GONE
        } else {
            lockPulse.alpha = HOME_LOCKED_PULSE_SETTLED_ALPHA
            lockPulse.visibility = View.VISIBLE
        }
    }

    private fun bindLevelProgressFill(state: PlayerState) {
        val requiredXp = state.xpForCurrentLevel
        val progress = if (requiredXp <= 0) {
            1f
        } else {
            state.xpInCurrentLevel.toFloat()
                .div(requiredXp)
                .coerceIn(0f, 1f)
        }
        binding.homeXpProgressFill.pivotX = 0f
        binding.homeXpProgressFill.pivotY = binding.homeXpProgressFill.height / 2f
        binding.homeXpProgressFill.scaleX = progress
        binding.homeXpTrack.doOnLayout {
            bindLevelProgressMarker(progress)
        }
    }

    private fun bindLevelProgressMarker(progress: Float) {
        val trackWidth = binding.homeXpTrack.usableWidth()
        val capWidth = binding.homeXpProgressCap.usableWidth()
        val pulseWidth = binding.homeXpProgressPulse.usableWidth()
        val minCenter = capWidth / 2f
        val maxCenter = (trackWidth - capWidth / 2f).coerceAtLeast(minCenter)
        val centerX = (trackWidth * progress).coerceIn(minCenter, maxCenter)

        binding.homeXpProgressCap.translationX = centerX - capWidth / 2f
        binding.homeXpProgressPulse.translationX = centerX - pulseWidth / 2f
        binding.homeXpProgressPulse.alpha = 0.22f + progress * 0.28f
        binding.homeXpProgressCap.alpha = if (progress <= 0f) 0.74f else 1f
    }

    private fun animateNewlyUnlockedSlots(playerLevel: Int) {
        val previousLevel = lastObservedPlayerLevel
        lastObservedPlayerLevel = playerLevel
        if (previousLevel == null || playerLevel <= previousLevel) return

        SlotUnlockRules.slotsUnlockedBetween(previousLevel, playerLevel)
            .forEachIndexed { index, slotId ->
                pulseSlotUnlock(slotId, index)
            }
    }

    private fun pulseSlotUnlock(slotId: String, order: Int) {
        val burst = unlockBurstForSlot(slotId) ?: return
        val card = cardForSlot(slotId) ?: return
        unlockBurstAnimators.remove(slotId)?.cancel()
        burst.animate().cancel()
        card.animate().cancel()
        burst.visibility = View.VISIBLE
        burst.alpha = 0f
        burst.scaleX = 0.94f
        burst.scaleY = 0.94f
        burst.translationY = HOME_UNLOCK_BURST_ENTER_TRAVEL_DP.dp().toFloat()
        card.scaleX = 1f
        card.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetUnlockBurstView(burst)
            card.scaleX = 1f
            card.scaleY = 1f
            return
        }

        unlockBurstAnimators[slotId] = AnimatorSet().apply {
            startDelay = order * HOME_UNLOCK_BURST_STAGGER_MS
            playTogether(
                ObjectAnimator.ofFloat(burst, View.ALPHA, 0f, HOME_UNLOCK_BURST_PEAK_ALPHA, HOME_UNLOCK_BURST_SETTLED_ALPHA, 0f),
                ObjectAnimator.ofFloat(burst, View.SCALE_X, 0.94f, 1.04f, 1f, 0.99f),
                ObjectAnimator.ofFloat(burst, View.SCALE_Y, 0.94f, 1.04f, 1f, 0.99f),
                ObjectAnimator.ofFloat(burst, View.TRANSLATION_Y, HOME_UNLOCK_BURST_ENTER_TRAVEL_DP.dp().toFloat(), -HOME_UNLOCK_BURST_LIFT_DP.dp().toFloat(), 0f),
                ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, 1.012f, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, 1.012f, 1f)
            )
            duration = HOME_UNLOCK_BURST_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleSlotUnlockBurst(slotId, burst, card, animation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    settleSlotUnlockBurst(slotId, burst, card, animation)
                }
            })
            start()
        }
    }

    private fun settleSlotUnlockBurst(slotId: String, burst: ImageView, card: View, animation: Animator) {
        if (unlockBurstAnimators[slotId] === animation) {
            unlockBurstAnimators.remove(slotId)
        }
        resetUnlockBurstView(burst)
        card.scaleX = 1f
        card.scaleY = 1f
    }

    private fun resetUnlockBurstView(burst: ImageView) {
        burst.visibility = View.GONE
        burst.alpha = 0f
        burst.scaleX = 1f
        burst.scaleY = 1f
        burst.translationY = 0f
    }

    private fun View.usableWidth(): Float {
        val measured = width
            .takeIf { it > 0 }
            ?: measuredWidth.takeIf { it > 0 }
            ?: layoutParams.width.takeIf { it > 0 }
            ?: 1
        return measured.toFloat()
    }

    private fun startDailyBonusCountdown(lastDailyBonusTimestamp: Long) {
        if (dailyBonusCountdownTimestamp == lastDailyBonusTimestamp && dailyBonusCountdownJob?.isActive == true) {
            bindDailyBonusCountdown(lastDailyBonusTimestamp)
            return
        }
        stopDailyBonusCountdown()
        dailyBonusCountdownTimestamp = lastDailyBonusTimestamp
        dailyBonusCountdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val binding = _binding ?: return@launch
                if (latestPlayerState.isDailyBonusAvailable()) {
                    renderDailyBonusState(latestPlayerState)
                    return@launch
                }
                bindDailyBonusCountdown(lastDailyBonusTimestamp)
                delay(dailyBonusCountdownTickDelayMs())
                if (_binding !== binding) return@launch
            }
        }
    }

    private fun bindDailyBonusCountdown(lastDailyBonusTimestamp: Long) {
        val cooldown = dailyBonusCooldown(lastDailyBonusTimestamp)
        bindDailyBonusCountdownAccessibility(cooldown)
        binding.dailyBonusCountdownDigits.setCharacters(
            cooldown.digits,
            spacingPx = 0,
            compactSeparators = true,
            fixedGlyphBaseWidthDp = 12f
        )
        bindDailyBonusCountdownCharge(cooldown)
    }

    private fun bindDailyBonusCountdownCharge(cooldown: CooldownDisplay) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            binding.dailyBonusCountdownCharge.alpha = DAILY_BONUS_COUNTDOWN_CHARGE_SETTLED_ALPHA
            binding.dailyBonusCountdownCharge.translationX = 0f
            return
        }
        val phase = cooldown.seconds % DAILY_BONUS_COUNTDOWN_CHARGE_PHASES
        val middle = (DAILY_BONUS_COUNTDOWN_CHARGE_PHASES - 1) / 2f
        val normalized = kotlin.math.abs(phase - middle) / middle
        binding.dailyBonusCountdownCharge.alpha = DAILY_BONUS_COUNTDOWN_CHARGE_LOW_ALPHA +
            (1f - normalized) * DAILY_BONUS_COUNTDOWN_CHARGE_ALPHA_RANGE
        binding.dailyBonusCountdownCharge.translationX = (phase - middle) *
            DAILY_BONUS_COUNTDOWN_CHARGE_TRAVEL_DP.dp().toFloat()
    }

    private fun resetDailyBonusCountdownCharge() {
        binding.dailyBonusCountdownCharge.alpha = 0f
        binding.dailyBonusCountdownCharge.translationX = 0f
    }

    private fun bindDailyBonusCountdownAccessibility(cooldown: CooldownDisplay) {
        val accessibility = DailyBonusCountdownFormatter.accessibility(
            hours = cooldown.hours,
            minutes = cooldown.minutes,
            seconds = cooldown.seconds
        )
        val cooldownDescription = if (accessibility.usesSeconds) {
            getString(R.string.daily_bonus_cooldown_remaining_seconds_accessibility, accessibility.seconds)
        } else {
            getString(
                R.string.daily_bonus_cooldown_remaining_accessibility,
                accessibility.hours,
                accessibility.minutes
            )
        }
        if (
            dailyBonusAccessibilityBucket == accessibility.bucket &&
            binding.dailyBonusButton.contentDescription == cooldownDescription
        ) {
            return
        }
        dailyBonusAccessibilityBucket = accessibility.bucket
        binding.dailyBonusButton.contentDescription = cooldownDescription
    }

    private fun dailyBonusCountdownTickDelayMs(): Long {
        return if (isDailyBonusCountdownRailVisible()) {
            DAILY_BONUS_COUNTDOWN_VISIBLE_TICK_MS
        } else {
            DAILY_BONUS_COUNTDOWN_BACKGROUND_TICK_MS
        }
    }

    private fun isDailyBonusCountdownRailVisible(): Boolean {
        val binding = _binding ?: return false
        if (binding.dailyBonusCountdownRail.visibility != View.VISIBLE) return false
        val visibleRect = Rect()
        return binding.dailyBonusCountdownRail.getGlobalVisibleRect(visibleRect) &&
            visibleRect.width() > 0 &&
            visibleRect.height() > 0
    }

    private fun stopDailyBonusCountdown() {
        dailyBonusCountdownJob?.cancel()
        dailyBonusCountdownJob = null
        dailyBonusCountdownTimestamp = null
        dailyBonusAccessibilityBucket = null
    }

    private fun dailyBonusCooldown(lastDailyBonusTimestamp: Long): CooldownDisplay {
        val countdown = DailyBonusCountdownFormatter.format(lastDailyBonusTimestamp)
        return CooldownDisplay(
            hours = countdown.hours,
            minutes = countdown.minutes,
            seconds = countdown.seconds,
            digits = countdown.digits
        )
    }

    private data class CooldownDisplay(
        val hours: Int,
        val minutes: Int,
        val seconds: Int,
        val digits: String
    )

    private fun openSlot(slotId: String, slotName: String) {
        val opened = navigateFromHome(
            R.id.action_home_to_slot,
            Bundle().apply { putString("slotId", slotId) },
            beforeNavigation = {
                releaseHomeImageResources()
                (activity as? com.vslot.app.MainActivity)?.prepareScreenBackgroundForSlot(slotId)
            }
        )
        if (opened) {
            viewModel.onSlotSelected(slotId, slotName)
        }
    }

    private fun releaseHomeImageResources() {
        _binding?.root?.clearImageResourcesRecursively()
    }

    private fun navigateFromHome(
        actionId: Int,
        args: Bundle? = null,
        beforeNavigation: () -> Unit = {}
    ): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.homeFragment) return false
        if (parentFragmentManager.isStateSaved) return false
        beforeNavigation()
        navController.navigate(actionId, args)
        return true
    }

    private fun openDailyBonusFromUserAction() {
        val available = latestPlayerState.isDailyBonusAvailable()
        val dialogShown = showDailyBonusDialog(
            claimEnabled = available,
            lastDailyBonusTimestamp = latestPlayerState.lastDailyBonusTimestamp
        )
        if (dialogShown) {
            viewModel.onDailyBonusOpen(available)
        }
    }

    private fun showDailyBonusDialog(claimEnabled: Boolean, lastDailyBonusTimestamp: Long): Boolean {
        if (parentFragmentManager.isStateSaved) return false
        if (parentFragmentManager.findFragmentByTag(DAILY_BONUS_DIALOG_TAG) != null) return false
        DailyBonusDialogFragment.newInstance(
            claimEnabled = claimEnabled,
            lastDailyBonusTimestamp = lastDailyBonusTimestamp
        )
            .show(parentFragmentManager, DAILY_BONUS_DIALOG_TAG)
        return true
    }

    private fun openSlotIfUnlocked(slotId: String, slotName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val playerLevel = viewModel.playerState.first().playerLevel
            if (_binding == null) return@launch
            if (!SlotUnlockRules.isUnlocked(slotId, playerLevel)) {
                pulseLockedSlot(slotId)
                return@launch
            }
            openSlot(slotId, slotName)
        }
    }

    private fun pulseLockedSlot(slotId: String) {
        val lockOverlay = lockOverlayForSlot(slotId) ?: return
        val lockPulse = lockedPulseForSlot(slotId)
        lockOverlay.visibility = View.VISIBLE
        lockOverlay.animate().cancel()
        lockOverlay.scaleX = 0.988f
        lockOverlay.scaleY = 0.988f
        lockOverlay.alpha = 0.92f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            lockOverlay.scaleX = 1f
            lockOverlay.scaleY = 1f
            lockOverlay.alpha = 1f
            bindLockPulse(lockPulse, unlocked = false)
            return
        }
        pulseLockedSlotImage(lockPulse)
        lockOverlay.animate()
            .scaleX(1.018f)
            .scaleY(1.018f)
            .alpha(1f)
            .setDuration(HOME_LOCKED_PULSE_UP_MS)
            .withEndAction {
                lockOverlay.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(HOME_LOCKED_PULSE_DOWN_MS)
                    .start()
            }
            .start()
    }

    private fun lockOverlayForSlot(slotId: String): ImageView? {
        return when (slotId) {
            SlotUnlockRules.NEON_NIGHTS -> binding.neonLockedOverlay
            SlotUnlockRules.PHARAOH_GOLD -> binding.pharaohLockedOverlay
            SlotUnlockRules.OCEAN_PEARL -> binding.oceanLockedOverlay
            else -> null
        }
    }

    private fun lockedPulseForSlot(slotId: String): ImageView? {
        return when (slotId) {
            SlotUnlockRules.NEON_NIGHTS -> binding.neonLockedPulse
            SlotUnlockRules.PHARAOH_GOLD -> binding.pharaohLockedPulse
            SlotUnlockRules.OCEAN_PEARL -> binding.oceanLockedPulse
            else -> null
        }
    }

    private fun unlockBurstForSlot(slotId: String): ImageView? {
        return when (slotId) {
            SlotUnlockRules.NEON_NIGHTS -> binding.neonUnlockBurst
            SlotUnlockRules.PHARAOH_GOLD -> binding.pharaohUnlockBurst
            SlotUnlockRules.OCEAN_PEARL -> binding.oceanUnlockBurst
            else -> null
        }
    }

    private fun cardForSlot(slotId: String): View? {
        return when (slotId) {
            SlotUnlockRules.NEON_NIGHTS -> binding.neonCard
            SlotUnlockRules.PHARAOH_GOLD -> binding.pharaohCard
            SlotUnlockRules.OCEAN_PEARL -> binding.oceanCard
            SlotUnlockRules.VIOLET_FORTUNE -> binding.violetCard
            SlotUnlockRules.ROMAN_REELS -> binding.romanCard
            else -> null
        }
    }

    private fun pulseLockedSlotImage(lockPulse: ImageView?) {
        if (lockPulse == null) return
        lockPulse.visibility = View.VISIBLE
        lockPulse.animate().cancel()
        lockPulse.alpha = HOME_LOCKED_PULSE_SETTLED_ALPHA
        lockPulse.scaleX = 0.985f
        lockPulse.scaleY = 0.985f
        lockPulse.animate()
            .alpha(HOME_LOCKED_PULSE_PEAK_ALPHA)
            .scaleX(1.028f)
            .scaleY(1.028f)
            .setDuration(HOME_LOCKED_IMAGE_PULSE_UP_MS)
            .withEndAction {
                lockPulse.animate()
                    .alpha(HOME_LOCKED_PULSE_SETTLED_ALPHA)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(HOME_LOCKED_IMAGE_PULSE_DOWN_MS)
                    .start()
            }
            .start()
    }

    private fun stopLockedSlotPulseAnimations() {
        if (_binding == null) return
        listOf(
            binding.neonLockedPulse,
            binding.pharaohLockedPulse,
            binding.oceanLockedPulse
        ).forEach { lockPulse ->
            lockPulse.animate().cancel()
            lockPulse.scaleX = 1f
            lockPulse.scaleY = 1f
            if (lockPulse.isVisible) {
                lockPulse.alpha = HOME_LOCKED_PULSE_SETTLED_ALPHA
            } else {
                lockPulse.alpha = 0f
            }
        }
    }

    private fun stopUnlockBurstAnimations() {
        if (_binding == null) return
        unlockBurstAnimators.values.toList().forEach { it.cancel() }
        unlockBurstAnimators.clear()
        listOf(
            binding.neonUnlockBurst,
            binding.pharaohUnlockBurst,
            binding.oceanUnlockBurst
        ).forEach(::resetUnlockBurstView)
        listOf(
            binding.neonCard,
            binding.pharaohCard,
            binding.oceanCard
        ).forEach { card ->
            card.scaleX = 1f
            card.scaleY = 1f
        }
    }

    private fun slotLockOverlayDrawable(requiredLevel: Int): Int {
        return when (requiredLevel) {
            2 -> R.drawable.slot_card_lock_level_2
            3 -> R.drawable.slot_card_lock_level_3
            else -> R.drawable.slot_card_lock_level_4
        }
    }

    private fun startHomeShineAnimations() {
        stopHomeShineAnimations()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        shineJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(HOME_SHINE_INITIAL_DELAY_MS)
            val binding = _binding ?: return@launch
            listOf(
                binding.violetCardShine,
                binding.romanCardShine,
                binding.neonCardShine,
                binding.pharaohCardShine,
                binding.oceanCardShine,
                binding.dailyBonusShine
            ).forEach { shineView ->
                animateShineOverlay(shineView)
                delay(HOME_SHINE_STAGGER_MS)
            }
        }
    }

    private fun startHomeAuraAnimations() {
        stopHomeAuraAnimations()
        homeAuraViews().forEach { (auraView, settledAlpha, _) ->
            auraView.visibility = View.VISIBLE
            auraView.alpha = settledAlpha
            auraView.scaleX = 1f
            auraView.scaleY = 1f
        }
        if (!ValueAnimator.areAnimatorsEnabled()) return

        auraJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(HOME_AURA_INITIAL_DELAY_MS)
            val binding = _binding ?: return@launch
            homeAuraViews(binding).forEach { (auraView, settledAlpha, peakAlpha) ->
                animateAuraOverlay(auraView, settledAlpha, peakAlpha)
                delay(HOME_AURA_STAGGER_MS)
            }
        }
    }

    private fun homeAuraViews(binding: FragmentHomeBinding = this.binding): List<Triple<View, Float, Float>> {
        return listOf(
            Triple(binding.violetCardAura, HOME_VIOLET_AURA_SETTLED_ALPHA, HOME_VIOLET_AURA_PEAK_ALPHA),
            Triple(binding.romanCardAura, HOME_ROMAN_AURA_SETTLED_ALPHA, HOME_ROMAN_AURA_PEAK_ALPHA),
            Triple(binding.neonCardAura, HOME_VIOLET_AURA_SETTLED_ALPHA, HOME_VIOLET_AURA_PEAK_ALPHA),
            Triple(binding.pharaohCardAura, HOME_ROMAN_AURA_SETTLED_ALPHA, HOME_ROMAN_AURA_PEAK_ALPHA),
            Triple(binding.oceanCardAura, HOME_VIOLET_AURA_SETTLED_ALPHA, HOME_VIOLET_AURA_PEAK_ALPHA)
        )
    }

    private suspend fun animateAuraOverlay(auraView: View, settledAlpha: Float, peakAlpha: Float) {
        auraView.visibility = View.VISIBLE
        auraView.alpha = settledAlpha
        auraView.scaleX = 1f
        auraView.scaleY = 1f
        val alphaAnimator = ObjectAnimator.ofFloat(auraView, View.ALPHA, settledAlpha, peakAlpha, settledAlpha).apply {
            duration = HOME_AURA_DURATION_MS
            start()
        }
        val scaleXAnimator = ObjectAnimator.ofFloat(auraView, View.SCALE_X, 1f, 1.012f, 1f).apply {
            duration = HOME_AURA_DURATION_MS
            start()
        }
        val scaleYAnimator = ObjectAnimator.ofFloat(auraView, View.SCALE_Y, 1f, 1.012f, 1f).apply {
            duration = HOME_AURA_DURATION_MS
            start()
        }
        auraAnimators += alphaAnimator
        auraAnimators += scaleXAnimator
        auraAnimators += scaleYAnimator
        delay(HOME_AURA_DURATION_MS)
        alphaAnimator.cancel()
        scaleXAnimator.cancel()
        scaleYAnimator.cancel()
        auraAnimators.remove(alphaAnimator)
        auraAnimators.remove(scaleXAnimator)
        auraAnimators.remove(scaleYAnimator)
        auraView.alpha = settledAlpha
        auraView.scaleX = 1f
        auraView.scaleY = 1f
    }

    private suspend fun animateShineOverlay(shineView: View) {
        shineView.visibility = View.VISIBLE
        shineView.alpha = 0f
        shineView.translationX = -22f
        val alphaAnimator = ObjectAnimator.ofFloat(shineView, View.ALPHA, 0f, 0.34f, 0f).apply {
            duration = HOME_SHINE_DURATION_MS
            start()
        }
        val motionAnimator = ObjectAnimator.ofFloat(shineView, View.TRANSLATION_X, -22f, 22f).apply {
            duration = HOME_SHINE_DURATION_MS
            start()
        }
        shineAnimators += alphaAnimator
        shineAnimators += motionAnimator
        delay(HOME_SHINE_DURATION_MS)
        alphaAnimator.cancel()
        motionAnimator.cancel()
        shineAnimators.remove(alphaAnimator)
        shineAnimators.remove(motionAnimator)
        shineView.alpha = 0f
        shineView.translationX = 0f
        shineView.visibility = View.INVISIBLE
    }

    private fun stopHomeShineAnimations() {
        shineJob?.cancel()
        shineJob = null
        shineAnimators.forEach { it.cancel() }
        shineAnimators.clear()
        if (_binding == null) return
        listOf(
            binding.violetCardShine,
            binding.romanCardShine,
            binding.neonCardShine,
            binding.pharaohCardShine,
            binding.oceanCardShine,
            binding.dailyBonusShine
        ).forEach { shineView ->
            shineView.alpha = 0f
            shineView.translationX = 0f
            shineView.visibility = View.INVISIBLE
        }
    }

    private fun stopHomeAuraAnimations() {
        auraJob?.cancel()
        auraJob = null
        auraAnimators.forEach { it.cancel() }
        auraAnimators.clear()
        if (_binding == null) return
        homeAuraViews().forEach { (auraView, _, _) ->
            auraView.alpha = 0f
            auraView.scaleX = 1f
            auraView.scaleY = 1f
            auraView.visibility = View.INVISIBLE
        }
    }

    override fun onDestroyView() {
        stopDailyBonusCountdown()
        stopLockedSlotPulseAnimations()
        stopUnlockBurstAnimations()
        stopHomeShineAnimations()
        stopHomeAuraAnimations()
        releaseHomeImageResources()
        lastObservedPlayerLevel = null
        _binding = null
        super.onDestroyView()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val HOME_XP_CONTENT_WIDTH_DP = 80f
        const val HOME_XP_MAX_GLYPH_BASE_WIDTH_DP = 15f
        const val HOME_XP_SLASH_WEIGHT = 0.92
        const val DAILY_BONUS_DIALOG_TAG = "daily_bonus"
        const val HOME_SHINE_DURATION_MS = 1_150L
        const val HOME_SHINE_INITIAL_DELAY_MS = 700L
        const val HOME_SHINE_STAGGER_MS = 520L
        const val XP_LABEL_MIN_WIDTH_DP = 400
        const val HOME_AURA_DURATION_MS = 1_450L
        const val HOME_AURA_INITIAL_DELAY_MS = 450L
        const val HOME_AURA_STAGGER_MS = 820L
        const val DAILY_BONUS_COUNTDOWN_VISIBLE_TICK_MS = 1_000L
        const val DAILY_BONUS_COUNTDOWN_BACKGROUND_TICK_MS = 15_000L
        const val DAILY_BONUS_COUNTDOWN_CHARGE_PHASES = 10
        const val DAILY_BONUS_COUNTDOWN_CHARGE_TRAVEL_DP = 1
        const val DAILY_BONUS_COUNTDOWN_CHARGE_LOW_ALPHA = 0.34f
        const val DAILY_BONUS_COUNTDOWN_CHARGE_ALPHA_RANGE = 0.24f
        const val DAILY_BONUS_COUNTDOWN_CHARGE_SETTLED_ALPHA = 0.45f
        const val HOME_LOCKED_PULSE_UP_MS = 110L
        const val HOME_LOCKED_PULSE_DOWN_MS = 130L
        const val HOME_LOCKED_IMAGE_PULSE_UP_MS = 160L
        const val HOME_LOCKED_IMAGE_PULSE_DOWN_MS = 260L
        const val HOME_LOCKED_PULSE_SETTLED_ALPHA = 0.18f
        const val HOME_LOCKED_PULSE_PEAK_ALPHA = 0.74f
        const val HOME_UNLOCK_BURST_DURATION_MS = 980L
        const val HOME_UNLOCK_BURST_STAGGER_MS = 160L
        const val HOME_UNLOCK_BURST_ENTER_TRAVEL_DP = 12
        const val HOME_UNLOCK_BURST_LIFT_DP = 8
        const val HOME_UNLOCK_BURST_PEAK_ALPHA = 0.96f
        const val HOME_UNLOCK_BURST_SETTLED_ALPHA = 0.58f
        const val HOME_VIOLET_AURA_SETTLED_ALPHA = 0.22f
        const val HOME_VIOLET_AURA_PEAK_ALPHA = 0.38f
        const val HOME_ROMAN_AURA_SETTLED_ALPHA = 0.2f
        const val HOME_ROMAN_AURA_PEAK_ALPHA = 0.34f
    }
}
