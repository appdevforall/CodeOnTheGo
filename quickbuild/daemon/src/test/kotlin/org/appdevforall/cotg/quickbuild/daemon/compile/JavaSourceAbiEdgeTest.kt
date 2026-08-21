package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Declaration kinds beyond JavaSourceAbiTest's classes-and-methods core: whether each
 * kind's edit is IN the fingerprint decides between a stale-bytecode bug (ignored when it
 * shouldn't be) and a needless full Kotlin recompile (included when it needn't be).
 */
class JavaSourceAbiEdgeTest {
	@TempDir
	lateinit var tempDir: File

	private fun write(
		name: String,
		content: String,
	): File = File(tempDir, name).apply { writeText(content.trimIndent()) }

	private fun fingerprintOf(file: File): String {
		val snapshot = JavaSourceAbi.snapshot(listOf(file))
		assertThat(snapshot).isNotNull()
		return snapshot!!.getValue(file).fingerprint
	}

	@Test
	fun `a source that becomes unreadable still flags its old types as changed`() {
		// javac error-recovers instead of throwing: an unreadable file parses to an
		// EMPTY declaration set, so its fingerprint moves and changedTypeNames names the
		// types it used to declare - which is exactly what forces the conservative full
		// Kotlin recompile. (The snapshot's null path is reserved for real exceptions.)
		val locked = write("Locked.java", "package demo;\n\npublic class Locked {}")
		val previous = JavaSourceAbi.snapshot(listOf(locked))!!
		check(locked.setReadable(false)) { "could not revoke read permission" }
		try {
			val current = JavaSourceAbi.snapshot(listOf(locked))!!

			assertThat(JavaSourceAbi.changedTypeNames(previous, current)).containsExactly("Locked")
		} finally {
			locked.setReadable(true)
		}
	}

	@Test
	fun `the package declaration is part of the ABI`() {
		val without = write("A.java", "public class Widget {}")
		val with = write("B.java", "package demo;\n\npublic class Widget {}")

		assertThat(fingerprintOf(without)).isNotEqualTo(fingerprintOf(with))
		assertThat(JavaSourceAbi.snapshot(listOf(without))!!.getValue(without).declaredTypeNames)
			.containsExactly("Widget")
	}

	@Test
	fun `an interface constant's value is ABI even without static final modifiers`() {
		// Interface fields are implicitly constant; Kotlin inlines them like any other
		// compile-time constant.
		val before = fingerprintOf(write("Limits.java", "package demo;\n\npublic interface Limits { int MAX = 5; }"))
		val after = fingerprintOf(write("Limits.java", "package demo;\n\npublic interface Limits { int MAX = 7; }"))

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `an annotation member's default value is ABI`() {
		val before =
			fingerprintOf(
				write("Marker.java", "package demo;\n\npublic @interface Marker { String value() default \"x\"; }"),
			)
		val after =
			fingerprintOf(
				write("Marker.java", "package demo;\n\npublic @interface Marker { String value() default \"y\"; }"),
			)

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `a constructor's parameter list is ABI`() {
		val before = fingerprintOf(write("Box.java", "package demo;\n\npublic class Box {\n\tpublic Box() {}\n}"))
		val after = fingerprintOf(write("Box.java", "package demo;\n\npublic class Box {\n\tpublic Box(int size) {}\n}"))

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `a static initializer block is not ABI`() {
		val without = fingerprintOf(write("Init.java", "package demo;\n\npublic class Init {\n\tstatic int x;\n}"))
		val with =
			fingerprintOf(
				write("Init.java", "package demo;\n\npublic class Init {\n\tstatic int x;\n\tstatic { x = 3; }\n}"),
			)

		assertThat(with).isEqualTo(without)
	}

	@Test
	fun `an extends clause is ABI`() {
		val plain = fingerprintOf(write("Leaf.java", "package demo;\n\npublic class Leaf {}"))
		val extending =
			fingerprintOf(
				write("Leaf.java", "package demo;\n\npublic class Leaf extends java.util.ArrayList<String> {}"),
			)

		assertThat(extending).isNotEqualTo(plain)
	}

	@Test
	fun `a non-final static field's initializer is not ABI`() {
		// Only static AND final makes a Java compile-time constant Kotlin can inline; a
		// mutable static's initializer is implementation, and charging a full Kotlin
		// recompile for editing it would make the ABI shortcut pointless.
		val before =
			fingerprintOf(write("Counter.java", "package demo;\n\npublic class Counter { static int next = 1; }"))
		val after =
			fingerprintOf(write("Counter.java", "package demo;\n\npublic class Counter { static int next = 2; }"))

		assertThat(after).isEqualTo(before)
	}

	@Test
	fun `an explicitly static final interface constant is still a constant`() {
		// Redundant modifiers spelled out must not change the classification.
		val before =
			fingerprintOf(write("Caps.java", "package demo;\n\npublic interface Caps { static final int M = 1; }"))
		val after =
			fingerprintOf(write("Caps.java", "package demo;\n\npublic interface Caps { static final int M = 2; }"))

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `a stray top-level semicolon is not ABI`() {
		val without = fingerprintOf(write("Tidy.java", "package demo;\n\npublic class Tidy {}"))
		val with = fingerprintOf(write("Tidy.java", "package demo;\n\npublic class Tidy {};"))

		assertThat(with).isEqualTo(without)
	}

	@Test
	fun `a duplicated source entry yields null - the per-file map cannot attribute it`() {
		// Conservative contract: when the snapshot cannot represent the input faithfully
		// it must say "unknown" (forcing a full Kotlin recompile), never half an answer.
		val file = write("Dup.java", "package demo;\n\npublic class Dup {}")

		assertThat(JavaSourceAbi.snapshot(listOf(file, file))).isNull()
	}

	@Test
	fun `an enum's constant set is ABI`() {
		// Kotlin `when` exhaustiveness and constant references both see enum constants.
		val before = fingerprintOf(write("Color.java", "package demo;\n\npublic enum Color { RED }"))
		val after = fingerprintOf(write("Color.java", "package demo;\n\npublic enum Color { RED, BLUE }"))

		assertThat(after).isNotEqualTo(before)
	}
}
