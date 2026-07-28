package com.vslot.app.ui.dialog

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.vslot.app.AppGraph
import com.vslot.app.R
import com.vslot.app.databinding.DialogPaytableBinding
import com.vslot.app.game.SlotConfig
import com.vslot.app.game.SlotTheme
import com.vslot.app.ui.widget.BitmapNumberView
import com.vslot.app.ui.slot.SlotSymbolResources
import kotlin.math.roundToInt

class PaytableDialogFragment : DialogFragment() {
    private lateinit var config: SlotConfig
    private val paytableBonusLaneViews = mutableListOf<ImageView>()
    private var paytableCabinetLatticeAnimator: AnimatorSet? = null
    private var paytableScrollHintAnimator: AnimatorSet? = null
    private var paytableBonusLaneAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppGraph.slotRepository.getSlot(arguments?.getString(ARG_SLOT_ID).orEmpty())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogPaytableBinding.inflate(layoutInflater)
        paytableBonusLaneAnimator?.cancel()
        paytableBonusLaneAnimator = null
        paytableBonusLaneViews.clear()
        val title = "${getString(R.string.paytable)}: ${config.name}"
        val bets = config.bets.joinToString(" / ")
        val compactBets = config.bets.joinToString("/")
        binding.paytableModalPanel.setImageResource(paytableModalPanelDrawable())
        binding.paytableTitle.setImageResource(paytableTitleDrawable())
        binding.paytableCabinetLattice.setImageResource(paytableCabinetLatticeDrawable())
        binding.paytableOddsHeaderGlow.setImageResource(paytableOddsHeaderGlowDrawable())
        binding.paytableTitle.contentDescription = title
        binding.paytableBetsGroup.contentDescription = getString(R.string.paytable_bets_content_description, bets)
        binding.paytableBetsDigits.setCharacters(
            compactBets,
            spacingPx = 0,
            compactSeparators = true,
            fixedGlyphBaseWidthDp = 7f
        )
        binding.paytableBetsDigits.contentDescription = bets
        binding.paytablePaylineGuide.setImageResource(paytablePaylineGuideDrawable())
        val footerText = paytableFooterText()
        binding.paytableFooter.setImageResource(paytableFooterDrawable())
        binding.paytableFooter.contentDescription = getString(footerText)
        binding.paytableFooterLargeText.setText(footerText)
        bindScalableDialogCopy(
            binding.paytablePaylineGuide to binding.paytablePaylineGuideLargeText,
            binding.paytableBetExplanation to binding.paytableBetExplanationLargeText,
            binding.paytableFooter to binding.paytableFooterLargeText
        )
        adaptCompactLargeFontLayout(binding)
        binding.paytableHeaderThree.setMultiplierHeader(3)
        binding.paytableHeaderFour.setMultiplierHeader(4)
        binding.paytableHeaderFive.setMultiplierHeader(5)
        binding.closeButton.setOnClickListener { dismiss() }

