package com.vslot.app.ui.dialog

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.vslot.app.databinding.DialogAnalyticsConsentBinding

class AnalyticsConsentDialogFragment : DialogFragment() {
    private var consentSignalAnimator: AnimatorSet? = null
    private var resultSent = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAnalyticsConsentBinding.inflate(layoutInflater)
        ViewCompat.setAccessibilityHeading(binding.analyticsConsentTitle, true)
        bindScalableDialogCopy(
            binding.analyticsConsentBody to binding.analyticsConsentBodyLargeText
        )
        binding.allowButton.setOnClickListener { setResult(accepted = true) }
        binding.declineButton.setOnClickListener { setResult(accepted = false) }

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                applyGameDialogDim(CONSENT_DIALOG_DIM_AMOUNT)
                animateConsentSignal(binding)
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private fun animateConsentSignal(binding: DialogAnalyticsConsentBinding) {
        consentSignalAnimator?.cancel()
        consentSignalAnimator = null
        val signal = binding.analyticsConsentSignal
        signal.visibility = View.VISIBLE
        signal.alpha = CONSENT_SIGNAL_SETTLED_ALPHA
        signal.scaleX = 1f
        signal.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        signal.alpha = 0.08f
        signal.scaleX = 0.96f
        signal.scaleY = 0.96f
        consentSignalAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    signal,
                    View.ALPHA,
                    0.08f,
                    CONSENT_SIGNAL_PEAK_ALPHA,
                    CONSENT_SIGNAL_SETTLED_ALPHA
                ),
                ObjectAnimator.ofFloat(signal, View.SCALE_X, 0.96f, 1.035f, 1f),
                ObjectAnimator.ofFloat(signal, View.SCALE_Y, 0.96f, 1.035f, 1f)
            )
            duration = CONSENT_SIGNAL_DURATION_MS
            start()
        }
    }

    private fun setResult(accepted: Boolean) {
        dispatchResult(accepted)
        dismiss()
    }

    private fun dispatchResult(accepted: Boolean) {
        if (resultSent) return
        resultSent = true
        setFragmentResult(
            REQUEST_KEY,
            Bundle().apply { putBoolean(KEY_ACCEPTED, accepted) }
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        dispatchResult(accepted = false)
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        consentSignalAnimator?.cancel()
        consentSignalAnimator = null
        super.onDestroyView()
    }

    companion object {
        const val REQUEST_KEY = "analytics_consent"
        const val KEY_ACCEPTED = "accepted"
        private const val CONSENT_SIGNAL_DURATION_MS = 900L
        private const val CONSENT_SIGNAL_SETTLED_ALPHA = 0.32f
        private const val CONSENT_SIGNAL_PEAK_ALPHA = 0.58f
        private const val CONSENT_DIALOG_DIM_AMOUNT = 0.78f
    }
}
