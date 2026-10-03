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

package com.itsaky.androidide.lsp.java

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADFA-5052 defers the javac reset to the first real Java interaction, which makes the server's
 * lifecycle something with states rather than a single construction. These cover the transitions
 * that do not need a project fixture; the concurrent ones (an operation holding a compiler while
 * shutdown lands) are ADFA-5261.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.DEFAULT_VALUE_STRING)
class JavaLanguageServerLifecycleTest {
	// Every server registers itself on the global EventBus and adds indexing services to the
	// ProjectManagerImpl singleton in its init block, and Robolectric caches a sandbox per @Config --
	// so a server left running here would keep receiving events posted by other test classes.
	private val servers = mutableListOf<JavaLanguageServer>()

	@After
	fun tearDown() {
		servers.forEach { it.shutdown() }
		servers.clear()
	}

	private fun newServer() = JavaLanguageServer().also { servers += it }

	// The whole point of the deferral: nothing javac-related is built until a Java file is touched,
	// so shutting down before that must be a no-op rather than a teardown of things never made.
	@Test
	fun `shutdown before the first java interaction is safe`() {
		val server = newServer()

		server.shutdown()

		assertThat(server.isShutDown).isTrue()
	}

	// A server whose javac state has been destroyed does not come back because a project opened
	// afterwards -- otherwise it rebuilds compilers that nothing will shut down again.
	@Test
	fun `a project opened after shutdown does not revive the server`() {
		val server = newServer()
		server.shutdown()

		server.setupWithProject(mockk(relaxed = true))

		assertThat(server.isShutDown).isTrue()
	}

	@Test
	fun `a fresh server is not shut down`() {
		assertThat(newServer().isShutDown).isFalse()
	}
}
