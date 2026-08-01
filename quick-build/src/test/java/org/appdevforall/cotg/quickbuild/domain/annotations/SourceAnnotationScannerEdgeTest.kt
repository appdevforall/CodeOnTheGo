package org.appdevforall.cotg.quickbuild.domain.annotations

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Structural edge cases of [SourceAnnotationScanner]: lexer bail-outs, `@` tokens that
 * are not annotations, and body-exclusion shapes beyond the happy paths in
 * [SourceAnnotationScannerTest]. The scanner's contract under stress is fail-safe:
 * anything it cannot classify must either stay in the fingerprint or null the scan.
 */
class SourceAnnotationScannerEdgeTest {
	@Test
	fun `a file without a package declaration scans with an empty package`() {
		val facts = SourceAnnotationScanner.scan("class NoPackage")!!

		assertThat(facts.packageName).isEmpty()
		assertThat(facts.declaredTypeNames).containsExactly("NoPackage")
	}

	@Test
	fun `java static imports resolve to the imported path`() {
		val facts =
			SourceAnnotationScanner.scan(
				"""
				package com.example;
				import static org.junit.Assert.assertEquals;
				import java.util.List;
				class J {}
				""".trimIndent(),
			)!!

		assertThat(facts.imports).containsExactly("org.junit.Assert.assertEquals", "java.util.List").inOrder()
	}

	@Test
	fun `an empty string literal at end of line does not open a raw string`() {
		val facts = SourceAnnotationScanner.scan("""val a = ""${'\n'}val b = 2""")!!

		assertThat(facts.declarationFingerprint).contains("val b = 2")
	}

