package com.itsaky.androidide.quickbuild

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins how the proxy app is brought back after a restart deploy.
 *
 * The launcher's own ACTION_MAIN intent means "resume this app"; an explicit-component intent
 * does not, and preferring one left the app dead in 2 of 8 restart deploys - the start was
 * delivered to the just-killed top record and the task went with it. Nothing about that is
 * visible from the return value, which reports only that a start was issued, so these tests
 * assert on the intent actually handed to startActivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidProxyAppLauncherTest {
	/**
	 * Records the start rather than letting Android dispatch it: the intent's shape is the
	 * whole subject here, and a thrown start is the launcher's other documented branch.
	 */
	private class RecordingContext(
		base: Context,
	) : ContextWrapper(base) {
		val started = mutableListOf<Intent>()
		var failStartWith: RuntimeException? = null

		override fun startActivity(intent: Intent) {
			failStartWith?.let { throw it }
			started += intent
		}
	}

	private val context = RecordingContext(ApplicationProvider.getApplicationContext())

	private val launcher = AndroidProxyAppLauncher(context)

	private fun declareLauncherActivity(component: ComponentName) {
		val shadowPackageManager = shadowOf(context.packageManager)
		shadowPackageManager.addActivityIfNotPresent(component)
		shadowPackageManager.addIntentFilterForActivity(
			component,
			IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
		)
	}

	@Test
	fun `an app that declares a launcher is resumed through it, never through the explicit component`() {
		val launcherActivity = ComponentName(PACKAGE, "$PACKAGE.LauncherActivity")
		declareLauncherActivity(launcherActivity)

		val issued = launcher.launch(PACKAGE, "$PACKAGE.DeployedActivity")

		assertThat(issued).isTrue()
		val intent = context.started.single()
		assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
		assertThat(intent.component).isEqualTo(launcherActivity)
	}

	@Test
	fun `an app declaring no launcher falls back to the named activity, with the NEW_TASK a non-activity context needs`() {
		val issued = launcher.launch(PACKAGE, "$PACKAGE.DeployedActivity")

		assertThat(issued).isTrue()
		val intent = context.started.single()
		assertThat(intent.component).isEqualTo(ComponentName(PACKAGE, "$PACKAGE.DeployedActivity"))
		// This intent is built here, not by PackageManager, so it carries no flag but ours.
		assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
	}

	@Test
	fun `with neither a launcher nor a named activity nothing is started and the caller is told so`() {
		val issued = launcher.launch(PACKAGE, null)

		assertThat(issued).isFalse()
		assertThat(context.started).isEmpty()
	}

	@Test
	fun `a refused start is reported as not issued rather than thrown at the deploy`() {
		context.failStartWith = ActivityNotFoundException("no activity")

		val issued = launcher.launch(PACKAGE, "$PACKAGE.DeployedActivity")

		assertThat(issued).isFalse()
	}

	private companion object {
		private const val PACKAGE = "com.example.proxy"
	}
}
