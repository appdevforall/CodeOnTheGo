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

package com.itsaky.androidide.editor.floating

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.floating.model.DockingEvent
import com.itsaky.androidide.floating.model.DockingManager
import com.itsaky.androidide.floating.window.WindowBounds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ADFA-4501: closing the project must leave no floating window behind. A docked plugin tab or file
 * panel is closed with the project; an undocked one is the same tab living in another window, and
 * used to keep running over other apps against a project that no longer exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = FloatingWindowProjectCloseTest.TestApp::class)
class FloatingWindowProjectCloseTest {
	open class TestApp : BaseApplication()

	private lateinit var controller: IdeFloatingTabController

	@Before
	fun setUp() {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
		controller = IdeFloatingTabController(activity)
	}

	@After
	fun tearDown() {
		DockingManager.windows.value.forEach { DockingManager.close(it.id) }
	}

	@Test
	fun `closing the project tears down every floating window`() =
		runTest {
			DockingManager.undock(PluginTabDockableContent("keygen.tab", "Keystore Generator"), BOUNDS)
			DockingManager.undock(EditorPanelDockableContent(File("/tmp/adfa4501/Main.kt")), BOUNDS)
			assertThat(DockingManager.windows.value).hasSize(2)

			controller.closeAll(save = false)

			assertThat(DockingManager.windows.value).isEmpty()
		}

	/**
	 * The teardown saves and releases file panels itself, according to the choice the user made in
	 * the close dialog. A [DockingEvent.Close] would hand the same panel to a listener that saves
	 * unconditionally, overriding "close without saving".
	 */
	@Test
	fun `teardown emits no docking event`() =
		runTest {
			val events = mutableListOf<DockingEvent>()
			backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				DockingManager.events.collect(events::add)
			}
			DockingManager.undock(EditorPanelDockableContent(File("/tmp/adfa4501/Notes.md")), BOUNDS)

			controller.closeAll(save = false)

			assertThat(events).isEmpty()
			assertThat(DockingManager.windows.value).isEmpty()
		}

	@Test
	fun `tearing down with no floating windows is a no-op`() =
		runTest {
			controller.closeAll(save = true)

			assertThat(DockingManager.windows.value).isEmpty()
		}

	@Test
	fun `save and close writes a modified panel out before releasing it`() =
		runTest {
			val panel = floatingPanel("/tmp/adfa4501/Main.kt", modified = true)
			coEvery { panel.save() } returns true
			DockingManager.undock(panel, BOUNDS)

			controller.closeAll(save = true)

			coVerifyOrder {
				panel.save()
				panel.release()
			}
			assertThat(DockingManager.windows.value).isEmpty()
		}

	/** "Close without saving" must reach the panel too, not just the docked editors. */
	@Test
	fun `close without saving discards a modified panel`() =
		runTest {
			val panel = floatingPanel("/tmp/adfa4501/Draft.kt", modified = true)
			DockingManager.undock(panel, BOUNDS)

			controller.closeAll(save = false)

			coVerify(exactly = 0) { panel.save() }
			verify { panel.release() }
			assertThat(DockingManager.windows.value).isEmpty()
		}

	/**
	 * A file that went away under its panel (deleted directory, revoked permission) makes
	 * `writeTo` throw. The project is closing: that must not crash the teardown, nor strand the
	 * windows queued behind the bad one - which is the very state this ticket exists to prevent.
	 */
	@Test
	fun `a panel whose save throws does not strand the windows behind it`() =
		runTest {
			val failing = floatingPanel("/tmp/adfa4501/Gone.kt", modified = true)
			coEvery { failing.save() } throws RuntimeException("parent directory deleted")
			val queued = floatingPanel("/tmp/adfa4501/Queued.kt", modified = false)
			DockingManager.undock(failing, BOUNDS)
			DockingManager.undock(queued, BOUNDS)

			controller.closeAll(save = true)

			assertThat(DockingManager.windows.value).isEmpty()
			verify { failing.release() }
			verify { queued.release() }
		}

	private fun floatingPanel(
		path: String,
		modified: Boolean,
	): EditorPanelDockableContent =
		mockk<EditorPanelDockableContent>(relaxed = true).also { panel ->
			every { panel.id } returns path
			every { panel.title } returns File(path).name
			every { panel.isModified } returns modified
		}

	private companion object {
		private val BOUNDS = WindowBounds(x = 0, y = 0, width = 600, height = 400)
	}
}
