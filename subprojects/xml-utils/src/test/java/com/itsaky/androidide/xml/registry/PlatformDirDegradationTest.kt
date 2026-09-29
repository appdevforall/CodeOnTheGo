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

package com.itsaky.androidide.xml.registry

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.xml.resources.ResourceTableRegistry
import com.itsaky.androidide.xml.widgets.WidgetTableRegistry
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every platform table feeds editor hints and is read on the path that opens a project, so an
 * unreadable platform file must cost the hints rather than the project.
 *
 * @author Akash Yadav
 */
@RunWith(RobolectricTestRunner::class)
class PlatformDirDegradationTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	@Test
	fun `an unopenable intent action list degrades to no resource table`() {
		val platform = tempDir.newFolder("res-platform")
		platform.writeFile("data/res/values/attrs_manifest.xml", "<resources/>")

		/*
		 * A directory passes the exists/canRead guard and then fails to open, which is how a
		 * half-extracted SDK looks -- and needs no permission change, so it behaves the same
		 * for a root test runner.
		 */
		check(File(platform, "data/activity_actions.txt").mkdirs())

		assertThat(ResourceTableRegistry.getInstance().forPlatformDir(platform)).isNull()
	}

	@Test
	fun `an unreadable widget list degrades to no widget table`() {
		val platform = tempDir.newFolder("widget-platform")
		val widgets = platform.writeFile("data/widgets.txt", "android.view.View")
		widgets.setReadable(false)
		assumeFalse("Test runner can read a file with no read permission", widgets.canRead())

		assertThat(WidgetTableRegistry.getInstance().forPlatformDir(platform)).isNull()
	}

	private fun File.writeFile(
		path: String,
		content: String,
	): File =
		File(this, path).apply {
			parentFile.mkdirs()
			writeText(content)
		}
}
