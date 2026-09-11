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

package com.itsaky.androidide.fragments

import android.animation.ObjectAnimator
import androidx.appcompat.app.AppCompatActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.viewmodel.MainViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the wiring that feeds [shouldBlinkScrollIndicator]: the indicator's endlessly repeating
 * animator must exist only while the fragment's view is started and this screen is current.
 *
 * The animator re-posts a vsync callback every frame for as long as it lives, so an animator that
 * outlives the view being on screen burns frames for the rest of the process. Pre-fix the blink
 * started once in `onViewCreated` and was only cancelled in `onDestroyView`, so both cases here go
 * red: leaving the screen and stopping the activity each left it running.
 *
 * The stop case also pins the return: the observer watches START/STOP rather than cancelling once
 * so that the blink comes back with the screen, and nothing else covers that half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TemplateDetailsBlinkLifecycleTest.TestApp::class)
class TemplateDetailsBlinkLifecycleTest {
	open class TestApp : BaseApplication()

	private var startedKoin = false

	/*
	 * activityViewModel<MainViewModel> resolves through Koin, so a context has to exist. Another
	 * test's application may already have started one, in which case startKoin would throw
	 * KoinApplicationAlreadyStartedException -- join that context and leave it to its owner.
	 */
	@Before
	fun setUp() {
		val binding = module { viewModel { MainViewModel() } }
		val existing = GlobalContext.getOrNull()
		if (existing == null) {
			startedKoin = true
			startKoin { modules(binding) }
		} else {
			existing.loadModules(listOf(binding))
		}
	}

	@After
	fun tearDown() {
		if (startedKoin) {
			stopKoin()
		}
	}

	@Test
	fun `blink stops when the view lifecycle stops and returns when it starts`() {
		val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
		val fragment = controller.get().showTemplateDetails()

		assertThat(fragment.blinkAnimator()?.isRunning).isTrue()

		controller.pause().stop()

		assertThat(fragment.blinkAnimator()).isNull()

		controller.start().resume()

		assertThat(fragment.blinkAnimator()?.isRunning).isTrue()
	}

	@Test
	fun `blink stops when another screen becomes current`() {
		val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
		val fragment = controller.get().showTemplateDetails()

		assertThat(fragment.blinkAnimator()?.isRunning).isTrue()

		fragment.viewModel().setScreen(MainViewModel.SCREEN_MAIN)

		assertThat(fragment.blinkAnimator()).isNull()
	}

	private fun AppCompatActivity.showTemplateDetails(): TemplateDetailsFragment {
		val fragment = TemplateDetailsFragment()
		supportFragmentManager
			.beginTransaction()
			.add(android.R.id.content, fragment)
			.commitNow()

		fragment.viewModel().setScreen(MainViewModel.SCREEN_TEMPLATE_DETAILS)
		return fragment
	}

	private fun TemplateDetailsFragment.viewModel(): MainViewModel =
		readPrivate("viewModel\$delegate").let { delegate ->
			@Suppress("UNCHECKED_CAST")
			(delegate as Lazy<MainViewModel>).value
		}

	private fun TemplateDetailsFragment.blinkAnimator(): ObjectAnimator? = readPrivate("blinkAnimator") as ObjectAnimator?

	/*
	 * The animator field and the view-model delegate are private production state with no reason to
	 * be otherwise; reading them reflectively keeps the test-only access in the test.
	 */
	private fun TemplateDetailsFragment.readPrivate(name: String): Any? =
		TemplateDetailsFragment::class.java
			.getDeclaredField(name)
			.apply { isAccessible = true }
			.get(this)
}
