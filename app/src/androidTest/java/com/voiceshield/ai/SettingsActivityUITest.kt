package com.voiceshield.ai

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Page 2 (Settings).
 *
 * The scan mode written here is what FloatingBubbleService.armScanTimer()
 * reads, so these tests are the contract between the two screens.
 */
@RunWith(AndroidJUnit4::class)
class SettingsActivityUITest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The mode is persisted in SharedPreferences, which outlives a single test.
     * Pin it to SAFE on both sides so test order cannot change the outcome.
     */
    @Before
    fun resetMode() {
        ProtectionState.setMode(context, ScanMode.SAFE)
    }

    @After
    fun restoreMode() {
        ProtectionState.setMode(context, ScanMode.SAFE)
    }

    @Test
    fun radioOptions_areDisplayed() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.settings_title)).check(matches(isDisplayed()))
            onView(withId(R.id.mode_group)).check(matches(isDisplayed()))
            onView(withId(R.id.mode_safe)).check(matches(isDisplayed()))
            onView(withId(R.id.mode_warning)).check(matches(isDisplayed()))
            onView(withId(R.id.btn_save_config)).check(matches(isDisplayed()))
            onView(withId(R.id.btn_back_home)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun defaultSelection_isSafeMode() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.mode_safe)).check(matches(isChecked()))
            onView(withId(R.id.mode_warning)).check(matches(isNotChecked()))
        }
        assertEquals(ScanMode.SAFE, ProtectionState.getMode(context))
    }

    @Test
    fun selectingWarningAndSaving_persists() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.mode_warning)).perform(click())
            onView(withId(R.id.mode_warning)).check(matches(isChecked()))
            // saveConfig() persists and then finishes the activity.
            onView(withId(R.id.btn_save_config)).perform(click())
        }
        assertEquals(ScanMode.WARNING, ProtectionState.getMode(context))
    }

    @Test
    fun savedMode_isPreselectedOnReopen() {
        ProtectionState.setMode(context, ScanMode.WARNING)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.mode_warning)).check(matches(isChecked()))
            onView(withId(R.id.mode_safe)).check(matches(isNotChecked()))
        }
    }

    @Test
    fun backHomeButton_finishesActivity() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            onView(withId(R.id.btn_back_home)).perform(click())
            awaitState(scenario, Lifecycle.State.DESTROYED)
        }
    }

    /**
     * developer_setting_page.md §6: the page is locked *only* while a detection session runs.
     * No bubble service exists in these tests, so this pins the unlocked half — every control is
     * editable, "Về trang chủ" stays reachable, and the mode shown is the selected one rather
     * than a session's. The locked half needs a live scan and is covered by the manual
     * verification steps, plus `ScanModeInteractionUnitTest` for the rule itself
     * ([scanModePageState]) and `DesignTokenResourceTest` for the disabled *look* — which is the
     * half this page gets wrong when the lock only disables without repainting.
     */
    @Test
    fun pageIsUnlockedWhenNoSessionIsRunning() {
        assertFalse(FloatingBubbleService.isScanSessionActive)
        assertNull(FloatingBubbleService.activeSessionMode)
        val selected = ProtectionState.getMode(context)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.mode_group)).check(matches(isEnabled()))
            onView(withId(R.id.mode_safe)).check(matches(isEnabled()))
            onView(withId(R.id.mode_warning)).check(matches(isEnabled()))
            onView(withId(R.id.btn_save_config)).check(matches(isEnabled()))
            // The lock may never trap the user on the page.
            onView(withId(R.id.btn_back_home)).check(matches(isEnabled()))
            val expectedId = if (selected == ScanMode.SAFE) R.id.mode_safe else R.id.mode_warning
            onView(withId(expectedId)).check(matches(isChecked()))
        }
        assertEquals(
            selected,
            scanModePageState(
                sessionActive = FloatingBubbleService.isScanSessionActive,
                sessionMode = FloatingBubbleService.activeSessionMode,
                selectedMode = selected
            ).displayedMode
        )
    }

    /** Polls briefly: finish() destroys the activity on a later main-loop pass. */
    private fun awaitState(scenario: ActivityScenario<*>, expected: Lifecycle.State) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(100) {
            if (scenario.state == expected) return
            instrumentation.waitForIdleSync()
            Thread.sleep(20)
        }
        assertEquals(expected, scenario.state)
    }
}
