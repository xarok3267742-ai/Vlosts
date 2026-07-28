package com.vslot.app.ui.dialog

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.vslot.app.R
import com.vslot.app.databinding.DialogAutoSpinCountBinding
import com.vslot.app.databinding.ItemAutoSpinCountOptionBinding

class AutoSpinCountDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAutoSpinCountBinding.inflate(layoutInflater)
        bindOption(binding.autoSpinOption10, 10)
        bindOption(binding.autoSpinOption25, 25)
        bindOption(binding.autoSpinOption50, 50)
        binding.closeButton.setOnClickListener { dismiss() }

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                binding.autoSpinTitle.requestFocus()
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private fun bindOption(binding: ItemAutoSpinCountOptionBinding, count: Int) {
        binding.optionDigits.setCharacters(
            "$count" + "x",
            spacingPx = 0,
            compactSeparators = true,
            fixedGlyphBaseWidthDp = 14f
        )
        binding.optionButton.contentDescription = getString(R.string.auto_spin_count_action, count)
        binding.optionButton.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                Bundle().apply { putInt(KEY_COUNT, count) }
            )
            dismiss()
        }
    }

    companion object {
        const val REQUEST_KEY = "auto_spin_count_request"
        const val KEY_COUNT = "auto_spin_count"
        const val TAG = "auto_spin_count"
    }
}
