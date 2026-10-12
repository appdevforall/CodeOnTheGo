package com.itsaky.androidide.app

import android.app.Application
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import org.junit.Test
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
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PluginTerminalLauncherTest {
	@Test
	fun `a backgrounded IDE is refused at once and nothing is started`() {
		val controller =
			Robolectric
				.buildActivity(ComponentActivity::class.java)
				.setup()
				.pause()
				.stop()
		val activity = controller.get()
		val results = mutableListOf<TerminalCommandResult>()

		PluginTerminalLauncher { activity }.launch("true", null, "plugin") { results += it }

		assertThat(results).containsExactly(TerminalCommandResult.NotStarted("Code On the Go is not in the foreground"))
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
	}

	@Test
	fun `an IDE on screen opens the Terminal`() {
		val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
		val results = mutableListOf<TerminalCommandResult>()

		val cancel = PluginTerminalLauncher { activity }.launch("true", null, "plugin") { results += it }

		assertThat(results).isEmpty()
		assertThat(shadowOf(activity).nextStartedActivity).isNotNull()
		cancel()
	}
}
