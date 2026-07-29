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

package com.itsaky.androidide.lsp.util;

import androidx.annotation.NonNull;
import com.itsaky.androidide.lsp.models.DiagnosticItem;
import com.itsaky.androidide.models.Position;
import com.itsaky.androidide.models.Range;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for working with diagnostic items.
 *
 * @author Akash Yadav
 */
public class DiagnosticUtil {

	/**
	 * Binary search the diagnostic item which contains the given line and column.
	 *
	 * @param diagnostics
	 *            The list of diagnostics.
	 * @param line
	 *            The line to search for.
	 * @param column
	 *            The column to search for.
	 * @return The diagnostic item.
	 */
	public static DiagnosticItem binarySearchDiagnostic(
			List<DiagnosticItem> diagnostics, int line, int column) {
		if (diagnostics == null) {
			return null;
		}
		final var index = binarySearchDiagnosticPosition(diagnostics, line, column);
		if (index == -1) {
			return null;
		}

		return diagnostics.get(index);
	}

	/**
	 * Binary search the diagnostic item which contains the given line and column.
	 *
	 * @param diagnostics
	 *            The list of diagnostics.
	 * @param position
	 *            The position to search for.
	 * @return The diagnostic item.
	 */
	public static DiagnosticItem binarySearchDiagnostic(
			List<DiagnosticItem> diagnostics, Position position) {
		return binarySearchDiagnostic(diagnostics, position.getLine(), position.getColumn());
	}

	/**
	 * Binary search the diagnostic item which contains the given line and column.
	 *
	 * @param diagnostics
	 *            The list of diagnostics.
	 * @param line
	 *            The line to search for.
	 * @param column
	 *            The column to search for.
	 * @return The index of the found diagnostic item.
	 */
	public static int binarySearchDiagnosticPosition(
			List<DiagnosticItem> diagnostics, int line, int column) {
		if (diagnostics.isEmpty()) {
			return -1;
		}

		final var pos = new Position(line, column);
		int left = 0;
		int right = diagnostics.size() - 1;
		int mid;
		while (left <= right) {
			mid = (left + right) / 2;
			var d = diagnostics.get(mid);
			var r = d.getRange();
			var c = r.containsForBinarySearch(pos);
			if (c < 0) {
				right = mid - 1;
			} else if (c > 0) {
				left = mid + 1;
			} else {
				return mid;
			}
		}

		return -1;
	}

	/**
	 * Find all diagnostic items whose range overlaps the given range.
	 *
	 * @param diagnostics
	 *            The diagnostics to search items from.
	 * @param range
	 *            The range to look for diagnostics in.
	 * @return The list of overlapping diagnostics, in the order they appear in {@code diagnostics}.
	 */
	@NonNull
	public static List<DiagnosticItem> findDiagnosticsInRange(
			List<DiagnosticItem> diagnostics, Range range) {
		if (diagnostics == null || range == null || diagnostics.isEmpty()) {
			return Collections.emptyList();
		}

		final var start = range.getStart();
		final var end = range.getEnd();
		final var result = new ArrayList<DiagnosticItem>();
		for (final var diagnostic : diagnostics) {
			final var r = diagnostic.getRange();
			// Overlap: the diagnostic ends at/after the selection start and begins at/before its end.
			if (r.getEnd().compareTo(start) >= 0 && r.getStart().compareTo(end) <= 0) {
				result.add(diagnostic);
			}
		}

		return result;
	}
}
