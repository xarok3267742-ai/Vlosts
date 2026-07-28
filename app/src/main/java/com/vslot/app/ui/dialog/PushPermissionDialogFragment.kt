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
import com.vslot.app.databinding.DialogPushPermissionBinding

class PushPermissionDialogFragment : DialogFragment() {
    private var pushSignalAnimator: AnimatorSet? = null
    private var resultSent = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogPushPermissionBinding.inflate(layoutInflater)
        ViewCompat.setAccessibilityHeading(binding.pushPromptTitle, true)
        bindPushDisclosure(binding)
        binding.allowButton.setOnClickListener {
            setResult(accepted = true)
        }
        binding.maybeLaterButton.setOnClickListener {
            setResult(accepted = false)
        }

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                animatePushSignal(binding)
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private fun bindPushDisclosure(binding: DialogPushPermissionBinding) {
        binding.pushPromptBody.visibility = View.GONE
        binding.pushPromptBody.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        binding.pushPromptBodyLargeText.visibility = View.VISIBLE
        binding.pushPromptBodyLargeText.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun animatePushSignal(binding: DialogPushPermissionBinding) {
        pushSignalAnimator?.cancel()
        pushSignalAnimator = null
        val signal = binding.pushSignalOverlay
        signal.visibility = View.VISIBLE
        signal.alpha = PUSH_SIGNAL_SETTLED_ALPHA
        signal.scaleX = 1f
        signal.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        signal.alpha = 0.08f
        signal.scaleX = 0.96f
        signal.scaleY = 0.96f

        pushSignalAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(signal, View.ALPHA, 0.08f, 0.58f, PUSH_SIGNAL_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(signal, View.SCALE_X, 0.96f, 1.035f, 1f),
                ObjectAnimator.ofFloat(signal, View.SCALE_Y, 0.96f, 1.035f, 1f)
            )
            duration = PUSH_SIGNAL_DURATION_MS
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
        pushSignalAnimator?.cancel()
        pushSignalAnimator = null
        super.onDestroyView()
    }

    companion object {
        const val REQUEST_KEY = "push_permission_pre_prompt"
        const val KEY_ACCEPTED = "accepted"
        private const val PUSH_SIGNAL_DURATION_MS = 1_100L
        private const val PUSH_SIGNAL_SETTLED_ALPHA = 0.42f
    }
}
