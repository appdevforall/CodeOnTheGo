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

package com.itsaky.androidide.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressManagerTest {

  @Test
  fun `cancel flips a registered checker`() {
    // Regression for ADFA-4174: cancel(thread) must act on the *registered* checker so a caller that
    // polls that same checker (the completion cancel checker driving mid-analyze abort) observes the
    // cancellation — before, cancel() always stored a throwaway Default and the registered checker
    // never became cancelled.
    val checker = ICancelChecker.Default()
    val thread = Thread.currentThread()

    ProgressManager.instance.register(thread, checker)
    try {
      assertThat(checker.isCancelled()).isFalse()

      ProgressManager.instance.cancel(thread)

      assertThat(checker.isCancelled()).isTrue()
    } finally {
      ProgressManager.instance.unregister(thread)
    }
  }

  @Test
  fun `unregister detaches the checker so cancel no longer affects it`() {
    val checker = ICancelChecker.Default()
    val thread = Thread.currentThread()

    ProgressManager.instance.register(thread, checker)
    ProgressManager.instance.unregister(thread)

    ProgressManager.instance.cancel(thread)

    assertThat(checker.isCancelled()).isFalse()
  }
}
