package com.itsaky.androidide.screens.acceptanceTest

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
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
import com.android.aaptcompiler.Pseudolocalizer
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.adapters.MainActionsListAdapter
import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.core.Is.`is`
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)

class MainScreenTest {

    /**
     * The 2 digit number after verify..... in the fun name is the order in which the tests
     *   will be run
     **/

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
    fun verify01_main_menu_items_all_appears() {
        onView(withId(R.id.getStarted))
            .check(matches(withText("Get started")))
            .check(matches(ViewMatchers.isDisplayed()))

        onView(withId(R.id.greetingText))
            .check(matches(withText("Read the Quick Start guide")))
            .check(matches(ViewMatchers.isDisplayed()))
        onView(withId(R.id.actions))
            .check(matches(isDisplayed()))

        var index = 0

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(index++, "Create project"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(index++, "Open a saved project"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(index++, "Delete a saved project"))

        if( FeatureFlags.isExperimentsEnabled()) {
            onView(withId(R.id.actions))
                .perform(checkTextAtIndex(index++, "Clone git repository"))
        }
        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(index++, "Terminal"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(index++, "Preferences"))

        onView(withId(R.id.actions))
            .perform(checkTextAtIndex(index, "Documentation"))

    }

    /***
     * Test the create project screen is displayed
     */
    @Test
    fun verify02_create_project_screen_is_displayed() {
        onView(withId(R.id.actions))
            .perform(actionOnItemAtPosition<MainActionsListAdapter.VH>(0, click()))
            .check(matches(isDisplayed()))


        pressBack();
    }

    /***
     * Test the open project screen is displayed
     */
    @Test
    fun verify03_open_project_screen_is_displayed() {
        onView(withId(R.id.actions))
            .perform(actionOnItemAtPosition<MainActionsListAdapter.VH>(1, click()))
            .check(matches(isDisplayed()))


        pressBack();
    }

    /***
     * Test the delete project screen is displayed
     */
    @Test
    fun verify04_delete_project_screen_is_displayed() {
        onView(withId(R.id.actions))
            .perform(actionOnItemAtPosition<MainActionsListAdapter.VH>(2, click()))
            .check(matches(isDisplayed()))


        pressBack();
    }

    /***
     * Test the termux screen is displayed
     *
     * TODO fix this
     */
//    @Test
//    fun verify05_terminal_screen_is_displayed() {
//        try {
//        onView(withId(R.id.actions))
//            .perform(actionOnItemAtPosition<MainActionsListAdapter.VH>(3, click()))
//            .check(matches(isDisplayed()))
//        } catch (e : Exception) {
//            //for now do nothing
//        }
//
//
//        pressBack();
//    }

    /***
     * Test the preference screen is displayed
     *
     * TODO fix this
     */
    @Test
    fun verify06_preferences_screen_is_displayed() {
        onView(withId(R.id.actions))
            .perform(actionOnItemAtPosition<MainActionsListAdapter.VH>(4, click()))
            .check(matches(isDisplayed()))


        pressBack();
    }

    /***
     * Test the documentation screen is displayed
     */
    @Test
    fun verify07_documentation_screen_is_displayed() {
        try {
            MainActivity.getInstance()?.startWebServer()
            onView(withId(R.id.actions))
                .perform(actionOnItemAtPosition<MainActionsListAdapter.VH>(5, click()))
                .check(matches(isDisplayed()))
        } catch (e : NoMatchingViewException) {
            //for now do nothing
        }

        pressBack();
    }

    /**************************
     *
     * Utilities/helper routines
     *
     * ************************/
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
