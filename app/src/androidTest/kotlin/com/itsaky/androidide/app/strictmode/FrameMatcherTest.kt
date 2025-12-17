package com.itsaky.androidide.app.strictmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FrameMatcherTest {

	private val frame =
		stackTraceElement(
			className = "com.example.foo.BarService",
			methodName = "doWork"
		)

	/* ---------- Class matchers ---------- */

	@Test
	fun classEquals_matches_exact_class_name() {
		val matcher = FrameMatcher.classEquals("com.example.foo.BarService")
		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun classEquals_fails_on_different_class_name() {
		val matcher = FrameMatcher.classEquals("com.example.foo.Other")
		assertThat(matcher.matches(frame)).isFalse()
	}

	@Test
	fun classStartsWith_matches_prefix() {
		val matcher = FrameMatcher.classStartsWith("com.example.foo")
		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun classStartsWith_fails_on_wrong_prefix() {
		val matcher = FrameMatcher.classStartsWith("org.example")
		assertThat(matcher.matches(frame)).isFalse()
	}

	@Test
	fun classContains_matches_token() {
		val matcher = FrameMatcher.classContains("Bar")
		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun classContains_fails_when_token_absent() {
		val matcher = FrameMatcher.classContains("Baz")
		assertThat(matcher.matches(frame)).isFalse()
	}

	/* ---------- Method matchers ---------- */

	@Test
	fun methodEquals_matches_exact_method_name() {
		val matcher = FrameMatcher.methodEquals("doWork")
		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun methodEquals_fails_on_different_method_name() {
		val matcher = FrameMatcher.methodEquals("run")
		assertThat(matcher.matches(frame)).isFalse()
	}

	@Test
	fun methodStartsWith_matches_prefix() {
		val matcher = FrameMatcher.methodStartsWith("do")
		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun methodStartsWith_fails_on_wrong_prefix() {
		val matcher = FrameMatcher.methodStartsWith("run")
		assertThat(matcher.matches(frame)).isFalse()
	}

	@Test
	fun methodContains_matches_token() {
		val matcher = FrameMatcher.methodContains("Work")
		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun methodContains_fails_when_token_absent() {
		val matcher = FrameMatcher.methodContains("Task")
		assertThat(matcher.matches(frame)).isFalse()
	}

	/* ---------- Combined matchers ---------- */

	@Test
	fun classAndMethod_matches_when_both_match() {
		val matcher = FrameMatcher.classAndMethod(
			"com.example.foo.BarService",
			"doWork"
		)

		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun classAndMethod_fails_when_class_mismatches() {
		val matcher = FrameMatcher.classAndMethod(
			"com.example.Other",
			"doWork"
		)

		assertThat(matcher.matches(frame)).isFalse()
	}

	@Test
	fun classStartsWithAndMethod_matches_prefix_and_exact_method() {
		val matcher = FrameMatcher.classStartsWithAndMethod(
			"com.example.foo",
			"doWork"
		)

		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun classStartsWithAndMethod_fails_when_method_differs() {
		val matcher = FrameMatcher.classStartsWithAndMethod(
			"com.example.foo",
			"run"
		)

		assertThat(matcher.matches(frame)).isFalse()
	}

	/* ---------- Composition ---------- */

	@Test
	fun anyOf_matches_when_any_matcher_matches() {
		val matcher = FrameMatcher.anyOf(
			FrameMatcher.classEquals("wrong.Class"),
			FrameMatcher.methodEquals("doWork")
		)

		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun anyOf_fails_when_no_matcher_matches() {
		val matcher = FrameMatcher.anyOf(
			FrameMatcher.classEquals("wrong.Class"),
			FrameMatcher.methodEquals("run")
		)

		assertThat(matcher.matches(frame)).isFalse()
	}

	@Test
	fun allOf_matches_when_all_matchers_match() {
		val matcher = FrameMatcher.allOf(
			FrameMatcher.classContains("Bar"),
			FrameMatcher.methodStartsWith("do")
		)

		assertThat(matcher.matches(frame)).isTrue()
	}

	@Test
	fun allOf_fails_when_any_matcher_fails() {
		val matcher = FrameMatcher.allOf(
			FrameMatcher.classContains("Bar"),
			FrameMatcher.methodEquals("run")
		)

		assertThat(matcher.matches(frame)).isFalse()
	}
}
