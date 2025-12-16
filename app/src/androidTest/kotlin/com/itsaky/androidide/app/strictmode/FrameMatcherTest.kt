package com.itsaky.androidide.app.strictmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMatcherTest {

	@Test
	fun classAndMethodMatchesWhenClassAndMethodNameAreCorrect() {
		val matcher = FrameMatcher.classAndMethod("com.example.TestClass", "testMethod")
		val frame = StackTraceElement("com.example.TestClass", "testMethod", "TestClass.java", 10)
		assertTrue(matcher.matches(frame))
	}

	@Test
	fun classAndMethodDoesNotMatchWhenClassNameIsIncorrect() {
		val matcher = FrameMatcher.classAndMethod("com.example.TestClass", "testMethod")
		val frame = StackTraceElement("com.example.WrongClass", "testMethod", "WrongClass.java", 10)
		assertFalse(matcher.matches(frame))
	}

	@Test
	fun classAndMethodDoesNotMatchWhenMethodNameIsIncorrect() {
		val matcher = FrameMatcher.classAndMethod("com.example.TestClass", "testMethod")
		val frame = StackTraceElement("com.example.TestClass", "wrongMethod", "TestClass.java", 10)
		assertFalse(matcher.matches(frame))
	}
}
