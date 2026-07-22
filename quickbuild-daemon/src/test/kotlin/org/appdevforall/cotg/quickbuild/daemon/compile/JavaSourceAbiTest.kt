package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The fingerprint decides whether a `.java` edit costs a full Kotlin recompile, so what it
 * ignores matters as much as what it captures: ignore too much and Kotlin bytecode goes
 * stale, ignore too little and every Java keystroke pays for a recompile it does not need.
 */
class JavaSourceAbiTest {
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

	private fun calculator(body: String) =
		write(
			"Calculator.java",
			"""
			package demo;

			public class Calculator {
				public int compute(int a, int b) { $body }
			}
			""",
		)

	@Test
	fun `a method body edit leaves the fingerprint unchanged`() {
		val before = fingerprintOf(calculator("return a + b;"))

		val after = fingerprintOf(calculator("int sum = a + b; return sum;"))

		assertThat(after).isEqualTo(before)
	}

	@Test
	fun `a return type change moves the fingerprint`() {
		val before = fingerprintOf(calculator("return a + b;"))

		val after =
			fingerprintOf(
				write(
					"Calculator.java",
					"""
					package demo;

					public class Calculator {
						public long compute(int a, int b) { return (long) a + b; }
					}
					""",
				),
			)

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `a parameter list change moves the fingerprint`() {
		val before = fingerprintOf(calculator("return a + b;"))

		val after =
			fingerprintOf(
				write(
					"Calculator.java",
					"""
					package demo;

					public class Calculator {
						public int compute(int a, int b, int c) { return a + b + c; }
					}
					""",
				),
			)

		assertThat(after).isNotEqualTo(before)
	}

	private fun limits(value: String) =
		write(
			"Limits.java",
			"""
			package demo;

			public class Limits {
				public static final int MAX = $value;
				private int scratch = 1;
			}
			""",
		)

	@Test
	fun `a static final constant's VALUE is part of the ABI`() {
		// Kotlin inlines Java compile-time constants into its callers, so the value moving
		// is an ABI change even though no signature did. Dropping this would let the
		// fast path leave Kotlin callers holding the old constant.
		val before = fingerprintOf(limits("5"))

		val after = fingerprintOf(limits("7"))

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `an instance field's initializer is not part of the ABI`() {
		val before = fingerprintOf(limits("5"))

		val after =
			fingerprintOf(
				write(
					"Limits.java",
					"""
					package demo;

					public class Limits {
						public static final int MAX = 5;
						private int scratch = 42;
					}
					""",
				),
			)

		assertThat(after).isEqualTo(before)
	}

	@Test
	fun `an annotation change moves the fingerprint`() {
		val before =
			fingerprintOf(
				write(
					"Annotated.java",
					"""
					package demo;

					public class Annotated {
						public String value() { return "x"; }
					}
					""",
				),
			)

		val after =
			fingerprintOf(
				write(
					"Annotated.java",
					"""
					package demo;

					public class Annotated {
						@Deprecated
						public String value() { return "x"; }
					}
					""",
				),
			)

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `a supertype change moves the fingerprint`() {
		val before =
			fingerprintOf(
				write(
					"Leaf.java",
					"""
					package demo;

					public class Leaf {
					}
					""",
				),
			)

		val after =
			fingerprintOf(
				write(
					"Leaf.java",
					"""
					package demo;

					public class Leaf implements java.io.Serializable {
					}
					""",
				),
			)

		assertThat(after).isNotEqualTo(before)
	}

	@Test
	fun `declared type names cover nested types`() {
		val file =
			write(
				"Outer.java",
				"""
				package demo;

				public class Outer {
					public static class Inner {
						public interface Deep {}
					}
				}
				""",
			)

		val abi = JavaSourceAbi.snapshot(listOf(file))!!.getValue(file)

		assertThat(abi.declaredTypeNames).containsExactly("Outer", "Inner", "Deep")
	}

	@Test
	fun `changedTypeNames reports a modified file's types`() {
		val file = calculator("return a + b;")
		val previous = JavaSourceAbi.snapshot(listOf(file))!!
		val current =
			JavaSourceAbi.snapshot(
				listOf(
					write(
						"Calculator.java",
						"""
						package demo;

						public class Calculator {
							public long compute(int a, int b) { return a; }
						}
						""",
					),
				),
			)!!

		assertThat(JavaSourceAbi.changedTypeNames(previous, current)).containsExactly("Calculator")
	}

	@Test
	fun `changedTypeNames reports nothing when only bodies moved`() {
		val previous = JavaSourceAbi.snapshot(listOf(calculator("return a + b;")))!!
		val current = JavaSourceAbi.snapshot(listOf(calculator("return b + a;")))!!

		assertThat(JavaSourceAbi.changedTypeNames(previous, current)).isEmpty()
	}

	@Test
	fun `changedTypeNames reports a deleted file's types, which callers may still reference`() {
		val gone = calculator("return a + b;")
		val previous = JavaSourceAbi.snapshot(listOf(gone))!!

		assertThat(JavaSourceAbi.changedTypeNames(previous, emptyMap())).containsExactly("Calculator")
	}

	@Test
	fun `changedTypeNames reports an added file's types`() {
		val added = calculator("return a + b;")
		val current = JavaSourceAbi.snapshot(listOf(added))!!

		assertThat(JavaSourceAbi.changedTypeNames(emptyMap(), current)).containsExactly("Calculator")
	}

	@Test
	fun `a rename reports both the old and the new name`() {
		val file = write("Renamed.java", "package demo;\n\npublic class Before {}")
		val previous = JavaSourceAbi.snapshot(listOf(file))!!
		val current =
			JavaSourceAbi.snapshot(listOf(write("Renamed.java", "package demo;\n\npublic class After {}")))!!

		assertThat(JavaSourceAbi.changedTypeNames(previous, current)).containsExactly("Before", "After")
	}

	@Test
	fun `no java sources is a known-empty ABI, not an unknown one`() {
		assertThat(JavaSourceAbi.snapshot(emptyList())).isEmpty()
	}
}
