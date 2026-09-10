package com.voiceshield.ai

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Instrumented tests for Page 1 (Home) and Page 2 (Settings).
 * They pin the redesign: one status card with the CTA inside it, a version
 * footer instead of bottom navigation, and no protection-level chips.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    @Test
    fun activity_launches() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.btn_start_protection)).check(matches(isDisplayed()))
        scenario.close()
    }

    @Test
    fun protectionButton_isDisplayed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.btn_start_protection)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_start_protection)).check(matches(withText("Kích hoạt ứng dụng")))
        scenario.close()
    }

    @Test
    fun statusBadge_isDisplayed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.status_badge)).check(matches(isDisplayed()))
        onView(withId(R.id.protection_status)).check(matches(isDisplayed()))
        onView(withId(R.id.protection_status)).check(matches(withText("Chưa kích hoạt")))
        scenario.close()
    }

    @Test
    fun statusIcon_isDisplayed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.status_icon)).check(matches(isDisplayed()))
        scenario.close()
    }

    @Test
    fun footerVersion_isDisplayed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.footer_version)).check(matches(isDisplayed()))
        onView(withId(R.id.footer_version)).check(matches(withText("Version 1.0.1")))
        scenario.close()
    }

    @Test
    fun settingsScreen_defaultsToSafeMode() {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        onView(withId(R.id.settings_title)).check(matches(isDisplayed()))
        onView(withId(R.id.mode_safe)).check(matches(isDisplayed()))
        onView(withId(R.id.mode_warning)).check(matches(isDisplayed()))
        onView(withId(R.id.mode_safe)).check(matches(isChecked()))
        onView(withId(R.id.btn_save_config)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_back_home)).check(matches(isDisplayed()))
        scenario.close()
    }

    @Test
    fun appContext_isCorrect() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.voiceshield.ai", appContext.packageName)
    }
}
