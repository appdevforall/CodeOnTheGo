package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * javac's structured diagnostics mapped onto the protocol shape: errors block with a
 * location, advisory notes pass through as warnings without one - the severity split is
 * what lets the client fail a build on ERROR while still showing the rest.
 */
class JavaCompileStepTest {
	@TempDir
	lateinit var tempDir: File

	private fun outputDir(): File = File(tempDir, "classes").apply { mkdirs() }

	@Test
	fun `a compile error fails with an ERROR diagnostic locating the problem`() {
		val broken =
			File(tempDir, "Broken.java").apply {
				writeText("package demo;\n\npublic class Broken {\n\tint x = ;\n}\n")
			}

		val result = JavaCompileStep.compile(listOf(broken), emptyList(), outputDir())

		assertThat(result.success).isFalse()
		val error = result.diagnostics.first { it.severity == Diagnostic.Severity.ERROR }
		assertThat(error.file).contains("Broken.java")
		assertThat(error.line).isEqualTo(4)
		assertThat(error.column).isNotNull()
	}

	@Test
	fun `an advisory javac note compiles successfully as a WARNING without a fabricated location`() {
		// Raw-type use draws javac's file-level "uses unchecked or unsafe operations"
		// note: no position exists, so line/column must read back null - inventing one
		// would send the IDE's jump-to-diagnostic somewhere wrong.
		val rawUser =
			File(tempDir, "RawUser.java").apply {
				writeText(
					"package demo;\n\n" +
						"public class RawUser {\n" +
						"\tpublic void fill(java.util.List list) { list.add(\"x\"); }\n" +
						"}\n",
				)
			}

		val result = JavaCompileStep.compile(listOf(rawUser), emptyList(), outputDir())

		assertThat(result.success).isTrue()
		assertThat(File(outputDir(), "demo/RawUser.class").isFile).isTrue()
		assertThat(result.diagnostics).isNotEmpty()
		assertThat(result.diagnostics.map { it.severity }).doesNotContain(Diagnostic.Severity.ERROR)
		assertThat(result.diagnostics.any { it.line == null && it.column == null }).isTrue()
	}
}
