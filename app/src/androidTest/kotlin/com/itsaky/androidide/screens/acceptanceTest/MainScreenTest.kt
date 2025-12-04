package com.itsaky.androidide.screens.acceptanceTest

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.adapters.MainActionsListAdapter
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.core.Is.`is`
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    /**
     * UI Test class to verify the initial startup dialog behavior.
     *
     * NOTE: This test assumes the main application activity is MainActivity,
     * and that it displays a dialog containing the text "Important Notice" (or similar dialog text)
     * and a button labeled "I Understand".
     */
    // Rule to launch the starting Activity before each test.
    // Replace MainActivity.class with your application's starting Activity if different.
    @get:Rule
    var activityRule: ActivityScenarioRule<MainActivity> =
        ActivityScenarioRule<MainActivity>(
            MainActivity::class.java
        )

    /**
     * Verifies that the  menu appears, the correct buttons are present,
     * and clicking the buttons work
     */
    @Test
    fun verify_main_menu_items_all_appears() {
        onView(withId(R.id.getStarted))
            .check(matches(withText("Get started")))
            .check(matches(ViewMatchers.isDisplayed()))

        onView(withId(R.id.greetingText))
            .check(matches(withText("Read the Quick Start guide")))
            .check(matches(ViewMatchers.isDisplayed()))
        onView(withId(R.id.actions))
            .check(matches(isDisplayed()))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(0, "Create project"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(0, "Create project"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(1, "Open a saved project"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(2, "Delete a saved project"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(3, "Terminal"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(4, "Preferences"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(5, "Documentation"))

    }

    @Test
    fun verify_create_project_screen_is_displayed() {
        onView(withId(R.id.actions))
            .perform(actionOnItemAtPosition<MainActionsListAdapter.VH>(0, click()))
            .check(matches(isDisplayed()))


        pressBack();
    }


    private fun checkTextAtIndex(position: Int, expected: String) =
        actionOnItemAtPosition<RecyclerView.ViewHolder>(position, object : ViewAction {
            override fun getDescription() = "Check text at index $position"
            override fun getConstraints() = isAssignableFrom(View::class.java)

            override fun perform(uiController: UiController?, view: View) {
                val button =
                    view.findViewById<TextView>(R.id.itemButton) // itemButton ID is hardcoded here, make it a parameter if needed
                assertThat(button.text.toString(), `is`(expected))
            }
        })
}
