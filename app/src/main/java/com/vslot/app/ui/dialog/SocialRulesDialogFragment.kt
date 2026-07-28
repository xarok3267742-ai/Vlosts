package com.vslot.app.ui.dialog

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import com.vslot.app.databinding.DialogSocialRulesBinding

class SocialRulesDialogFragment : DialogFragment() {
    private var socialRulesSealAnimator: AnimatorSet? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogSocialRulesBinding.inflate(layoutInflater)
        ViewCompat.setAccessibilityHeading(binding.socialRulesTitle, true)
        bindScalableDialogCopy(
            binding.socialRulesBody to binding.socialRulesBodyLargeText,
            binding.socialRulesFooter to binding.socialRulesFooterLargeText
        )
        binding.socialRulesComplianceSeal.visibility = View.VISIBLE
        binding.socialRulesComplianceSeal.alpha = SOCIAL_RULES_SEAL_SETTLED_ALPHA
        binding.closeButton.setOnClickListener { dismiss() }

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                animateSocialRulesSeal(binding)
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private fun animateSocialRulesSeal(binding: DialogSocialRulesBinding) {
        socialRulesSealAnimator?.cancel()
        socialRulesSealAnimator = null
        val seal = binding.socialRulesComplianceSeal
        seal.visibility = View.VISIBLE
        seal.alpha = SOCIAL_RULES_SEAL_SETTLED_ALPHA
        seal.scaleX = 1f
        seal.scaleY = 1f
        binding.socialRulesBadge.scaleX = 1f
        binding.socialRulesBadge.scaleY = 1f
        binding.closeButton.scaleX = 1f
        binding.closeButton.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        seal.alpha = 0.08f
        seal.scaleX = 0.985f
        seal.scaleY = 0.985f
        socialRulesSealAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(seal, View.ALPHA, 0.08f, SOCIAL_RULES_SEAL_PEAK_ALPHA, SOCIAL_RULES_SEAL_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(seal, View.SCALE_X, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(seal, View.SCALE_Y, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(binding.socialRulesBadge, View.SCALE_X, 0.94f, 1.045f, 1f),
                ObjectAnimator.ofFloat(binding.socialRulesBadge, View.SCALE_Y, 0.94f, 1.045f, 1f),
                ObjectAnimator.ofFloat(binding.closeButton, View.SCALE_X, 0.985f, 1.025f, 1f),
                ObjectAnimator.ofFloat(binding.closeButton, View.SCALE_Y, 0.985f, 1.025f, 1f)
            )
            duration = SOCIAL_RULES_SEAL_POLISH_DURATION_MS
            start()
        }
    }

    override fun onDestroyView() {
        socialRulesSealAnimator?.cancel()
        socialRulesSealAnimator = null
        super.onDestroyView()
    }

    private companion object {
        const val SOCIAL_RULES_SEAL_POLISH_DURATION_MS = 820L
        const val SOCIAL_RULES_SEAL_SETTLED_ALPHA = 0.28f
        const val SOCIAL_RULES_SEAL_PEAK_ALPHA = 0.54f
    }
}
