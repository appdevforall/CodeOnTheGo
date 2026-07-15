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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test for ADFA-3472.
 *
 * [BuildOutputFragment.clearOutput] and [BuildOutputFragment.getShareableContent] touch
 * `buildOutputViewModel`, which is created via `by activityViewModels()`. Forcing that lazy
 * delegate while the fragment is detached calls `requireActivity()`, which throws an
 * [IllegalStateException] ("not attached to an activity"). The run-tasks dialog / config-change
 * path can invoke these methods on a detached fragment, crashing the app (Sentry ADFA-3472).
 *
 * The fix guards both methods with `if (!isAdded || activity == null) return`. These tests
 * assert that a detached fragment does NOT crash and returns the safe no-op values.
 *
 * Mutation-mindset: on the pre-fix code (no guard), both calls force the activityViewModels
 * delegate -> requireActivity() -> IllegalStateException, so each test goes RED.
 */
@RunWith(RobolectricTestRunner::class)
class BuildOutputFragmentDetachedTest {

  /** Verifies clearOutput() is a safe no-op on a detached fragment instead of crashing. */
  @Test
  fun `clearOutput on a detached fragment does not crash`() {
    // A freshly-constructed fragment that was never added to an activity is "detached":
    // isAdded == false and activity == null, exactly the run-tasks / config-change state
    // in which the Sentry crash was observed.
    val fragment = BuildOutputFragment()

    assertThat(fragment.isAdded).isFalse()

    // Pre-fix: this forces the `by activityViewModels()` delegate, which calls
    // requireActivity() on a detached fragment and throws IllegalStateException.
    // Post-fix: the guard returns early, no exception.
    fragment.clearOutput()
  }

  /** Verifies getShareableContent() returns an empty string on a detached fragment instead of crashing. */
  @Test
  fun `getShareableContent on a detached fragment returns empty without crashing`() {
    val fragment = BuildOutputFragment()

    assertThat(fragment.isAdded).isFalse()

    // Pre-fix: forces the activityViewModels delegate -> requireActivity() -> ISE.
    // Post-fix: guard returns "" without touching the view model.
    val content = fragment.getShareableContent()

    assertThat(content).isEmpty()
  }
}
