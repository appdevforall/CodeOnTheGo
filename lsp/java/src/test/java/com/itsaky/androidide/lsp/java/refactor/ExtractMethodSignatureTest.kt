package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ExtractMethodSignatureTest {
	@Test
	fun `a bare tail return in a void method is refused, not emitted as a value return`() {
		val f =
			fixture(
				"class F {\n\tvoid m(boolean c) {\n\t\tif (c) { log(); return; }\n\t\tmore();\n\t}\n\tvoid log() {}\n\tvoid more() {}\n}",
			)
		val result = f.analyse("log(); return;")
		assertThat(result).isInstanceOf(AnalysisResult.Refused::class.java)
		assertThat((result as AnalysisResult.Refused).refusal).isEqualTo(ExtractionRefusal.ExitsRegion)
	}

	@Test
	fun `a try-with-resources handled by its own catch declares no checked exception`() {
		val f =
			fixture(
				"class F {\n\tvoid m(java.io.File file) {\n\t\ttry (java.io.Reader r = new java.io.FileReader(file)) {\n\t\t\tuse(r);\n\t\t} catch (java.io.IOException e) {\n\t\t}\n\t}\n\tstatic void use(java.io.Reader r) {}\n}",
			)
		val result =
			f.analyse(
				"try (java.io.Reader r = new java.io.FileReader(file)) {\n\t\t\tuse(r);\n\t\t} catch (java.io.IOException e) {\n\t\t}",
			)
		assertThat(result).isInstanceOf(AnalysisResult.Analysed::class.java)
		assertThat((result as AnalysisResult.Analysed).candidate.thrownTypes).isEmpty()
	}

	private fun JavacFixture.analyse(selection: String): AnalysisResult {
		val start = text.indexOf(selection)
		require(start >= 0) { "the fixture contains no selection" }
		val positions = trees.sourcePositions
		val region =
			resolveExtractionRegions(task, root, positions, text, start, start + selection.length)
				.filterIsInstance<ExtractionRegion.Statements>()
				.firstOrNull() ?: error("no statements region for the selection")
		return analyseRegion(region, task, root, trees, positions, text)
	}

	private fun fixture(source: String): JavacFixture = JavacFixture(source).also { fixtures += it }

	@After
	fun closeFixtures() {
		fixtures.forEach(JavacFixture::close)
		fixtures.clear()
	}

	private val fixtures = mutableListOf<JavacFixture>()
}
