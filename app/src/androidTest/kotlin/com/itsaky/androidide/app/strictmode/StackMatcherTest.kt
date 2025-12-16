package com.itsaky.androidide.app.strictmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StackMatcherTest {

	@Test
	fun adjacentFramesMatcherMatchesWhenFramesAreInOrder() {
		val matcher = StackMatcher.AdjacentInOrder(
			listOf(
				listOf(FrameMatcher.classAndMethod("com.example.ClassA", "methodA")),
				listOf(FrameMatcher.classAndMethod("com.example.ClassB", "methodB"))
			)
		)
		val stack = listOf(
			stackTraceElement("com.example.ClassA", "methodA"),
			stackTraceElement("com.example.ClassB", "methodB")
		)
		assertTrue(matcher.matches(stack))
	}

	@Test
	fun adjacentFramesMatcherDoesNotMatchWhenFramesAreNotInOrder() {
		val matcher = StackMatcher.AdjacentInOrder(
			listOf(
				listOf(FrameMatcher.classAndMethod("com.example.ClassA", "methodA")),
				listOf(FrameMatcher.classAndMethod("com.example.ClassB", "methodB"))
			)
		)
		val stack = listOf(
			stackTraceElement("com.example.ClassB", "methodB"),
			stackTraceElement("com.example.ClassA", "methodA")
		)
		assertFalse(matcher.matches(stack))
	}

	@Test
	fun adjacentFramesMatcherDoesNotMatchWhenOneFrameIsMissing() {
		val matcher = StackMatcher.AdjacentInOrder(
			listOf(
				listOf(FrameMatcher.classAndMethod("com.example.ClassA", "methodA")),
				listOf(FrameMatcher.classAndMethod("com.example.ClassB", "methodB"))
			)
		)
		val stack = listOf(
			stackTraceElement("com.example.ClassA", "methodA")
		)
		assertFalse(matcher.matches(stack))
	}

	@Test
	fun adjacentFramesMatcherHandlesAlternatives() {
		val matcher = StackMatcher.AdjacentInOrder(
			listOf(
				listOf(
					FrameMatcher.classAndMethod("com.example.ClassA", "methodA"),
					FrameMatcher.classAndMethod("com.example.ClassA", "alternativeA")
				),
				listOf(FrameMatcher.classAndMethod("com.example.ClassB", "methodB"))
			)
		)
		val stack = listOf(
			stackTraceElement("com.example.ClassA", "alternativeA"),
			stackTraceElement("com.example.ClassB", "methodB")
		)
		assertTrue(matcher.matches(stack))
	}

	private fun stackTraceElement(className: String, methodName: String): StackTraceElement {
		return StackTraceElement(className, methodName, "$className.java", 1)
	}
}
