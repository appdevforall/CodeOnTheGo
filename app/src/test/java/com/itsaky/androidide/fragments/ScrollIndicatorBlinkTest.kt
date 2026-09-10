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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.viewmodel.MainViewModel
import org.junit.Test

/**
 * The three inputs that decide whether the template scroll indicator's endless blink may run, as
 * documented on [shouldBlinkScrollIndicator].
 *
 * Scope: this pins the decision only, not the wiring that feeds it. The three call sites that
 * re-evaluate it -- the view-lifecycle observer, the `currentScreen` observer and
 * `updateFinishEnabledState` -- are unpinned, so deleting one would leave every case here passing.
 * Pinning them needs a host activity and Koin scaffolding to stand the fragment up, since the
 * module has no `fragment-testing` dependency.
 */
class ScrollIndicatorBlinkTest {
	@Test
	fun `blinks only when started, on the details screen, with the indicator showing`() {
		assertThat(
			shouldBlinkScrollIndicator(
				isViewStarted = true,
				currentScreen = MainViewModel.SCREEN_TEMPLATE_DETAILS,
				isIndicatorVisible = true,
			),
		).isTrue()
	}

	@Test
	fun `does not blink behind another screen sharing the container`() {
		val offScreens =
			listOf(
				MainViewModel.SCREEN_MAIN,
				MainViewModel.SCREEN_TEMPLATE_LIST,
				MainViewModel.SCREEN_SAVED_PROJECTS,
				MainViewModel.SCREEN_DELETE_PROJECTS,
				MainViewModel.SCREEN_CLONE_REPO,
				MainViewModel.TOOLTIPS_WEB_VIEW,
			)

		for (screen in offScreens) {
			assertThat(
				shouldBlinkScrollIndicator(
					isViewStarted = true,
					currentScreen = screen,
					isIndicatorVisible = true,
				),
			).isFalse()
		}
	}

	@Test
	fun `does not blink while the screen sentinel is unset`() {
		/*
		 * -1, not null: MainViewModel backs currentScreen with MutableLiveData(-1) and documents -1
		 * as the "no screen yet" sentinel, so the value is never null. It is live between
		 * MainActivity's setContentView, which creates this fragment, and its first setScreen.
		 */
		assertThat(
			shouldBlinkScrollIndicator(
				isViewStarted = true,
				currentScreen = -1,
				isIndicatorVisible = true,
			),
		).isFalse()

		// null only because LiveData.getValue() is platform-nullable at the call site
		assertThat(
			shouldBlinkScrollIndicator(
				isViewStarted = true,
				currentScreen = null,
				isIndicatorVisible = true,
			),
		).isFalse()
	}

	@Test
	fun `does not blink once the form has been scrolled to the bottom`() {
		assertThat(
			shouldBlinkScrollIndicator(
				isViewStarted = true,
				currentScreen = MainViewModel.SCREEN_TEMPLATE_DETAILS,
				isIndicatorVisible = false,
			),
		).isFalse()
	}

	@Test
	fun `does not blink while the view is stopped`() {
		assertThat(
			shouldBlinkScrollIndicator(
				isViewStarted = false,
				currentScreen = MainViewModel.SCREEN_TEMPLATE_DETAILS,
				isIndicatorVisible = true,
			),
		).isFalse()
	}
}
