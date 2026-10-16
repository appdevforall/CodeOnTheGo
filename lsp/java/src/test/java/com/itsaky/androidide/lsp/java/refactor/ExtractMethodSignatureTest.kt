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

	@Test
	fun `one declarator of a multi-declarator local is refused rather than swallowing its sibling`() {
		val f =
			fixture(
				"class F {\n\tvoid m() {\n\t\tint a = 1, b = 2;\n\t\tuse(a);\n\t\tuse(b);\n\t}\n\tstatic void use(int v) {}\n}",
			)
		val result = f.analyse("b = 2")
		assertThat(result).isInstanceOf(AnalysisResult.Refused::class.java)
		assertThat((result as AnalysisResult.Refused).refusal).isEqualTo(ExtractionRefusal.NotASingleRegion)
	}

	@Test
	fun `a nested block is extracted, not mistaken for a region reaching its own statements`() {
		val f =
			fixture(
				"class F {\n\tvoid m() {\n\t\t{ use(1); }\n\t\tuse(2);\n\t}\n\tstatic void use(int v) {}\n}",
			)
		val result = f.analyse("{ use(1); }")
		assertThat(result).isInstanceOf(AnalysisResult.Analysed::class.java)
	}

	@Test
	fun `a rethrow nested in another catch declares what its own try throws`() {
		val f =
			fixture(
				"class F {\n\tvoid m() throws java.io.IOException {\n\t\ttry {\n\t\t\tio();\n\t\t} catch (Exception e) {\n\t\t\ttry { g(); } catch (RuntimeException f) { throw e; }\n\t\t}\n\t}\n\tstatic void io() throws java.io.IOException {}\n\tstatic void g() {}\n}",
			)
		val result = f.analyse("try { g(); } catch (RuntimeException f) { throw e; }")
		assertThat(result).isInstanceOf(AnalysisResult.Analysed::class.java)
		assertThat((result as AnalysisResult.Analysed).candidate.thrownTypes).containsExactly("java.io.IOException")
	}

	@Test
	fun `a rethrow the region's own catch handles declares no checked exception`() {
		val f =
			fixture(
				"class F {\n\tvoid m() {\n\t\ttry {\n\t\t\ttry { io(); } catch (java.io.IOException e) { throw e; }\n\t\t} catch (java.io.IOException x) { handle(x); }\n\t}\n\tstatic void io() throws java.io.IOException {}\n\tstatic void handle(java.io.IOException x) {}\n}",
			)
		val result =
			f.analyse(
				"try {\n\t\t\ttry { io(); } catch (java.io.IOException e) { throw e; }\n\t\t} catch (java.io.IOException x) { handle(x); }",
			)
		assertThat(result).isInstanceOf(AnalysisResult.Analysed::class.java)
		assertThat((result as AnalysisResult.Analysed).candidate.thrownTypes).isEmpty()
	}

	@Test
	fun `the insertion point clears a member whose sibling declarator holds a semicolon in a string`() {
		val f =
			fixture(
				"class F {\n\tprivate Runnable a = () -> {\n\t\tlog();\n\t\tlog();\n\t}, b = \";\".isEmpty() ? null : null;\n\tstatic void log() {}\n}",
			)
		val result = f.analyse("log();\n\t\tlog();")
		assertThat(result).isInstanceOf(AnalysisResult.Analysed::class.java)
		assertThat((result as AnalysisResult.Analysed).candidate.insertOffset)
			.isEqualTo(f.text.indexOf("null;") + "null;".length)
	}

	private fun JavacFixture.analyse(selection: String): AnalysisResult {
		val start = text.indexOf(selection)
		require(start >= 0) { "the fixture contains no selection" }
		val positions = trees.sourcePositions
		val region =
			resolveExtractionRegions(task, root, text, start, start + selection.length)
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
