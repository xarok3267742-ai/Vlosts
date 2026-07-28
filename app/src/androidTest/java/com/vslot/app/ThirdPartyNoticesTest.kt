package com.vslot.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThirdPartyNoticesTest {
    @Test
    fun settingsOpensThirdPartyNoticesWithBundledNoticeText() {
        seedScenario("daily_wait")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundledNotices = THIRD_PARTY_NOTICES_ASSETS.joinToString("\n\n") { assetName ->
            context.assets.open(assetName)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }
        val expectedNotices =
            "${context.getString(R.string.third_party_notices_original_language)}\n\n$bundledNotices"
        assertTrue(expectedNotices.contains(NOTICES_BODY_MARKER))
        assertTrue(expectedNotices.contains(EMBEDDED_NOTICES_MARKER))

        launchMain().use {
            waitForDisplayed(withId(R.id.settingsButton))
            onView(withId(R.id.settingsButton)).perform(click())

            waitForDisplayed(withId(R.id.noticesButton))
            onView(withId(R.id.noticesButton))
                .check(matches(isDisplayed()))
                .perform(click())

            waitForDisplayed(withText(R.string.third_party_notices_title), isDialog())
            onView(withText(R.string.third_party_notices_title))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))

            val noticeBodyMatcher = allOf(
                isAssignableFrom(TextView::class.java),
                withText(containsString(NOTICES_BODY_MARKER))
            )
            waitForDisplayed(noticeBodyMatcher, isDialog())
            onView(noticeBodyMatcher)
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
                .check { view, noViewFound ->
                    noViewFound?.let { throw it }
                    assertEquals(expectedNotices, (view as TextView).text.toString())
                }
        }
    }

    private fun launchMain(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun seedScenario(scenario: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val completed = CountDownLatch(1)
        val intent = Intent(QA_STATE_ACTION)
            .setClassName(context.packageName, QA_STATE_RECEIVER)
            .putExtra(QA_SCENARIO_EXTRA, scenario)
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    completed.countDown()
                }
            },
            null,
            Activity.RESULT_OK,
            null,
            null
        )
        assertTrue(
            "Timed out seeding QA scenario $scenario.",
            completed.await(QA_STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )
    }

    private fun waitForDisplayed(viewMatcher: Matcher<View>, rootMatcher: Matcher<Root>? = null) {
        val timeoutAt = SystemClock.elapsedRealtime() + VIEW_WAIT_TIMEOUT_MS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < timeoutAt) {
            try {
                interaction(viewMatcher, rootMatcher).check(matches(isDisplayed()))
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                SystemClock.sleep(VIEW_WAIT_STEP_MS)
            }
        }
        throw AssertionError("Timed out waiting for an Espresso view assertion.", lastFailure)
    }

    private fun interaction(viewMatcher: Matcher<View>, rootMatcher: Matcher<Root>?): ViewInteraction {
        return onView(viewMatcher).let { interaction ->
            rootMatcher?.let(interaction::inRoot) ?: interaction
        }
    }

    private companion object {
        val THIRD_PARTY_NOTICES_ASSETS = listOf(
            "third_party_notices.txt",
            "third_party_embedded_licenses.txt"
        )
        const val NOTICES_BODY_MARKER = "AppMetrica Analytics SDK and AppMetrica Push SDK"
        const val EMBEDDED_NOTICES_MARKER = "schema=release-runtime-embedded-licenses-v1"
        const val QA_STATE_ACTION = "com.vslot.app.debug.QA_STATE"
        const val QA_STATE_RECEIVER = "com.vslot.app.debug.QaStateReceiver"
        const val QA_SCENARIO_EXTRA = "scenario"
        const val QA_STATE_TIMEOUT_MS = 5_000L
        const val VIEW_WAIT_TIMEOUT_MS = 7_000L
        const val VIEW_WAIT_STEP_MS = 120L
    }
}
