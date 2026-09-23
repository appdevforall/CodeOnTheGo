/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.java.actions

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.java.JavaCompilerProvider
import com.itsaky.androidide.lsp.java.JavaLSPTest
import com.itsaky.androidide.lsp.java.actions.diagnostics.AddImportAction
import com.itsaky.androidide.lsp.java.actions.diagnostics.ImportCandidates
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** @author Akash Yadav */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.DEFAULT_VALUE_STRING)
class AddImportTest {
	@Before
	fun setup() {
		JavaLSPTest.setup()
	}

	@Test
	fun addImport() {
		JavaLSPTest.apply {
			openFile("actions/AddImportAction")
			val diagnostic = unresolvedLocationDiagnostic()

			val file = this.file!!.toFile()
			val data = createActionData(diagnostic, file, this.file!!, this.server)

			val action = AddImportAction()
			action.prepare(data)
			assertThat(action.visible).isTrue()
			assertThat(action.enabled).isTrue()

			val execResult = runBlocking { action.execAction(data) }
			assertThat(execResult).isInstanceOf(ImportCandidates.Found::class.java)

			val result = execResult as ImportCandidates.Found
			assertThat(result.titles).contains("java.util.stream.Stream")
		}
	}

	@Test
	fun `execAction returns None when nothing is importable`() {
		JavaLSPTest.apply {
			openFile("actions/AddImportActionNone")
			val diagnostic = unresolvedLocationDiagnostic()

			val file = this.file!!.toFile()
			val data = createActionData(diagnostic, file, this.file!!, this.server)

			val action = AddImportAction()
			action.prepare(data)
			assertThat(action.visible).isTrue()

			val execResult = runBlocking { action.execAction(data) }
			assertThat(execResult).isInstanceOf(ImportCandidates.None::class.java)
			assertThat((execResult as ImportCandidates.None).simpleName).isEqualTo("NoSuchClassAnywhere")
		}
	}

	@Test
	fun `prepare does not touch the compiler provider`() {
		JavaLSPTest.apply {
			openFile("actions/AddImportAction")
			val diagnostic = unresolvedLocationDiagnostic()

			val file = this.file!!.toFile()
			val data = createActionData(diagnostic, file, this.file!!, this.server)

			// A call to the compiler provider from prepare() would be main-thread SQLite I/O once the
			// index backs class lookups, so record every call to it and assert none happened.
			mockkStatic(JavaCompilerProvider::class)
			try {
				val action = AddImportAction()
				action.prepare(data)

				assertThat(action.visible).isTrue()
				assertThat(action.enabled).isTrue()
				verify(exactly = 0) { JavaCompilerProvider.get(any()) }
			} finally {
				unmockkStatic(JavaCompilerProvider::class)
			}
		}
	}

	private fun unresolvedLocationDiagnostic() =
		runBlocking {
			checkNotNull(
				JavaLSPTest.server.analyze(JavaLSPTest.file!!).diagnostics.firstOrNull {
					it.code == "compiler.err.cant.resolve.location"
				},
			) { "no unresolved-location diagnostic found in ${JavaLSPTest.file}" }
		}
}
