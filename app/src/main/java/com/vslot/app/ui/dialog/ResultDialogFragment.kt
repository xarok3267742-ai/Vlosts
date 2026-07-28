package com.vslot.app.ui.dialog

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.vslot.app.R
import com.vslot.app.BuildConfig
import com.vslot.app.databinding.DialogResultBinding
import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotTheme
import com.vslot.app.ui.asCoins
import com.vslot.app.ui.widget.setImageResourceIfChanged

class ResultDialogFragment : DialogFragment() {
    private var resultStageAnimator: AnimatorSet? = null
    private var rewardPolishAnimator: AnimatorSet? = null
    private var presentationDrawObserver: ViewTreeObserver? = null
    private var presentationDrawListener: ViewTreeObserver.OnDrawListener? = null
    private val dismissGate = ResultDialogDismissGate()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogResultBinding.inflate(layoutInflater)
        val args = arguments ?: Bundle.EMPTY
        val winAmount = args.getInt(ARG_WIN_AMOUNT)
        val freeSpinsAwarded = args.getInt(ARG_FREE_SPINS_AWARDED)
        val slotTheme = args
            .getString(ARG_SLOT_THEME)
            ?.let { runCatching { SlotTheme.valueOf(it) }.getOrNull() }
            ?: SlotTheme.Violet
        val resultType = args
            .getString(ARG_RESULT_TYPE)
            ?.let { runCatching { ResultType.valueOf(it) }.getOrNull() }
            ?: if (winAmount > 0) ResultType.Win else ResultType.Lose
        val isWin = resultType != ResultType.Lose
        binding.resultModalPanel.setImageResourceIfChanged(resultModalPanelDrawable(slotTheme))
        binding.resultStageLattice.setImageResourceIfChanged(resultStageLatticeDrawable(slotTheme))
        binding.resultThemeWinBurst.setImageResourceIfChanged(themeWinBurstDrawable(slotTheme))
        binding.resultRewardOverlay.setImageResourceIfChanged(resultRewardOverlayDrawable(slotTheme))
        binding.resultFreeSpinsAwardPanel.setImageResourceIfChanged(resultFreeSpinsAwardPanelDrawable(slotTheme))
        val titleImage = when (resultType) {
            ResultType.Bonus -> R.drawable.title_bonus
            ResultType.Win -> R.drawable.title_win
            ResultType.Lose -> R.drawable.title_lose
        }
        val titleText = when (resultType) {
            ResultType.Bonus -> R.string.result_bonus_title
            ResultType.Win -> R.string.win_title
            ResultType.Lose -> R.string.lose_title
        }
        binding.resultTitle.setImageResourceIfChanged(titleImage)
        binding.resultTitle.contentDescription = getString(titleText)
        ViewCompat.setAccessibilityPaneTitle(binding.root, getString(titleText))
        val bodyImage = when (resultType) {
            ResultType.Bonus -> R.drawable.label_result_bonus_body
            ResultType.Win -> R.drawable.label_result_win_body
            ResultType.Lose -> R.drawable.label_result_lose_body
        }
        val bodyText = when (resultType) {
            ResultType.Bonus -> R.string.result_bonus_body
            ResultType.Win -> R.string.result_win_body
            ResultType.Lose -> R.string.result_lose_body
        }
        binding.resultBody.setImageResourceIfChanged(bodyImage)
        binding.resultBody.contentDescription = getString(bodyText)
        binding.resultBodyLargeText.setText(bodyText)
        bindScalableDialogCopy(binding.resultBody to binding.resultBodyLargeText)
        binding.resultGlow.setImageResourceIfChanged(resultType.badgeImage())
        binding.resultGlow.alpha = 1f
        binding.resultStageLattice.alpha = RESULT_STAGE_SETTLED_ALPHA
        binding.resultStageLattice.scaleX = 1f
        binding.resultStageLattice.scaleY = 1f
        binding.resultThemeWinBurst.visibility = if (isWin) View.VISIBLE else View.INVISIBLE
        binding.resultThemeWinBurst.alpha = 0f
        binding.resultThemeWinBurst.scaleX = 1f
        binding.resultThemeWinBurst.scaleY = 1f
        binding.resultRewardOverlay.visibility = if (isWin) View.VISIBLE else View.INVISIBLE
        binding.resultRewardOverlay.alpha = 0f
        binding.resultRewardSparkle.visibility = if (isWin) View.VISIBLE else View.INVISIBLE
        binding.resultRewardSparkle.alpha = 0f
        binding.resultRewardSparkle.scaleX = 1f
        binding.resultRewardSparkle.scaleY = 1f
        val hasFreeSpinsAward = freeSpinsAwarded > 0
        binding.resultFreeSpinsAwardGroup.visibility = if (hasFreeSpinsAward) View.VISIBLE else View.GONE
        binding.resultFreeSpinsAwardGroup.alpha = if (hasFreeSpinsAward) BONUS_AWARD_SETTLED_ALPHA else 0f
        binding.resultFreeSpinsAwardGroup.contentDescription = if (hasFreeSpinsAward) {
            getString(R.string.result_free_spins_award, freeSpinsAwarded)
        } else {
            null
        }
        binding.resultFreeSpinsAwardDigits.setNumber(freeSpinsAwarded, showPlus = true)
        binding.winAmountGroup.visibility = if (isWin) android.view.View.VISIBLE else android.view.View.GONE
        binding.winAmountDigits.layoutParams = binding.winAmountDigits.layoutParams.apply {
            width = winAmount.bitmapAmountWidthPx()
        }
        binding.winAmountDigits.setNumber(winAmount, showPlus = true)
        binding.winAmountDigits.contentDescription = getString(
            R.string.result_win_amount_accessibility,
            winAmount.asCoins()
        )
        binding.closeButton.setOnClickListener { dismiss() }

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                applyGameDialogDim(RESULT_DIALOG_DIM_AMOUNT)
                animateResultStage(binding)
                animateRewardPolish(binding, resultType, freeSpinsAwarded)
                notifyPresentationAfterFirstDraw(binding.root, args.getString(ARG_PRESENTATION_ID).orEmpty())
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    override fun dismiss() {
        dismissGate.requestDismiss()
        super.dismiss()
    }

