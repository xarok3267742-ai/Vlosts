package com.vslot.app.ui.dialog

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vslot.app.AppGraph
import com.vslot.app.R
import com.vslot.app.analytics.AnalyticsEvents
import com.vslot.app.databinding.DialogBonusBinding
import com.vslot.app.data.retryTransientPersistenceIo
import com.vslot.app.ui.DailyBonusCountdownFormatter
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DailyBonusDialogFragment : DialogFragment() {
    private var cooldownTimerJob: Job? = null
    private var bonusStageAnimator: AnimatorSet? = null
    private var bonusRewardAnimator: AnimatorSet? = null
    private var bonusCooldownAnimator: AnimatorSet? = null
    private var cooldownAccessibilityBucket: Int? = null
    private var dialogUiActive = false
    private var renderedClaimEnabled: Boolean? = null
    private var renderedLastDailyBonusTimestamp: Long? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        dialogUiActive = true
        val binding = DialogBonusBinding.inflate(layoutInflater)
        bindScalableDialogCopy(binding.bonusBody to binding.bonusBodyLargeText)
        val args = arguments ?: Bundle.EMPTY
        var claimEnabled = savedInstanceState.readClaimEnabledOrNull()
            ?: args.getBoolean(ARG_CLAIM_ENABLED)
        var activeLastDailyBonusTimestamp = savedInstanceState.readLastDailyBonusTimestampOrNull()
            ?: args.getLong(ARG_LAST_DAILY_BONUS_TIMESTAMP)
        var claimInProgress = false
        var playerStateObserved = false

        fun renderClaimState(enabled: Boolean) {
            bonusRewardAnimator?.cancel()
            bonusRewardAnimator = null
            bonusCooldownAnimator?.cancel()
            bonusCooldownAnimator = null
            claimInProgress = false
            claimEnabled = enabled
            renderedClaimEnabled = enabled
            renderedLastDailyBonusTimestamp = activeLastDailyBonusTimestamp
            val bonusBody = if (enabled) {
                getString(R.string.bonus_ready)
            } else {
                getString(R.string.bonus_wait)
            }
            binding.bonusBody.setImageResource(
                if (enabled) R.drawable.label_bonus_ready_body else R.drawable.label_bonus_wait_body
            )
            binding.bonusBody.contentDescription = bonusBody
            binding.bonusBodyLargeText.text = bonusBody
            bindScalableDialogCopy(binding.bonusBody to binding.bonusBodyLargeText)
            binding.claimButton.isEnabled = true
            binding.claimButton.alpha = if (enabled) 1f else 0.72f
            val claimLabel = if (enabled) getString(R.string.claim_bonus) else getString(R.string.ok_action)
            binding.claimButtonLabel.setImageResource(
                if (enabled) R.drawable.label_claim_bonus else R.drawable.label_ok_action
            )
            binding.claimButton.setImageResource(
                if (enabled) R.drawable.btn_bonus_claim_selector else R.drawable.btn_modal_close_selector
            )
            binding.claimButton.contentDescription = claimLabel
            binding.bonusRewardOverlay.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
            binding.bonusRewardOverlay.alpha = 0f
            binding.bonusCooldownOverlay.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
            binding.bonusCooldownOverlay.alpha = 0f
            bindCooldownTimer(binding, enabled, activeLastDailyBonusTimestamp) {
                renderClaimState(enabled = true)
                animateBonusRewardPolish(binding)
            }
        }

        renderClaimState(claimEnabled)
        binding.bonusStageLattice.alpha = BONUS_STAGE_SETTLED_ALPHA
        binding.bonusStageLattice.scaleX = 1f
        binding.bonusStageLattice.scaleY = 1f
        binding.bonusCloseButton.setOnClickListener { dismiss() }
        binding.claimButton.setOnClickListener {
            if (!claimEnabled) {
                dismiss()
                return@setOnClickListener
            }
            if (claimInProgress) return@setOnClickListener
            claimInProgress = true
            binding.claimButton.isEnabled = false
            binding.claimButton.alpha = 0.72f
            lifecycleScope.launch {
                val result = try {
                    retryTransientPersistenceIo {
                        AppGraph.playerRepository.claimDailyBonus()
                    }
                } catch (_: IOException) {
                    if (dialogUiActive) {
                        renderClaimState(enabled = true)
                        val message = getString(R.string.persistence_save_error_retry)
                        binding.bonusBody.visibility = View.GONE
                        binding.bonusBody.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        binding.bonusBodyLargeText.visibility = View.VISIBLE
                        binding.bonusBodyLargeText.importantForAccessibility =
                            View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        binding.bonusBodyLargeText.accessibilityLiveRegion =
                            View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
                        binding.bonusBodyLargeText.text = message
                    }
                    return@launch
                }
                if (result.claimed) {
                    AppGraph.analyticsTracker.track(
                        AnalyticsEvents.BonusClaim,
                        mapOf(
                            "amount" to result.amount,
                            "balance_after" to result.balanceAfter
                        )
                    )
                }
                if (!dialogUiActive) return@launch
                activeLastDailyBonusTimestamp = AppGraph.playerRepository.playerState
                    .first()
                    .lastDailyBonusTimestamp
                if (!dialogUiActive) return@launch
                renderClaimState(enabled = false)
                animateBonusCooldownPolish(binding)
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.playerRepository.playerState.collect { state ->
                    if (!dialogUiActive) return@collect
                    val latestClaimEnabled = state.isDailyBonusAvailable()
                    if (claimInProgress && latestClaimEnabled) return@collect
                    val stateChanged =
                        !playerStateObserved ||
                            claimEnabled != latestClaimEnabled ||
                            activeLastDailyBonusTimestamp != state.lastDailyBonusTimestamp
                    playerStateObserved = true
                    activeLastDailyBonusTimestamp = state.lastDailyBonusTimestamp
                    if (!stateChanged) return@collect
                    renderClaimState(enabled = latestClaimEnabled)
                    if (latestClaimEnabled) {
                        animateBonusRewardPolish(binding)
                    } else {
                        animateBonusCooldownPolish(binding)
                    }
                }
            }
        }

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                animateBonusStage(binding)
                if (claimEnabled) {
                    animateBonusRewardPolish(binding)
                } else {
                    animateBonusCooldownPolish(binding)
                }
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private fun animateBonusStage(binding: DialogBonusBinding) {
        bonusStageAnimator?.cancel()
        bonusStageAnimator = null
        binding.bonusStageLattice.alpha = BONUS_STAGE_SETTLED_ALPHA
        binding.bonusStageLattice.scaleX = 1f
        binding.bonusStageLattice.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        binding.bonusStageLattice.alpha = 0.66f
        binding.bonusStageLattice.scaleX = 0.985f
        binding.bonusStageLattice.scaleY = 0.985f

        bonusStageAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    binding.bonusStageLattice,
                    View.ALPHA,
                    0.66f,
                    0.92f,
                    BONUS_STAGE_SETTLED_ALPHA
                ),
                ObjectAnimator.ofFloat(binding.bonusStageLattice, View.SCALE_X, 0.985f, 1.012f, 1f),
                ObjectAnimator.ofFloat(binding.bonusStageLattice, View.SCALE_Y, 0.985f, 1.012f, 1f)
            )
            duration = BONUS_STAGE_POLISH_DURATION_MS
            start()
        }
    }

    private fun animateBonusRewardPolish(binding: DialogBonusBinding) {
        bonusRewardAnimator?.cancel()
        bonusRewardAnimator = null
        bonusCooldownAnimator?.cancel()
        bonusCooldownAnimator = null
        val overlay = binding.bonusRewardOverlay
        overlay.visibility = View.VISIBLE
        overlay.alpha = BONUS_REWARD_SETTLED_ALPHA
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        overlay.alpha = 0.08f
        overlay.scaleX = 0.94f
        overlay.scaleY = 0.94f

        bonusRewardAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(overlay, View.ALPHA, 0.08f, 0.5f, BONUS_REWARD_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.94f, 1.06f, 1f),
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.94f, 1.06f, 1f)
            )
            duration = BONUS_REWARD_POLISH_DURATION_MS
            start()
        }
    }

    private fun animateBonusCooldownPolish(binding: DialogBonusBinding) {
        bonusCooldownAnimator?.cancel()
        bonusCooldownAnimator = null
        bonusRewardAnimator?.cancel()
        bonusRewardAnimator = null
        val overlay = binding.bonusCooldownOverlay
        overlay.visibility = View.VISIBLE
        overlay.alpha = BONUS_COOLDOWN_SETTLED_ALPHA
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        binding.bonusBadge.scaleX = 1f
        binding.bonusBadge.scaleY = 1f
        binding.claimButton.scaleX = 1f
        binding.claimButton.scaleY = 1f
        binding.bonusCooldownTimerRail.alpha = 1f
        binding.bonusCooldownTimerRail.scaleX = 1f
        binding.bonusCooldownTimerRail.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        overlay.alpha = 0.08f
        overlay.scaleX = 0.985f
        overlay.scaleY = 0.985f
        binding.bonusBadge.scaleX = 0.965f
        binding.bonusBadge.scaleY = 0.965f
        binding.claimButton.scaleX = 0.985f
        binding.claimButton.scaleY = 0.985f
        binding.bonusCooldownTimerRail.alpha = 0.18f
        binding.bonusCooldownTimerRail.scaleX = 0.965f
        binding.bonusCooldownTimerRail.scaleY = 0.965f

        bonusCooldownAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    overlay,
                    View.ALPHA,
                    0.08f,
                    BONUS_COOLDOWN_PEAK_ALPHA,
                    BONUS_COOLDOWN_SETTLED_ALPHA
                ),
                ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.985f, 1.012f, 1f),
                ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.985f, 1.012f, 1f),
                ObjectAnimator.ofFloat(binding.bonusBadge, View.SCALE_X, 0.965f, 1.035f, 1f),
                ObjectAnimator.ofFloat(binding.bonusBadge, View.SCALE_Y, 0.965f, 1.035f, 1f),
                ObjectAnimator.ofFloat(binding.claimButton, View.SCALE_X, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(binding.claimButton, View.SCALE_Y, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(binding.bonusCooldownTimerRail, View.ALPHA, 0.18f, 1f),
                ObjectAnimator.ofFloat(binding.bonusCooldownTimerRail, View.SCALE_X, 0.965f, 1.035f, 1f),
                ObjectAnimator.ofFloat(binding.bonusCooldownTimerRail, View.SCALE_Y, 0.965f, 1.035f, 1f)
            )
            duration = BONUS_COOLDOWN_POLISH_DURATION_MS
            start()
        }
    }

    private fun bindCooldownTimer(
        binding: DialogBonusBinding,
        claimEnabled: Boolean,
        lastDailyBonusTimestamp: Long,
        onReady: () -> Unit
    ) {
        cooldownTimerJob?.cancel()
        cooldownTimerJob = null
        cooldownAccessibilityBucket = null
        if (claimEnabled) {
            binding.bonusCooldownTimerRail.visibility = View.GONE
            return
        }
        binding.bonusCooldownTimerRail.visibility = View.VISIBLE
        binding.bonusCooldownTimerDigits.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (updateCooldownTimer(binding, lastDailyBonusTimestamp)) {
            onReady()
            return
        }
        cooldownTimerJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(BONUS_COUNTDOWN_TICK_MS)
                    if (updateCooldownTimer(binding, lastDailyBonusTimestamp)) {
                        cooldownTimerJob = null
                        onReady()
                        currentCoroutineContext().cancel()
                        return@repeatOnLifecycle
                    }
                }
            }
        }
    }

    private fun updateCooldownTimer(binding: DialogBonusBinding, lastDailyBonusTimestamp: Long): Boolean {
        val cooldown = dailyBonusCooldown(lastDailyBonusTimestamp)
        if (cooldown.isReady) {
            cooldownAccessibilityBucket = null
            return true
        }
        bindCooldownTimerAccessibility(binding, cooldown)
        binding.bonusCooldownTimerDigits.setCharacters(
            cooldown.digits,
            spacingPx = 0,
            compactSeparators = true,
            fixedGlyphBaseWidthDp = 13.2f
        )
        return false
    }

    private fun bindCooldownTimerAccessibility(binding: DialogBonusBinding, cooldown: CooldownDisplay) {
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
            cooldownAccessibilityBucket == accessibility.bucket &&
            binding.bonusCooldownTimerRail.contentDescription == cooldownDescription
        ) {
            return
        }
        cooldownAccessibilityBucket = accessibility.bucket
        binding.bonusCooldownTimerRail.contentDescription = cooldownDescription
    }

    private fun dailyBonusCooldown(lastDailyBonusTimestamp: Long): CooldownDisplay {
        val countdown = DailyBonusCountdownFormatter.format(lastDailyBonusTimestamp)
        return CooldownDisplay(
            hours = countdown.hours,
            minutes = countdown.minutes,
            seconds = countdown.seconds,
            isReady = countdown.isReady,
            digits = countdown.digits
        )
    }

    private data class CooldownDisplay(
        val hours: Int,
        val minutes: Int,
        val seconds: Int,
        val isReady: Boolean,
        val digits: String
    )

    companion object {
        private const val ARG_CLAIM_ENABLED = "claimEnabled"
        private const val ARG_LAST_DAILY_BONUS_TIMESTAMP = "lastDailyBonusTimestamp"
        private const val STATE_CLAIM_ENABLED = "stateClaimEnabled"
        private const val STATE_LAST_DAILY_BONUS_TIMESTAMP = "stateLastDailyBonusTimestamp"
        private const val BONUS_COUNTDOWN_TICK_MS = 1_000L
        private const val BONUS_STAGE_POLISH_DURATION_MS = 720L
        private const val BONUS_STAGE_SETTLED_ALPHA = 1f
        private const val BONUS_REWARD_POLISH_DURATION_MS = 1_250L
        private const val BONUS_REWARD_SETTLED_ALPHA = 0.24f
        private const val BONUS_COOLDOWN_POLISH_DURATION_MS = 1_000L
        private const val BONUS_COOLDOWN_SETTLED_ALPHA = 0.52f
        private const val BONUS_COOLDOWN_PEAK_ALPHA = 0.72f

        fun newInstance(
            claimEnabled: Boolean,
            lastDailyBonusTimestamp: Long = 0L
        ): DailyBonusDialogFragment {
            return DailyBonusDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_CLAIM_ENABLED, claimEnabled)
                    putLong(ARG_LAST_DAILY_BONUS_TIMESTAMP, lastDailyBonusTimestamp)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        renderedClaimEnabled?.let { outState.putBoolean(STATE_CLAIM_ENABLED, it) }
        renderedLastDailyBonusTimestamp?.let {
            outState.putLong(STATE_LAST_DAILY_BONUS_TIMESTAMP, it)
        }
        super.onSaveInstanceState(outState)
    }

    private fun Bundle?.readClaimEnabledOrNull(): Boolean? {
        return this?.takeIf { it.containsKey(STATE_CLAIM_ENABLED) }
            ?.getBoolean(STATE_CLAIM_ENABLED)
    }

    private fun Bundle?.readLastDailyBonusTimestampOrNull(): Long? {
        return this?.takeIf { it.containsKey(STATE_LAST_DAILY_BONUS_TIMESTAMP) }
            ?.getLong(STATE_LAST_DAILY_BONUS_TIMESTAMP)
    }

    override fun onDestroyView() {
        dialogUiActive = false
        cooldownTimerJob?.cancel()
        cooldownTimerJob = null
        bonusStageAnimator?.cancel()
        bonusStageAnimator = null
        bonusRewardAnimator?.cancel()
        bonusRewardAnimator = null
        bonusCooldownAnimator?.cancel()
        bonusCooldownAnimator = null
        cooldownAccessibilityBucket = null
        super.onDestroyView()
    }
}
