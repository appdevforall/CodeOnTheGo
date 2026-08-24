package com.itsaky.androidide.lsp.java

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
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
	// The whole point of the deferral: nothing javac-related is built until a Java file is touched,
	// so shutting down before that must be a no-op rather than a teardown of things never made.
	@Test
	fun `shutdown before the first java interaction is safe`() {
		val server = JavaLanguageServer()

		server.shutdown()

		assertThat(server.isShutDown).isTrue()
	}

	// A server whose javac state has been destroyed does not come back because a project opened
	// afterwards -- otherwise it rebuilds compilers that nothing will shut down again.
	@Test
	fun `a project opened after shutdown does not revive the server`() {
		val server = JavaLanguageServer()
		server.shutdown()

		server.setupWithProject(mockk(relaxed = true))

		assertThat(server.isShutDown).isTrue()
	}

	@Test
	fun `a fresh server is not shut down`() {
		assertThat(JavaLanguageServer().isShutDown).isFalse()
	}
}