	@Test
	fun `a string ending in a bare escape at EOF bails`() {
		assertThat(SourceAnnotationScanner.scan("""val s = "abc\""")).isNull()
	}

	@Test
	fun `a newline inside a single-quoted literal bails`() {
		assertThat(SourceAnnotationScanner.scan("val s = \"abc\ndef\"")).isNull()
	}

	@Test
	fun `an escaped quote does not close the literal`() {
		val facts = SourceAnnotationScanner.scan("""@Suppress("say \"hi\"") class A""")!!

		assertThat(facts.annotations.single().arguments).contains("\\\"hi\\\"")
	}

	@Test
	fun `a raw string closed with only two quotes at EOF bails`() {
		assertThat(SourceAnnotationScanner.scan("val s = \"\"\"body\"\"")).isNull()
	}

	@Test
	fun `a close brace before any open bails`() {
		assertThat(SourceAnnotationScanner.scan("}\nclass A {}")).isNull()
	}

	@Test
	fun `a qualified this reference is not an annotation`() {
		val facts =
			SourceAnnotationScanner.scan(
				"""
				class Outer {
					val id = this@Outer.hashCode()
				}
				""".trimIndent(),
			)!!

		assertThat(facts.annotations).isEmpty()
	}

	@Test
	fun `an at sign not followed by an identifier is skipped`() {
		val facts = SourceAnnotationScanner.scan("val weird = 1 @ 2\nclass A")!!

		assertThat(facts.annotations).isEmpty()
		assertThat(facts.declaredTypeNames).containsExactly("A")
	}

	@Test
	fun `an annotation at end of file parses without arguments`() {
		val facts = SourceAnnotationScanner.scan("class A\n@Deprecated")!!

		assertThat(facts.annotations.single().name).isEqualTo("Deprecated")
		assertThat(facts.annotations.single().arguments).isEmpty()
	}

	@Test
	fun `a fully qualified annotation keeps its dotted name`() {
		val facts = SourceAnnotationScanner.scan("@java.lang.Deprecated class A")!!

		assertThat(facts.annotations.single().name).isEqualTo("java.lang.Deprecated")
	}

	@Test
	fun `a trailing dot after an annotation name belongs to the next token`() {
		val facts = SourceAnnotationScanner.scan("class A\n@Outer.")!!

		assertThat(facts.annotations.single().name).isEqualTo("Outer")
	}

	@Test
	fun `an unclosed annotation argument list stops annotation extraction`() {
		val facts = SourceAnnotationScanner.scan("@First class A\n@Broken(unclosed")!!

		// The paren never closes, so extraction keeps what it had - the file still
		// scans (braces balance) and the earlier annotation survives.
		assertThat(facts.annotations.map { it.name }).containsExactly("First")
	}

	@Test
	fun `tab-separated annotation arguments still attach`() {
		val facts = SourceAnnotationScanner.scan("@Suppress\t(\"x\") class A")!!

		assertThat(facts.annotations.single().arguments).isEqualTo("""("x")""")
	}

	@Test
	fun `secondary constructor bodies are excluded from the fingerprint`() {
		val facts =
			SourceAnnotationScanner.scan(
				"""
				class A(val x: Int) {
					constructor() : this(0) {
						println("side effect")
					}
				}
				""".trimIndent(),
			)!!

		assertThat(facts.declarationFingerprint.joinToString("\n")).doesNotContain("side effect")
		assertThat(facts.declarationFingerprint.joinToString("\n")).contains("constructor()")
	}

	@Test
	fun `init blocks are excluded from the fingerprint`() {
		val facts =
			SourceAnnotationScanner.scan(
				"""
				class A {
					init {
						val hidden = 1
					}
					val kept = 2
				}
				""".trimIndent(),
			)!!

		val fingerprint = facts.declarationFingerprint.joinToString("\n")
		assertThat(fingerprint).doesNotContain("hidden")
		assertThat(fingerprint).contains("val kept = 2")
	}

	@Test
	fun `property accessor bodies are excluded from the fingerprint`() {
		val facts =
			SourceAnnotationScanner.scan(
				"""
				class A {
					val v: Int
						get() {
							return 42
						}
				}
				""".trimIndent(),
			)!!

		assertThat(facts.declarationFingerprint.joinToString("\n")).doesNotContain("return 42")
	}

	@Test
	fun `a single-line function keeps the fingerprint balanced`() {
		// Opens and closes on one line: net zero braces, so the line itself stays.
		val facts =
			SourceAnnotationScanner.scan(
				"""
				class A {
					fun f() { work() }
					val kept = 1
				}
				""".trimIndent(),
			)!!

		assertThat(facts.declarationFingerprint.joinToString("\n")).contains("val kept = 1")
	}

	@Test
	fun `an empty string as the file's last token still scans`() {
		val facts = SourceAnnotationScanner.scan("val s = \"\"")!!

		assertThat(facts.declarationFingerprint).contains("val s = \"\"")
	}

	@Test
	fun `an at sign as the file's last character is not an annotation`() {
		val facts = SourceAnnotationScanner.scan("class A\n@")!!

		assertThat(facts.annotations).isEmpty()
	}

	@Test
	fun `at signs glued to identifiers or other at signs are not annotations`() {
		val facts = SourceAnnotationScanner.scan("val a = b@c\nval d_@e = 1\nval f = g@@h\nclass A")!!

		assertThat(facts.annotations).isEmpty()
	}

	@Test
	fun `a lone double quote inside a raw string does not close it`() {
		val facts = SourceAnnotationScanner.scan("val s = \"\"\"say \" once\"\"\"\nval kept = 1")!!

		assertThat(facts.declarationFingerprint.joinToString("\n")).contains("val kept = 1")
	}

	@Test
	fun `a block comment on a single line strips without eating the line`() {
		val facts = SourceAnnotationScanner.scan("val a = /* inline */ 1")!!

		assertThat(facts.declarationFingerprint).containsExactly("val a = 1")
	}

	@Test
	fun `a line comment as the file's last bytes strips cleanly`() {
		val facts = SourceAnnotationScanner.scan("class A // no trailing newline")!!

		assertThat(facts.declarationFingerprint).containsExactly("class A")
	}

	@Test
	fun `a lone star inside a block comment does not close it`() {
		val facts = SourceAnnotationScanner.scan("val a = /* 2*3 */ 6")!!

		assertThat(facts.declarationFingerprint).containsExactly("val a = 6")
	}

	@Test
	fun `a lambda default in the signature still excludes only the body`() {
		// Two opens on the signature line ({} default + the body brace): the body mark
		// must attach to the LAST open, not the first.
		val facts =
			SourceAnnotationScanner.scan(
				"""
				class A {
					fun f(block: () -> Unit = {}) {
						hiddenWork()
					}
					val kept = 1
				}
				""".trimIndent(),
			)!!

		val fingerprint = facts.declarationFingerprint.joinToString("\n")
		assertThat(fingerprint).doesNotContain("hiddenWork")
		assertThat(fingerprint).contains("val kept = 1")
	}

	@Test
	fun `a multi-line raw string keeps its line structure and its braces masked`() {
		val facts =
			SourceAnnotationScanner.scan(
				"class A {\n\tval sql = \"\"\"SELECT *\n\t\tFROM { nowhere }\n\t\"\"\"\n}",
			)!!

		// The brace inside the raw string must not have derailed nesting, and the
		// literal's content stays in the fingerprint verbatim.
		assertThat(facts.declarationFingerprint.joinToString("\n")).contains("FROM { nowhere }")
	}

	@Test
	fun `division and multiplication are not comment openers`() {
		val facts = SourceAnnotationScanner.scan("val half = 6 / 2\nval product = 2 * 3")!!

		assertThat(facts.declarationFingerprint)
			.containsExactly("val half = 6 / 2", "val product = 2 * 3")
			.inOrder()
	}

	@Test
	fun `annotation argument types count as referenced`() {
		val facts = SourceAnnotationScanner.scan("@TypeConverters(DateConverter::class) class Db")!!

		assertThat(facts.referencedTypeNames).contains("DateConverter")
	}
}
