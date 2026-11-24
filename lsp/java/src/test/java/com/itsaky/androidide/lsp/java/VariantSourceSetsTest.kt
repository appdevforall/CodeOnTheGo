package com.itsaky.androidide.lsp.java

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.util.findAppModule
import com.itsaky.androidide.utils.FileProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.path.readText

/**
 * The test project's app has `VariantConfig` in both `src/debug` and `src/release`, each with its own
 * field. Synced with `debug` selected, every Java feature must see the debug copy and never the
 * release one, and a release-only source gets no analysis.
 */
@RunWith(RobolectricTestRunner::class)
class VariantSourceSetsTest {
	@Before
	fun setup() {
		JavaLSPTest.setup()
		assertThat(findAppModule()?.getSelectedVariant()?.name).isEqualTo("debug")
	}

	@Test
	fun `completion offers the selected variant's members and not another variant's`() {
		JavaLSPTest.apply {
			openFile("variants/VariantMembers")

			val labels =
				server
					.complete(CompletionParams(cursorPosition(), file!!, ICancelChecker.NOOP).apply { prefix = "" })
					.items
					.map { it.ideLabel.toString() }

			assertThat(labels).contains("DEBUG_ONLY_FIELD")
			assertThat(labels).doesNotContain("RELEASE_ONLY_FIELD")
		}
	}

	@Test
	fun `diagnostics resolve the selected variant's sources and flag another variant's`() {
		JavaLSPTest.apply {
			openFile("variants/VariantUse")

			val errors = runBlocking { server.analyze(file!!) }.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }

			// Line 6 reads RELEASE_ONLY_FIELD; line 5 reads DEBUG_ONLY_FIELD and must resolve.
			assertThat(errors.map { it.range.start.line }.distinct()).containsExactly(6)
		}
	}

	@Test
	fun `definition goes to the selected variant's copy of a class`() {
		JavaLSPTest.apply {
			openFile("variants/VariantDefinition")

			// On `VariantConfig` in `String d = VariantConfig.DEBUG_ONLY_FIELD;`
			val params = DefinitionParams(file!!, Position(5, 18), ICancelChecker.NOOP)
			val files = runBlocking { server.findDefinition(params) }.locations.map { it.file.toString() }

			assertThat(files).isNotEmpty()
			assertThat(files.filterNot { "/src/debug/java/" in it }).isEmpty()
		}
	}

	@Test
	fun `a source of a variant that is not selected gets no diagnostics`() {
		val releaseUser =
			FileProvider
				.testProjectRoot()
				.resolve("app/src/release/java/com/itsaky/test/app/variant/ReleaseUser.java")
		JavaLSPTest.dispatchEvent(DocumentOpenEvent(releaseUser, releaseUser.readText(), 0))

		assertThat(runBlocking { JavaLSPTest.server.analyze(releaseUser) }.diagnostics).isEmpty()
	}
}
