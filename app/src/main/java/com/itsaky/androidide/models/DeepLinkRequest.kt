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
import com.itsaky.androidide.utils.ContainedPathResolver
import kotlinx.parcelize.Parcelize
import java.nio.file.Paths

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
 * `https://appdevforall.org/device/open/project/{projectName}[/file/{filename}[/line/{n}[/column/{n}]]]`
 * (the `www` subdomain works identically -- see [HOSTS]).
 */
@Parcelize
data class DeepLinkRequest(
	val projectName: String,
	val fileRequest: PendingFileRequest? = null,
) : Parcelable {
	companion object {
		const val EXTRA_KEY = "com.itsaky.androidide.DEEP_LINK_REQUEST"

		private const val SCHEME = "https"

		/**
		 * The host [buildUrl] writes.
		 *
		 * Both entries in [HOSTS] resolve identically, so this is only a choice about which one a
		 * generated link shows the person who receives it; the bare domain is the shorter.
		 */
		private const val CANONICAL_HOST = "appdevforall.org"

		// Both hosts serve an identical, verified assetlinks.json (see AndroidManifest.xml's matching
		// pair of <data> elements on DeepLinkActivity's intent-filter) -- kept in sync with that list.
		private val HOSTS = setOf("www.$CANONICAL_HOST", CANONICAL_HOST)

		/**
		 * See [parse]: an upper bound on the whole path, since the parsed pieces get parcelled.
		 *
		 * Sized against the Binder budget rather than the filesystem. Up to
		 * `ConsumedRequests.MAX_REMEMBERED` (32) of these are held per set, Parcel writes UTF-16
		 * (2 bytes per char plus a length), and BaseEditorActivity saves TWO such sets while
		 * MainActivity saves a third -- so the ceiling that matters is 32 x 3 x 2 bytes x this value.
		 * At 4096 that was ~768 KB against a ~1 MB transaction limit, which is not a bound at all. At
		 * 512 it is ~96 KB. Still far above any real project path: Linux caps a single name at 255
		 * bytes, and a link longer than this cannot name a project that exists.
		 */
		private const val MAX_LINK_PATH_LENGTH = 512

		private const val SEGMENT_PROJECT = "project"
		private const val SEGMENT_FILE = "file"
		private const val SEGMENT_LINE = "line"
		private const val SEGMENT_COLUMN = "column"

		/**
		 * The fixed segments every link starts with, and the prefix [parse] matches, derived from them
		 * so the literal "project" is spelled once. The old pairing had it in both [PATH_PREFIX] and
		 * [SEGMENT_PROJECT], which is the drift this is meant to prevent -- [parse] reads both.
		 */
		private val PATH_SEGMENTS = listOf("device", "open", SEGMENT_PROJECT)
		private val PATH_PREFIX = PATH_SEGMENTS.joinToString(separator = "/", prefix = "/", postfix = "/")

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
				HOSTS.none { it.equals(uri.host, ignoreCase = true) } ||
				uri.path?.startsWith(PATH_PREFIX) != true
			) {
				return null
			}

			// Uri.pathSegments silently drops empty segments, shifting everything after them one slot
			// left -- ".../project//file/Main.kt" would pass the prefix check above and then parse as
			// a project literally named "file" (and open it, should one exist). Reject the malformed
			// link outright instead of resolving a name the user never wrote.
			if (uri.path?.contains("//") == true) {
				return null
			}

			// A length ceiling, because everything downstream of here is parcelled. DeepLinkActivity is
			// exported, so any co-installed app can send explicit ACTION_VIEW intents in a loop; the
			// parsed name and path are kept in ConsumedRequests (up to 32 of them) and written verbatim
			// to MainActivity's saved-instance Bundle, so unbounded names cross the ~1 MB Binder budget
			// and crash the activity with TransactionTooLargeException on every rotation or
			// backgrounding until the task is cleared. Rejecting is safe: this bounds a legitimate
			// path far above anything the filesystem accepts (Linux caps a single name at 255 bytes),
			// and a link this long cannot name a real project anyway.
			if ((uri.path?.length ?: 0) > MAX_LINK_PATH_LENGTH) {
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

					// `.../file/line/5` peels "line/5" off and leaves nothing between startIdx and
					// endIdx, so the file request would carry an empty path -- which resolves against
					// nothing and surfaces to the user as the nonsense `File "" was not found in the
					// project.` The link named no file, so it is a plain project-open link; the line and
					// column, having nothing to apply to, go with it.
					if (filePath.isEmpty()) {
						null
					} else {
						PendingFileRequest(
							filePath = filePath,
							lineRaw = lineRaw,
							columnRaw = columnRaw,
						)
					}
				}

			return DeepLinkRequest(projectName = projectName, fileRequest = fileRequest)
		}

		/**
		 * The inverse of [parse]: the canonical URL naming [projectName], optionally the
		 * project-relative [filePath] inside it, and optionally a [line] and [column] inside that file.
		 *
		 * [line] and [column] are ONE-BASED, matching the URL scheme rather than the editor. The
		 * editor's own cursor is zero-based and
		 * [EditorHandlerActivity][com.itsaky.androidide.activities.editor.EditorHandlerActivity]
		 * subtracts one again when it reads a link back, so a caller holding a cursor passes
		 * `cursor.leftLine + 1`.
		 *
		 * Returns `null` rather than a URL that [parse] would reject or read back as something other
		 * than what was asked for. Each rejection below mirrors one of the *static* checks in [parse]
		 * or in [lookupValidProjectByName][com.itsaky.androidide.utils.lookupValidProjectByName]:
		 * there is nothing to be gained by handing someone a link this same app refuses to open.
		 *
		 * Three of the reader's checks are deliberately NOT mirrored, because none of them can be
		 * answered without a filesystem call or a fact that outlives the link:
		 * [ContainedPathResolver][com.itsaky.androidide.utils.ContainedPathResolver]'s real-path
		 * containment (a file reached through a symlink inside the project relativises to a clean path
		 * here and resolves outside the project there), the reader's NFC/NFD candidate matching --
		 * which it applies to the project NAME only, so an intermediary that normalizes the URL breaks
		 * the FILE half of a link even though the project half survives -- and that reader's
		 * [isValidProjectDirectory][com.itsaky.androidide.utils.isValidProjectDirectory] requirement,
		 * which is a fact about the project at *open* time -- one that stops looking like an Android
		 * project after the link is sent (its `app/build.gradle` renamed, say) invalidates an
		 * already-sent link no matter what was verified here.
		 *
		 * So a link can still be emitted that this app later declines. The guarantee this function
		 * does make is narrower and worth stating exactly: nothing it returns will be *misread* --
		 * read back as naming a different project, file, line or column than the caller asked for.
		 */
		fun buildUrl(
			projectName: String,
			filePath: String? = null,
			line: Int? = null,
			column: Int? = null,
		): String? {
			// A project name is a single path segment naming a direct child of the projects root, so a
			// name carrying a separator can never resolve -- lookupValidProjectByName rejects it before
			// it ever touches the disk. A leading dot is rejected for the same reason one level down:
			// isProjectCandidateDir() refuses any name starting with '.', so a hidden directory is
			// never a project. That one check also covers "." and "..", which matter more than merely
			// being unopenable -- a browser or messenger that normalizes dot segments rewrites
			// "/device/open/project/../file/x" into an entirely different path before the app ever
			// sees it.
			if (projectName.isEmpty() ||
				projectName.startsWith('.') ||
				projectName.contains('/') ||
				projectName.contains('\\')
			) {
				return null
			}

			// parse() keeps line/column only when there is a file for them to apply to, and silently
			// drops them otherwise. Refusing beats emitting a link that quietly loses them.
			if (filePath == null && (line != null || column != null)) {
				return null
			}

			// And a column with no line is refused for exactly the same reason: zeroBasedOrInvalid(null)
			// yields 0, so the reader would silently apply the column to line 1 -- a position the link
			// never named, with no invalid-value message. parse() can still READ that shape (a
			// hand-authored link), it is just not one worth writing.
			if (column != null && line == null) {
				return null
			}

			// Zero and negative are exactly what zeroBasedOrInvalid() reports back to the user as an
			// invalid line/column, so they must not be written down in the first place.
			if ((line != null && line <= 0) || (column != null && column <= 0)) {
				return null
			}

			val builder = Uri.Builder().scheme(SCHEME).authority(CANONICAL_HOST)

			// The same list parse()'s own prefix is built from, so the two cannot disagree.
			PATH_SEGMENTS.forEach { builder.appendPath(it) }
			builder.appendPath(projectName)

			if (filePath != null) {
				// The reader's own lexical rule, called rather than re-spelled: it splits on '\\' as
				// well as '/' and refuses a leading one, which a guard looking only at '/' components
				// misses -- a file legitimately named "a\\..\\b.kt" is one harmless-looking component
				// here and a traversal there, so the link would copy with a success message and then be
				// refused on open.
				if (ContainedPathResolver.isLexicallyRejected(filePath)) {
					return null
				}

				val segments = filePath.split('/')
				// Refused locally on top of that rule: an empty component would put "//" in the path,
				// which parse() rejects outright, and a "." component normalizes away to the base
				// directory, which resolveWithinDirectory refuses as "not a path inside". Other
				// dot-prefixed names are fine -- unlike a project directory, a hidden FILE
				// (.gitignore) is perfectly linkable.
				if (segments.any { it.isEmpty() || it == "." }) {
					return null
				}

				// A character the reader's base.resolve() cannot accept -- a NUL, say -- percent-encodes
				// and round-trips through parse() cleanly, then dies there with InvalidPathException.
				// Refuse it on the same terms, with no filesystem call.
				if (runCatching { Paths.get(filePath) }.isFailure) {
					return null
				}

				builder.appendPath(SEGMENT_FILE)

				// One appendPath call PER COMPONENT. Uri.Builder.appendPath encodes its argument as a
				// single segment, which means it percent-encodes '/' along with everything outside
				// [A-Za-z0-9_-!.~'()*] -- so handing it the whole relative path in one call emits
				// ".../file/src%2Fmain%2FMain.kt". parse() does read that back correctly (getPathSegments
				// splits the ENCODED path, so an encoded slash never becomes a separator), but it is
				// neither the shape this class documents nor a URL a human can read. Encoding is not
				// optional either way: '#' and '?' are legal in a Linux filename and would otherwise
				// truncate the path into a fragment or a query.
				segments.forEach { builder.appendPath(it) }

				// Always written as a full keyword/value pair, even though parse() tolerates a bare
				// trailing keyword. A real pair is also what keeps a file path whose own trailing
				// segments look like "line"/"column" out of peelTrailingKeyword's reach -- see parse()'s
				// notes on the shapes it cannot resolve. Hand-authored links stay exposed to that;
				// links from here do not.
				line?.let { builder.appendPath(SEGMENT_LINE).appendPath(it.toString()) }
				column?.let { builder.appendPath(SEGMENT_COLUMN).appendPath(it.toString()) }
			}

			val url = builder.build().toString()

			// Re-parsed from the STRING rather than checked against the builder's own Uri: that string
			// is what goes on the clipboard and comes back through Uri.parse in DeepLinkActivity, and
			// a builder-built Uri is a different implementation of the same interface. Verifying the
			// object the reader never sees would leave the difference untested.
			//
			// There is deliberately no separate length check here: parse() applies
			// MAX_LINK_PATH_LENGTH to this very path, so the round-trip below already enforces it.
			val parsed = parse(Uri.parse(url)) ?: return null
			if (parsed.projectName != projectName ||
				parsed.fileRequest?.filePath != filePath ||
				parsed.fileRequest?.lineRaw != line?.toString() ||
				parsed.fileRequest?.columnRaw != column?.toString()
			) {
				return null
			}

			return url
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
	/**
	 * Whether `recordProjectOpenedBookkeeping` has already run for [projectRoot].
	 *
	 * True for the plain project-switch path, where MainActivity.openProject records the open before
	 * it even sends the intent; false for a deep link arriving at an already-live editor, which
	 * never goes through MainActivity at all. Without the distinction the hand-off recorded every
	 * plain switch twice -- two Recents inserts and, more visibly, two `trackProjectOpened` events
	 * for one user action.
	 */
	val bookkeepingAlreadyRecorded: Boolean = false,
	/**
	 * The project that was open when this switch was requested.
	 *
	 * Captured at request time, because by the time the handoff is performed the live
	 * `IProjectManager` global no longer holds it: on the plain-switch path
	 * `MainActivity.openProject` overwrote it with the NEW path before the intent was even delivered.
	 * Reading the global there produced previous == new, which made the receiver treat a confirmed
	 * switch as a same-project no-op.
	 */
	val previousProjectPath: String? = null,
) : Parcelable