    override fun dismissNow() {
        dismissGate.requestDismiss()
        super.dismissNow()
    }

    override fun dismissAllowingStateLoss() {
        dismissGate.requestDismiss()
        super.dismissAllowingStateLoss()
    }

    override fun onCancel(dialog: DialogInterface) {
        dismissGate.requestDismiss()
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (dismissGate.consumeDismissResult()) {
            setFragmentResult(
                REQUEST_KEY,
                Bundle().apply {
                    putBoolean(KEY_DISMISSED, true)
                    putString(KEY_PRESENTATION_ID, arguments?.getString(ARG_PRESENTATION_ID).orEmpty())
                }
            )
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        clearPresentationDrawListener()
        resultStageAnimator?.cancel()
        resultStageAnimator = null
        rewardPolishAnimator?.cancel()
        rewardPolishAnimator = null
        super.onDestroyView()
    }

    private fun notifyPresentationAfterFirstDraw(root: View, presentationId: String) {
        if (presentationId.isBlank() || presentationDrawListener != null) return
        val observer = root.viewTreeObserver
        var drawObserved = false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (!drawObserved) {
                drawObserved = true
                root.post {
                    if (observer.isAlive) observer.removeOnDrawListener(listener)
                    presentationDrawObserver = null
                    presentationDrawListener = null
                    if (!isAdded) return@post
                    if (BuildConfig.QA_ENABLED) {
                        Log.i(QA_PRESENTATION_TAG, QA_MODAL_FIRST_DRAW)
                    }
                    setFragmentResult(
                        PRESENTED_REQUEST_KEY,
                        Bundle().apply { putString(KEY_PRESENTATION_ID, presentationId) }
                    )
                }
            }
        }
        presentationDrawObserver = observer
        presentationDrawListener = listener
        observer.addOnDrawListener(listener)
        root.invalidate()
    }

    private fun clearPresentationDrawListener() {
        val observer = presentationDrawObserver
        val listener = presentationDrawListener
        if (observer?.isAlive == true && listener != null) {
            observer.removeOnDrawListener(listener)
        }
        presentationDrawObserver = null
        presentationDrawListener = null
    }

