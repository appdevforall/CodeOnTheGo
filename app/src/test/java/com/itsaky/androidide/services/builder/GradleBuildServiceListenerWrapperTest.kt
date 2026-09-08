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

package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.services.builder.GradleBuildService.EventListener
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

/**
 * Pins that the build service's listener wrapper forwards every callback it is given.
 *
 * It did not. A now-removed `onBuildCancelRequested` was declared with a `= Unit` default so that
 * only listeners that cared had to implement it; the wrapper then inherited that no-op rather than
 * passing the call on, so the cancel never reached the real listener and a build the user had
 * stopped went on being annotated as a failure -- which is what BUILD_CANCELLED exists to prevent.
 * The feature was unreachable, and the store-level test for it passed the whole time, because it
 * called the store directly and nothing exercised the path to it.
 *
 * That callback is gone: the server classifies the throwable Gradle raised and says so on the
 * BuildResult, so nothing on this side has to be told separately (ADFA-5542). The invariant it
 * left behind outlives it and still guards every remaining member.
 */
@RunWith(RobolectricTestRunner::class)
class GradleBuildServiceListenerWrapperTest {
	/** Records which interface method it was handed, so no call has to be spelled out here. */
	private class Recorder {
		val calls = mutableListOf<String>()

		var lastArgs: List<Any?> = emptyList()

		val listener: EventListener =
			Proxy.newProxyInstance(
				EventListener::class.java.classLoader,
				arrayOf(EventListener::class.java),
			) { _, method, args ->
				calls += method.name
				lastArgs = args.orEmpty().toList()
				null
			} as EventListener
	}

	@Test
	fun `no callback on the interface has a default implementation`() {
		// This is the invariant that would have caught the bug, and it is not the one you reach
		// for first: asserting that the wrapper "overrides every declared method" looks right and
		// cannot fail, because Kotlin emits a bridge method on the implementing class for an
		// inherited default, so reflection sees an override that is really a no-op.
		//
		// A default is what let the wrapper inherit silence instead of being asked to forward. With
		// none, the compiler demands an implementation from every implementor -- the wrapper
		// included -- and this class of omission stops being possible.
		val defaults =
			EventListener::class.java.declaredClasses
				.firstOrNull { it.simpleName == "DefaultImpls" }
				?.declaredMethods
				.orEmpty()
				.map { it.name }

		assertThat(defaults).isEmpty()
	}

	@Test
	fun `a failure reaches the listener with the server's reason for it`() {
		val recorder = Recorder()
		val wrapped = GradleBuildService.wrap(recorder.listener)!!

		wrapped.onBuildFailed(listOf(":app:assembleDebug"), TaskExecutionResult.Failure.BUILD_CANCELLED)

		assertThat(recorder.calls).containsExactly("onBuildFailed")

		// The reason is the whole of what tells a cancel from a failure (ADFA-5542), and it is the
		// server's answer rather than one this side worked out. A wrapper that forwarded the call
		// and dropped the argument would be the earlier defect one layer in, with no signature to
		// complain about it.
		assertThat(recorder.lastArgs)
			.containsExactly(listOf(":app:assembleDebug"), TaskExecutionResult.Failure.BUILD_CANCELLED)
			.inOrder()
	}

	@Test
	fun `wrapping nothing yields nothing`() {
		assertThat(GradleBuildService.wrap(null)).isNull()
	}
}
