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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the push-based [ICancelChecker.invokeOnCancel] (ADFA-4174), which lets the Kotlin LSP
 * abort an in-flight `analyze` the moment cancellation happens instead of polling.
 */
class ICancelCheckerTest {

  @Test
  fun `invokeOnCancel fires when cancel is called`() {
    val checker = ICancelChecker.Default()
    val fired = AtomicInteger(0)

    checker.invokeOnCancel { fired.incrementAndGet() }
    assertThat(fired.get()).isEqualTo(0)

    checker.cancel()
    assertThat(fired.get()).isEqualTo(1)
  }

  @Test
  fun `invokeOnCancel fires immediately when already cancelled`() {
    val checker = ICancelChecker.Default(cancelled = true)
    val fired = AtomicInteger(0)

    checker.invokeOnCancel { fired.incrementAndGet() }

    assertThat(fired.get()).isEqualTo(1)
  }

  @Test
  fun `invokeOnCancel fires at most once across repeated cancel calls`() {
    val checker = ICancelChecker.Default()
    val fired = AtomicInteger(0)

    checker.invokeOnCancel { fired.incrementAndGet() }
    checker.cancel()
    checker.cancel()
    checker.cancel()

    assertThat(fired.get()).isEqualTo(1)
  }

  @Test
  fun `multiple listeners all fire on cancel`() {
    val checker = ICancelChecker.Default()
    val fired = AtomicInteger(0)

    checker.invokeOnCancel { fired.incrementAndGet() }
    checker.invokeOnCancel { fired.incrementAndGet() }

    checker.cancel()

    assertThat(fired.get()).isEqualTo(2)
  }

  @Test
  fun `removeOnCancel drops the listener so it does not fire`() {
    val checker = ICancelChecker.Default()
    val fired = AtomicInteger(0)
    val listener: () -> Unit = { fired.incrementAndGet() }

    checker.invokeOnCancel(listener)
    checker.removeOnCancel(listener)
    checker.cancel()

    assertThat(fired.get()).isEqualTo(0)
  }

  @Test
  fun `removeOnCancel only drops the given listener`() {
    val checker = ICancelChecker.Default()
    val fired = AtomicInteger(0)
    val removed: () -> Unit = { fired.incrementAndGet() }

    checker.invokeOnCancel(removed)
    checker.invokeOnCancel { fired.incrementAndGet() }
    checker.removeOnCancel(removed)
    checker.cancel()

    assertThat(fired.get()).isEqualTo(1)
  }

  @Test
  fun `NOOP invokeOnCancel is a no-op`() {
    val fired = AtomicInteger(0)

    // NOOP is a shared singleton that never cancels: registering must be a no-op so captured listeners
    // don't accumulate forever. We deliberately don't call NOOP.cancel() — flipping the shared
    // singleton would corrupt every other user of it.
    ICancelChecker.NOOP.invokeOnCancel { fired.incrementAndGet() }

    assertThat(fired.get()).isEqualTo(0)
  }

  @Test
  fun `CANCELLED fires immediately`() {
    val fired = AtomicInteger(0)

    ICancelChecker.CANCELLED.invokeOnCancel { fired.incrementAndGet() }

    assertThat(fired.get()).isEqualTo(1)
  }
}
