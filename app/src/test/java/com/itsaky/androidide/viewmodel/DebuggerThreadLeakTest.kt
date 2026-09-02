package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.lsp.BreakpointHandler
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.repositories.BreakpointRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ADFA-5375: a debugger session must not cost the process a thread that is never given back.
 *
 * LeakCanary cannot see this - it watches objects - so the guard is a thread count, the same
 * measurement that found the leak on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = DebuggerThreadLeakTest.TestApp::class)
class DebuggerThreadLeakTest {
	/** IDEApplication's async loaders call exitProcess when they fail under Robolectric. */
	open class TestApp : BaseApplication()

	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	@get:Rule
	val projectFolder = TemporaryFolder()

	@Before
	fun setUp() {
		// Breakpoint persistence resolves against the project dir. Without this it resolves against
		// the module directory and drops an untracked file into the worktree.
		(IProjectManager.getInstance() as ProjectManagerImpl).projectPath =
			projectFolder.root.absolutePath
	}

	@Test
	fun `repeated view model lifecycles do not accumulate threads`() {
		// The shared IO dispatcher grows once, on demand, so an absolute count says nothing. What
		// must not happen is growth that keeps tracking the number of sessions: seven open/close
		// cycles left seven live debugger threads on device before the fix.
		runCycles(CYCLES)
		val afterWarmup = settledThreadCount()

		runCycles(CYCLES)

		assertThat(settledThreadCount()).isAtMost(afterWarmup + SLACK)
	}

	private fun runCycles(count: Int) {
		repeat(count) {
			val store = ViewModelStore()
			try {
				newViewModel(store).debugClient.toggleBreakpoint(sourceFile(), 1)
			} finally {
				store.clear()
			}
		}
	}

	@Test
	fun `clearing the view model closes the debug client`() {
		val store = ViewModelStore()
		val viewModel = newViewModel(store)
		try {
			assertThat(viewModel.debugClient.isClientScopeActive).isTrue()
		} finally {
			store.clear()
		}

		assertThat(viewModel.debugClient.isClientScopeActive).isFalse()
	}

	@Test
	fun `a closed client ignores further breakpoint work`() {
		val store = ViewModelStore()
		val client = newViewModel(store).debugClient
		store.clear()

		client.toggleBreakpoint(sourceFile(), 3)

		// The guard must reject the call outright; a cancelled scope would swallow it silently.
		assertThat(client.breakpoints.allBreakpoints).isEmpty()
	}

	@Test
	fun `closing writes a breakpoint made inside the debounce window, without waiting it out`() {
		val handler = BreakpointHandler()
		val stored = BreakpointRepository.getBreakpointsStorageFile(projectFolder.root)
		val closedAt: Long
		try {
			handler.begin { }
			handler.toggle(sourceFile(), BREAKPOINT_LINE)
			awaitTrue("breakpoint registered") { handler.allBreakpoints.isNotEmpty() }
		} finally {
			// Well inside the save debounce. The write must survive the close (it runs on a scope
			// close does not cancel) and must not wait out the remaining delay.
			closedAt = System.currentTimeMillis()
			handler.close()
		}

		awaitTrue("breakpoints written to $stored") { stored.exists() }
		assertThat(System.currentTimeMillis() - closedAt).isLessThan(SAVE_DEBOUNCE_MS)
		assertThat(stored.readText().filterNot(Char::isWhitespace))
			.contains("\"line\":$BREAKPOINT_LINE")
	}

	private fun newViewModel(store: ViewModelStore) =
		ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
			.get(DebuggerViewModel::class.java)

	private fun sourceFile() = File(projectFolder.root, "app/src/main/java/Main.java")

	private fun jvmThreadCount(): Int {
		var group = Thread.currentThread().threadGroup!!
		while (group.parent != null) {
			group = group.parent!!
		}
		return group.activeCount()
	}

	/** Lets the just-finished cycles' coroutines drain before sampling. */
	private fun settledThreadCount(): Int {
		var last = jvmThreadCount()
		val deadline = System.currentTimeMillis() + SETTLE_MS
		while (System.currentTimeMillis() < deadline) {
			Thread.sleep(POLL_MS)
			val now = jvmThreadCount()
			if (now == last) {
				return now
			}
			last = now
		}

		return last
	}

	private fun awaitTrue(
		what: String,
		condition: () -> Boolean,
	) {
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		while (System.currentTimeMillis() < deadline) {
			if (condition()) {
				return
			}
			Thread.sleep(POLL_MS)
		}

		throw AssertionError("Timed out waiting for: $what")
	}

	companion object {
		private const val CYCLES = 7
		private const val SLACK = 2
		private const val BREAKPOINT_LINE = 5
		private const val TIMEOUT_MS = 5_000L
		private const val SETTLE_MS = 2_000L

		/** Comfortably under BreakpointHandler's 1s debounce, comfortably over a prompt flush. */
		private const val SAVE_DEBOUNCE_MS = 500L
		private const val POLL_MS = 25L
	}
}
