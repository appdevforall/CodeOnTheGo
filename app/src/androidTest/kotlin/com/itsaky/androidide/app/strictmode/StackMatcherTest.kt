package com.itsaky.androidide.app.strictmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class StackMatcherTest {

	/* ---------- Adjacent ---------- */

	@Test
	fun adjacent_matches_when_frames_are_adjacent_and_in_order() {
		val frames = listOf(
			stackTraceElement("a.A", "m1"),
			stackTraceElement("b.B", "m2"),
			stackTraceElement("c.C", "m3"),
		)

		val matcher = StackMatcher.Adjacent(
			listOf(
				FrameMatcher.classAndMethod("b.B", "m2"),
				FrameMatcher.classAndMethod("c.C", "m3"),
			)
		)

		assertThat(matcher.matches(frames)).isTrue()
	}

	@Test
	fun adjacent_does_not_match_when_order_is_wrong() {
		val frames = listOf(
			stackTraceElement("b.B", "m2"),
			stackTraceElement("c.C", "m3"),
		)

		val matcher = StackMatcher.Adjacent(
			listOf(
				FrameMatcher.classAndMethod("c.C", "m3"),
				FrameMatcher.classAndMethod("b.B", "m2"),
			)
		)

		assertThat(matcher.matches(frames)).isFalse()
	}

	@Test
	fun adjacent_does_not_match_when_not_adjacent() {
		val frames = listOf(
			stackTraceElement("a.A", "m1"),
			stackTraceElement("x.X", "gap"),
			stackTraceElement("b.B", "m2"),
		)

		val matcher = StackMatcher.Adjacent(
			listOf(
				FrameMatcher.classAndMethod("a.A", "m1"),
				FrameMatcher.classAndMethod("b.B", "m2"),
			)
		)

		assertThat(matcher.matches(frames)).isFalse()
	}

	/* ---------- InOrder ---------- */

	@Test
	fun inOrder_matches_when_frames_exist_in_sequence() {
		val frames = listOf(
			stackTraceElement("a.A", "m1"),
			stackTraceElement("x.X", "gap"),
			stackTraceElement("b.B", "m2"),
			stackTraceElement("y.Y", "gap"),
			stackTraceElement("c.C", "m3"),
		)

		val matcher = StackMatcher.InOrder(
			listOf(
				FrameMatcher.classAndMethod("a.A", "m1"),
				FrameMatcher.classAndMethod("b.B", "m2"),
				FrameMatcher.classAndMethod("c.C", "m3"),
			)
		)

		assertThat(matcher.matches(frames)).isTrue()
	}

	@Test
	fun inOrder_does_not_match_when_order_is_violated() {
		val frames = listOf(
			stackTraceElement("a.A", "m1"),
			stackTraceElement("c.C", "m3"),
			stackTraceElement("b.B", "m2"),
		)

		val matcher = StackMatcher.InOrder(
			listOf(
				FrameMatcher.classAndMethod("a.A", "m1"),
				FrameMatcher.classAndMethod("b.B", "m2"),
				FrameMatcher.classAndMethod("c.C", "m3"),
			)
		)

		assertThat(matcher.matches(frames)).isFalse()
	}

	/* ---------- AdjacentInOrder ---------- */

	@Test
	fun adjacentInOrder_matches_multiple_groups_in_sequence() {
		val frames = listOf(
			stackTraceElement("a.A", "m1"),
			stackTraceElement("a.A", "m2"),
			stackTraceElement("x.X", "gap"),
			stackTraceElement("b.B", "m3"),
		)

		val matcher = StackMatcher.AdjacentInOrder(
			listOf(
				listOf(
					FrameMatcher.classAndMethod("a.A", "m1"),
					FrameMatcher.classAndMethod("a.A", "m2"),
				),
				listOf(
					FrameMatcher.classAndMethod("b.B", "m3"),
				),
			)
		)

		assertThat(matcher.matches(frames)).isTrue()
	}

	@Test
	fun adjacentInOrder_fails_when_any_group_is_missing() {
		val frames = listOf(
			stackTraceElement("a.A", "m1"),
			stackTraceElement("x.X", "gap"),
			stackTraceElement("b.B", "m3"),
		)

		val matcher = StackMatcher.AdjacentInOrder(
			listOf(
				listOf(
					FrameMatcher.classAndMethod("a.A", "m1"),
					FrameMatcher.classAndMethod("a.A", "m2"),
				),
				listOf(
					FrameMatcher.classAndMethod("b.B", "m3"),
				),
			)
		)

		assertThat(matcher.matches(frames)).isFalse()
	}
}
