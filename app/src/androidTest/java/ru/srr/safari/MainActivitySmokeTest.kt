package ru.srr.safari

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator smoke — launch main chrome without hanging.
 * Session may restore last tab, so we only assert always-on chrome.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun launches_showsAddressChrome() {
        onView(withId(R.id.addressBar)).check(matches(isDisplayed()))
        onView(withId(R.id.bottomChrome)).check(matches(isDisplayed()))
        onView(withContentDescription("Назад")).check(matches(isDisplayed()))
        onView(withContentDescription("Ещё")).check(matches(isDisplayed()))
    }
}