    private fun animateResultStage(binding: DialogResultBinding) {
        resultStageAnimator?.cancel()
        resultStageAnimator = null
        binding.resultStageLattice.alpha = RESULT_STAGE_SETTLED_ALPHA
        binding.resultStageLattice.scaleX = 1f
        binding.resultStageLattice.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        binding.resultStageLattice.alpha = 0.62f
        binding.resultStageLattice.scaleX = 0.985f
        binding.resultStageLattice.scaleY = 0.985f

        val stageAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    binding.resultStageLattice,
                    View.ALPHA,
                    0.62f,
                    0.92f,
                    RESULT_STAGE_SETTLED_ALPHA
                ).apply {
                    duration = RESULT_STAGE_POLISH_DURATION_MS
                },
                ObjectAnimator.ofFloat(binding.resultStageLattice, View.SCALE_X, 0.985f, 1.012f, 1f).apply {
                    duration = RESULT_STAGE_POLISH_DURATION_MS
                },
                ObjectAnimator.ofFloat(binding.resultStageLattice, View.SCALE_Y, 0.985f, 1.012f, 1f).apply {
                    duration = RESULT_STAGE_POLISH_DURATION_MS
                }
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (resultStageAnimator === animation) resultStageAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (resultStageAnimator === animation) resultStageAnimator = null
                }
            })
        }
        resultStageAnimator = stageAnimator
        stageAnimator.start()
    }

    private fun animateRewardPolish(
        binding: DialogResultBinding,
        resultType: ResultType,
        freeSpinsAwarded: Int
    ) {
        rewardPolishAnimator?.cancel()
        rewardPolishAnimator = null
        if (resultType == ResultType.Lose) return
        val overlay = binding.resultRewardOverlay
        val themeBurst = binding.resultThemeWinBurst
        val sparkle = binding.resultRewardSparkle
        themeBurst.visibility = View.VISIBLE
        themeBurst.alpha = RESULT_THEME_BURST_SETTLED_ALPHA
        themeBurst.scaleX = 1f
        themeBurst.scaleY = 1f
        overlay.visibility = View.VISIBLE
        overlay.alpha = REWARD_SETTLED_ALPHA
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        sparkle.visibility = View.VISIBLE
        sparkle.alpha = RESULT_SPARKLE_SETTLED_ALPHA
        sparkle.scaleX = 1f
        sparkle.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        themeBurst.alpha = 0.18f
        themeBurst.scaleX = 0.9f
        themeBurst.scaleY = 0.9f
        overlay.alpha = 0.08f
        overlay.scaleX = 0.94f
        overlay.scaleY = 0.94f
        sparkle.alpha = 0f
        sparkle.scaleX = 0.94f
        sparkle.scaleY = 0.94f

        val animators = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(
                themeBurst,
                View.ALPHA,
                0.18f,
                0.64f,
                RESULT_THEME_BURST_SETTLED_ALPHA
            ).apply {
                duration = RESULT_THEME_BURST_POLISH_DURATION_MS
            },
            ObjectAnimator.ofFloat(themeBurst, View.SCALE_X, 0.9f, 1.08f, 1f).apply {
                duration = RESULT_THEME_BURST_POLISH_DURATION_MS
            },
            ObjectAnimator.ofFloat(themeBurst, View.SCALE_Y, 0.9f, 1.08f, 1f).apply {
                duration = RESULT_THEME_BURST_POLISH_DURATION_MS
            },
            ObjectAnimator.ofFloat(overlay, View.ALPHA, 0.08f, 0.52f, REWARD_SETTLED_ALPHA).apply {
                duration = REWARD_POLISH_DURATION_MS
            },
            ObjectAnimator.ofFloat(overlay, View.SCALE_X, 0.94f, 1.06f, 1f).apply {
                duration = REWARD_POLISH_DURATION_MS
            },
            ObjectAnimator.ofFloat(overlay, View.SCALE_Y, 0.94f, 1.06f, 1f).apply {
                duration = REWARD_POLISH_DURATION_MS
            },
            ObjectAnimator.ofFloat(sparkle, View.ALPHA, 0f, RESULT_SPARKLE_PEAK_ALPHA, RESULT_SPARKLE_SETTLED_ALPHA).apply {
                duration = RESULT_SPARKLE_POLISH_DURATION_MS
                startDelay = RESULT_SPARKLE_POLISH_DELAY_MS
            },
            ObjectAnimator.ofFloat(sparkle, View.SCALE_X, 0.94f, 1.055f, 1f).apply {
                duration = RESULT_SPARKLE_POLISH_DURATION_MS
                startDelay = RESULT_SPARKLE_POLISH_DELAY_MS
            },
            ObjectAnimator.ofFloat(sparkle, View.SCALE_Y, 0.94f, 1.055f, 1f).apply {
                duration = RESULT_SPARKLE_POLISH_DURATION_MS
                startDelay = RESULT_SPARKLE_POLISH_DELAY_MS
            },
            ObjectAnimator.ofFloat(binding.resultGlow, View.SCALE_X, 0.9f, 1.08f, 1f).apply {
                duration = BADGE_POLISH_DURATION_MS
            },
            ObjectAnimator.ofFloat(binding.resultGlow, View.SCALE_Y, 0.9f, 1.08f, 1f).apply {
                duration = BADGE_POLISH_DURATION_MS
            }
        )
        if (freeSpinsAwarded > 0) {
            binding.resultFreeSpinsAwardGroup.alpha = 0f
            binding.resultFreeSpinsAwardGroup.scaleX = 0.88f
            binding.resultFreeSpinsAwardGroup.scaleY = 0.88f
            binding.resultFreeSpinsAwardDigits.scaleX = 1f
            binding.resultFreeSpinsAwardDigits.scaleY = 1f
            animators += listOf(
                ObjectAnimator.ofFloat(
                    binding.resultFreeSpinsAwardGroup,
                    View.ALPHA,
                    0f,
                    1f,
                    BONUS_AWARD_SETTLED_ALPHA
                ).apply {
                    duration = BONUS_AWARD_POLISH_DURATION_MS
                    startDelay = BONUS_AWARD_POLISH_DELAY_MS
                },
                ObjectAnimator.ofFloat(binding.resultFreeSpinsAwardGroup, View.SCALE_X, 0.88f, 1.045f, 1f).apply {
                    duration = BONUS_AWARD_POLISH_DURATION_MS
                    startDelay = BONUS_AWARD_POLISH_DELAY_MS
                },
                ObjectAnimator.ofFloat(binding.resultFreeSpinsAwardGroup, View.SCALE_Y, 0.88f, 1.045f, 1f).apply {
                    duration = BONUS_AWARD_POLISH_DURATION_MS
                    startDelay = BONUS_AWARD_POLISH_DELAY_MS
                },
                ObjectAnimator.ofFloat(binding.resultFreeSpinsAwardDigits, View.SCALE_X, 1f, 1.16f, 1f).apply {
                    duration = BONUS_AWARD_DIGITS_POP_DURATION_MS
                    startDelay = BONUS_AWARD_DIGITS_POP_DELAY_MS
                },
                ObjectAnimator.ofFloat(binding.resultFreeSpinsAwardDigits, View.SCALE_Y, 1f, 1.16f, 1f).apply {
                    duration = BONUS_AWARD_DIGITS_POP_DURATION_MS
                    startDelay = BONUS_AWARD_DIGITS_POP_DELAY_MS
                }
            )
        }
        val polishAnimator = AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (rewardPolishAnimator === animation) rewardPolishAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (rewardPolishAnimator === animation) rewardPolishAnimator = null
                }
            })
        }
        rewardPolishAnimator = polishAnimator
        polishAnimator.start()
    }

    private fun ResultType.badgeImage(): Int {
        return when (this) {
            ResultType.Bonus -> R.drawable.modal_badge_bonus
            ResultType.Win -> R.drawable.modal_badge_win
            ResultType.Lose -> R.drawable.modal_badge_loss
        }
    }

    private fun resultModalPanelDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Roman -> R.drawable.result_modal_panel_roman_premium
            SlotTheme.Neon -> R.drawable.result_modal_panel_neon_premium
            SlotTheme.Pharaoh -> R.drawable.result_modal_panel_pharaoh_premium
            SlotTheme.Ocean -> R.drawable.result_modal_panel_ocean_premium
            SlotTheme.Violet -> R.drawable.result_modal_panel_violet_premium
        }
    }

    private fun resultStageLatticeDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Roman -> R.drawable.result_stage_lattice_roman
            SlotTheme.Neon -> R.drawable.result_stage_lattice_neon
            SlotTheme.Pharaoh -> R.drawable.result_stage_lattice_pharaoh
            SlotTheme.Ocean -> R.drawable.result_stage_lattice_ocean
            SlotTheme.Violet -> R.drawable.result_stage_lattice
        }
    }

    private fun resultRewardOverlayDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Roman -> R.drawable.result_win_payout_burst_roman
            SlotTheme.Neon -> R.drawable.result_win_payout_burst_neon
            SlotTheme.Pharaoh -> R.drawable.result_win_payout_burst_pharaoh
            SlotTheme.Ocean -> R.drawable.result_win_payout_burst_ocean
            SlotTheme.Violet -> R.drawable.result_win_payout_burst
        }
    }

    private fun themeWinBurstDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Roman -> R.drawable.theme_win_burst_roman
            SlotTheme.Neon -> R.drawable.theme_win_burst_neon
            SlotTheme.Pharaoh -> R.drawable.theme_win_burst_pharaoh
            SlotTheme.Ocean -> R.drawable.theme_win_burst_ocean
            SlotTheme.Violet -> R.drawable.theme_win_burst_violet
        }
    }

    private fun resultFreeSpinsAwardPanelDrawable(theme: SlotTheme): Int {
        return when (theme) {
            SlotTheme.Roman -> R.drawable.result_free_spins_award_panel_roman
            SlotTheme.Neon -> R.drawable.result_free_spins_award_panel_neon
            SlotTheme.Pharaoh -> R.drawable.result_free_spins_award_panel_pharaoh
            SlotTheme.Ocean -> R.drawable.result_free_spins_award_panel_ocean
            SlotTheme.Violet -> R.drawable.result_free_spins_award_panel
        }
    }

    private fun Int.bitmapAmountWidthPx(): Int {
        val value = if (this > 0) "+${asCoins()}" else asCoins()
        val widthDp = value.sumOf { character ->
            when (character) {
                ' ' -> 14
                '+', '-' -> 32
                else -> 38
            }.toInt()
        }.coerceIn(104, 236)
        return (widthDp * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val QA_PRESENTATION_TAG = "VSlotPresentation"
        private const val QA_MODAL_FIRST_DRAW = "modal_first_draw"
        private const val ARG_RESULT_TYPE = "resultType"
        private const val ARG_WIN_AMOUNT = "winAmount"
        private const val ARG_FREE_SPINS_AWARDED = "freeSpinsAwarded"
        private const val ARG_SLOT_THEME = "slotTheme"
        private const val ARG_PRESENTATION_ID = "presentationId"
        private const val REWARD_POLISH_DURATION_MS = 1_350L
        private const val REWARD_SETTLED_ALPHA = 0.26f
        private const val RESULT_THEME_BURST_POLISH_DURATION_MS = 1_520L
        private const val RESULT_THEME_BURST_SETTLED_ALPHA = 0.34f
        private const val RESULT_SPARKLE_POLISH_DURATION_MS = 1_180L
        private const val RESULT_SPARKLE_POLISH_DELAY_MS = 90L
        private const val RESULT_SPARKLE_SETTLED_ALPHA = 0.28f
        private const val RESULT_SPARKLE_PEAK_ALPHA = 0.64f
        private const val RESULT_STAGE_POLISH_DURATION_MS = 720L
        private const val RESULT_STAGE_SETTLED_ALPHA = 1f
        private const val BADGE_POLISH_DURATION_MS = 520L
        private const val BONUS_AWARD_POLISH_DURATION_MS = 680L
        private const val BONUS_AWARD_POLISH_DELAY_MS = 120L
        private const val BONUS_AWARD_DIGITS_POP_DURATION_MS = 420L
        private const val BONUS_AWARD_DIGITS_POP_DELAY_MS = 210L
        private const val BONUS_AWARD_SETTLED_ALPHA = 1f
        private const val RESULT_DIALOG_DIM_AMOUNT = 0.78f
        const val REQUEST_KEY = "spin_result_dialog"
        const val KEY_DISMISSED = "dismissed"
        const val PRESENTED_REQUEST_KEY = "spin_result_dialog_presented"
        const val KEY_PRESENTATION_ID = "presentation_id"

        fun newInstance(
            resultType: ResultType,
            winAmount: Int = 0,
            freeSpinsAwarded: Int = 0,
            slotTheme: SlotTheme = SlotTheme.Violet,
            presentationId: String = ""
        ): ResultDialogFragment {
            return ResultDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_RESULT_TYPE, resultType.name)
                    putInt(ARG_WIN_AMOUNT, winAmount)
                    putInt(ARG_FREE_SPINS_AWARDED, freeSpinsAwarded)
                    putString(ARG_SLOT_THEME, slotTheme.name)
                    putString(ARG_PRESENTATION_ID, presentationId)
                }
            }
        }
    }
}

internal class ResultDialogDismissGate {
    private var dismissRequested = false
    private var resultConsumed = false

    fun requestDismiss() {
        dismissRequested = true
    }

    fun consumeDismissResult(): Boolean {
        if (!dismissRequested || resultConsumed) return false
        resultConsumed = true
        return true
    }
}
