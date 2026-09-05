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

package com.itsaky.androidide.utils

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pins ADFA-5486's snapshot export: a chart becomes a PNG in the cache, named after the chart, with
 * only the newest one kept.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsSnapshotTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun bitmap() = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)

	@Test
	fun `writes a png into the cache`() {
		val file = MetricsSnapshot.write(context, bitmap(), "Memory usage")

		assertThat(file).isNotNull()
		assertThat(file!!.exists()).isTrue()
		assertThat(file.extension).isEqualTo("png")
		assertThat(file.length()).isGreaterThan(0L)
		// Under the cache, so the platform can reclaim it.
		assertThat(file.absolutePath).startsWith(context.cacheDir.absolutePath)
	}

	@Test
	fun `names the file after the chart`() {
		val file = MetricsSnapshot.write(context, bitmap(), "Network traffic")

		assertThat(file!!.name).startsWith("network-traffic-")
	}

	@Test
	fun `a title with punctuation or non-ascii still makes a usable filename`() {
		// Chart titles are translated, so they are not guaranteed to be filename-safe.
		val file = MetricsSnapshot.write(context, bitmap(), "Mémoire / usage (MB)")

		assertThat(file).isNotNull()
		assertThat(file!!.name).matches("[a-z0-9-]+\\.png")
	}

	@Test
	fun `a title with nothing usable still produces a file`() {
		val file = MetricsSnapshot.write(context, bitmap(), "***")

		assertThat(file).isNotNull()
		assertThat(file!!.name).startsWith("metrics-")
	}

	@Test
	fun `only the newest snapshot is kept`() {
		val first = MetricsSnapshot.write(context, bitmap(), "Memory usage")
		val second = MetricsSnapshot.write(context, bitmap(), "Network traffic")

		assertThat(second).isNotNull()
		// This is a scratch directory for handing one image to another app, not a gallery.
		val directory = File(context.cacheDir, "metrics-snapshots")
		assertThat(directory.listFiles()!!.map { it.name }).containsExactly(second!!.name)
		assertThat(first!!.exists()).isFalse()
	}
}
