package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression tests for ReflectUtils, added when replacing com.blankj:utilcodex's ReflectUtils
 * with an in-house implementation (ADFA-4649), including a fix for a final-field-modifier gap
 * (blankj clears a field's FINAL bit before setting it; the initial port omitted this).
 */
class ReflectUtilsTest {
	private open class Base {
		@Suppress("unused")
		private val baseField: String = "base-value"
	}

	private class Target(
		private var count: Int,
	) : Base() {
		@Suppress("unused")
		private fun add(delta: Int): Int {
			count += delta
			return count
		}

		@Suppress("unused")
		private fun reset() {
			count = 0
		}
	}

	private class HasFinalField {
		@Suppress("unused")
		private val locked: String = "original"
	}

	private class Widget(
		val label: String,
	)

	@Test
	fun `field reads a private field declared on the class itself`() {
		val value = ReflectUtils.reflect(Target(5)).field("count").get<Int>()

		assertThat(value).isEqualTo(5)
	}

	@Test
	fun `field reads a private field declared on a superclass`() {
		val value = ReflectUtils.reflect(Target(0)).field("baseField").get<String>()

		assertThat(value).isEqualTo("base-value")
	}

	@Test
	fun `field sets a private field`() {
		val target = Target(0)

		ReflectUtils.reflect(target).field("count", 42)

		assertThat(ReflectUtils.reflect(target).field("count").get<Int>()).isEqualTo(42)
	}

	@Test
	fun `field sets a final instance field`() {
		val instance = HasFinalField()

		ReflectUtils.reflect(instance).field("locked", "changed")

		val value = ReflectUtils.reflect(instance).field("locked").get<String>()
		assertThat(value).isEqualTo("changed")
	}

	@Test
	fun `field throws ReflectException for a field that doesn't exist`() {
		assertThrows(ReflectUtils.ReflectException::class.java) {
			ReflectUtils.reflect(Target(0)).field("doesNotExist")
		}
	}

	@Test
	fun `method invokes a private method and unwraps its return value`() {
		val result = ReflectUtils.reflect(Target(10)).method("add", 5).get<Int>()

		assertThat(result).isEqualTo(15)
	}

	@Test
	fun `method returns the same wrapper for a void method, allowing chaining`() {
		val target = Target(10)

		val count =
			ReflectUtils
				.reflect(target)
				.method("reset")
				.field("count")
				.get<Int>()

		assertThat(count).isEqualTo(0)
	}

	@Test
	fun `method throws ReflectException for a method that doesn't exist`() {
		assertThrows(ReflectUtils.ReflectException::class.java) {
			ReflectUtils.reflect(Target(0)).method("doesNotExist")
		}
	}

	@Test
	fun `newInstance constructs via a matching constructor`() {
		val widget = ReflectUtils.reflect(Widget::class.java).newInstance("hello").get<Widget>()

		assertThat(widget.label).isEqualTo("hello")
	}
}
