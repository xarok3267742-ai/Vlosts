package com.vslot.app.ui.disclaimer

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vslot.app.AppGraph
import com.vslot.app.R
import com.vslot.app.databinding.FragmentDisclaimerBinding
import kotlinx.coroutines.launch

class DisclaimerFragment : Fragment() {
    private var _binding: FragmentDisclaimerBinding? = null
    private val binding get() = _binding!!
    private var disclaimerAccepted = false
    private var acceptanceInProgress = false
    private var acceptanceAnimator: AnimatorSet? = null
    private val viewModel: DisclaimerViewModel by viewModels {
        DisclaimerViewModel.Factory(AppGraph.playerRepository, AppGraph.analyticsTracker)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDisclaimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        disclaimerAccepted = savedInstanceState?.getBoolean(KEY_DISCLAIMER_SELECTED) ?: false
        ViewCompat.setAccessibilityHeading(binding.disclaimerTitle, true)
        bindScalableCopy()
        ViewCompat.setAccessibilityDelegate(
            binding.disclaimerCheckRow,
            object : AccessibilityDelegateCompat() {
                @Suppress("DEPRECATION")
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.CheckBox"
                    info.isCheckable = true
                    info.isChecked = disclaimerAccepted
                }
            }
        )
        renderDisclaimerAccepted()
        binding.disclaimerCheckRow.setOnClickListener {
            toggleDisclaimerAccepted()
        }
        binding.continueButton.setOnClickListener {
            if (!disclaimerAccepted || acceptanceInProgress) return@setOnClickListener
            viewModel.accept()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.acceptanceState.collect { state ->
                    acceptanceInProgress = state == DisclaimerAcceptanceState.Saving
                    renderDisclaimerAccepted()
                    if (state == DisclaimerAcceptanceState.Saved) {
                        navigateFromDisclaimer()
                    } else if (state == DisclaimerAcceptanceState.Failed) {
                        val message = getString(R.string.persistence_save_error_retry)
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.acceptanceState.value == DisclaimerAcceptanceState.Saved) {
            navigateFromDisclaimer()
        }
    }

    private fun bindScalableCopy() {
        val useScalableCopy = resources.configuration.fontScale > DEFAULT_FONT_SCALE
        binding.disclaimerBody.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.disclaimerBodyLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.disclaimerCheckboxLabelImage.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        binding.disclaimerCheckboxLargeText.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        binding.disclaimerBody.importantForAccessibility = if (useScalableCopy) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        binding.disclaimerBodyLargeText.importantForAccessibility = if (useScalableCopy) {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun toggleDisclaimerAccepted() {
        disclaimerAccepted = !disclaimerAccepted
        renderDisclaimerAccepted()
        if (disclaimerAccepted) {
            animateAcceptanceFeedback()
        }
    }

    private fun navigateFromDisclaimer(): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        if (parentFragmentManager.isStateSaved) return false
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.disclaimerFragment) return false
        navController.navigate(R.id.action_disclaimer_to_home)
        return true
    }

    private fun renderDisclaimerAccepted() {
        acceptanceAnimator?.cancel()
        acceptanceAnimator = null
        binding.disclaimerCheckButton.isSelected = disclaimerAccepted
        ViewCompat.setStateDescription(
            binding.disclaimerCheckRow,
            getString(
                if (disclaimerAccepted) {
                    R.string.disclaimer_checkbox_checked
                } else {
                    R.string.disclaimer_checkbox_unchecked
                }
            )
        )
        binding.disclaimerCheckButton.scaleX = 1f
        binding.disclaimerCheckButton.scaleY = 1f
        binding.continueButton.isEnabled = disclaimerAccepted && !acceptanceInProgress
        binding.continueButton.alpha = if (disclaimerAccepted) 1f else 0.52f
        binding.continueButtonLabel.alpha = if (disclaimerAccepted) 1f else CONTINUE_LABEL_DISABLED_ALPHA
        binding.continueButton.scaleX = 1f
        binding.continueButton.scaleY = 1f
        binding.disclaimerAcceptGlow.animate().cancel()
        binding.disclaimerAcceptGlow.visibility = if (disclaimerAccepted) View.VISIBLE else View.INVISIBLE
        binding.disclaimerAcceptGlow.alpha = if (disclaimerAccepted) ACCEPT_GLOW_SETTLED_ALPHA else 0f
        binding.disclaimerAcceptGlow.scaleX = 1f
        binding.disclaimerAcceptGlow.scaleY = 1f
    }

    private fun animateAcceptanceFeedback() {
        acceptanceAnimator?.cancel()
        binding.disclaimerAcceptGlow.visibility = View.VISIBLE
        binding.disclaimerAcceptGlow.alpha = ACCEPT_GLOW_SETTLED_ALPHA
        binding.disclaimerAcceptGlow.scaleX = 1f
        binding.disclaimerAcceptGlow.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        acceptanceAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.disclaimerAcceptGlow, View.ALPHA, 0.04f, ACCEPT_GLOW_PEAK_ALPHA, ACCEPT_GLOW_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(binding.disclaimerAcceptGlow, View.SCALE_X, 0.98f, 1.02f, 1f),
                ObjectAnimator.ofFloat(binding.disclaimerAcceptGlow, View.SCALE_Y, 0.98f, 1.02f, 1f),
                ObjectAnimator.ofFloat(binding.disclaimerCheckButton, View.SCALE_X, 0.92f, 1.08f, 1f),
                ObjectAnimator.ofFloat(binding.disclaimerCheckButton, View.SCALE_Y, 0.92f, 1.08f, 1f),
                ObjectAnimator.ofFloat(binding.continueButton, View.SCALE_X, 0.98f, 1.03f, 1f),
                ObjectAnimator.ofFloat(binding.continueButton, View.SCALE_Y, 0.98f, 1.03f, 1f),
                ObjectAnimator.ofFloat(binding.continueButtonLabel, View.ALPHA, CONTINUE_LABEL_DISABLED_ALPHA, 1f)
            )
            duration = ACCEPT_FEEDBACK_DURATION_MS
            start()
        }
    }

    override fun onDestroyView() {
        acceptanceAnimator?.cancel()
        acceptanceAnimator = null
        _binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_DISCLAIMER_SELECTED, disclaimerAccepted)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val ACCEPT_FEEDBACK_DURATION_MS = 520L
        const val ACCEPT_GLOW_SETTLED_ALPHA = 0.28f
        const val ACCEPT_GLOW_PEAK_ALPHA = 0.58f
        const val CONTINUE_LABEL_DISABLED_ALPHA = 0.48f
        const val DEFAULT_FONT_SCALE = 1.0f
        const val KEY_DISCLAIMER_SELECTED = "disclaimer_selected"
    }
}
