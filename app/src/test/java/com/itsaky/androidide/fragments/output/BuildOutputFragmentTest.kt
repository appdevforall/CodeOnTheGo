/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.fragments.output

import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = BuildOutputFragmentTest.TestApp::class)
class BuildOutputFragmentTest {
	open class TestApp : BaseApplication()

	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `offscreen batch times out without blocking later editor work`() =
		runTest(testDispatcher) {
			val controller = Robolectric.buildActivity(FragmentActivity::class.java)
			controller.get().setTheme(R.style.Theme_AndroidIDE)
			val activity = controller.setup().get()
			try {
				val fragment = BuildOutputFragment()
				activity.supportFragmentManager
					.beginTransaction()
					.add(android.R.id.content, fragment)
					.commitNow()
				testDispatcher.scheduler.advanceUntilIdle()

				val editor = checkNotNull(fragment.editor)
				editor.visibility = View.GONE
				editor.layout(0, 0, 0, 0)
				val viewModel = ViewModelProvider(activity)[BuildOutputViewModel::class.java]
				val sessionToken = viewModel.currentSessionToken

				fragment.flushToEditor("first\n", 6, 0, sessionToken, 0)
				fragment.flushToEditor("second\n", 7, 0, sessionToken, 0)

				assertThat(editor.text.toString()).isEmpty()
			} finally {
				controller.destroy()
			}
		}
}
