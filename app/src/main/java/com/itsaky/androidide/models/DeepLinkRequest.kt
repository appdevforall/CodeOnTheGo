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

package com.itsaky.androidide.models

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A request to open a file at an optional line/column, carried as part of a [DeepLinkRequest] or a
 * [DeepLinkOpenRequest].
 *
 * [lineRaw]/[columnRaw] are kept as raw strings rather than parsed [Int]s so that callers can
 * distinguish "segment absent from the URL" (`null`) from "segment present but not a valid positive
 * integer" (non-null, fails [String.toIntOrNull] or non-positive) -- the latter must be reported to the
 * user, the former must not.
 */
@Parcelize
data class PendingFileRequest(
	val filePath: String,
	val lineRaw: String?,
	val columnRaw: String?,
) : Parcelable {
	companion object {
		const val EXTRA_KEY = "com.itsaky.androidide.PENDING_FILE_REQUEST"
	}
}

/**
 * A parsed (but not yet resolved-to-a-path) request for
 * `https://www.appdevforall.org/device/open/project/{projectName}[/file/{filename}[/line/{n}[/column/{n}]]]`.
 */
@Parcelize
data class DeepLinkRequest(
	val projectName: String,
	val fileRequest: PendingFileRequest? = null,
) : Parcelable {
	companion object {
		const val EXTRA_KEY = "com.itsaky.androidide.DEEP_LINK_REQUEST"

		private const val SCHEME = "https"
		private const val HOST = "www.appdevforall.org"
		private const val PATH_PREFIX = "/device/open/project/"

		private const val SEGMENT_PROJECT = "project"
		private const val SEGMENT_FILE = "file"
		private const val SEGMENT_LINE = "line"
		private const val SEGMENT_COLUMN = "column"

		/** First index at or after [from] holding [segment], or -1. Unlike [List.indexOf], never
		 * matches an already-consumed segment earlier in the path -- e.g. a project name that
		 * happens to equal `"line"` can't be mistaken for the `line` keyword that follows it. */
		private fun List<String>.indexOfFrom(
			from: Int,
			segment: String,
		): Int {
			for (i in from until size) {
				if (this[i] == segment) return i
			}
			return -1
		}

		/**
		 * Peels a trailing `keyword`/value pair off the end of `this[startIdx until endIdx]`, or a
		 * bare, valueless `keyword` at the very last position (e.g. a URL ending in `.../column` with
		 * nothing after it). Returns the raw value paired with the new `endIdx` (that segment, and its
		 * value if any, excluded) -- `null` raw if `keyword` wasn't found at all (endIdx unchanged),
		 * `""` raw if found dangling with no value, e.g. -- see [parse]'s inline docs for why there's
		 * no numeric check on the paired value itself.
		 */
		private fun List<String>.peelTrailingKeyword(
			startIdx: Int,
			endIdx: Int,
			keyword: String,
		): Pair<String?, Int> {
			val pairIdx = (endIdx - 2).takeIf { it >= startIdx && this[it] == keyword }
			if (pairIdx != null) {
				return this[pairIdx + 1] to pairIdx
			}
			val danglingIdx = (endIdx - 1).takeIf { it >= startIdx && this[it] == keyword }
			if (danglingIdx != null) {
				return "" to danglingIdx
			}
			return null to endIdx
		}

		/**
		 * Parses a deep-link [Uri] of the form described in [DeepLinkRequest]'s docs. Returns `null` if
		 * the URI does not match this scheme/host/path at all, or does not contain a `project` segment
		 * followed by a name -- i.e. it isn't a deep link this app understands, not merely a deep link
		 * with missing optional parts.
		 *
		 * [DeepLinkActivity][com.itsaky.androidide.activities.DeepLinkActivity] is `exported="true"` (a
		 * requirement for App Links), which means its `<intent-filter>` data scoping only constrains
		 * *implicit* intent matching -- any co-installed app can still target it directly with an
		 * explicit intent carrying an arbitrary [Uri]. Re-checking scheme/host/path prefix here, rather
		 * than trusting the manifest declaration alone, closes that gap regardless of how the intent
		 * arrived.
		 */
		fun parse(uri: Uri?): DeepLinkRequest? {
			// Scheme and host are case-insensitive per RFC 3986 -- an explicit intent from another app
			// (see this function's own doc on why that's re-validated at all) could carry either in
			// non-canonical case, and a semantically valid link must not be rejected over that alone.
			if (uri == null ||
				!uri.scheme.equals(SCHEME, ignoreCase = true) ||
				!uri.host.equals(HOST, ignoreCase = true) ||
				uri.path?.startsWith(PATH_PREFIX) != true
			) {
				return null
			}

			val segments = uri.pathSegments

			val projectIdx = segments.indexOfFrom(0, SEGMENT_PROJECT)
			if (projectIdx < 0 || projectIdx + 1 >= segments.size) {
				return null
			}
			val projectName = segments[projectIdx + 1]

			val fileIdx = segments.indexOfFrom(projectIdx + 2, SEGMENT_FILE)
			val fileRequest =
				fileIdx.takeIf { it >= 0 }?.let { fIdx ->
					val startIdx = fIdx + 1
					if (startIdx >= segments.size) {
						return@let null
					}

					// line/column are trailing modifiers, so -- unlike the project/file lookup above --
					// they're matched from the END of the path backward (column peeled off first, then
					// line against whatever remains), never by searching for the keyword's first
					// occurrence. That makes a literal "line"/"column" segment earlier in the file path
					// (e.g. a directory named "line") part of the filename rather than misread as
					// metadata, as long as a real trailing pair follows it. Peeling column off before
					// checking for line (rather than computing both against the original, un-trimmed end)
					// matters for a case like ".../Main.kt/line/5/column": a bare trailing "column" with
					// no value consumed first re-exposes "line/5" as a real pair for the line check that
					// follows, instead of two independent checks both missing it against the original end.
					// The shape this can't resolve: any path whose last two segments happen to be
					// [directory-literally-named "line"/"column", some other segment] -- not just the
					// degenerate two-segment case (`file/line/Main.kt` alone), but equally a longer one
					// (`file/foo/line/Notes.txt`, where "foo" is a real preceding directory). Neither is
					// distinguishable from an actual line/column suffix by position alone, and this URL
					// scheme has no delimiter to tell them apart -- there's no numeric-lookahead check on
					// the value segment because that would instead break the *intentional* "malformed but
					// present" case this class's docs call out (e.g. `.../line/abc`, which must surface as
					// an invalid line number, not silently become part of the file path). Both read as the
					// keyword (existing behavior, unchanged); a user who genuinely has a directory named
					// "line"/"column" must avoid placing the target file's segment where it would be
					// misread as the value.
					var endIdx = segments.size
					val (columnRaw, endIdxAfterColumn) = segments.peelTrailingKeyword(startIdx, endIdx, SEGMENT_COLUMN)
					endIdx = endIdxAfterColumn
					val (lineRaw, endIdxAfterLine) = segments.peelTrailingKeyword(startIdx, endIdx, SEGMENT_LINE)
					endIdx = endIdxAfterLine

					val filePath = segments.subList(startIdx, endIdx).joinToString("/")

					PendingFileRequest(
						filePath = filePath,
						lineRaw = lineRaw,
						columnRaw = columnRaw,
					)
				}

			return DeepLinkRequest(projectName = projectName, fileRequest = fileRequest)
		}
	}
}

/**
 * The resolved-path counterpart to [DeepLinkRequest], used once the project name has been resolved to
 * an absolute directory -- e.g. when handing a pending "close current project, then open this one" off
 * across activities via [com.itsaky.androidide.deeplink.PendingDeepLinkOpen].
 */
@Parcelize
data class DeepLinkOpenRequest(
	val projectRoot: String,
	val fileRequest: PendingFileRequest?,
) : Parcelable
