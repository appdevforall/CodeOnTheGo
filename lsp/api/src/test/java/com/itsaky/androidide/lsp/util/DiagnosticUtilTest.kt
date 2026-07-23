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
package com.itsaky.androidide.lsp.util

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class DiagnosticUtilTest {
	private fun diagnostic(
		startLine: Int,
		startCol: Int,
		endLine: Int,
		endCol: Int,
		code: String = "",
	): DiagnosticItem =
		DiagnosticItem(
			message = "",
			code = code,
			range = Range(Position(startLine, startCol), Position(endLine, endCol)),
			source = "",
			severity = DiagnosticSeverity.ERROR,
		)

	private fun range(
		startLine: Int,
		startCol: Int,
		endLine: Int,
		endCol: Int,
	) = Range(Position(startLine, startCol), Position(endLine, endCol))

	@Test
	fun `empty or null inputs yield empty list`() {
		assertThat(DiagnosticUtil.findDiagnosticsInRange(null, range(0, 0, 0, 0))).isEmpty()
		assertThat(DiagnosticUtil.findDiagnosticsInRange(emptyList(), range(0, 0, 0, 0))).isEmpty()
		assertThat(DiagnosticUtil.findDiagnosticsInRange(listOf(diagnostic(0, 0, 0, 1)), null))
			.isEmpty()
	}

	@Test
	fun `zero-width cursor returns the containing diagnostic and matches binarySearch`() {
		val diagnostics =
			listOf(
				diagnostic(0, 0, 0, 4, "a"),
				diagnostic(1, 2, 1, 8, "b"),
				diagnostic(3, 0, 3, 5, "c"),
			)
		// Cursor inside "b".
		val cursor = range(1, 4, 1, 4)
		val inRange = DiagnosticUtil.findDiagnosticsInRange(diagnostics, cursor)

		assertThat(inRange.map { it.code }).containsExactly("b")
		assertThat(DiagnosticUtil.binarySearchDiagnostic(inRange, 1, 4))
			.isEqualTo(DiagnosticUtil.binarySearchDiagnostic(diagnostics, 1, 4))
	}

	@Test
	fun `zero-width cursor outside every diagnostic returns empty`() {
		val diagnostics = listOf(diagnostic(0, 0, 0, 4, "a"), diagnostic(2, 0, 2, 4, "b"))
		assertThat(DiagnosticUtil.findDiagnosticsInRange(diagnostics, range(1, 0, 1, 0))).isEmpty()
	}

	@Test
	fun `selection spanning several diagnostics returns all overlapping in order`() {
		val diagnostics =
			listOf(
				diagnostic(0, 0, 0, 4, "a"),
				diagnostic(1, 0, 1, 4, "b"),
				diagnostic(2, 0, 2, 4, "c"),
				diagnostic(5, 0, 5, 4, "d"),
			)
		// Selection covers lines 1..2 fully and partially line 0.
		val selection = range(0, 2, 2, 1)
		val inRange = DiagnosticUtil.findDiagnosticsInRange(diagnostics, selection)

		assertThat(inRange.map { it.code }).containsExactly("a", "b", "c").inOrder()
	}

	@Test
	fun `selection touching only the edges is inclusive`() {
		val diagnostics = listOf(diagnostic(1, 2, 1, 6, "a"))
		// Selection ends exactly at the diagnostic start.
		assertThat(DiagnosticUtil.findDiagnosticsInRange(diagnostics, range(0, 0, 1, 2)).map { it.code })
			.containsExactly("a")
		// Selection starts exactly at the diagnostic end.
		assertThat(DiagnosticUtil.findDiagnosticsInRange(diagnostics, range(1, 6, 2, 0)).map { it.code })
			.containsExactly("a")
		// Selection entirely before the diagnostic.
		assertThat(DiagnosticUtil.findDiagnosticsInRange(diagnostics, range(0, 0, 1, 1))).isEmpty()
	}
}
