package com.itsaky.androidide.lsp.java.providers

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.java.JavaLSPTest
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The compiler reuses one javac context, so a class an earlier request compiled is already known
 * when a later request asks for its declaration in another file.
 */
@RunWith(RobolectricTestRunner::class)
class DefinitionAfterCompletionTest {
	@Before
	fun setup() {
		JavaLSPTest.setup()
	}

	@Test
	fun `definition finds a class in another file that an earlier completion compiled`() {
		JavaLSPTest.apply {
			openFile("definition/CompletesMainActivity")
			server.complete(CompletionParams(cursorPosition(), file!!, ICancelChecker.NOOP).apply { prefix = "" })

			openFile("definition/ReferencesMainActivity")
			// On `MainActivity` in `MainActivity activity;`
			val params = DefinitionParams(file!!, Position(3, 6), ICancelChecker.NOOP)
			val files = runBlocking { server.findDefinition(params) }.locations.map { it.file.fileName.toString() }

			assertThat(files).containsExactly("MainActivity.java")
		}
	}
}
