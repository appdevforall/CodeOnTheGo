package com.itsaky.androidide.lsp.kotlin.navigation

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.fixtures.TestSourceModuleSpec
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Test
import java.nio.file.Path

/**
 * Test sources are modules layered over main (ADFA-7115): each sees main, `internal` included, and
 * find usages from main reaches them, while main and the other test scope see none of their code.
 */
class TestSourceScopesTest : KtLspTest() {
	override val moduleSpecs =
		listOf(
			TestSourceModuleSpec("main"),
			TestSourceModuleSpec("unitTest", dependsOn = listOf("main"), friends = listOf("main")),
			TestSourceModuleSpec("androidTest", dependsOn = listOf("main"), friends = listOf("main")),
		)

	@Test
	fun `find usages of a main declaration reaches both test scopes`() {
		val main = "package p\n\nfun shared() {}\n"
		val mainPath = path(createSourceFile("main", "p/Shared.kt", main))
		val unit = path(createSourceFile("unitTest", "p/UnitUse.kt", "package p\n\nfun u() { shared() }\n"))
		val device = path(createSourceFile("androidTest", "p/DeviceUse.kt", "package p\n\nfun d() { shared() }\n"))

		val usages = usagesAt(mainPath, main.indexOf("shared"))

		assertThat(usages.map { it.first }).containsExactly(unit, device)
	}

	@Test
	fun `find usages of an internal main declaration reaches its test modules`() {
		val main = "package p\n\ninternal fun helper() {}\n"
		val mainPath = path(createSourceFile("main", "p/Helper.kt", main))
		val unit = path(createSourceFile("unitTest", "p/UnitHelperUse.kt", "package p\n\nfun u() { helper() }\n"))

		val usages = usagesAt(mainPath, main.indexOf("helper"))

		assertThat(usages.map { it.first }).containsExactly(unit)
	}

	@Test
	fun `a test file may use an internal main declaration`() {
		createSourceFile("main", "p/Internal.kt", "package p\n\ninternal fun helper(): Int = 1\n")
		val test = createSourceFile("unitTest", "p/UsesInternal.kt", "package p\n\nfun t(): Int = helper()\n")

		assertThat(diagnostics(test)).isEmpty()
	}

	@Test
	fun `a unit test cannot see android test code`() {
		createSourceFile("androidTest", "p/DeviceOnly.kt", "package p\n\nclass DeviceOnly\n")
		val unit = createSourceFile("unitTest", "p/UsesDevice.kt", "package p\n\nval d = DeviceOnly()\n")

		assertThat(diagnostics(unit)).contains("UNRESOLVED_REFERENCE")
	}

	@Test
	fun `main cannot see test code`() {
		createSourceFile("unitTest", "p/UnitOnly.kt", "package p\n\nclass UnitOnly\n")
		val main = createSourceFile("main", "p/UsesUnit.kt", "package p\n\nval u = UnitOnly()\n")

		assertThat(diagnostics(main)).contains("UNRESOLVED_REFERENCE")
	}

	private fun path(file: KtFile): Path = Path.of(file.virtualFile.path)

	private fun diagnostics(file: KtFile): List<String> =
		analyzeMaybeDanglingForTest(file) {
			file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).map { it.factoryName }
		}

	/** Usages for a caret at [offset] in [file], as (path, startOffset) pairs. */
	private fun usagesAt(
		file: Path,
		offset: Int,
	): List<Pair<Path, Int>> =
		runBlocking {
			context(env) {
				findUsagesAt(ReferenceParams(file, Position(0, 0, offset), true, ICancelChecker.NOOP))
			}.locations.map { it.file to it.range.start.index }
		}
}
