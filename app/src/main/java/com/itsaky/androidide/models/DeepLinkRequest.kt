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
		 * Parses a deep-link [Uri] of the form described in [DeepLinkRequest]'s docs. Returns `null` if
		 * the URI does not contain a `project` segment followed by a name -- i.e. it isn't a deep link
		 * this app understands, not merely a deep link with missing optional parts.
		 */
		fun parse(uri: Uri?): DeepLinkRequest? {
			val segments = uri?.pathSegments ?: return null

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
					// they're matched from the END of the path backward (column first, then line in
					// whatever remains), never by searching for the keyword's first occurrence. That
					// makes a literal "line"/"column" segment earlier in the file path (e.g. a directory
					// named "line") part of the filename rather than misread as metadata, as long as a
					// real trailing pair follows it. The one shape this can't resolve: a file path whose
					// *entire* content is just "line"/"column" plus one more segment, with nothing else
					// following -- e.g. `file/line/Main.kt` alone -- is indistinguishable from an actual
					// line suffix; this URL scheme has no delimiter to tell the two apart, so it's read
					// as the keyword (existing behavior, unchanged).
					var endIdx = segments.size
					val columnIdx =
						(endIdx - 2)
							.takeIf { it >= startIdx && segments[it] == SEGMENT_COLUMN }
							?.also { endIdx = it }
					val lineIdx =
						(endIdx - 2)
							.takeIf { it >= startIdx && segments[it] == SEGMENT_LINE }
							?.also { endIdx = it }

					val filePath = segments.subList(startIdx, endIdx).joinToString("/")

					PendingFileRequest(
						filePath = filePath,
						lineRaw = lineIdx?.let { segments.getOrNull(it + 1) },
						columnRaw = columnIdx?.let { segments.getOrNull(it + 1) },
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
