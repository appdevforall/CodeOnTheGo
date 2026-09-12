package com.itsaky.androidide.activities.editor

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.services.builder.GradleBuildServiceConnnection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A configuration change destroys the editor without finishing it, so [isDestroying] is false and
 * the finish-only teardown is skipped. The build service callback must be released anyway: it is a
 * bound reference to this activity (`this::onGradleBuildServiceConnected`), so a bind still pending
 * at destroy would otherwise deliver into a dead instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = BuildServiceCallbackClearedTest.TestApp::class)
class BuildServiceCallbackClearedTest {
	open class TestApp : BaseApplication()

	@Test
	fun givenANonFinishingDestroy_whenPreDestroyRuns_thenTheBuildServiceCallbackIsCleared() {
		assertCallbackClearedWhenDestroying(false)
	}

	@Test
	fun givenAFinishingDestroy_whenPreDestroyRuns_thenTheBuildServiceCallbackIsCleared() {
		assertCallbackClearedWhenDestroying(true)
	}

	/**
	 * Both destroy shapes, because the bug was an asymmetry between them: the clearing used to sit
	 * inside the `isDestroying` guard, so it ran for a finishing destroy and not for a recreation.
	 * Pinning only one shape would not have caught that.
	 */
	private fun assertCallbackClearedWhenDestroying(destroying: Boolean) {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
		val connection = connectionField.get(activity) as GradleBuildServiceConnnection
		onConnected.set(connection, { _: GradleBuildService -> })
		isDestroying.set(activity, destroying)

		// preDestroy goes on to tear down members that a bare activity never initialised; the
		// clearing under test happens first, so what it throws afterwards is not this test's
		// concern. If the clearing ever moves back below that teardown, the assertion fails.
		runCatching { preDestroy.invoke(activity) }

		assertThat(onConnected.get(connection)).isNull()
	}

	private val connectionField =
		ProjectHandlerActivity::class
			.java
			.getDeclaredField("buildServiceConnection")
			.apply { isAccessible = true }
	private val onConnected =
		GradleBuildServiceConnnection::class
			.java
			.getDeclaredField("onConnected")
			.apply { isAccessible = true }
	private val isDestroying =
		BaseEditorActivity::class.java.getDeclaredField("isDestroying").apply { isAccessible = true }
	private val preDestroy =
		ProjectHandlerActivity::class.java.getDeclaredMethod("preDestroy").apply { isAccessible = true }
}
