package com.itsaky.androidide.activities.editor

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.services.debug.DebuggerService
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = DebuggerServiceUnbindTest.TestApp::class)
class DebuggerServiceUnbindTest {
	open class TestApp : BaseApplication()

	@Test
	fun givenAHeldServiceReference_whenUnbound_thenTheReferenceIsCleared() {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
		serviceField.set(activity, mockk<DebuggerService>(relaxed = true))

		unbind.invoke(activity)

		assertThat(serviceField.get(activity)).isNull()
	}

	private val serviceField =
		BaseEditorActivity::class.java.getDeclaredField("debuggerService").apply { isAccessible = true }
	private val unbind =
		BaseEditorActivity::class.java.getDeclaredMethod("unbindDebuggerService").apply { isAccessible = true }
}
