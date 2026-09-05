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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the annotation throttle of ADFA-5486: significant events are Gradle task starts and stops,
 * and there are far too many of them to draw, so at most one every five seconds is kept.
 */
class MetricsAnnotationStoreTest {
	private var now = 1_000L
	private val store = MetricsAnnotationStore(nowMillis = { now })

	@Test
	fun `the first event is always recorded`() {
		assertThat(store.record(":app:compileKotlin")).isTrue()
		assertThat(store.recentAnnotations(60_000L)).hasSize(1)
	}

	@Test
	fun `events inside the throttle window are dropped`() {
		store.record("first")
		now += 1_000L
		assertThat(store.record("second")).isFalse()
		now += 3_000L
		assertThat(store.record("third")).isFalse()

		// A real build emits dozens of these a second; only the first survives.
		val labels = store.recentAnnotations(60_000L).map { it.label }
		assertThat(labels).containsExactly("first")
	}

	@Test
	fun `an event after the window is recorded`() {
		store.record("first")
		now += MetricsAnnotationStore.THROTTLE_INTERVAL_MS

		assertThat(store.record("second")).isTrue()
		assertThat(store.recentAnnotations(60_000L).map { it.label })
			.containsExactly("first", "second")
			.inOrder()
	}

	@Test
	fun `the first event of a quiet period is the one kept`() {
		// The interesting moment is when work began, not one from the middle of a burst.
		store.record("burst start")
		repeat(20) {
			now += 100L
			store.record("noise")
		}

		assertThat(store.recentAnnotations(60_000L).map { it.label }).containsExactly("burst start")
	}

	@Test
	fun `only annotations within the requested age are returned`() {
		store.record("old")
		now += 30_000L
		store.record("recent")

		assertThat(store.recentAnnotations(10_000L).map { it.label }).containsExactly("recent")
		assertThat(store.recentAnnotations(60_000L).map { it.label }).containsExactly("old", "recent").inOrder()
	}

	@Test
	fun `the store is bounded`() {
		repeat(MetricsAnnotationStore.MAX_ANNOTATIONS * 2) {
			now += MetricsAnnotationStore.THROTTLE_INTERVAL_MS
			store.record("task $it")
		}

		val all = store.recentAnnotations(Long.MAX_VALUE / 2)
		assertThat(all).hasSize(MetricsAnnotationStore.MAX_ANNOTATIONS)
		// The oldest are the ones dropped.
		assertThat(all.last().label).endsWith(
			(MetricsAnnotationStore.MAX_ANNOTATIONS * 2 - 1).toString(),
		)
	}

	@Test
	fun `clearing forgets the throttle as well as the annotations`() {
		store.record("first")
		store.clear()

		assertThat(store.recentAnnotations(60_000L)).isEmpty()
		// Without resetting the throttle, the next event would be swallowed for five seconds.
		assertThat(store.record("second")).isTrue()
	}
}
