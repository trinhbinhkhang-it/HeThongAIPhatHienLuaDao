package com.voiceshield.ai

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import android.os.SystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Instrumented tests for Page 1 (Home).
 * They pin the redesign: one status card with the CTA inside it, a version
 * footer instead of bottom navigation, and no protection-level chips.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    /**
     * Every test asserts on the inactive/SAFE starting state, so reset the
     * persisted flags first — SettingsActivityUITest writes WARNING to the
     * same SharedPreferences file and test order is not guaranteed.
     */
    @Before
    fun resetProtectionState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProtectionState.setActive(context, false)
        ProtectionState.setMode(context, ScanMode.SAFE)
        // developer_setting_page.md §1–§3/§7 decide which footer gesture the assertions below
        // are exercising, so they are pinned to their defaults too rather than left to
        // whatever a previous run happened to save.
        ProtectionState.resetScanModeInteraction(context)
    }

    @Test
    fun activity_launches() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btn_start_protection)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun protectionButton_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btn_start_protection)).check(matches(isDisplayed()))
            onView(withId(R.id.btn_start_protection))
                .check(matches(withText(R.string.btn_activate_app)))
        }
    }

    @Test
    fun statusBadge_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.status_badge)).check(matches(isDisplayed()))
            onView(withId(R.id.protection_status)).check(matches(isDisplayed()))
            onView(withId(R.id.protection_status))
                .check(matches(withText(R.string.status_inactive)))
        }
    }

    @Test
    fun statusIcon_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.status_icon)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun footerVersion_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.footer_version)).check(matches(isDisplayed()))
            onView(withId(R.id.footer_version))
                .check(matches(withText(R.string.footer_version)))
        }
    }

    /**
     * The footer replaced the bottom nav as the entry point to Page 2.
     * espresso-intents is not in the offline dependency cache, so this asserts
     * on the destination screen's own views rather than intended(hasComponent()).
     *
     * developer_setting_page.md §1 makes single click the default, and a lone click *defers*
     * its navigation to the end of [TRIPLE_CLICK_WINDOW_MS] so that taps 2 and 3 can still
     * reach the footer for the hidden developer page. Espresso treats a delayed message as an
     * idle looper, so the wait has to be explicit here.
     */
    @Test
    fun footerVersion_opensSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.footer_version)).perform(click())
            SystemClock.sleep(TRIPLE_CLICK_WINDOW_MS + 500L)
            onView(withId(R.id.settings_title)).check(matches(isDisplayed()))
            onView(withId(R.id.mode_safe)).check(matches(isDisplayed()))
            pressBack()
        }
    }

    /**
     * The deferral above is not cosmetic: three taps inside the burst must still reach the
     * hidden developer page, and the pending single-click navigation must be dropped.
     */
    @Test
    fun footerVersion_tripleTapStillOpensTheHiddenPage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(TRIPLE_CLICK_COUNT) {
                onView(withId(R.id.footer_version)).perform(click())
            }
            SystemClock.sleep(TRIPLE_CLICK_WINDOW_MS + 500L)
            onView(withId(R.id.hidden_settings_title)).check(matches(isDisplayed()))
            // The four developer_setting_page.md groups live further down the same scroll view,
            // so each has to be scrolled to before it can be "displayed".
            onView(withId(R.id.group_scan_mode_entry)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.group_hold_duration)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.group_auto_mode_selection)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.group_mode_feedback)).perform(scrollTo())
                .check(matches(isDisplayed()))
            pressBack()
        }
    }

    /**
     * The reported lockout, end to end.
     *
     * The hidden page is the only place §1 can be changed, so a triple tap must reach it under
     * *every* §1 method. Before the fix, a quick tap under a hold-based method resolved to
     * `FooterAction.NONE`, `performClick()` never ran, `versionTapCount` never moved off zero and
     * the page — together with the very setting that caused the problem — was unreachable for
     * good. This loops all three methods so neither hold option can regress it again.
     */
    @Test
    fun footerVersion_tripleTapOpensTheHiddenPageUnderEveryOpeningMethod() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ScanModeEntry.values().forEach { entry ->
            ProtectionState.setScanModeInteraction(context, ScanModeInteractionSettings(entry = entry))
            ActivityScenario.launch(MainActivity::class.java).use {
                repeat(TRIPLE_CLICK_COUNT) {
                    onView(withId(R.id.footer_version)).perform(click())
                }
                SystemClock.sleep(TRIPLE_CLICK_WINDOW_MS + 500L)
                onView(withId(R.id.hidden_settings_title)).check(matches(isDisplayed()))
                pressBack()
            }
        }
        ProtectionState.resetScanModeInteraction(context)
    }

    @Test
    fun appContext_isCorrect() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.voiceshield.ai", appContext.packageName)
    }
}

