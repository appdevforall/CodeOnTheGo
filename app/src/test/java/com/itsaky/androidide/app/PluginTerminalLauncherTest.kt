package com.itsaky.androidide.app

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.manager.services.LaunchedTerminalCommand
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import com.itsaky.androidide.terminal.AgentRunner
import com.itsaky.androidide.terminal.TerminalStartFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The application's foreground activity survives a plain pause, so a non-null activity does not
 * mean the IDE is on screen. A start from the background is dropped without an exception, so
 * the launcher has to refuse up front or the plugin waits out the open timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PluginTerminalLauncherTest {
	@get:Rule
	val tmp = TemporaryFolder()

	// The open is posted to the main looper, which Robolectric runs only when idled.
	private fun launch(activity: Activity): LaunchedTerminalCommand {
		val launched =
			runBlocking {
				PluginTerminalLauncher(runner = AgentRunner(tmp.root)) { activity }.launch("true", null, "plugin", "plugin")
			}
		shadowOf(Looper.getMainLooper()).idle()
		return launched
	}

	@Test
	fun `a backgrounded IDE is refused at once and nothing is started`() {
		val controller =
			Robolectric
				.buildActivity(ComponentActivity::class.java)
				.setup()
				.pause()
				.stop()
		val activity = controller.get()

		val launched = launch(activity)

		assertThat(launched.result.isCompleted).isTrue()
		assertThat(launched.result.getCompleted())
			.isEqualTo(TerminalCommandResult.NotStarted(TerminalStartFailure.NotInForeground.message))
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
	}

	@Test
	fun `an IDE on screen opens the Terminal`() {
		val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

		val launched = launch(activity)

		assertThat(launched.result.isCompleted).isFalse()
		assertThat(shadowOf(activity).nextStartedActivity).isNotNull()
		launched.interrupt()
		shadowOf(Looper.getMainLooper()).idle()
	}
}
