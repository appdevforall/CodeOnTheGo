package com.itsaky.androidide.lsp

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.models.Position
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ComputeNewBreakpointPositionTest {

	private fun compute(
		line: Int,
		column: Int,
		startLine: Int,
		startCol: Int,
		endLine: Int,
		endCol: Int,
		changeType: ChangeType
	): Pair<Int, Int> {
		val start = Position(startLine, startCol)
		val end = Position(endLine, endCol)
		return BreakpointHandler.computeNewBreakpointPosition(line, column, start, end, changeType)
	}

	@Test
	fun `insert before breakpoint line shifts line down by inserted line delta`() {
		// insertion spans 2 new lines (end.line - start.line = 2) and occurs before breakpoint line 10
		val result = compute(
			line = 10,
			column = 5,
			startLine = 3,
			startCol = 0,
			endLine = 5,
			endCol = 0,
			changeType = ChangeType.INSERT
		)
		// newLine = 10 + (5 - 3) = 12
		assertThat(result).isEqualTo(Pair(12, 5))
	}

	@Test
	fun `insert on same line after start column single-line shifts column right`() {
		// single-line insertion on line 5, from column 2 to column 7 (delta columns = 5)
		val result = compute(
			line = 5,
			column = 10,
			startLine = 5,
			startCol = 2,
			endLine = 5,
			endCol = 7,
			changeType = ChangeType.INSERT
		)
		// newColumn = 10 + (7 - 2) = 15
		assertThat(result).isEqualTo(Pair(5, 15))
	}

	@Test
	fun `insert on same line at column equal to start does not shift column`() {
		// insertion starts at same column as breakpoint -> column is NOT considered "after start column"
		val result = compute(
			line = 5,
			column = 2,
			startLine = 5,
			startCol = 2,
			endLine = 5,
			endCol = 6,
			changeType = ChangeType.INSERT
		)
		assertThat(result).isEqualTo(Pair(5, 2))
	}

	@Test
	fun `multiLine insert on same start line shifts line and adjusts column`() {
		// multi-line insertion: start.line=3 end.line=6 (delta lines = 3)
		// breakpoint is on start line (3) and column > start.column
		val result = compute(
			line = 3,
			column = 10,
			startLine = 3,
			startCol = 4,
			endLine = 6,
			endCol = 2,
			changeType = ChangeType.INSERT
		)
		// newLine = 3 + (6 - 3) = 6
		// newColumn = column - start.column + end.column = 10 - 4 + 2 = 8
		assertThat(result).isEqualTo(Pair(6, 8))
	}

	@Test
	fun `insert earlier line does not affect earlier breakpoints`() {
		// insertion occurs after breakpoint line -> no change
		val result = compute(
			line = 2,
			column = 1,
			startLine = 5,
			startCol = 0,
			endLine = 5,
			endCol = 3,
			changeType = ChangeType.INSERT
		)
		assertThat(result).isEqualTo(Pair(2, 1))
	}

	@Test
	fun `delete after deletion range shifts line up`() {
		// deletion spans lines 2..4 (delta = 2), breakpoint at line 6 should move up
		val result = compute(
			line = 6,
			column = 0,
			startLine = 2,
			startCol = 0,
			endLine = 4,
			endCol = 0,
			changeType = ChangeType.DELETE
		)
		// newLine = 6 - (4 - 2) = 4
		assertThat(result).isEqualTo(Pair(4, 0))
	}

	@Test
	fun `singleLine delete on same line after end column shifts column left`() {
		// deletion on single line 5 from col 3 to col 8 (col delta = 5)
		val result = compute(
			line = 5,
			column = 12,
			startLine = 5,
			startCol = 3,
			endLine = 5,
			endCol = 8,
			changeType = ChangeType.DELETE
		)
		// newColumn = 12 - (8 - 3) = 7
		assertThat(result).isEqualTo(Pair(5, 7))
	}

	@Test
	fun `multiLine delete when breakpoint on end line after end column collapses to start line with adjusted column`() {
		// deletion from (2,4) to (5,6) removal of multiple lines
		// breakpoint at line 5 (end line) and column > end.column should map to start line
		val result = compute(
			line = 5,
			column = 10,
			startLine = 2,
			startCol = 4,
			endLine = 5,
			endCol = 6,
			changeType = ChangeType.DELETE
		)
		// newLine = start.line = 2
		// newColumn = start.column + (column - end.column) = 4 + (10 - 6) = 8
		assertThat(result).isEqualTo(Pair(2, 8))
	}

	@Test
	fun `delete inside deleted range marks breakpoint for deletion`() {
		// deletion covers line 3 col 2 .. line 4 col 5. Breakpoint at (3,5) is inside -> removed
		val result = compute(
			line = 3,
			column = 5,
			startLine = 3,
			startCol = 2,
			endLine = 4,
			endCol = 5,
			changeType = ChangeType.DELETE
		)
		assertThat(result).isEqualTo(Pair(-1, -1))
	}

	@Test
	fun `delete boundary cases inclusive endpoints are removed`() {
		// breakpoint exactly at start (should be considered inside and removed)
		val r1 = compute(
			line = 3,
			column = 2,
			startLine = 3,
			startCol = 2,
			endLine = 3,
			endCol = 8,
			changeType = ChangeType.DELETE
		)
		assertThat(r1).isEqualTo(Pair(-1, -1))

		// breakpoint exactly at end (should be considered inside and removed)
		val r2 = compute(
			line = 3,
			column = 8,
			startLine = 3,
			startCol = 2,
			endLine = 3,
			endCol = 8,
			changeType = ChangeType.DELETE
		)
		assertThat(r2).isEqualTo(Pair(-1, -1))
	}

	@Test
	fun `insert on different line with column less than start column does not shift`() {
		// insertion on start line but breakpoint column <= start.column -> should not shift column
		val result = compute(
			line = 3,
			column = 2,
			startLine = 3,
			startCol = 5,
			endLine = 3,
			endCol = 9,
			changeType = ChangeType.INSERT
		)
		assertThat(result).isEqualTo(Pair(3, 2))
	}

	@Test
	fun `delete when breakpoint is on end line but column equals end column is inside deletion`() {
		val result = compute(
			line = 7,
			column = 10,
			startLine = 5,
			startCol = 3,
			endLine = 7,
			endCol = 10,
			changeType = ChangeType.DELETE
		)
		assertThat(result).isEqualTo(Pair(-1, -1))
	}

	@Test
	fun `insert multi-line where breakpoint is below shifts properly even if start and end columns vary`() {
		// insertion from (4,8) to (6,1) (delta lines = 2)
		val result = compute(
			line = 9,
			column = 3,
			startLine = 4,
			startCol = 8,
			endLine = 6,
			endCol = 1,
			changeType = ChangeType.INSERT
		)
		// newLine = 9 + (6 - 4) = 11
		assertThat(result).isEqualTo(Pair(11, 3))
	}

	@Test
	fun `delete single-line where breakpoint is before start remains unchanged`() {
		val result = compute(
			line = 5,
			column = 1,
			startLine = 7,
			startCol = 0,
			endLine = 7,
			endCol = 4,
			changeType = ChangeType.DELETE
		)
		assertThat(result).isEqualTo(Pair(5, 1))
	}
}
