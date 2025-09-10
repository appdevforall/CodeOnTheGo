package com.itsaky.androidide.testing.android

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Custom [AndroidJUnitRunner] for testing IDE. Doesn't do anything fancy, yet.
 *
 * @author Akash Yadav
 */
@Suppress("UNUSED")
class TestInstrumentationRunner : AndroidJUnitRunner() {

	override fun onCreate(arguments: Bundle?) {
		super.onCreate(arguments)
	}
}