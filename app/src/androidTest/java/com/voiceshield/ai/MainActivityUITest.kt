package com.voiceshield.ai

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
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
     */
    @Test
    fun footerVersion_opensSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.footer_version)).perform(click())
            onView(withId(R.id.settings_title)).check(matches(isDisplayed()))
            onView(withId(R.id.mode_safe)).check(matches(isDisplayed()))
            pressBack()
        }
    }

    @Test
    fun appContext_isCorrect() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.voiceshield.ai", appContext.packageName)
    }
}

