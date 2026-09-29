/************************************************************************************
 * This file is part of AndroidIDE.
 *
 *
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 **************************************************************************************/
package com.itsaky.androidide.utils;

import com.itsaky.androidide.models.Position;
import com.itsaky.androidide.models.Range;
import com.itsaky.androidide.models.SearchResult;
import com.itsaky.androidide.tasks.TaskExecutor;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class provides API to search in files recursively
 *
 * @author Akash Yadav
 */
public class RecursiveFileSearcher {

	public static Map<File, List<SearchResult>> search(
			String query, List<String> exts, List<File> dirs, ProjectSearchOptions options) {
		final Map<File, List<SearchResult>> result = new LinkedHashMap<>();
		final MultiFileFilter filter = new MultiFileFilter(exts);
		final Set<File> excludedDirs = new HashSet<>();
		for (File dir : options.getExcludedDirs()) {
			excludedDirs.add(dir.getAbsoluteFile());
		}
		final Set<File> seen = new HashSet<>();
		final int flags = options.getMatchCase() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
		final Pattern pattern = Pattern.compile(Pattern.quote(query), flags);
		final File nameExclusionRoot = options.getNameExclusionRoot() == null ? null : options.getNameExclusionRoot().getAbsoluteFile();
		for (File dir : dirs) {
			final File walkRoot = dir.getAbsoluteFile();
			final Set<String> excludedNames = walkRoot.equals(nameExclusionRoot)
					? options.getExcludedDirNames()
					: Collections.<String> emptySet();
			final List<File> files = new ArrayList<>();
			collectFiles(walkRoot, filter, excludedNames, excludedDirs, seen, files);
			for (File file : files) {
				final String text = readText(file, options.getBufferOverrides());
				if (text == null || text.trim().isEmpty()) {
					continue;
				}
				final List<SearchResult> ranges = findMatches(file, text, pattern, options.getWholeWord());
				if (!ranges.isEmpty()) {
					result.put(file, ranges);
				}
			}
		}
		return result;
	}

	/**
	 * Search the given text in files recursively in given search directories
	 *
	 * @param text
	 *            Text to search
	 * @param exts
	 *            Extentions of file to search. Maybe null.
	 * @param searchDirs
	 *            Directories to search in. Subdirectories will be included
	 * @param callback
	 *            A listener that will listen to the search result
	 */
	public static void searchRecursiveAsync(
			String text, List<String> exts, List<File> searchDirs, Callback callback) {
		searchRecursiveAsync(text, exts, searchDirs, ProjectSearchOptions.DEFAULT, callback);
	}

	public static void searchRecursiveAsync(
			String text, List<String> exts, List<File> searchDirs, ProjectSearchOptions options, Callback callback) {
		// Cannot search empty or null text
		if (text == null || text.isEmpty()) {
			return;
		}

		// If there is no listener to the search, search is meaningless
		if (callback == null) {
			return;
		}

		// Avoid searching if no directories are specified
		if (searchDirs == null || searchDirs.isEmpty()) {
			return;
		}

		TaskExecutor.executeAsync(() -> search(text, exts, searchDirs, options), callback::onResult);
	}

	static int previewMatchOffset(String text, int previewStart, int matchStart) {
		final String before = "...".concat(text.substring(previewStart, matchStart)).replaceAll("\\s+", " ");
		final boolean mergesWithMatch = before.endsWith(" ")
				&& matchStart < text.length()
				&& Character.isWhitespace(text.charAt(matchStart));
		return mergesWithMatch ? before.length() - 1 : before.length();
	}

	private static void collectFiles(
			File dir, FileFilter filter, Set<String> excludedNames, Set<File> excludedDirs, Set<File> seen, List<File> out) {
		final File[] children = dir.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (child.isDirectory()) {
				if (excludedNames.contains(child.getName()) || excludedDirs.contains(child.getAbsoluteFile())) {
					continue;
				}
				collectFiles(child, filter, excludedNames, excludedDirs, seen, out);
			} else if (filter.accept(child) && seen.add(child.getAbsoluteFile())) {
				out.add(child);
			}
		}
	}

	private static List<SearchResult> findMatches(File file, String text, Pattern pattern, boolean wholeWord) {
		final Content content = new Content(text);
		final List<SearchResult> ranges = new ArrayList<>();
		final Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			if (wholeWord && !WordBoundary.isWholeWord(text, matcher.start(), matcher.end())) {
				continue;
			}
			final Range range = new Range();
			final CharPosition start = content.getIndexer().getCharPosition(matcher.start());
			final CharPosition end = content.getIndexer().getCharPosition(matcher.end());
			range.setStart(new Position(start.line, start.column));
			range.setEnd(new Position(end.line, end.column));
			final int previewStart = Math.max(0, matcher.start() - 30);
			final String sub = "..."
					.concat(text.substring(previewStart, Math.min(matcher.end() + 31, text.length())))
					.trim()
					.concat("...");
			final String match = content.subContent(start.line, start.column, end.line, end.column).toString();
			final int matchOffset = previewMatchOffset(text, previewStart, matcher.start());
			ranges.add(new SearchResult(range, file, sub.replaceAll("\\s+", " "), match, matchOffset));
		}
		return ranges;
	}

	private static String readText(File file, Map<File, String> overrides) {
		final String override = overrides.get(file.getAbsoluteFile());
		return override != null ? override : FileIOUtils.readFile2String(file);
	}

	public static interface Callback {

		void onResult(Map<File, List<SearchResult>> results);
	}

	private static class MultiFileFilter implements FileFilter {

		private final List<String> exts;

		public MultiFileFilter(List<String> exts) {
			this.exts = exts;
		}

		@Override
		public boolean accept(File file) {
			boolean accept = false;
			if (exts == null || exts.isEmpty() || file.isDirectory()) {
				accept = true;
			} else {
				for (String ext : exts) {
					if (file.getName().endsWith(ext)) {
						accept = true;
						break;
					}
				}
			}

			return accept && FileUtils.isUtf8(file);
		}
	}
}
