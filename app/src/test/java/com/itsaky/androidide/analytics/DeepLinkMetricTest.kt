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

package com.itsaky.androidide.analytics

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.models.PendingFileRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkMetricTest {
	private fun request(
		line: String? = null,
		column: String? = null,
		file: String? = null,
	) = DeepLinkRequest(
		projectName = "MyApp",
		fileRequest = file?.let { PendingFileRequest(filePath = it, lineRaw = line, columnRaw = column) },
	)

	@Test
	fun `depth reflects how far down the optional segments a link reached`() {
		assertThat(request().depth()).isEqualTo(DeepLinkDepth.PROJECT)
		assertThat(request(file = "a.kt").depth()).isEqualTo(DeepLinkDepth.FILE)
		assertThat(request(file = "a.kt", line = "42").depth()).isEqualTo(DeepLinkDepth.LINE)
		assertThat(request(file = "a.kt", line = "42", column = "7").depth()).isEqualTo(DeepLinkDepth.COLUMN)
	}

	// A malformed line/column is still *present* in the URL, and the code reports it to the user
	// rather than ignoring it -- so the metric has to count it as having reached that depth, or the
	// "links people send are more specific than they resolve" signal quietly loses its worst cases.
	@Test
	fun `a non-numeric line still counts as line depth`() {
		assertThat(request(file = "a.kt", line = "notanumber").depth()).isEqualTo(DeepLinkDepth.LINE)
	}

	// The whole point of hashing: a project name is the user's content and must not leave the device.
	@Test
	fun `the bundle carries a project hash, never the project name`() {
		val bundle = DeepLinkMetric(DeepLinkDepth.PROJECT, DeepLinkOutcome.RECEIVED, "MySecretProject").asBundle()

		assertThat(bundle.getLong("project_hash")).isEqualTo("MySecretProject".hashCode().toLong())
		for (key in bundle.keySet()) {
			assertThat(bundle.get(key).toString()).doesNotContain("MySecretProject")
		}
	}

	@Test
	fun `depth and outcome are recorded as lowercase names`() {
		val bundle = DeepLinkMetric(DeepLinkDepth.COLUMN, DeepLinkOutcome.PROJECT_UNVERIFIABLE).asBundle()

		assertThat(bundle.getString("depth")).isEqualTo("column")
		assertThat(bundle.getString("outcome")).isEqualTo("project_unverifiable")
	}

	// Omitted rather than logged as 0/"": a metric with no project (an unparseable link, or the
	// pre-parse setup gate) must not look like one for a project that hashes to zero.
	@Test
	fun `no project means no project_hash key at all`() {
		val bundle = DeepLinkMetric(DeepLinkDepth.UNKNOWN, DeepLinkOutcome.INVALID_LINK).asBundle()

		assertThat(bundle.containsKey("project_hash")).isFalse()
	}

	// One event name across every outcome, so the funnel is a single event filtered by `outcome`
	// rather than a set of separate events that have to be summed to spot a drop-off.
	@Test
	fun `every outcome uses the one event name`() {
		for (outcome in DeepLinkOutcome.entries) {
			assertThat(DeepLinkMetric(DeepLinkDepth.PROJECT, outcome).eventName).isEqualTo(DeepLinkMetric.EVENT_NAME)
		}
	}
}
