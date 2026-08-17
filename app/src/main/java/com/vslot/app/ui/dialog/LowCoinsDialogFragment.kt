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
import com.vslot.app.databinding.DialogLowCoinsBinding
import com.vslot.app.data.retryTransientPersistenceIo
import com.vslot.app.ui.DailyBonusCountdownFormatter
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LowCoinsDialogFragment : DialogFragment() {
    private var cooldownTimerJob: Job? = null
    private var lowCoinsPolishAnimator: AnimatorSet? = null
    private var cooldownAccessibilityBucket: Int? = null
    private var dialogUiActive = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        dialogUiActive = true
        val binding = DialogLowCoinsBinding.inflate(layoutInflater)
        bindScalableDialogCopy(binding.lowCoinsBody to binding.lowCoinsBodyLargeText)
        val args = arguments ?: Bundle.EMPTY
        val initialBonusAvailable = args.getBoolean(ARG_BONUS_AVAILABLE)
        val canReduceStake = args.getBoolean(ARG_CAN_REDUCE_STAKE)
        var bonusAvailable = initialBonusAvailable
        var claimInProgress = false

        fun renderState(available: Boolean) {
            claimInProgress = false
            bonusAvailable = available
            val bodyText = when {
                canReduceStake -> R.string.low_coins_reduce_body
                available -> R.string.low_coins_bonus_body
                else -> R.string.low_coins_wait_body
            }
            binding.lowCoinsBody.setImageResource(
                if (available) R.drawable.label_low_coins_bonus_body else R.drawable.label_low_coins_wait_body
            )
            binding.lowCoinsBody.contentDescription = getString(bodyText)
            binding.lowCoinsBodyLargeText.setText(bodyText)
            binding.actionButtonLabel.visibility = if (canReduceStake) View.GONE else View.VISIBLE
            binding.actionButtonText.visibility = if (canReduceStake) View.VISIBLE else View.GONE
            binding.actionButtonText.setText(R.string.low_coins_reduce_action)
            if (!canReduceStake) {
                binding.actionButtonLabel.setImageResource(
                    if (available) R.drawable.label_claim_bonus else R.drawable.label_ok_action
                )
            }
            binding.actionButton.setImageResource(
                if (available && !canReduceStake) {
                    R.drawable.btn_bonus_claim_selector
                } else {
                    R.drawable.btn_modal_close_selector
                }
            )
            binding.actionButton.contentDescription = getString(
                when {
                    canReduceStake -> R.string.low_coins_reduce_action
                    available -> R.string.claim_bonus
                    else -> R.string.ok_action
                }
            )
            binding.actionButton.isEnabled = true
            binding.actionButton.alpha = 1f
            binding.lowCoinsCooldownTimerRail.visibility = if (available || canReduceStake) {
                View.GONE
            } else {
                View.VISIBLE
            }
            binding.lowCoinsCooldownTimerDigits.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        renderState(initialBonusAvailable)
        if (!canReduceStake) {
            bindCooldownTimer(binding, initialBonusAvailable) {
                renderState(available = true)
                animateLowCoinsPolish(binding, bonusAvailable = true)
            }
        }
        binding.lowCoinsRescueGlow.visibility = View.VISIBLE
        binding.lowCoinsRescueGlow.alpha = if (bonusAvailable) {
            LOW_COINS_GLOW_SETTLED_ALPHA
        } else {
            LOW_COINS_WAIT_GLOW_ALPHA
        }
        binding.actionButton.setOnClickListener {
            if (canReduceStake) {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply { putBoolean(KEY_REDUCE_STAKE, true) }
                )
                dismiss()
                return@setOnClickListener
            }
            if (!bonusAvailable) {
                dismiss()
                return@setOnClickListener
            }
            if (claimInProgress) return@setOnClickListener
            claimInProgress = true
            binding.actionButton.isEnabled = false
            binding.actionButton.alpha = 0.72f
            lifecycleScope.launch {
                val result = try {
                    retryTransientPersistenceIo {
                        AppGraph.playerRepository.claimDailyBonus()
                    }
                } catch (_: IOException) {
                    if (dialogUiActive) renderState(available = true)
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
                dismiss()
            }
        }

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                animateLowCoinsPolish(binding, bonusAvailable)
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private fun bindCooldownTimer(
        binding: DialogLowCoinsBinding,
        bonusAvailable: Boolean,
        onReady: () -> Unit
    ) {
        cooldownTimerJob?.cancel()
        cooldownTimerJob = null
        cooldownAccessibilityBucket = null
        if (bonusAvailable) {
            binding.lowCoinsCooldownTimerRail.visibility = View.GONE
            return
        }
        binding.lowCoinsCooldownTimerRail.visibility = View.VISIBLE
        cooldownTimerJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val lastDailyBonusTimestamp = AppGraph.playerRepository.playerState.first().lastDailyBonusTimestamp
                if (updateCooldownTimer(binding, lastDailyBonusTimestamp)) {
                    cooldownTimerJob = null
                    onReady()
                    currentCoroutineContext().cancel()
                    return@repeatOnLifecycle
                }
                while (true) {
                    delay(LOW_COINS_COUNTDOWN_TICK_MS)
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

    private fun updateCooldownTimer(binding: DialogLowCoinsBinding, lastDailyBonusTimestamp: Long): Boolean {
        val cooldown = DailyBonusCountdownFormatter.format(lastDailyBonusTimestamp)
        if (cooldown.isReady) {
            cooldownAccessibilityBucket = null
            return true
        }
        bindCooldownTimerAccessibility(binding, cooldown.hours, cooldown.minutes, cooldown.seconds)
        binding.lowCoinsCooldownTimerDigits.setCharacters(
            cooldown.digits,
            spacingPx = 0,
            compactSeparators = true,
            fixedGlyphBaseWidthDp = 14.5f
        )
        return false
    }

    private fun bindCooldownTimerAccessibility(
        binding: DialogLowCoinsBinding,
        hours: Int,
        minutes: Int,
        seconds: Int
    ) {
        val accessibility = DailyBonusCountdownFormatter.accessibility(
            hours = hours,
            minutes = minutes,
            seconds = seconds
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
            binding.lowCoinsCooldownTimerRail.contentDescription == cooldownDescription
        ) {
            return
        }
        cooldownAccessibilityBucket = accessibility.bucket
        binding.lowCoinsCooldownTimerRail.contentDescription = cooldownDescription
    }

    private fun animateLowCoinsPolish(binding: DialogLowCoinsBinding, bonusAvailable: Boolean) {
        lowCoinsPolishAnimator?.cancel()
        lowCoinsPolishAnimator = null
        val glow = binding.lowCoinsRescueGlow
        glow.visibility = View.VISIBLE
        glow.alpha = if (bonusAvailable) LOW_COINS_GLOW_SETTLED_ALPHA else LOW_COINS_WAIT_GLOW_ALPHA
        glow.scaleX = 1f
        glow.scaleY = 1f
        binding.actionButton.scaleX = 1f
        binding.actionButton.scaleY = 1f
        if (!bonusAvailable || !ValueAnimator.areAnimatorsEnabled()) return

        glow.alpha = 0.06f
        glow.scaleX = 0.98f
        glow.scaleY = 0.98f
        lowCoinsPolishAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glow, View.ALPHA, 0.06f, LOW_COINS_GLOW_PEAK_ALPHA, LOW_COINS_GLOW_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.98f, 1.025f, 1f),
                ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.98f, 1.025f, 1f),
                ObjectAnimator.ofFloat(binding.actionButton, View.SCALE_X, 0.98f, 1.035f, 1f),
                ObjectAnimator.ofFloat(binding.actionButton, View.SCALE_Y, 0.98f, 1.035f, 1f)
            )
            duration = LOW_COINS_POLISH_DURATION_MS
            start()
        }
    }

    companion object {
        private const val ARG_BONUS_AVAILABLE = "bonusAvailable"
        private const val ARG_CAN_REDUCE_STAKE = "canReduceStake"
        const val REQUEST_KEY = "low_coins_request"
        const val KEY_REDUCE_STAKE = "reduce_stake"
        private const val LOW_COINS_POLISH_DURATION_MS = 720L
        private const val LOW_COINS_COUNTDOWN_TICK_MS = 1_000L
        private const val LOW_COINS_GLOW_SETTLED_ALPHA = 0.34f
        private const val LOW_COINS_GLOW_PEAK_ALPHA = 0.62f
        private const val LOW_COINS_WAIT_GLOW_ALPHA = 0.18f

        fun newInstance(
            bonusAvailable: Boolean,
            canReduceStake: Boolean = false
        ): LowCoinsDialogFragment {
            return LowCoinsDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_BONUS_AVAILABLE, bonusAvailable)
                    putBoolean(ARG_CAN_REDUCE_STAKE, canReduceStake)
                }
            }
        }
    }

    override fun onDestroyView() {
        dialogUiActive = false
        cooldownTimerJob?.cancel()
        cooldownTimerJob = null
        lowCoinsPolishAnimator?.cancel()
        lowCoinsPolishAnimator = null
        cooldownAccessibilityBucket = null
        super.onDestroyView()
    }
}
