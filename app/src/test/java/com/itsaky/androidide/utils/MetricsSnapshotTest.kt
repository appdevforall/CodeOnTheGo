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
	fun `a shared snapshot survives the next few exports`() {
		val shared = MetricsSnapshot.write(context, bitmap(), "Memory usage")!!

		// A share hands the recipient a FileProvider URI and the chooser returns long before the
		// recipient opens it. Deleting the previous file on the next export pulled the image out
		// from under an app that had not read it yet.
		repeat(3) { index -> MetricsSnapshot.write(context, bitmap(), "Chart $index") }

		assertThat(shared.exists()).isTrue()
	}

	@Test
	fun `the directory stays bounded across many exports`() {
		repeat(20) { index -> MetricsSnapshot.write(context, bitmap(), "Chart $index") }

		// Bounded, not unbounded: this is a scratch directory, not a gallery.
		val directory = MetricsSnapshot.write(context, bitmap(), "Last")!!.parentFile!!
		assertThat(directory.listFiles()!!.size).isAtMost(MetricsSnapshot.KEEP_RECENT)
	}

	@Test
	fun `the newest snapshot is the one handed back, and it is on disk`() {
		MetricsSnapshot.write(context, bitmap(), "Memory usage")
		val newest = MetricsSnapshot.write(context, bitmap(), "Network traffic")

		// This used to assert that the previous file was gone. It is not, deliberately: a share
		// can still be reading it. What has to hold is that the file returned exists and is in
		// the scratch directory, which stays bounded -- see the two tests above.
		assertThat(newest).isNotNull()
		assertThat(newest!!.exists()).isTrue()
		assertThat(newest.parentFile).isEqualTo(File(context.cacheDir, "metrics-snapshots"))
	}
}