        config.symbols.forEach { symbol ->
            if (symbol == config.scatter && config.scatterBonus.isNotEmpty()) {
                binding.paytableRows.addView(createScatterBonusRow())
            } else {
                binding.paytableRows.addView(createRow(symbol))
            }
        }
        binding.paytableScrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            updatePaytableScrollHint(binding)
        }
        binding.paytableScrollView.isVerticalScrollBarEnabled = false

        return Dialog(requireContext()).apply {
            setContentView(binding.root)
            setOnShowListener {
                keepGameFullscreen()
                animatePaytableCabinetLattice(binding)
                animatePaytableBonusLane()
                binding.paytableScrollView.post {
                    updatePaytableScrollHint(binding)
                    animatePaytableScrollHint(binding)
                }
            }
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
    }

    private fun adaptCompactLargeFontLayout(binding: DialogPaytableBinding) {
        val configuration = resources.configuration
        if (
            configuration.fontScale <= 1f ||
            configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp >= COMPACT_LANDSCAPE_MAX_WIDTH_DP
        ) {
            return
        }
        val payouts = binding.paytableRowsStage.parent as? LinearLayout ?: return
        val columns = payouts.parent as? LinearLayout ?: return
        columns.orientation = LinearLayout.VERTICAL
        columns.gravity = Gravity.CENTER_HORIZONTAL
        payouts.layoutParams = (payouts.layoutParams as LinearLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            weight = 0f
        }
        binding.paytableFooterLargeText.layoutParams =
            (binding.paytableFooterLargeText.layoutParams as LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                marginStart = 0
                topMargin = 8.dpPx()
            }
    }

    private fun animatePaytableCabinetLattice(binding: DialogPaytableBinding) {
        paytableCabinetLatticeAnimator?.cancel()
        paytableCabinetLatticeAnimator = null
        binding.paytableCabinetLattice.alpha = PAYTABLE_LATTICE_SETTLED_ALPHA
        binding.paytableCabinetLattice.scaleX = 1f
        binding.paytableCabinetLattice.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return

        binding.paytableCabinetLattice.alpha = 0.58f
        binding.paytableCabinetLattice.scaleX = 0.992f
        binding.paytableCabinetLattice.scaleY = 0.992f

        paytableCabinetLatticeAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    binding.paytableCabinetLattice,
                    View.ALPHA,
                    0.58f,
                    1f,
                    PAYTABLE_LATTICE_SETTLED_ALPHA
                ),
                ObjectAnimator.ofFloat(binding.paytableCabinetLattice, View.SCALE_X, 0.992f, 1.006f, 1f),
                ObjectAnimator.ofFloat(binding.paytableCabinetLattice, View.SCALE_Y, 0.992f, 1.006f, 1f)
            )
            duration = PAYTABLE_LATTICE_POLISH_DURATION_MS
            start()
        }
    }

    private fun updatePaytableScrollHint(binding: DialogPaytableBinding) {
        val canScrollDown = binding.paytableScrollView.canScrollVertically(1)
        binding.paytableScrollHint.visibility = if (canScrollDown) View.VISIBLE else View.INVISIBLE
        binding.paytableScrollHint.alpha = if (canScrollDown) PAYTABLE_SCROLL_HINT_SETTLED_ALPHA else 0f
        if (!canScrollDown) {
            binding.paytableScrollHint.translationY = 0f
        }
    }

    private fun animatePaytableScrollHint(binding: DialogPaytableBinding) {
        paytableScrollHintAnimator?.cancel()
        paytableScrollHintAnimator = null
        if (!binding.paytableScrollView.canScrollVertically(1)) return
        binding.paytableScrollHint.alpha = 0f
        binding.paytableScrollHint.translationY = (-6).dpPx().toFloat()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            updatePaytableScrollHint(binding)
            return
        }

        paytableScrollHintAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(
                    binding.paytableScrollHint,
                    View.ALPHA,
                    0f,
                    1f,
                    PAYTABLE_SCROLL_HINT_SETTLED_ALPHA
                ),
                ObjectAnimator.ofFloat(
                    binding.paytableScrollHint,
                    View.TRANSLATION_Y,
                    (-6).dpPx().toFloat(),
                    8.dpPx().toFloat(),
                    0f
                )
            )
            duration = PAYTABLE_SCROLL_HINT_POLISH_DURATION_MS
            start()
        }
    }

    private fun createRow(symbol: String): FrameLayout {
        return createImageBackedRow(paytableRowContent(symbol))
    }

    private fun createScatterBonusRow(): FrameLayout {
        return createImageBackedRow(scatterBonusRowContent(), includeBonusLane = true)
    }

    private fun createImageBackedRow(content: View, includeBonusLane: Boolean = false): FrameLayout {
        val rowHeight = resources.getDimensionPixelSize(R.dimen.paytable_row_height)
        return FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
            addView(
                ImageView(requireContext()).apply {
                    setImageResource(paytableRowPanelDrawable())
                    contentDescription = null
                    scaleType = ImageView.ScaleType.FIT_XY
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            if (includeBonusLane) {
                addView(
                    ImageView(requireContext()).apply {
                        setImageResource(paytableBonusLaneDrawable())
                        contentDescription = null
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        scaleType = ImageView.ScaleType.FIT_XY
                        alpha = PAYTABLE_BONUS_LANE_SETTLED_ALPHA
                        paytableBonusLaneViews += this
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }
            addView(content)
        }
    }

    private fun paytableRowPanelDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.paytable_row_panel_roman
            SlotTheme.Neon -> R.drawable.paytable_row_panel_neon
            SlotTheme.Pharaoh -> R.drawable.paytable_row_panel_pharaoh
            SlotTheme.Ocean -> R.drawable.paytable_row_panel_ocean
            SlotTheme.Violet -> R.drawable.paytable_row_panel
        }
    }

    private fun paytableBonusLaneDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.paytable_bonus_lane_roman
            SlotTheme.Neon -> R.drawable.paytable_bonus_lane_neon
            SlotTheme.Pharaoh -> R.drawable.paytable_bonus_lane_pharaoh
            SlotTheme.Ocean -> R.drawable.paytable_bonus_lane_ocean
            SlotTheme.Violet -> R.drawable.paytable_bonus_lane
        }
    }

    private fun paytableModalPanelDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.paytable_modal_panel_roman
            SlotTheme.Neon -> R.drawable.paytable_modal_panel_neon
            SlotTheme.Pharaoh -> R.drawable.paytable_modal_panel_pharaoh
            SlotTheme.Ocean -> R.drawable.paytable_modal_panel_ocean
            SlotTheme.Violet -> R.drawable.paytable_modal_panel_violet
        }
    }

    private fun animatePaytableBonusLane() {
        paytableBonusLaneAnimator?.cancel()
        paytableBonusLaneAnimator = null
        if (paytableBonusLaneViews.isEmpty()) return

        paytableBonusLaneViews.forEach { lane ->
            lane.alpha = PAYTABLE_BONUS_LANE_SETTLED_ALPHA
            lane.scaleX = 1f
            lane.scaleY = 1f
        }
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val animators = paytableBonusLaneViews.flatMap { lane ->
            lane.alpha = 0.14f
            lane.scaleX = 0.985f
            lane.scaleY = 0.94f
            listOf(
                ObjectAnimator.ofFloat(lane, View.ALPHA, 0.14f, PAYTABLE_BONUS_LANE_PEAK_ALPHA, PAYTABLE_BONUS_LANE_SETTLED_ALPHA),
                ObjectAnimator.ofFloat(lane, View.SCALE_X, 0.985f, 1.018f, 1f),
                ObjectAnimator.ofFloat(lane, View.SCALE_Y, 0.94f, 1.06f, 1f)
            )
        }
        paytableBonusLaneAnimator = AnimatorSet().apply {
            playTogether(animators)
            duration = PAYTABLE_BONUS_LANE_POLISH_DURATION_MS
            start()
        }
    }

    private fun paytableCabinetLatticeDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.paytable_cabinet_lattice_roman
            SlotTheme.Neon -> R.drawable.paytable_cabinet_lattice_neon
            SlotTheme.Pharaoh -> R.drawable.paytable_cabinet_lattice_pharaoh
            SlotTheme.Ocean -> R.drawable.paytable_cabinet_lattice_ocean
            SlotTheme.Violet -> R.drawable.paytable_cabinet_lattice
        }
    }

    private fun paytableOddsHeaderGlowDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.paytable_odds_header_glow
            SlotTheme.Neon -> R.drawable.paytable_odds_header_glow_neon
            SlotTheme.Pharaoh -> R.drawable.paytable_odds_header_glow_pharaoh
            SlotTheme.Ocean -> R.drawable.paytable_odds_header_glow_ocean
            SlotTheme.Violet -> R.drawable.paytable_odds_header_glow
        }
    }

    private fun paytablePaylineGuideDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.paytable_payline_guide_roman
            SlotTheme.Neon -> R.drawable.paytable_payline_guide_neon
            SlotTheme.Pharaoh -> R.drawable.paytable_payline_guide_pharaoh
            SlotTheme.Ocean -> R.drawable.paytable_payline_guide_ocean
            SlotTheme.Violet -> R.drawable.paytable_payline_guide
        }
    }

    private fun paytableTitleDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.title_paytable_roman_reels
            SlotTheme.Neon -> R.drawable.title_paytable_neon_nights
            SlotTheme.Pharaoh -> R.drawable.title_paytable_pharaoh_gold
            SlotTheme.Ocean -> R.drawable.title_paytable_ocean_pearl
            SlotTheme.Violet -> R.drawable.title_paytable_violet_fortune
        }
    }

    private fun paytableFooterText(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.string.paytable_footer_roman
            SlotTheme.Neon -> R.string.paytable_footer_neon
            SlotTheme.Pharaoh -> R.string.paytable_footer_pharaoh
            SlotTheme.Ocean -> R.string.paytable_footer_ocean
            SlotTheme.Violet -> R.string.paytable_footer_violet
        }
    }

    private fun paytableFooterDrawable(): Int {
        return when (config.theme) {
            SlotTheme.Roman -> R.drawable.label_paytable_footer_roman
            SlotTheme.Neon -> R.drawable.label_paytable_footer_nn
            SlotTheme.Pharaoh -> R.drawable.label_paytable_footer_pg
            SlotTheme.Ocean -> R.drawable.label_paytable_footer_op
            SlotTheme.Violet -> R.drawable.label_paytable_footer_violet
        }
    }

    private fun paytableRowContent(symbol: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 5, 8, 5)
            addView(
                ImageView(requireContext()).apply {
                    setImageResource(SlotSymbolResources.image(config.theme, symbol))
                    contentDescription = SlotSymbolResources.label(config.theme, symbol)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = false
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f)
            )
            listOf(3, 4, 5).forEach { count ->
                addView(lineMultiplierView(config.payouts[symbol]?.get(count), count))
            }
        }
    }

    private fun scatterBonusRowContent(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 5, 8, 5)
            addView(
                bonusSymbolCell(),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f)
            )
            listOf(3, 4, 5).forEach { count ->
                addView(scatterMultiplierView(config.scatterBonus[count], count))
            }
        }
    }

    private fun bonusSymbolCell(): FrameLayout {
        val symbolLabel = SlotSymbolResources.label(config.theme, config.scatter)
        return FrameLayout(requireContext()).apply {
            clipChildren = false
            clipToPadding = false
            contentDescription = getString(R.string.paytable_bonus_symbol_accessibility, symbolLabel)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(
                ImageView(requireContext()).apply {
                    setImageResource(R.drawable.symbol_bonus_scatter_halo)
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = PAYTABLE_BONUS_SYMBOL_HALO_ALPHA
                    adjustViewBounds = false
                },
                FrameLayout.LayoutParams(50.dpPx(), 50.dpPx()).apply {
                    gravity = Gravity.CENTER
                }
            )
            addView(
                ImageView(requireContext()).apply {
                    setImageResource(SlotSymbolResources.image(config.theme, config.scatter))
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = false
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                ImageView(requireContext()).apply {
                    setImageResource(R.drawable.modal_badge_bonus)
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = false
                },
                FrameLayout.LayoutParams(
                    PAYTABLE_BONUS_BADGE_SIZE_DP.dpPx(),
                    PAYTABLE_BONUS_BADGE_SIZE_DP.dpPx()
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    leftMargin = PAYTABLE_BONUS_BADGE_LEFT_MARGIN_DP.dpPx()
                    bottomMargin = PAYTABLE_BONUS_BADGE_BOTTOM_MARGIN_DP.dpPx()
                }
            )
        }
    }

    private fun lineMultiplierView(multiplier: Int?, count: Int): FrameLayout {
        return multiplierView(
            multiplier = multiplier,
            count = count,
            contentDescriptionSuffix = getString(R.string.paytable_line_payout_suffix)
        )
    }

    private fun scatterMultiplierView(multiplier: Int?, count: Int): FrameLayout {
        return multiplierView(
            multiplier = multiplier,
            count = count,
            contentDescriptionSuffix = getString(R.string.paytable_total_bet_payout_suffix),
            compactBonusRow = true
        )
    }

    private fun multiplierView(
        multiplier: Int?,
        count: Int,
        contentDescriptionSuffix: String,
        compactBonusRow: Boolean = false
    ): FrameLayout {
        val value = if (multiplier == null) "-" else "${multiplier}x"
        return FrameLayout(requireContext()).apply {
            contentDescription = getString(
                R.string.paytable_payout_accessibility,
                count,
                symbolCountWord(count),
                multiplier.accessibilityValue(),
                contentDescriptionSuffix
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(
                BitmapNumberView(requireContext()).apply {
                    if (compactBonusRow) {
                        setCharacters(
                            value = value,
                            spacingPx = 0,
                            compactSeparators = true
                        )
                    } else {
                        setCharacters(value)
                    }
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (compactBonusRow) PAYTABLE_BONUS_MULTIPLIER_HEIGHT_DP.dpPx() else ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.CENTER
                    marginStart = PAYTABLE_MULTIPLIER_HORIZONTAL_MARGIN_DP.dpPx()
                    marginEnd = PAYTABLE_MULTIPLIER_HORIZONTAL_MARGIN_DP.dpPx()
                }
            )
        }
    }

    private fun BitmapNumberView.setMultiplierHeader(count: Int) {
        val value = "${count}x"
        setCharacters(value)
        contentDescription = value
    }

    private fun symbolCountWord(count: Int): String {
        return getString(if (count in 2..4) R.string.paytable_symbol_count_few else R.string.paytable_symbol_count_many)
    }

    private fun Int?.accessibilityValue(): String {
        return if (this == null) getString(R.string.paytable_no_payout) else "${this}x"
    }

    private fun Int.dpPx(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }

    override fun onDestroyView() {
        paytableCabinetLatticeAnimator?.cancel()
        paytableCabinetLatticeAnimator = null
        paytableScrollHintAnimator?.cancel()
        paytableScrollHintAnimator = null
        paytableBonusLaneAnimator?.cancel()
        paytableBonusLaneAnimator = null
        paytableBonusLaneViews.clear()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SLOT_ID = "slotId"
        private const val PAYTABLE_LATTICE_POLISH_DURATION_MS = 720L
        private const val PAYTABLE_LATTICE_SETTLED_ALPHA = 0.92f
        private const val PAYTABLE_BONUS_SYMBOL_HALO_ALPHA = 0.84f
        private const val PAYTABLE_BONUS_LANE_POLISH_DURATION_MS = 680L
        private const val PAYTABLE_BONUS_LANE_SETTLED_ALPHA = 0.32f
        private const val PAYTABLE_BONUS_LANE_PEAK_ALPHA = 0.68f
        private const val PAYTABLE_BONUS_BADGE_SIZE_DP = 18
        private const val PAYTABLE_BONUS_BADGE_LEFT_MARGIN_DP = 12
        private const val PAYTABLE_BONUS_BADGE_BOTTOM_MARGIN_DP = 3
        private const val PAYTABLE_BONUS_MULTIPLIER_HEIGHT_DP = 28
        private const val PAYTABLE_MULTIPLIER_HORIZONTAL_MARGIN_DP = 2
        private const val COMPACT_LANDSCAPE_MAX_WIDTH_DP = 600
        private const val PAYTABLE_SCROLL_HINT_POLISH_DURATION_MS = 620L
        private const val PAYTABLE_SCROLL_HINT_SETTLED_ALPHA = 0.64f

        fun newInstance(slotId: String): PaytableDialogFragment {
            return PaytableDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SLOT_ID, slotId)
                }
            }
        }
    }
}
