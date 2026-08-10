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

		/**
		 * Parses a deep-link [Uri] of the form described in [DeepLinkRequest]'s docs. Returns `null` if
		 * the URI does not contain a `project` segment followed by a name -- i.e. it isn't a deep link
		 * this app understands, not merely a deep link with missing optional parts.
		 */
		fun parse(uri: Uri?): DeepLinkRequest? {
			val segments = uri?.pathSegments ?: return null

			val projectNameIdx = segments.indexOf(SEGMENT_PROJECT) + 1
			if (projectNameIdx <= 0 || projectNameIdx >= segments.size) {
				return null
			}
			val projectName = segments[projectNameIdx]

			val fileIdx = segments.indexOf(SEGMENT_FILE).takeIf { it >= 0 }?.plus(1)
			val fileRequest =
				fileIdx?.let { startIdx ->
					if (startIdx >= segments.size) {
						return@let null
					}

					// filenames may themselves contain '/', so the filename is every segment from
					// `file` up to (but not including) the next recognized keyword, joined back together
					val endIdx =
						listOf(SEGMENT_LINE, SEGMENT_COLUMN)
							.mapNotNull { keyword -> segments.indexOf(keyword).takeIf { it > startIdx } }
							.minOrNull() ?: segments.size

					val filePath = segments.subList(startIdx, endIdx).joinToString("/")

					val lineIdx = segments.indexOf(SEGMENT_LINE).takeIf { it >= 0 }?.plus(1)
					val columnIdx = segments.indexOf(SEGMENT_COLUMN).takeIf { it >= 0 }?.plus(1)

					PendingFileRequest(
						filePath = filePath,
						lineRaw = lineIdx?.let { segments.getOrNull(it) },
						columnRaw = columnIdx?.let { segments.getOrNull(it) },
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
