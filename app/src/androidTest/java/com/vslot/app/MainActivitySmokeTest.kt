package com.vslot.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.vslot.app.game.SlotEngine
import com.vslot.app.data.PlayerState
import com.vslot.app.ui.dialog.PushPermissionDialogFragment
import com.vslot.app.ui.asCoins
import com.vslot.app.ui.widget.BitmapNumberView
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun firstLaunchShowsImageDisclaimerAndRoutesAfterAcceptance() {
        seedScenario("first_launch")

        launchMain().use {
            waitForPresent(R.id.disclaimerCheckRow)
            scrollViewIntoView(R.id.disclaimerCheckRow)
            waitForDisplayed(R.id.disclaimerCheckRow)
            waitForDisabled(R.id.continueButton)

            clickView(R.id.disclaimerCheckRow)
            scrollViewIntoView(R.id.continueButton)
            waitForEnabled(R.id.continueButton)
            clickView(R.id.continueButton)

            waitForDisplayed(R.id.bonusCloseButton)
            clickView(R.id.bonusCloseButton)
            waitForDisplayed(R.id.violetCard)
            waitForDisplayed(R.id.romanCard)
        }
    }

    @Test
    fun largeFontLegalCopyWrapsAndKeepsActionsReachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "Large-font verification requires fontScale >= 1.8 in portrait.",
            context.resources.configuration.fontScale >= LARGE_FONT_TEST_SCALE &&
                context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        )
        seedScenario("first_launch")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.disclaimerBodyLargeText)
            assertTextFullyLaidOut(R.id.disclaimerBodyLargeText, context.getString(R.string.disclaimer_body))
            assertTextFullyLaidOut(
                R.id.disclaimerCheckboxLargeText,
                context.getString(R.string.disclaimer_checkbox)
            )
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.disclaimerBody).visibility)
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.disclaimerCheckboxLabelImage).visibility
                )
            }

            scrollViewIntoView(R.id.disclaimerCheckRow)
            waitForDisplayed(R.id.disclaimerCheckRow)
            clickView(R.id.disclaimerCheckRow)
            scrollViewIntoView(R.id.continueButton)
            waitForEnabled(R.id.continueButton)
            assertViewFullyVisible(R.id.continueButton)
            captureLayoutMatrixScreenshot("large-font-01-disclaimer.png")
            clickView(R.id.continueButton)

            waitForDisplayed(R.id.bonusCloseButton)
            clickView(R.id.bonusCloseButton)
            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)
            waitForDisplayed(R.id.settingsSafetyLargeText)

            assertTextFullyLaidOut(
                R.id.settingsSafetyLargeText,
                context.getString(R.string.settings_safety_panel)
            )
            scrollViewIntoView(R.id.socialDisclaimerLargeText)
            assertTextFullyLaidOut(
                R.id.socialDisclaimerLargeText,
                context.getString(R.string.social_disclaimer_short)
            )
            scrollViewIntoView(R.id.versionLargeText)
            assertTextFullyLaidOut(
                R.id.versionLargeText,
                context.getString(R.string.version_format, BuildConfig.VERSION_NAME.removeSuffix("-qa"))
            )
            assertViewFullyVisible(R.id.versionLargeText)
            assertViewFullyVisible(R.id.settingsSafetyLargeText)
            captureLayoutMatrixScreenshot("large-font-02-settings.png")
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.versionImage).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.socialDisclaimerImage).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsSafetyPanel).visibility)
            }
        }
    }

    @Test
    fun compactScaledLegalAndSettingsCopyStayInsideTheirFrames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "Combined compact-font verification requires portrait, width <= 360dp, and fontScale >= 1.3.",
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                context.resources.configuration.screenWidthDp <= COMPACT_SCALED_MAX_WIDTH_DP &&
                context.resources.configuration.fontScale >= COMPACT_SCALED_MIN_FONT_SCALE
        )
        seedScenario("first_launch")

        launchMain().use {
            waitForDisplayed(R.id.disclaimerBodyLargeText)
            assertTextFullyLaidOut(R.id.disclaimerBodyLargeText, context.getString(R.string.disclaimer_body))
            scrollViewIntoView(R.id.disclaimerCheckRow)
            assertTextFullyLaidOut(
                R.id.disclaimerCheckboxLargeText,
                context.getString(R.string.disclaimer_checkbox)
            )
            clickView(R.id.disclaimerCheckRow)
            scrollViewIntoView(R.id.continueButton)
            assertViewFullyVisible(R.id.continueButton)
            clickView(R.id.continueButton)

            waitForDisplayed(R.id.bonusCloseButton)
            clickView(R.id.bonusCloseButton)
            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)
            waitForDisplayed(R.id.settingsSafetyLargeText)

            val settingsCopy = listOf(
                R.id.versionLargeText to context.getString(
                    R.string.version_format,
                    BuildConfig.VERSION_NAME.removeSuffix("-qa")
                ),
                R.id.socialDisclaimerLargeText to context.getString(R.string.social_disclaimer_short),
                R.id.privacyButtonLargeText to context.getString(R.string.privacy_policy),
                R.id.noticesButtonLargeText to context.getString(R.string.third_party_notices_action),
                R.id.rulesButtonLargeText to context.getString(R.string.social_casino_rules),
                R.id.pushStatusLargeText to context.getString(R.string.push_unconfigured_status)
            )
            settingsCopy.forEach { (viewId, expectedText) ->
                scrollViewIntoView(viewId)
                waitForDisplayed(viewId)
                assertTextFullyLaidOut(viewId, expectedText)
                assertViewFullyVisible(viewId)
                assertViewsDoNotOverlap(viewId, R.id.settingsSafetyLargeText)
            }
            assertTextFullyLaidOut(
                R.id.settingsSafetyLargeText,
                context.getString(R.string.settings_safety_panel)
            )
            assertViewFullyVisible(R.id.settingsSafetyLargeText)
            captureLayoutMatrixScreenshot("compact-scaled-settings.png")
        }
    }

    @Test
    fun largeFontDialogCopyWrapsAndKeepsActionsReachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "Large-font dialog verification requires fontScale >= 1.8 in portrait.",
            context.resources.configuration.fontScale >= LARGE_FONT_TEST_SCALE &&
                context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        )
        seedScenario(QA_DIALOG_LOW_WAIT)

        assertLargeFontQaDialog(
            dialog = QA_DIALOG_DAILY_WAIT,
            bitmapId = R.id.bonusBody,
            textId = R.id.bonusBodyLargeText,
            expectedText = context.getString(R.string.bonus_wait),
            actionId = R.id.claimButton,
            screenshotPrefix = "large-font-03-daily-dialog"
        )
        assertLargeFontQaDialog(
            dialog = QA_DIALOG_LOW_WAIT,
            bitmapId = R.id.lowCoinsBody,
            textId = R.id.lowCoinsBodyLargeText,
            expectedText = context.getString(R.string.low_coins_wait_body),
            actionId = R.id.actionButton,
            screenshotPrefix = "large-font-04-low-coins-dialog"
        )
        assertLargeFontQaDialog(
            dialog = QA_DIALOG_PUSH,
            bitmapId = R.id.pushPromptBody,
            textId = R.id.pushPromptBodyLargeText,
            expectedText = context.getString(R.string.push_prompt_body),
            actionId = R.id.maybeLaterButton,
            screenshotPrefix = "large-font-05-push-dialog"
        )
        assertLargeFontQaDialog(
            dialog = QA_DIALOG_ANALYTICS,
            bitmapId = R.id.analyticsConsentBody,
            textId = R.id.analyticsConsentBodyLargeText,
            expectedText = context.getString(R.string.analytics_consent_body),
            actionId = R.id.declineButton,
            screenshotPrefix = "large-font-06-analytics-dialog"
        )
        assertLargeFontQaDialog(
            dialog = QA_DIALOG_RESULT,
            bitmapId = R.id.resultBody,
            textId = R.id.resultBodyLargeText,
            expectedText = context.getString(R.string.result_bonus_body),
            actionId = R.id.closeButton,
            screenshotPrefix = "large-font-07-result-dialog"
        )

        seedScenario("daily_wait")
        launchMain().use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitForPortrait()
            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)
            scrollViewIntoView(R.id.rulesButton)
            waitForDisplayed(R.id.rulesButton)
            clickView(R.id.rulesButton)

            assertLargeFontCopy(
                R.id.socialRulesBody,
                R.id.socialRulesBodyLargeText,
                context.getString(R.string.social_casino_rules_body)
            )
            assertLargeFontCopy(
                R.id.socialRulesFooter,
                R.id.socialRulesFooterLargeText,
                context.getString(R.string.social_casino_rules_footer)
            )
            scrollViewIntoView(R.id.closeButton)
            assertViewFullyVisible(R.id.closeButton)
            captureLayoutMatrixScreenshot("large-font-08-rules-portrait.png")
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()
            assertLargeFontCopy(
                R.id.socialRulesBody,
                R.id.socialRulesBodyLargeText,
                context.getString(R.string.social_casino_rules_body)
            )
            assertLargeFontCopy(
                R.id.socialRulesFooter,
                R.id.socialRulesFooterLargeText,
                context.getString(R.string.social_casino_rules_footer)
            )
            scrollViewIntoView(R.id.closeButton)
            assertViewFullyVisible(R.id.closeButton)
            captureLayoutMatrixScreenshot("large-font-09-rules-landscape.png")
            clickView(R.id.closeButton)

            scrollViewIntoView(R.id.backButton)
            clickView(R.id.backButton)
            waitForDisplayed(R.id.violetCard)
            clickView(R.id.violetCard)
            waitForEnabled(R.id.paytableButton)
            scrollViewIntoView(R.id.paytableButton)
            assertViewFullyVisible(R.id.paytableButton)
            clickView(R.id.paytableButton)

            assertLargeFontCopy(
                R.id.paytablePaylineGuide,
                R.id.paytablePaylineGuideLargeText,
                context.getString(R.string.paytable_payline_guide)
            )
            assertLargeFontCopy(
                R.id.paytableBetExplanation,
                R.id.paytableBetExplanationLargeText,
                context.getString(R.string.paytable_bet_explanation)
            )
            assertLargeFontCopy(
                R.id.paytableFooter,
                R.id.paytableFooterLargeText,
                context.getString(R.string.paytable_footer_violet)
            )
            scrollViewIntoView(R.id.closeButton)
            assertViewFullyVisible(R.id.closeButton)
            captureLayoutMatrixScreenshot("large-font-10-paytable-landscape.png")
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitForPortrait()
            assertLargeFontCopy(
                R.id.paytablePaylineGuide,
                R.id.paytablePaylineGuideLargeText,
                context.getString(R.string.paytable_payline_guide)
            )
            assertLargeFontCopy(
                R.id.paytableBetExplanation,
                R.id.paytableBetExplanationLargeText,
                context.getString(R.string.paytable_bet_explanation)
            )
            assertLargeFontCopy(
                R.id.paytableFooter,
                R.id.paytableFooterLargeText,
                context.getString(R.string.paytable_footer_violet)
            )
            scrollViewIntoView(R.id.closeButton)
            assertViewFullyVisible(R.id.closeButton)
            captureLayoutMatrixScreenshot("large-font-11-paytable-portrait.png")
            clickView(R.id.closeButton)
        }
    }

    @Test
    fun homeNavigationOpensSlotPaytableSettingsAndPrivacyFallback() {
        seedScenario("daily_wait")

        launchMain().use {
            waitForDisplayed(R.id.violetCard)
            waitForDisplayed(R.id.romanCard)
            captureStoreScreenshot("01-home.png")

            clickView(R.id.violetCard)
            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")
            waitForContentDescription(R.id.linesDigits, "10 линий выплат")
            waitForContentDescription(R.id.totalBetDigits, "Общая ставка 100")
            captureStoreScreenshot("02-violet-slot.png")

            waitForEnabled(R.id.paytableButton)
            clickView(R.id.paytableButton)
            waitForDisplayed(R.id.paytablePaylineGuideLargeText)
            captureStoreScreenshot("03-paytable.png")
            clickView(R.id.closeButton)
            clickView(R.id.backButton)

            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)
            waitForDisplayed(R.id.privacyButton)
            captureStoreScreenshot("04-settings.png")
            clickView(R.id.privacyButton)
            waitForPrivacyState()
        }
    }

    @Test
    fun settingsFeedbackControlsPersistAcrossRecreate() {
        seedScenario("daily_wait")

        launchMain().use { scenario ->
            val context = ApplicationProvider.getApplicationContext<Context>()

            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)
            waitForContentDescription(R.id.soundToggleButton, context.getString(R.string.settings_sound))
            waitForContentDescription(R.id.hapticsToggleButton, context.getString(R.string.settings_haptics))
            waitForContentDescription(R.id.analyticsToggleButton, context.getString(R.string.settings_analytics))
            waitForSelected(R.id.soundToggleButton, selected = true)
            waitForSelected(R.id.hapticsToggleButton, selected = true)
            waitForSelected(R.id.analyticsToggleButton, selected = false)
            assertViewsDoNotOverlap(R.id.pushStatusStage, R.id.settingsSafetyPanel)

            clickView(R.id.soundToggleButton)
            waitForSelected(R.id.soundToggleButton, selected = false)
            clickView(R.id.hapticsToggleButton)
            waitForSelected(R.id.hapticsToggleButton, selected = false)
            clickView(R.id.analyticsToggleButton)
            waitForContentDescription(R.id.analyticsConsentBody, context.getString(R.string.analytics_consent_body))
            clickView(R.id.declineButton)
            waitForSelected(R.id.analyticsToggleButton, selected = false)
            clickView(R.id.analyticsToggleButton)
            waitForContentDescription(R.id.analyticsConsentBody, context.getString(R.string.analytics_consent_body))
            clickView(R.id.allowButton)
            waitForSelected(R.id.analyticsToggleButton, selected = true)

            scenario.recreate()
            waitForSelected(R.id.soundToggleButton, selected = false)
            waitForSelected(R.id.hapticsToggleButton, selected = false)
            waitForSelected(R.id.analyticsToggleButton, selected = true)

            clickView(R.id.analyticsToggleButton)
            waitForContentDescription(R.id.analyticsToggleButton, context.getString(R.string.settings_analytics))
            waitForSelected(R.id.analyticsToggleButton, selected = false)
        }
    }

    @Test
    fun settingsCompactPortraitKeepsScrollableControlsAboveSafetyFooter() {
        seedScenario("daily_wait")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.settingsButton)

            var orientation = Configuration.ORIENTATION_UNDEFINED
            var screenHeightDp = Int.MAX_VALUE
            scenario.onActivity { activity ->
                orientation = activity.resources.configuration.orientation
                screenHeightDp = activity.resources.configuration.screenHeightDp
            }
            assumeTrue(
                "Compact portrait verification requires a portrait window shorter than ${COMPACT_PORTRAIT_MAX_HEIGHT_DP}dp.",
                orientation == Configuration.ORIENTATION_PORTRAIT &&
                    screenHeightDp < COMPACT_PORTRAIT_MAX_HEIGHT_DP
            )

            clickView(R.id.settingsButton)
            waitForDisplayed(R.id.settingsSafetyPanel)
            assertViewFullyVisible(R.id.settingsSafetyPanel)
            captureLayoutMatrixScreenshot("compact-portrait-01-settings-top.png")

            listOf(
                R.id.soundToggleButton,
                R.id.privacyButton,
                R.id.rulesButton,
                R.id.pushStatusStage
            ).forEach { controlId ->
                scrollViewIntoView(controlId)
                assertViewFullyVisible(controlId)
                assertViewFullyVisible(R.id.settingsSafetyPanel)
                assertViewsDoNotOverlap(controlId, R.id.settingsSafetyPanel)
            }
            captureLayoutMatrixScreenshot("compact-portrait-02-settings-bottom.png")
        }
    }

    @Test
    fun settingsLandscapeKeepsCommandsAndStatusFullyVisible() {
        seedScenario("daily_wait")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.settingsButton)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()
            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)

            waitForDisplayed(R.id.privacyButton)
            waitForDisplayed(R.id.pushStatusStage)
            var screenWidthDp = Int.MAX_VALUE
            var fontScale = 1f
            scenario.onActivity { activity ->
                screenWidthDp = activity.resources.configuration.screenWidthDp
                fontScale = activity.resources.configuration.fontScale
            }
            val safetyId = if (fontScale > 1f) {
                R.id.settingsSafetyLargeText
            } else {
                R.id.settingsSafetyPanel
            }
            var pushActionVisible = false
            scenario.onActivity { activity ->
                pushActionVisible = activity.findViewById<View>(R.id.pushActionStage).visibility == View.VISIBLE
            }
            val commandIds = buildList {
                add(R.id.privacyButton)
                add(R.id.noticesButton)
                add(R.id.rulesButton)
                if (pushActionVisible) add(R.id.pushButton)
                add(R.id.pushStatusStage)
            }
            if (screenWidthDp < COMPACT_LANDSCAPE_MAX_WIDTH_DP) {
                captureLayoutMatrixScreenshot("compact-landscape-04-settings-top.png")
                commandIds.forEach { commandId ->
                    scrollViewIntoView(commandId)
                    assertViewFullyVisible(commandId)
                }
                captureLayoutMatrixScreenshot("compact-landscape-05-settings-actions.png")
                scrollViewIntoView(safetyId)
                assertViewFullyVisible(safetyId)
                captureLayoutMatrixScreenshot("compact-landscape-06-settings-safety.png")
            } else {
                commandIds.forEach(::assertViewFullyVisible)
                assertViewFullyVisible(safetyId)
            }
        }
    }

    @Test
    fun unlockedHomeLandscapeSpinsThroughEverySlotTheme() {
        seedScenario(
            scenario = "slot_bonus",
            levelXp = PlayerState.xpRequiredForLevel(HOME_ALL_SLOTS_LEVEL)
        )

        launchMain().use { scenario ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            waitForDisplayed(R.id.violetCard)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()
            waitUntil {
                assertEquals(HOME_ALL_SLOTS_LEVEL, displayedBitmapNumber(R.id.homeLevelDigits))
            }

            assertViewFullyVisible(R.id.homeBalancePanel)
            assertViewFullyVisible(R.id.homeLevelPanel)
            assertViewFullyVisible(R.id.settingsButton)
            assertViewFullyVisible(R.id.homeSlotHorizontalScrollView)
            assertViewFullyVisible(R.id.dailyBonusButton)
            assertViewFullyVisible(R.id.privacyButton)

            val slots = listOf(
                Triple(R.id.violetCard, R.string.slot_violet_fortune, "violet_fortune"),
                Triple(R.id.romanCard, R.string.slot_roman_reels, "roman_reels"),
                Triple(R.id.neonCard, R.string.slot_neon_nights, "neon_nights"),
                Triple(R.id.pharaohCard, R.string.slot_pharaoh_gold, "pharaoh_gold"),
                Triple(R.id.oceanCard, R.string.slot_ocean_pearl, "ocean_pearl")
            )
            slots.forEach { (cardId, titleRes, slotId) ->
                scrollHomeCardIntoView(cardId)
                waitForDisplayed(cardId)
                assertViewFullyVisible(cardId)
                val beforeSpin = runBlocking { AppGraph.playerRepository.playerState.first() }
                val totalBet = beforeSpin.selectedBet * beforeSpin.selectedLines
                AppGraph.persistSlotEngineOverrideForDebug(
                    context,
                    findNoWinStops(
                        slotId = slotId,
                        lineBet = beforeSpin.selectedBet,
                        lines = beforeSpin.selectedLines
                    )
                )
                clickView(cardId)

                waitForContentDescription(R.id.slotTitle, context.getString(titleRes))
                assertViewFullyVisible(R.id.slotMachineFrame)
                assertViewFullyVisible(R.id.slotControlConsole)
                assertViewFullyVisible(R.id.spinButton)

                clickViewWithoutRenderIdle(R.id.spinButton)
                try {
                    waitForContentDescription(
                        R.id.spinButton,
                        context.getString(R.string.spin_slam_stop)
                    )
                } catch (failure: AssertionError) {
                    val failedState = runBlocking { AppGraph.playerRepository.playerState.first() }
                    throw AssertionError(
                        "Slot $slotId did not enter slam-stop state: " +
                            "balance ${beforeSpin.coinsBalance} -> ${failedState.coinsBalance}, " +
                            "animatorsEnabled=${ValueAnimator.areAnimatorsEnabled()}.",
                        failure
                    )
                }
                clickViewWithoutRenderIdle(R.id.spinButton)
                waitForContentDescription(
                    R.id.spinButton,
                    context.getString(R.string.spin),
                    SPIN_RESULT_WAIT_TIMEOUT_MS
                )
                waitForEnabled(R.id.paytableButton)

                val afterSpin = runBlocking { AppGraph.playerRepository.playerState.first() }
                assertEquals(beforeSpin.coinsBalance - totalBet, afterSpin.coinsBalance)
                assertEquals(0, afterSpin.freeSpinsForSlot(slotId))
                assertEquals(0, displayedBitmapNumber(R.id.lastWinDigits))

                clickView(R.id.backButton)
                waitForDisplayed(R.id.homeSlotHorizontalScrollView)
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun disclaimerLandscapeKeepsComplianceCopyAndActionsFullyVisible() {
        seedScenario("first_launch")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.disclaimerCheckRow)
            clickView(R.id.disclaimerCheckRow)
            waitForEnabled(R.id.continueButton)
            scenario.onActivity { activity ->
                val row = activity.findViewById<View>(R.id.disclaimerCheckRow)
                val node = row.createAccessibilityNodeInfo()
                assertEquals("android.widget.CheckBox", node.className)
                assertTrue(node.isCheckable)
                assertTrue(node.isChecked)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                    activity.findViewById<View>(R.id.disclaimerCheckButton).importantForAccessibility
                )
            }
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.disclaimerBody)
            assertViewFullyVisible(R.id.disclaimerBadge)
            assertViewFullyVisible(R.id.disclaimerTitle)
            assertViewFullyVisible(R.id.disclaimerBody)
            assertViewFullyVisible(R.id.disclaimerCheckRow)
            assertViewFullyVisible(R.id.disclaimerCheckButton)
            assertViewFullyVisible(R.id.continueButton)
            waitForEnabled(R.id.continueButton)
            assertViewFullyVisible(R.id.continueButton)
        }
    }

    @Test
    fun privacyLandscapeKeepsNavigationAndCurrentStateFullyVisible() {
        seedScenario("daily_wait")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.settingsButton)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()
            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)
            waitForDisplayed(R.id.privacyButton)
            clickView(R.id.privacyButton)
            waitForPrivacyState()

            assertViewFullyVisible(R.id.backButton)
            assertViewFullyVisible(R.id.privacyTitle)
            if (BuildConfig.PRIVACY_POLICY_URL.isBlank()) {
                assertViewFullyVisible(R.id.errorGroup)
                assertViewFullyVisible(R.id.privacyGuardBadge)
                assertViewFullyVisible(R.id.errorImage)
            } else {
                assertViewFullyVisible(R.id.privacyWebView)
            }
        }
    }

    @Test
    fun slotLandscapeKeepsHudReelsAndPrimaryControlsFullyVisible() {
        seedScenario("slot_bonus")

        launchMain(debugSlotId = "violet_fortune").use { scenario ->
            waitForDisplayed(R.id.slotTitle)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.slotControlConsole)
            assertViewFullyVisible(R.id.backButton)
            assertViewFullyVisible(R.id.slotTitle)
            assertViewFullyVisible(R.id.slotBalancePanel)
            assertViewFullyVisible(R.id.slotMachineFrame)
            assertViewFullyVisible(R.id.reelsGrid)
            assertViewFullyVisible(R.id.slotControlConsole)
            assertViewFullyVisible(R.id.betStepperGroup)
            assertViewFullyVisible(R.id.linesStepperGroup)
            assertViewFullyVisible(R.id.totalBetDigits)
            assertViewFullyVisible(R.id.lastWinDigits)
            assertViewFullyVisible(R.id.paytableButton)
            assertViewFullyVisible(R.id.spinButton)
            assertViewFullyVisible(R.id.autoSpinButton)
            assertViewFullyVisible(R.id.maxLinesButton)
            assertViewsDoNotOverlap(R.id.slotMachineFrame, R.id.slotControlConsole)
        }
    }

    @Test
    fun compactLandscapeKeepsHomeAndSlotActionsReachable() {
        seedScenario("slot_bonus")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.violetCard)
            scenario.onActivity { activity ->
                if (activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
            waitForLandscape()

            var screenWidthDp = Int.MAX_VALUE
            scenario.onActivity { activity ->
                screenWidthDp = activity.resources.configuration.screenWidthDp
            }
            assumeTrue(
                "Compact landscape verification requires a window narrower than 600dp.",
                screenWidthDp < COMPACT_LANDSCAPE_MAX_WIDTH_DP
            )

            scrollViewIntoView(R.id.dailyBonusButton)
            waitForDisplayed(R.id.dailyBonusButton)
            assertViewFullyVisible(R.id.dailyBonusButton)
            captureLayoutMatrixScreenshot("compact-landscape-01-home.png")
            clickView(R.id.dailyBonusButton)
            waitForDisplayed(R.id.bonusCooldownTimerRail)
            clickView(R.id.bonusCloseButton)

            scrollViewIntoView(R.id.violetCard)
            scrollHomeCardIntoView(R.id.violetCard)
            assertViewFullyVisible(R.id.violetCard)
            clickView(R.id.violetCard)

            waitForDisplayed(R.id.slotMachineFrame)
            assertViewFullyVisible(R.id.backButton)
            assertViewFullyVisible(R.id.slotTitle)
            waitForDisplayed(R.id.reelsGrid)
            captureLayoutMatrixScreenshot("compact-landscape-02-slot-reels.png")

            listOf(
                R.id.betStepperGroup,
                R.id.linesStepperGroup,
                R.id.spinButton,
                R.id.autoSpinButton,
                R.id.maxLinesButton
            ).forEach { viewId ->
                scrollViewIntoView(viewId)
                assertViewFullyVisible(viewId)
            }
            captureLayoutMatrixScreenshot("compact-landscape-03-slot-controls.png")
        }
    }

    @Test
    fun paytableLandscapeKeepsTitleAndCloseButtonFullyVisible() {
        seedScenario("daily_wait")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.violetCard)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()
            waitForDisplayed(R.id.violetCard)
            clickView(R.id.violetCard)
            waitForDisplayed(R.id.paytableButton)
            waitForEnabled(R.id.paytableButton)
            clickView(R.id.paytableButton)

            waitForDisplayed(R.id.paytableTitle)
            waitForDisplayed(R.id.closeButton)
            assertViewFullyVisible(R.id.paytableTitle)
            assertViewFullyVisible(R.id.paytablePaylineGuideLargeText)
            assertViewFullyVisible(R.id.closeButton)
        }
    }

    @Test
    fun bonusResultLandscapeKeepsRewardAndCloseButtonFullyVisible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent()
            .setClassName(context.packageName, "com.vslot.app.debug.QaResultDialogActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<Activity>(intent).use { scenario ->
            waitForDisplayed(R.id.resultTitle)
            if (isStoreScreenshotCaptureEnabled()) {
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                waitForPortrait()
                waitForDisplayed(R.id.resultTitle)
                waitForDisplayed(R.id.resultFreeSpinsAwardGroup)
                waitForDisplayed(R.id.closeButton)
                assertViewFullyVisible(R.id.resultTitle)
                assertViewFullyVisible(R.id.resultFreeSpinsAwardGroup)
                assertViewFullyVisible(R.id.closeButton)
                SystemClock.sleep(STORE_RESULT_SCREENSHOT_SETTLE_MS)
                captureStoreScreenshot("06-bonus-result.png")
            }
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.resultTitle)
            waitForDisplayed(R.id.resultFreeSpinsAwardGroup)
            waitForDisplayed(R.id.closeButton)
            assertViewFullyVisible(R.id.resultTitle)
            assertViewFullyVisible(R.id.winAmountGroup)
            assertViewFullyVisible(R.id.resultFreeSpinsAwardGroup)
            assertViewFullyVisible(R.id.resultBodyLargeText)
            assertViewFullyVisible(R.id.closeButton)
        }
    }

    @Test
    fun socialRulesLandscapeKeepsCopyAndCloseButtonFullyVisible() {
        seedScenario("daily_wait")

        launchMain().use { scenario ->
            waitForDisplayed(R.id.settingsButton)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()
            waitForDisplayed(R.id.settingsButton)
            clickView(R.id.settingsButton)
            waitForDisplayed(R.id.rulesButton)
            clickView(R.id.rulesButton)

            waitForDisplayed(R.id.socialRulesBodyLargeText)
            waitForDisplayed(R.id.closeButton)
            assertViewFullyVisible(R.id.socialRulesBadge)
            assertViewFullyVisible(R.id.socialRulesTitle)
            assertViewFullyVisible(R.id.socialRulesBodyLargeText)
            assertViewFullyVisible(R.id.socialRulesFooterLargeText)
            assertViewFullyVisible(R.id.closeButton)
        }
    }

    @Test
    fun dailyBonusLandscapeKeepsRewardAndCommandsFullyVisible() {
        launchQaDialog(QA_DIALOG_DAILY_BONUS).use { scenario ->
            waitForDisplayed(R.id.bonusTitle)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.bonusTitle)
            waitForDisplayed(R.id.claimButton)
            assertViewFullyVisible(R.id.bonusBadge)
            assertViewFullyVisible(R.id.bonusTitle)
            assertViewFullyVisible(R.id.bonusBodyLargeText)
            assertViewFullyVisible(R.id.claimButton)
            assertViewFullyVisible(R.id.bonusCloseButton)
        }
    }

    @Test
    fun dailyBonusCooldownLandscapeKeepsTimerAndCommandsFullyVisible() {
        launchQaDialog(QA_DIALOG_DAILY_WAIT).use { scenario ->
            waitForDisplayed(R.id.bonusTitle)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.bonusCooldownTimerRail)
            assertViewFullyVisible(R.id.bonusBadge)
            assertViewFullyVisible(R.id.bonusTitle)
            assertViewFullyVisible(R.id.bonusBodyLargeText)
            assertViewFullyVisible(R.id.bonusCooldownTimerRail)
            assertViewFullyVisible(R.id.claimButton)
            assertViewFullyVisible(R.id.bonusCloseButton)
        }
    }

    @Test
    fun lowCoinsBonusLandscapeKeepsCopyAndActionFullyVisible() {
        launchQaDialog(QA_DIALOG_LOW_BONUS).use { scenario ->
            waitForDisplayed(R.id.lowCoinsTitle)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.lowCoinsTitle)
            waitForDisplayed(R.id.actionButton)
            assertViewFullyVisible(R.id.lowCoinsTitle)
            assertViewFullyVisible(R.id.lowCoinsBodyLargeText)
            assertViewFullyVisible(R.id.actionButton)
        }
    }

    @Test
    fun lowCoinsCooldownLandscapeKeepsTimerAndActionFullyVisible() {
        seedScenario(QA_DIALOG_LOW_WAIT)

        launchQaDialog(QA_DIALOG_LOW_WAIT).use { scenario ->
            waitForDisplayed(R.id.lowCoinsTitle)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.lowCoinsTitle)
            waitForDisplayed(R.id.lowCoinsCooldownTimerRail)
            assertViewFullyVisible(R.id.lowCoinsTitle)
            assertViewFullyVisible(R.id.lowCoinsBodyLargeText)
            assertViewFullyVisible(R.id.lowCoinsCooldownTimerRail)
            assertViewFullyVisible(R.id.actionButton)
        }
    }

    @Test
    fun pushPromptLandscapeKeepsCopyAndChoicesFullyVisible() {
        launchQaDialog(QA_DIALOG_PUSH).use { scenario ->
            waitForDisplayed(R.id.pushPromptBodyLargeText)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.pushPromptBodyLargeText)
            waitForDisplayed(R.id.allowButton)
            assertViewFullyVisible(R.id.pushPromptBadge)
            assertViewFullyVisible(R.id.pushPromptTitle)
            assertViewFullyVisible(R.id.pushPromptBodyLargeText)
            assertViewFullyVisible(R.id.maybeLaterButton)
            assertViewFullyVisible(R.id.allowButton)
        }
    }

    @Test
    fun analyticsConsentLandscapeKeepsCopyAndChoicesFullyVisible() {
        launchQaDialog(QA_DIALOG_ANALYTICS).use { scenario ->
            waitForDisplayed(R.id.analyticsConsentBodyLargeText)
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()

            waitForDisplayed(R.id.analyticsConsentBodyLargeText)
            waitForDisplayed(R.id.allowButton)
            assertViewFullyVisible(R.id.analyticsConsentBadge)
            assertViewFullyVisible(R.id.analyticsConsentTitle)
            assertViewFullyVisible(R.id.analyticsConsentBodyLargeText)
            assertViewFullyVisible(R.id.declineButton)
            assertViewFullyVisible(R.id.allowButton)
        }
    }

    @Test
    fun directRomanSlotKeepsLinesTotalBetAndAutoSpinControlsStableAfterRecreate() {
        seedScenario("slot_bonus")

        launchMain(debugSlotId = "roman_reels").use { scenario ->
            waitForContentDescription(R.id.slotTitle, "Римские барабаны")
            waitForContentDescription(R.id.linesDigits, "10 линий выплат")
            waitForContentDescription(R.id.totalBetDigits, "Общая ставка 250")
            waitForContentDescription(R.id.autoSpinButton, "Настроить автоспин")

            clickView(R.id.linesMinusButton)
            waitForContentDescription(R.id.linesDigits, "9 линий выплат")
            waitForContentDescription(R.id.totalBetDigits, "Общая ставка 225")

            scenario.recreate()
            waitForContentDescription(R.id.slotTitle, "Римские барабаны")
            waitForContentDescription(R.id.linesDigits, "9 линий выплат")
            waitForContentDescription(R.id.totalBetDigits, "Общая ставка 225")
        }
    }

    @Test
    fun freeSpinsModeUsesFreeSpinCopyAndLocksStakeControls() {
        seedScenario("free_spins")

        launchMain(debugSlotId = "violet_fortune").use {
            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")
            waitForContentDescription(R.id.freeSpinsRail, "Фриспины: 5")
            waitForContentDescription(R.id.spinButton, "Запустить фриспин")
            waitForDisabled(R.id.betPlusButton)
            waitForDisabled(R.id.linesMinusButton)
            captureStoreScreenshot("05-free-spins.png")
        }
    }

    @Test
    fun autoSpinDuringFreeSpinsShowsActiveImageStateAndKeepsStakeLocked() {
        seedScenario("free_spins")

        launchMain(debugSlotId = "roman_reels").use {
            val context = ApplicationProvider.getApplicationContext<Context>()

            waitForContentDescription(R.id.slotTitle, "Римские барабаны")
            waitForContentDescription(R.id.freeSpinsRail, context.getString(R.string.free_spins_remaining, 5))
            waitForContentDescription(R.id.spinButton, context.getString(R.string.spin_free_spins))

            clickView(R.id.autoSpinButton)

            waitForContentDescriptionPrefix(
                R.id.autoSpinButton,
                context.dynamicCountPrefix(R.string.auto_spin_stop_free_spins)
            )
            waitForDisplayed(R.id.autoSpinActiveHalo)
            waitForDisabled(R.id.spinButton)
            waitForDisabled(R.id.betMinusButton)
            waitForDisabled(R.id.linesPlusButton)
            waitForDisabled(R.id.maxLinesButton)
            waitForDisabled(R.id.paytableButton)

            clickView(R.id.autoSpinButton)
            waitForContentDescription(R.id.autoSpinButton, context.getString(R.string.auto_spin_configure))
        }
    }

    @Test
    fun backgroundingStopsAutospinBeforeAnotherSpinCanBeScheduled() {
        seedScenario("slot_multi_win")

        launchMain(debugSlotId = "violet_fortune").use {
            val context = ApplicationProvider.getApplicationContext<Context>()
            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")

            startPaidAutoSpin(context)

            val backgroundedActivity = backgroundCurrentActivityWithoutRenderIdle()
            SystemClock.sleep(BACKGROUND_SETTLE_MS)
            foregroundMainActivityWithoutRenderIdle(backgroundedActivity)

            waitForContentDescription(R.id.autoSpinButton, context.getString(R.string.auto_spin_configure))
        }
    }

    @Test
    fun persistedFreeSpinFeaturePausesInBackgroundResumesAndHonorsExplicitStop() {
        seedScenario("free_spins")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initialState = runBlocking { AppGraph.playerRepository.playerState.first() }
        AppGraph.persistSlotEngineOverrideForDebug(
            context,
            findNoWinStops(
                slotId = FEATURE_RESUME_SLOT_ID,
                lineBet = initialState.freeSpinBetForSlot(FEATURE_RESUME_SLOT_ID),
                lines = initialState.freeSpinLinesForSlot(FEATURE_RESUME_SLOT_ID)
            )
        )
        launchMain(debugSlotId = FEATURE_RESUME_SLOT_ID).use {
            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")
            waitForContentDescription(R.id.autoSpinButton, context.getString(R.string.auto_spin_configure))
            clickViewWithoutRenderIdle(R.id.autoSpinButton)
            waitForContentDescriptionPrefix(
                R.id.autoSpinButton,
                context.dynamicCountPrefix(R.string.auto_spin_stop_free_spins)
            )
            waitUntil {
                val state = runBlocking { AppGraph.playerRepository.playerState.first() }
                assertTrue(state.shouldAutoPlayFreeSpinsForSlot(FEATURE_RESUME_SLOT_ID))
            }

            val backgroundedActivity = backgroundCurrentActivityWithoutRenderIdle()
            val pausedFreeSpins = runBlocking {
                AppGraph.playerRepository.playerState.first().freeSpinsForSlot(FEATURE_RESUME_SLOT_ID)
            }
            SystemClock.sleep(BACKGROUND_SETTLE_MS)
            assertEquals(
                pausedFreeSpins,
                runBlocking {
                    AppGraph.playerRepository.playerState.first().freeSpinsForSlot(FEATURE_RESUME_SLOT_ID)
                }
            )

            foregroundMainActivityWithoutRenderIdle(backgroundedActivity)
            waitForContentDescriptionPrefix(
                R.id.autoSpinButton,
                context.dynamicCountPrefix(R.string.auto_spin_stop_free_spins)
            )
            waitUntil(SPIN_RESULT_WAIT_TIMEOUT_MS) {
                val remaining = runBlocking {
                    AppGraph.playerRepository.playerState.first().freeSpinsForSlot(FEATURE_RESUME_SLOT_ID)
                }
                assertTrue(remaining < pausedFreeSpins)
            }

            clickViewWithoutRenderIdle(R.id.autoSpinButton)
            waitForContentDescription(R.id.autoSpinButton, context.getString(R.string.auto_spin_configure))
            waitUntil {
                val state = runBlocking { AppGraph.playerRepository.playerState.first() }
                assertFalse(state.shouldAutoPlayFreeSpinsForSlot(FEATURE_RESUME_SLOT_ID))
            }
        }
    }

    @Test
    fun settledWinCountsUpWithoutDelayingBalanceSettlement() {
        withAnimatorDurationScale(1f) {
            seedScenario("slot_multi_win")
            val context = ApplicationProvider.getApplicationContext<Context>()
            AppGraph.persistSlotEngineOverrideForDebug(context, COUNT_UP_STOPS)
            val config = AppGraph.slotRepository.getSlot(COUNT_UP_SLOT_ID)
            val expectedResult = SlotEngine().evaluate(
                config = config,
                reels = config.reelStrips.mapIndexed { reelIndex, strip ->
                    List(config.rows) { row ->
                        strip[(COUNT_UP_STOPS[reelIndex] + row) % strip.size]
                    }
                },
                bet = COUNT_UP_LINE_BET,
                lines = COUNT_UP_LINES,
                stopIndexes = COUNT_UP_STOPS.toList()
            )
            assertTrue(expectedResult.winAmount > 0)
            val expectedDescription =
                "${context.getString(R.string.last_win)} ${expectedResult.winAmount.asCoins()}"

            launchMain(debugSlotId = COUNT_UP_SLOT_ID).use {
                waitForContentDescription(
                    R.id.lastWinDigits,
                    "${context.getString(R.string.last_win)} 0"
                )
                clickViewWithoutRenderIdle(R.id.spinButton)
                waitUntil(SPIN_RESULT_WAIT_TIMEOUT_MS) {
                    val view = findCurrentViewById(R.id.lastWinDigits) as? BitmapNumberView
                        ?: throw AssertionError("Last-win meter is missing.")
                    assertEquals(expectedDescription, view.contentDescription?.toString())
                    val inProgressValue = view.displayedCharacters
                        .filter(Char::isDigit)
                        .toIntOrNull()
                        ?: 0
                    assertTrue(
                        "Win count-up should start after the final accessible value is announced",
                        inProgressValue in 0 until expectedResult.winAmount
                    )
                }
                waitUntil {
                    assertEquals(expectedResult.winAmount, displayedBitmapNumber(R.id.lastWinDigits))
                }

                val settledState = runBlocking { AppGraph.playerRepository.playerState.first() }
                assertEquals(
                    COUNT_UP_STARTING_BALANCE - expectedResult.totalBet + expectedResult.winAmount,
                    settledState.coinsBalance
                )
            }
        }
    }

    @Test
    fun settledWinRendersImmediatelyWhenSystemAnimationsAreDisabled() {
        seedScenario("slot_multi_win")
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.persistSlotEngineOverrideForDebug(context, COUNT_UP_STOPS)
        val expectedResult = countUpResult()
        val expectedDescription =
            "${context.getString(R.string.last_win)} ${expectedResult.winAmount.asCoins()}"
        var observedAccessibleValueBeforeVisualValue = false

        launchMain(debugSlotId = COUNT_UP_SLOT_ID).use {
            waitForContentDescription(
                R.id.lastWinDigits,
                "${context.getString(R.string.last_win)} 0"
            )
            waitForDisplayed(R.id.spinButton)
            waitForEnabled(R.id.spinButton)
            withAnimatorDurationScale(0f) {
                val spinStartedAtMs = SystemClock.elapsedRealtime()
                clickView(R.id.spinButton)
                waitUntil(SPIN_RESULT_WAIT_TIMEOUT_MS) {
                    val view = findCurrentViewById(R.id.lastWinDigits) as? BitmapNumberView
                        ?: throw AssertionError("Last-win meter is missing.")
                    if (view.contentDescription?.toString() != expectedDescription) {
                        throw AssertionError("Final last-win value is not available yet.")
                    }
                    val displayedValue = view.displayedCharacters
                        .filter(Char::isDigit)
                        .toIntOrNull()
                        ?: 0
                    if (displayedValue != expectedResult.winAmount) {
                        observedAccessibleValueBeforeVisualValue = true
                        throw AssertionError("Visual last-win value is not final yet.")
                    }
                }
                val renderDurationMs = SystemClock.elapsedRealtime() - spinStartedAtMs
                assertFalse(
                    "Disabled system animations must render the accessible and visual values together",
                    observedAccessibleValueBeforeVisualValue
                )
                assertTrue(
                    "Reduced-motion settlement took ${renderDurationMs}ms; expected at most " +
                        "${REDUCED_MOTION_RENDER_BUDGET_MS}ms.",
                    renderDurationMs <= REDUCED_MOTION_RENDER_BUDGET_MS
                )
            }
        }
    }

    @Test
    fun lowCoinsWaitSpinShowsImageCooldownModal() {
        seedScenario("low_wait")

        launchMain(debugSlotId = "violet_fortune").use {
            val context = ApplicationProvider.getApplicationContext<Context>()

            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")
            clickView(R.id.spinButton)

            waitForContentDescription(R.id.lowCoinsTitle, context.getString(R.string.low_coins))
            waitForContentDescription(R.id.lowCoinsBody, context.getString(R.string.low_coins_wait_body))
            waitForContentDescription(R.id.actionButton, context.getString(R.string.ok_action))
            waitForDisplayed(R.id.lowCoinsCooldownTimerRail)

            clickView(R.id.actionButton)
            waitForContentDescription(R.id.spinButton, context.getString(R.string.spin))
        }
    }

    @Test
    fun deterministicBonusSpinShowsFreeSpinsAwardImageModal() {
        seedScenario("slot_bonus")

        launchMain(debugSlotId = "violet_fortune").use {
            val context = ApplicationProvider.getApplicationContext<Context>()

            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")
            clickView(R.id.spinButton)

            waitForContentDescription(
                R.id.resultTitle,
                context.getString(R.string.result_bonus_title),
                SPIN_RESULT_WAIT_TIMEOUT_MS
            )
            waitForContentDescription(
                R.id.resultFreeSpinsAwardGroup,
                context.getString(R.string.result_free_spins_award, 5)
            )
            waitForDisplayed(R.id.resultFreeSpinsAwardPanel)
            assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                findCurrentViewById(R.id.resultFreeSpinsAwardDigits)?.importantForAccessibility
            )
            waitUntil {
                val state = runBlocking { AppGraph.playerRepository.playerState.first() }
                assertEquals(5, state.freeSpinsForSlot("violet_fortune"))
            }

            clickViewWithoutRenderIdle(R.id.closeButton)
            waitForContentDescriptionPrefix(
                R.id.freeSpinsRail,
                context.dynamicCountPrefix(R.string.free_spins_remaining)
            )
            waitForContentDescription(R.id.spinButton, context.getString(R.string.spin_free_spins))
            waitForContentDescriptionPrefix(
                R.id.autoSpinButton,
                context.dynamicCountPrefix(R.string.auto_spin_stop_free_spins)
            )
        }
    }

    @Test
    fun recreatingActivityDuringPaidSpinSettlesExactlyOnce() {
        seedScenario("slot_bonus")
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.persistSlotEngineOverrideForDebug(context, RECREATE_BONUS_STOPS)
        val config = AppGraph.slotRepository.getSlot(RECREATE_SLOT_ID)
        val expectedResult = SlotEngine().evaluate(
            config = config,
            reels = config.reelStrips.mapIndexed { reelIndex, strip ->
                List(config.rows) { row ->
                    strip[(RECREATE_BONUS_STOPS[reelIndex] + row) % strip.size]
                }
            },
            bet = RECREATE_LINE_BET,
            lines = RECREATE_LINES,
            stopIndexes = RECREATE_BONUS_STOPS.toList()
        )
        val expectedBalance = RECREATE_STARTING_BALANCE - expectedResult.totalBet + expectedResult.winAmount

        launchMain(debugSlotId = RECREATE_SLOT_ID).use {
            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")
            assertEquals(
                RECREATE_STARTING_BALANCE,
                runBlocking { AppGraph.playerRepository.playerState.first() }.coinsBalance
            )
            clickViewWithoutRenderIdle(R.id.spinButton)
            waitForContentDescription(
                R.id.spinButton,
                context.getString(R.string.spin_slam_stop)
            )
            waitForDisabled(R.id.paytableButton)

            recreateCurrentActivityWithoutRenderIdle()

            waitForContentDescription(R.id.slotTitle, "Фиолетовая Фортуна")
            waitForContentDescription(
                R.id.resultTitle,
                context.getString(R.string.result_bonus_title),
                SPIN_RESULT_WAIT_TIMEOUT_MS
            )
            waitForContentDescription(
                R.id.resultFreeSpinsAwardGroup,
                context.getString(R.string.result_free_spins_award, expectedResult.freeSpinsAwarded)
            )
            clickViewWithoutRenderIdle(R.id.closeButton)
            waitForContentDescriptionPrefix(
                R.id.autoSpinButton,
                context.dynamicCountPrefix(R.string.auto_spin_stop_free_spins)
            )
            waitForEnabled(R.id.autoSpinButton)
            clickViewWithoutRenderIdle(R.id.autoSpinButton)
            waitForEnabled(R.id.paytableButton)
            waitUntil {
                val state = runBlocking { AppGraph.playerRepository.playerState.first() }
                assertFalse(state.shouldAutoPlayFreeSpinsForSlot(RECREATE_SLOT_ID))
            }

            val settledState = runBlocking { AppGraph.playerRepository.playerState.first() }
            assertEquals(expectedBalance, settledState.coinsBalance)
            assertEquals(expectedResult.freeSpinsAwarded, settledState.freeSpinsForSlot(RECREATE_SLOT_ID))
            assertFalse(
                runBlocking {
                    AppGraph.playerRepository.recoverPendingSpinSettlement("activity-recreation-verifier")
                }
            )
            assertEquals(
                settledState,
                runBlocking { AppGraph.playerRepository.playerState.first() }
            )
        }
    }

    @Test
    fun pushPrePromptBackReportsDeferredExactlyOnce() {
        seedScenario("daily_wait")
        val results = Collections.synchronizedList(mutableListOf<Boolean>())

        launchMain().use { scenario ->
            waitForDisplayed(R.id.violetCard)
            scenario.onActivity { activity ->
                activity.supportFragmentManager.setFragmentResultListener(
                    PushPermissionDialogFragment.REQUEST_KEY,
                    activity
                ) { _, bundle ->
                    results += bundle.getBoolean(PushPermissionDialogFragment.KEY_ACCEPTED)
                }
                PushPermissionDialogFragment().show(
                    activity.supportFragmentManager,
                    "instrumented_push_pre_prompt"
                )
            }

            waitForDisplayed(R.id.maybeLaterButton)
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager
                    .findFragmentByTag("instrumented_push_pre_prompt")
                    as? PushPermissionDialogFragment
                    ?: throw AssertionError("Push pre-prompt dialog is missing.")
                @Suppress("DEPRECATION")
                dialog.requireDialog().onBackPressed()
            }
            waitUntil {
                assertEquals(listOf(false), results.toList())
            }
            SystemClock.sleep(VIEW_ACTION_SETTLE_MS)
            assertEquals(listOf(false), results.toList())
        }
    }

    private fun launchMain(debugSlotId: String? = null): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            debugSlotId?.let { putExtra("qa_open_slot", it) }
        }
        return ActivityScenario.launch(intent)
    }

    private fun seedScenario(scenario: String, levelXp: Int = 0) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageName = context.packageName
        val completed = CountDownLatch(1)
        var seedResultCode = Activity.RESULT_CANCELED
        val intent = Intent("com.vslot.app.debug.QA_STATE")
            .setClassName(packageName, "com.vslot.app.debug.QaStateReceiver")
            .putExtra("scenario", scenario)
            .putExtra("level_xp", levelXp)
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    seedResultCode = resultCode
                    completed.countDown()
                }
            },
            null,
            Activity.RESULT_OK,
            null,
            null
        )
        if (!completed.await(QA_STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw AssertionError("Timed out seeding QA scenario $scenario.")
        }
        if (seedResultCode != Activity.RESULT_OK) {
            throw AssertionError("QA scenario $scenario was rejected with result code $seedResultCode.")
        }
        if (scenario == "first_launch") {
            runBlocking {
                withTimeout(QA_STATE_TIMEOUT_MS) {
                    AppGraph.playerRepository.playerState.first { state ->
                        !state.disclaimerAccepted
                    }
                }
            }
        }
    }

    private fun scrollHomeCardIntoView(@IdRes cardId: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val scrollView = findCurrentViewByIdOnMain(R.id.homeSlotHorizontalScrollView)
                    as? HorizontalScrollView
                    ?: throw AssertionError("Home slot scroller is missing.")
                val card = findCurrentViewByIdOnMain(cardId)
                    ?: throw AssertionError("Home slot card $cardId is missing.")
                val content = scrollView.getChildAt(0)
                    ?: throw AssertionError("Home slot scroller has no content.")
                val maxScrollX = (content.width - scrollView.width).coerceAtLeast(0)
                val centeredScrollX = card.left - (scrollView.width - card.width) / 2
                scrollView.scrollTo(centeredScrollX.coerceIn(0, maxScrollX), 0)
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        SystemClock.sleep(VIEW_ACTION_SETTLE_MS)
    }

    private fun scrollViewIntoView(@IdRes viewId: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val view = findCurrentViewByIdOnMain(viewId)
                    ?: throw AssertionError("View $viewId is missing.")
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        SystemClock.sleep(VIEW_ACTION_SETTLE_MS)
    }

    private fun waitForDisplayed(@IdRes viewId: Int) {
        waitUntil {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            if (!view.isEffectivelyDisplayed()) {
                throw AssertionError("View $viewId is not displayed.")
            }
        }
    }

    private fun waitForPresent(@IdRes viewId: Int) {
        waitUntil {
            if (findCurrentViewById(viewId) == null) {
                throw AssertionError("View $viewId is missing.")
            }
        }
    }

    private fun waitForEnabled(@IdRes viewId: Int) {
        waitUntil {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            if (!view.isEnabled) {
                throw AssertionError("View $viewId is disabled.")
            }
        }
    }

    private fun waitForDisabled(@IdRes viewId: Int) {
        waitUntil {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            if (view.isEnabled) {
                throw AssertionError("View $viewId is enabled.")
            }
        }
    }

    private fun waitForSelected(@IdRes viewId: Int, selected: Boolean) {
        waitUntil {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            if (view.isSelected != selected) {
                throw AssertionError("View $viewId selected=${view.isSelected}, expected $selected.")
            }
        }
    }

    private fun waitForContentDescription(
        @IdRes viewId: Int,
        description: String,
        timeoutMs: Long = VIEW_WAIT_TIMEOUT_MS
    ) {
        waitUntil(timeoutMs = timeoutMs) {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            val actual = view.contentDescription?.toString()
            if (actual != description) {
                throw AssertionError("View $viewId contentDescription was \"$actual\", expected \"$description\".")
            }
        }
    }

    private fun waitForContentDescriptionPrefix(
        @IdRes viewId: Int,
        prefix: String,
        timeoutMs: Long = VIEW_WAIT_TIMEOUT_MS
    ) {
        waitUntil(timeoutMs = timeoutMs) {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            val actual = view.contentDescription?.toString().orEmpty()
            if (!actual.startsWith(prefix)) {
                throw AssertionError(
                    "View $viewId contentDescription was \"$actual\", expected prefix \"$prefix\"."
                )
            }
        }
    }

    private fun startPaidAutoSpin(context: Context) {
        waitForContentDescription(
            R.id.autoSpinButton,
            context.getString(R.string.auto_spin_configure)
        )
        clickView(R.id.autoSpinButton)
        waitForContentDescription(
            R.id.optionButton,
            context.getString(R.string.auto_spin_count_action, DEFAULT_AUTO_SPIN_COUNT)
        )
        clickView(R.id.optionButton)
        waitForContentDescriptionPrefix(
            R.id.autoSpinButton,
            context.dynamicCountPrefix(R.string.auto_spin_stop_remaining)
        )
    }

    private fun Context.dynamicCountPrefix(stringRes: Int): String {
        val marker = 987_654
        return getString(stringRes, marker).substringBefore(marker.toString())
    }

    private fun waitForPrivacyState() {
        if (BuildConfig.PRIVACY_POLICY_URL.isBlank()) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            waitForContentDescription(R.id.errorImage, context.getString(R.string.privacy_not_configured))
        } else {
            waitUntil {
                val view = findCurrentViewById(R.id.privacyWebView)
                    ?: throw AssertionError("Privacy WebView is missing.")
                if (!view.isEffectivelyDisplayed()) {
                    throw AssertionError("Privacy WebView is not displayed.")
                }
            }
        }
    }

    private fun waitUntil(timeoutMs: Long = VIEW_WAIT_TIMEOUT_MS, assertion: () -> Unit) {
        val timeoutAt = SystemClock.elapsedRealtime() + timeoutMs
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < timeoutAt) {
            try {
                assertion()
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                SystemClock.sleep(VIEW_WAIT_STEP_MS)
            }
        }
        throw AssertionError("Timed out waiting for view assertion.", lastFailure)
    }

    private fun clickView(@IdRes viewId: Int) {
        waitUntil {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            if (!view.isEffectivelyDisplayed()) {
                throw AssertionError("View $viewId is not displayed.")
            }
            if (!view.isEnabled) {
                throw AssertionError("View $viewId is disabled.")
            }
            tapViewWithPointerInput(viewId)
        }
        SystemClock.sleep(VIEW_ACTION_SETTLE_MS)
    }

    private fun clickViewWithoutRenderIdle(@IdRes viewId: Int) {
        waitUntil {
            val view = findCurrentViewById(viewId)
                ?: throw AssertionError("View $viewId is missing.")
            if (!view.isEffectivelyDisplayed()) {
                throw AssertionError("View $viewId is not displayed.")
            }
            if (!view.isEnabled) {
                throw AssertionError("View $viewId is disabled.")
            }
            tapViewWithPointerInput(viewId, waitForRenderIdle = false)
        }
    }

    private fun recreateCurrentActivityWithoutRenderIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var originalActivity: FragmentActivity? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                originalActivity = ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FragmentActivity>()
                    .lastOrNull()
                    ?: throw AssertionError("No resumed activity is available for recreation.")
                originalActivity?.recreate()
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        failure?.let { throw it }
        waitUntil {
            var recreatedActivity: FragmentActivity? = null
            instrumentation.runOnMainSync {
                recreatedActivity = ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FragmentActivity>()
                    .lastOrNull()
            }
            if (recreatedActivity == null || recreatedActivity === originalActivity) {
                throw AssertionError("Recreated activity has not resumed yet.")
            }
        }
    }

    private fun backgroundCurrentActivityWithoutRenderIdle(): FragmentActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var backgroundedActivity: FragmentActivity? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                backgroundedActivity = ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FragmentActivity>()
                    .lastOrNull()
                    ?: throw AssertionError("No resumed activity is available for backgrounding.")
                if (backgroundedActivity?.moveTaskToBack(true) != true) {
                    throw AssertionError("Activity task could not be moved to the background.")
                }
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        failure?.let { throw it }
        val activity = backgroundedActivity
            ?: throw AssertionError("Backgrounded activity is missing.")
        waitUntil {
            var isStopped = false
            instrumentation.runOnMainSync {
                isStopped = ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.STOPPED)
                    .contains(activity)
            }
            if (!isStopped) {
                throw AssertionError("Backgrounded activity has not stopped yet.")
            }
        }
        return activity
    }

    private fun foregroundMainActivityWithoutRenderIdle(expectedActivity: FragmentActivity) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        waitUntil {
            var resumedActivity: FragmentActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                resumedActivity = ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FragmentActivity>()
                    .lastOrNull()
            }
            if (resumedActivity !== expectedActivity) {
                throw AssertionError("Backgrounded activity has not returned to the foreground.")
            }
        }
    }

    private fun clickViewIfStillDisplayed(@IdRes viewId: Int) {
        val view = findCurrentViewById(viewId)
        if (view?.isEffectivelyDisplayed() != true) return
        try {
            clickView(viewId)
        } catch (failure: Throwable) {
            if (findCurrentViewById(viewId)?.isEffectivelyDisplayed() == true) throw failure
        }
    }

    private fun tapViewWithPointerInput(
        @IdRes viewId: Int,
        waitForRenderIdle: Boolean = true
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val visibleBounds = Rect()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val view = findCurrentViewByIdOnMain(viewId)
                    ?: throw AssertionError("View $viewId is missing.")
                val localVisibleBounds = Rect()
                if (!view.getLocalVisibleRect(localVisibleBounds) || localVisibleBounds.isEmpty) {
                    throw AssertionError("View $viewId has no visible bounds.")
                }
                val locationOnScreen = IntArray(2)
                view.getLocationOnScreen(locationOnScreen)
                visibleBounds.set(
                    locationOnScreen[0] + localVisibleBounds.left,
                    locationOnScreen[1] + localVisibleBounds.top,
                    locationOnScreen[0] + localVisibleBounds.right,
                    locationOnScreen[1] + localVisibleBounds.bottom
                )
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        failure?.let { throw it }

        val x = visibleBounds.exactCenterX()
        val y = visibleBounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        downEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.sendPointerSync(downEvent)
        } finally {
            downEvent.recycle()
        }
        SystemClock.sleep(POINTER_TAP_DURATION_MS)
        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
        upEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.sendPointerSync(upEvent)
        } finally {
            upEvent.recycle()
        }
        if (waitForRenderIdle) {
            instrumentation.waitForIdleSync()
        }
    }

    private fun findCurrentViewById(@IdRes viewId: Int): View? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var view: View? = null
        instrumentation.runOnMainSync {
            view = findCurrentViewByIdOnMain(viewId)
        }
        return view
    }

    private fun launchQaDialog(dialog: String): ActivityScenario<Activity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent()
            .setClassName(context.packageName, QA_DIALOG_ACTIVITY)
            .putExtra(QA_DIALOG_EXTRA, dialog)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    private fun assertLargeFontQaDialog(
        dialog: String,
        @IdRes bitmapId: Int,
        @IdRes textId: Int,
        expectedText: String,
        @IdRes actionId: Int,
        screenshotPrefix: String? = null
    ) {
        launchQaDialog(dialog).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitForPortrait()
            waitForPresent(textId)
            assertLargeFontCopy(bitmapId, textId, expectedText)
            scrollViewIntoView(actionId)
            waitForDisplayed(actionId)
            assertViewFullyVisible(actionId)
            screenshotPrefix?.let { captureLayoutMatrixScreenshot("$it-portrait.png") }
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForLandscape()
            waitForPresent(textId)
            assertLargeFontCopy(bitmapId, textId, expectedText)
            scrollViewIntoView(actionId)
            waitForDisplayed(actionId)
            assertViewFullyVisible(actionId)
            screenshotPrefix?.let { captureLayoutMatrixScreenshot("$it-landscape.png") }
            clickView(actionId)
        }
    }

    private fun assertLargeFontCopy(
        @IdRes bitmapId: Int,
        @IdRes textId: Int,
        expectedText: String
    ) {
        waitUntil {
            assertLargeFontCopyNow(bitmapId, textId, expectedText)
        }
    }

    private fun assertLargeFontCopyNow(
        @IdRes bitmapId: Int,
        @IdRes textId: Int,
        expectedText: String
    ) {
        assertTextFullyLaidOut(textId, expectedText)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val bitmap = findCurrentViewByIdOnMain(bitmapId)
                    ?: throw AssertionError("Bitmap copy $bitmapId is missing.")
                assertEquals(View.GONE, bitmap.visibility)
                val scalableText = findCurrentViewByIdOnMain(textId)
                    ?: throw AssertionError("Scalable copy $textId is missing.")
                assertEquals(View.VISIBLE, scalableText.visibility)
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun waitForLandscape() {
        waitUntil {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var orientation = Configuration.ORIENTATION_UNDEFINED
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .lastOrNull()
                    ?: throw AssertionError("No resumed activity after orientation request.")
                orientation = activity.resources.configuration.orientation
            }
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, orientation)
        }
    }

    private fun waitForPortrait() {
        waitUntil {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var orientation = Configuration.ORIENTATION_UNDEFINED
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .lastOrNull()
                    ?: throw AssertionError("No resumed activity after orientation request.")
                orientation = activity.resources.configuration.orientation
            }
            assertEquals(Configuration.ORIENTATION_PORTRAIT, orientation)
        }
    }

    private fun displayedBitmapNumber(@IdRes viewId: Int): Int {
        val view = findCurrentViewById(viewId) as? BitmapNumberView
            ?: throw AssertionError("Bitmap number view $viewId is missing.")
        return view.displayedCharacters
            .filter(Char::isDigit)
            .toIntOrNull()
            ?: 0
    }

    private fun assertViewsDoNotOverlap(@IdRes firstViewId: Int, @IdRes secondViewId: Int) {
        val first = Rect()
        val second = Rect()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val firstView = findCurrentViewByIdOnMain(firstViewId)
                    ?: throw AssertionError("View $firstViewId is missing.")
                val secondView = findCurrentViewByIdOnMain(secondViewId)
                    ?: throw AssertionError("View $secondViewId is missing.")
                if (!firstView.getGlobalVisibleRect(first) || !secondView.getGlobalVisibleRect(second)) {
                    throw AssertionError("Views $firstViewId and $secondViewId must both be visible.")
                }
                if (Rect.intersects(first, second)) {
                    throw AssertionError(
                        "Views $firstViewId $first and $secondViewId $second overlap."
                    )
                }
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        failure?.let { throw it }
    }

    private fun assertViewFullyVisible(@IdRes viewId: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val view = findCurrentViewByIdOnMain(viewId)
                    ?: throw AssertionError("View $viewId is missing.")
                val visibleRect = Rect()
                if (!view.getGlobalVisibleRect(visibleRect)) {
                    throw AssertionError("View $viewId has no visible area.")
                }
                val transformedBounds = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                val globalMatrix = Matrix()
                view.transformMatrixToGlobal(globalMatrix)
                globalMatrix.mapRect(transformedBounds)
                val tolerancePx = 2f
                val requiredVisibleWidth = minOf(view.width.toFloat(), transformedBounds.width())
                val requiredVisibleHeight = minOf(view.height.toFloat(), transformedBounds.height())
                val isClipped =
                    visibleRect.width() + tolerancePx < requiredVisibleWidth ||
                        visibleRect.height() + tolerancePx < requiredVisibleHeight
                if (isClipped) {
                    throw AssertionError(
                        "View $viewId is clipped: visible=$visibleRect " +
                            "visibleSize=${visibleRect.width()}x${visibleRect.height()} " +
                            "transformedBounds=$transformedBounds " +
                            "measuredSize=${view.width}x${view.height}."
                    )
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun isStoreScreenshotCaptureEnabled(): Boolean {
        return InstrumentationRegistry.getArguments()
            .getString(STORE_SCREENSHOT_ARGUMENT)
            .toBoolean()
    }

    private fun isLayoutMatrixCaptureEnabled(): Boolean {
        return InstrumentationRegistry.getArguments()
            .getString(LAYOUT_MATRIX_SCREENSHOT_ARGUMENT)
            .toBoolean()
    }

    private fun captureStoreScreenshot(fileName: String) {
        if (!isStoreScreenshotCaptureEnabled()) return
        captureScreenshot(fileName, STORE_SCREENSHOT_DIRECTORY)
    }

    private fun captureLayoutMatrixScreenshot(fileName: String) {
        if (!isLayoutMatrixCaptureEnabled()) return
        captureScreenshot(fileName, LAYOUT_MATRIX_SCREENSHOT_DIRECTORY)
    }

    private fun captureScreenshot(fileName: String, directory: String) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "QA screenshot capture requires Android 10 or newer."
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(STORE_SCREENSHOT_SETTLE_MS)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: error("Android did not return a screenshot bitmap for $fileName.")
        val resolver = instrumentation.targetContext.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$directory/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath)
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("Unable to create MediaStore entry for $fileName.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Unable to encode $fileName as PNG."
                }
            } ?: error("Unable to open MediaStore output for $fileName.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) {
                "Unable to publish MediaStore screenshot $fileName."
            }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertTextFullyLaidOut(@IdRes viewId: Int, expectedText: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        waitUntil {
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    val textView = findCurrentViewByIdOnMain(viewId) as? TextView
                        ?: throw AssertionError("TextView $viewId is missing.")
                    assertEquals(expectedText, textView.text.toString())
                    val layout = textView.layout ?: throw AssertionError("TextView $viewId has no text layout.")
                    assertTrue("TextView $viewId must lay out at least one line.", layout.lineCount > 0)
                    repeat(layout.lineCount) { line ->
                        assertEquals("TextView $viewId ellipsized line $line.", 0, layout.getEllipsisCount(line))
                    }
                    val requiredHeight = layout.height + textView.compoundPaddingTop + textView.compoundPaddingBottom
                    assertTrue(
                        "TextView $viewId is vertically clipped: height=${textView.height}, required=$requiredHeight.",
                        textView.height >= requiredHeight
                    )
                    val availableTextWidth =
                        textView.width - textView.compoundPaddingLeft - textView.compoundPaddingRight
                    repeat(layout.lineCount) { line ->
                        val lineStart = layout.getLineStart(line)
                        val visibleEnd = layout.getLineVisibleEnd(line)
                        val visibleLineWidth = Layout.getDesiredWidth(
                            textView.text,
                            lineStart,
                            visibleEnd,
                            textView.paint
                        )
                        assertTrue(
                            "TextView $viewId line $line exceeds its content width: " +
                                "visibleLineWidth=$visibleLineWidth, available=$availableTextWidth, " +
                                "text=${textView.text.substring(lineStart, visibleEnd)}.",
                            visibleLineWidth <= availableTextWidth + TEXT_LAYOUT_TOLERANCE_PX
                        )
                    }
                    val parent = textView.parent as? View
                    if (parent != null) {
                        assertTrue(
                            "TextView $viewId extends past its parent horizontally: " +
                                "left=${textView.left}, right=${textView.right}, parentWidth=${parent.width}.",
                            textView.left >= parent.paddingLeft - TEXT_LAYOUT_TOLERANCE_PX &&
                                textView.right <= parent.width - parent.paddingRight + TEXT_LAYOUT_TOLERANCE_PX
                        )
                    }
                    val scaledTextSizeDp = textView.textSize / textView.resources.displayMetrics.density
                    val minimumRenderedTextDp = if (
                        textView.resources.configuration.fontScale >= LARGE_FONT_TEST_SCALE
                    ) {
                        LARGE_FONT_MIN_RENDERED_TEXT_DP
                    } else {
                        COMPACT_SCALED_MIN_RENDERED_TEXT_DP
                    }
                    assertTrue(
                        "TextView $viewId did not scale with the user font setting: ${scaledTextSizeDp}dp.",
                        scaledTextSizeDp >= minimumRenderedTextDp
                    )
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw it }
        }
    }

    private fun countUpResult() = AppGraph.slotRepository.getSlot(COUNT_UP_SLOT_ID).let { config ->
        SlotEngine().evaluate(
            config = config,
            reels = config.reelStrips.mapIndexed { reelIndex, strip ->
                List(config.rows) { row ->
                    strip[(COUNT_UP_STOPS[reelIndex] + row) % strip.size]
                }
            },
            bet = COUNT_UP_LINE_BET,
            lines = COUNT_UP_LINES,
            stopIndexes = COUNT_UP_STOPS.toList()
        )
    }

    private fun findNoWinStops(slotId: String, lineBet: Int, lines: Int): IntArray {
        val config = AppGraph.slotRepository.getSlot(slotId)
        val random = Random(slotId.hashCode())
        repeat(NO_WIN_SEARCH_ATTEMPTS) {
            val stops = IntArray(config.reelStrips.size) { reelIndex ->
                random.nextInt(config.reelStrips[reelIndex].size)
            }
            val result = SlotEngine().evaluate(
                config = config,
                reels = config.reelStrips.mapIndexed { reelIndex, strip ->
                    List(config.rows) { row ->
                        strip[(stops[reelIndex] + row) % strip.size]
                    }
                },
                bet = lineBet,
                lines = lines,
                stopIndexes = stops.toList()
            )
            if (result.winAmount == 0 && result.freeSpinsAwarded == 0) return stops
        }
        throw AssertionError("Could not find a deterministic losing window for slot $slotId.")
    }

    private fun withAnimatorDurationScale(scale: Float, block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        setAnimatorDurationScale(scale)
        try {
            block()
        } finally {
            setAnimatorDurationScale(originalScale)
        }
    }

    private fun setAnimatorDurationScale(scale: Float) {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("settings put global animator_duration_scale $scale")
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        waitUntil {
            val context = ApplicationProvider.getApplicationContext<Context>()
            var processScale = Float.NaN
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                processScale = ValueAnimator.getDurationScale()
            }
            assertEquals(
                scale.toDouble(),
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ).toDouble(),
                0.001
            )
            assertEquals(scale.toDouble(), processScale.toDouble(), 0.001)
        }
    }

    private fun findCurrentViewByIdOnMain(@IdRes viewId: Int): View? {
        val activity = ActivityLifecycleMonitorRegistry
            .getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<FragmentActivity>()
            .lastOrNull()
            ?: return null
        return findViewInFragmentTree(activity.supportFragmentManager, viewId)
            ?: activity.findViewById(viewId)
    }

    private fun findViewInFragmentTree(fragmentManager: FragmentManager, @IdRes viewId: Int): View? {
        fragmentManager.fragments.asReversed().forEach { fragment ->
            if (fragment is DialogFragment) {
                fragment.dialog?.findViewById<View>(viewId)?.let { return it }
            }
            fragment.view?.findViewById<View>(viewId)?.let { return it }
            findViewInFragmentTree(fragment.childFragmentManager, viewId)?.let { return it }
        }
        return null
    }

    private fun View.isEffectivelyDisplayed(): Boolean {
        return isShown && width > 0 && height > 0
    }

    private companion object {
        const val QA_STATE_TIMEOUT_MS = 5_000L
        const val HOME_ALL_SLOTS_LEVEL = 4
        const val FEATURE_RESUME_SLOT_ID = "violet_fortune"
        const val NO_WIN_SEARCH_ATTEMPTS = 10_000
        const val VIEW_WAIT_TIMEOUT_MS = 7_000L
        const val SPIN_RESULT_WAIT_TIMEOUT_MS = 14_000L
        const val REDUCED_MOTION_RENDER_BUDGET_MS = 2_000L
        const val DEFAULT_AUTO_SPIN_COUNT = 10
        const val COMPACT_LANDSCAPE_MAX_WIDTH_DP = 600
        const val COMPACT_PORTRAIT_MAX_HEIGHT_DP = 700
        const val LARGE_FONT_TEST_SCALE = 1.8f
        const val LARGE_FONT_MIN_RENDERED_TEXT_DP = 20f
        const val STORE_SCREENSHOT_ARGUMENT = "capture_store_screenshots"
        const val STORE_SCREENSHOT_DIRECTORY = "VSlotStore"
        const val LAYOUT_MATRIX_SCREENSHOT_ARGUMENT = "capture_layout_matrix"
        const val LAYOUT_MATRIX_SCREENSHOT_DIRECTORY = "VSlotLayoutMatrix"
        const val COMPACT_SCALED_MAX_WIDTH_DP = 360
        const val COMPACT_SCALED_MIN_FONT_SCALE = 1.3f
        const val COMPACT_SCALED_MIN_RENDERED_TEXT_DP = 15f
        const val TEXT_LAYOUT_TOLERANCE_PX = 2f
        const val STORE_SCREENSHOT_SETTLE_MS = 700L
        const val STORE_RESULT_SCREENSHOT_SETTLE_MS = 1_200L
        const val QA_DIALOG_ACTIVITY = "com.vslot.app.debug.QaResultDialogActivity"
        const val QA_DIALOG_EXTRA = "dialog"
        const val QA_DIALOG_DAILY_BONUS = "daily_bonus"
        const val QA_DIALOG_DAILY_WAIT = "daily_wait"
        const val QA_DIALOG_LOW_BONUS = "low_bonus"
        const val QA_DIALOG_LOW_WAIT = "low_wait"
        const val QA_DIALOG_PUSH = "push"
        const val QA_DIALOG_ANALYTICS = "analytics"
        const val QA_DIALOG_RESULT = "result"
        const val VIEW_WAIT_STEP_MS = 120L
        const val VIEW_ACTION_SETTLE_MS = 120L
        const val POINTER_TAP_DURATION_MS = 50L
        const val BACKGROUND_SETTLE_MS = 600L
        const val RECREATE_SLOT_ID = "violet_fortune"
        const val RECREATE_LINE_BET = 25
        const val RECREATE_LINES = 10
        const val RECREATE_STARTING_BALANCE = 10_000L
        val RECREATE_BONUS_STOPS = intArrayOf(0, 0, 17, 20, 15)
        const val COUNT_UP_SLOT_ID = "violet_fortune"
        const val COUNT_UP_LINE_BET = 25
        const val COUNT_UP_LINES = 10
        const val COUNT_UP_STARTING_BALANCE = 10_000L
        val COUNT_UP_STOPS = intArrayOf(0, 5, 11, 1, 0)
    }
}
