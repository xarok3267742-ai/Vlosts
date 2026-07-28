package com.vslot.app.debug

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vslot.app.R
import com.vslot.app.data.PlayerState
import com.vslot.app.game.ResultType
import com.vslot.app.game.SlotTheme
import com.vslot.app.ui.dialog.AnalyticsConsentDialogFragment
import com.vslot.app.ui.dialog.DailyBonusDialogFragment
import com.vslot.app.ui.dialog.LowCoinsDialogFragment
import com.vslot.app.ui.dialog.PushPermissionDialogFragment
import com.vslot.app.ui.dialog.ResultDialogFragment

class QaResultDialogActivity : AppCompatActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val root = FrameLayout(this)
        val resultTheme = SlotTheme.Ocean
        root.addView(
            ImageView(this).apply {
                setImageResource(resultTheme.previewBackground())
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        hideSystemBars()
        root.post { showRequestedDialogIfMissing(resultTheme) }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun showRequestedDialogIfMissing(resultTheme: SlotTheme) {
        if (isFinishing || supportFragmentManager.isStateSaved) return
        val requestedDialog = intent.getStringExtra(EXTRA_DIALOG)
        val tag = when (requestedDialog) {
            DIALOG_PUSH -> QA_PUSH_PROMPT_TAG
            DIALOG_ANALYTICS -> QA_ANALYTICS_CONSENT_TAG
            DIALOG_DAILY_BONUS,
            DIALOG_DAILY_WAIT -> QA_DAILY_BONUS_TAG
            DIALOG_LOW_BONUS,
            DIALOG_LOW_WAIT -> QA_LOW_COINS_TAG
            else -> QA_BONUS_RESULT_TAG
        }
        if (supportFragmentManager.findFragmentByTag(tag) != null) return

        when (requestedDialog) {
            DIALOG_PUSH -> PushPermissionDialogFragment()
                .show(supportFragmentManager, tag)
            DIALOG_ANALYTICS -> AnalyticsConsentDialogFragment()
                .show(supportFragmentManager, tag)
            DIALOG_DAILY_BONUS -> DailyBonusDialogFragment
                .newInstance(claimEnabled = true)
                .show(supportFragmentManager, tag)
            DIALOG_DAILY_WAIT -> DailyBonusDialogFragment
                .newInstance(
                    claimEnabled = false,
                    lastDailyBonusTimestamp = System.currentTimeMillis()
                )
                .show(supportFragmentManager, tag)
            DIALOG_LOW_BONUS -> LowCoinsDialogFragment
                .newInstance(bonusAvailable = true)
                .show(supportFragmentManager, tag)
            DIALOG_LOW_WAIT -> LowCoinsDialogFragment
                .newInstance(bonusAvailable = false)
                .show(supportFragmentManager, tag)
            else -> ResultDialogFragment
                .newInstance(
                    ResultType.Bonus,
                    QA_BONUS_WIN_AMOUNT,
                    PlayerState.FREE_SPINS_BONUS_AWARD,
                    resultTheme
                )
                .show(supportFragmentManager, tag)
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun SlotTheme.previewBackground(): Int {
        return when (this) {
            SlotTheme.Roman -> R.drawable.rr_bg
            SlotTheme.Neon -> R.drawable.nn_bg
            SlotTheme.Pharaoh -> R.drawable.pg_bg
            SlotTheme.Ocean -> R.drawable.op_bg
            SlotTheme.Violet -> R.drawable.vf_bg
        }
    }

    private companion object {
        const val EXTRA_DIALOG = "dialog"
        const val DIALOG_PUSH = "push"
        const val DIALOG_ANALYTICS = "analytics"
        const val DIALOG_DAILY_BONUS = "daily_bonus"
        const val DIALOG_DAILY_WAIT = "daily_wait"
        const val DIALOG_LOW_BONUS = "low_bonus"
        const val DIALOG_LOW_WAIT = "low_wait"
        const val QA_BONUS_WIN_AMOUNT = 750
        const val QA_BONUS_RESULT_TAG = "qa_bonus_result"
        const val QA_PUSH_PROMPT_TAG = "qa_push_permission"
        const val QA_ANALYTICS_CONSENT_TAG = "qa_analytics_consent"
        const val QA_DAILY_BONUS_TAG = "qa_daily_bonus"
        const val QA_LOW_COINS_TAG = "qa_low_coins"
    }
}
