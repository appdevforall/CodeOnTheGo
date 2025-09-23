package com.itsaky.androidide.testing.android

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity

/**
 * A [TestAppCompatActivity] with Material theme.
 *
 * Use then when you want to test UI which uses material components and styles.
 *
 * @author Akash Yadav
 */
open class TestMaterialActivity : TestAppCompatActivity()

/**
 * An [AppCompatActivity] for testing purposes.
 *
 * Use this when you need access to use an `ActivityScenarioRule` with
 * app compat components.
 *
 * @author Akash Yadav
 */
open class TestAppCompatActivity : AppCompatActivity()

/**
 * A [Activity] for testing purposes.
 *
 * Use this for basic activity-based testing.
 *
 * @author Akash Yadav
 */
open class TestActivity : Activity()
