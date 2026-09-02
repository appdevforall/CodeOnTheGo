package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.BreakpointHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Thread leaks are invisible to LeakCanary, which watches objects. These tests count live OS
 * threads by name instead - the same way ADFA-5375 was found on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DebuggerThreadLeakTest {
	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	@Test
	fun `closing a BreakpointHandler releases its worker thread`() {
		val before = liveThreads(BREAKPOINT_THREAD)
		val handler = BreakpointHandler()
		handler.begin { }
		awaitThreads(BREAKPOINT_THREAD, before + 1)

		handler.close()

		awaitThreads(BREAKPOINT_THREAD, before)
	}

	@Test
	fun `clearing the view model releases the debug client threads`() {
		val breakpointThreads = liveThreads(BREAKPOINT_THREAD)
		val clientThreads = liveThreads(CLIENT_THREAD)

		val store = ViewModelStore()
		val viewModel =
			ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
				.get(DebuggerViewModel::class.java)

		// the client pool is lazy, so give it work to make its threads exist
		viewModel.debugClient.toggleBreakpoint(File("Main.java"), 1)

		awaitThreads(BREAKPOINT_THREAD, breakpointThreads + 1)
		assertThat(liveThreads(CLIENT_THREAD)).isGreaterThan(clientThreads)

		store.clear()

		awaitThreads(BREAKPOINT_THREAD, breakpointThreads)
		awaitThreads(CLIENT_THREAD, clientThreads)
	}

	@Test
	fun `repeated view model lifecycles do not accumulate threads`() {
		val before = liveThreads(BREAKPOINT_THREAD)

		// seven open/close cycles left seven live threads on device before the fix
		repeat(7) {
			val store = ViewModelStore()
			ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
				.get(DebuggerViewModel::class.java)
			store.clear()
		}

		awaitThreads(BREAKPOINT_THREAD, before)
	}

	private fun liveThreads(prefix: String) = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(prefix) }

	/** Thread termination is asynchronous, so poll rather than sample once. */
	private fun awaitThreads(
		prefix: String,
		expected: Int,
	) {
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		var actual = liveThreads(prefix)
		while (actual != expected && System.currentTimeMillis() < deadline) {
			Thread.sleep(25)
			actual = liveThreads(prefix)
		}

		assertThat(actual).isEqualTo(expected)
	}

	companion object {
		private const val BREAKPOINT_THREAD = "BreakpointHandler"
		private const val CLIENT_THREAD = "IDEDebugClient"
		private const val TIMEOUT_MS = 5_000L
	}
}
