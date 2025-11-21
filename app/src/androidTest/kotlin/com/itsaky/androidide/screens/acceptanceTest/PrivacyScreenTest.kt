package com.itsaky.androidide.screens.acceptanceTest

import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsaky.androidide.activities.OnboardingActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI Test class to verify the initial startup dialog behavior.
 *
 * NOTE: This test assumes the main application activity is MainActivity,
 * and that it displays a dialog containing the text "Important Notice" (or similar dialog text)
 * and a button labeled "I Understand".
 */
@RunWith(AndroidJUnit4::class)
class PrivacyScreenTest {
    // Rule to launch the starting Activity before each test.
    // Replace MainActivity.class with your application's starting Activity if different.
    @get:Rule
    var activityRule: ActivityScenarioRule<OnboardingActivity> =
        ActivityScenarioRule<OnboardingActivity>(
            OnboardingActivity::class.java
        )

    /**
     * Verifies that the startup dialog appears, the correct button is present,
     * and clicking the button dismisses the dialog.
     */
    @Test
    fun verify_dialog_appears_and_dismisses_with_understand_button() {
        // 1. Verify that the Dialog appears.
        // We check for a unique text element within the dialog (like a title or message).
        // The inRoot(isDialog()) matcher ensures we are looking within the dialog window root.

        Espresso.onView(
            ViewMatchers.withText(
                "Privacy & Analytics"
            )
        )
            .inRoot(RootMatchers.isDialog())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // 2. Verify the button labeled "I Understand" is displayed within the dialog.
        Espresso.onView(
            ViewMatchers.withText(
                "I Understand"
            )
        )
            .inRoot(RootMatchers.isDialog())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .check(ViewAssertions.matches(ViewMatchers.isClickable()))

        // 3. Click the "I Understand" button.
        Espresso.onView(
            ViewMatchers.withText(
                "I Understand"
            )
        )
            .inRoot(RootMatchers.isDialog())
            .perform(ViewActions.click())

        // 4. Verify the dialog is dismissed (no longer exists in the view hierarchy).
        // Check for the unique dialog text again, asserting that it does not exist.
        Espresso.onView(
            ViewMatchers.withText(
                "Privacy & Analytics"
            )
        )
            .check(ViewAssertions.doesNotExist())

        // OPTIONAL: You can add an extra check here to ensure the main screen element
        // is now visible, confirming navigation to the next state, e.g.:
        // onView(withText("Main Dashboard")).check(matches(isDisplayed()));
    }
}