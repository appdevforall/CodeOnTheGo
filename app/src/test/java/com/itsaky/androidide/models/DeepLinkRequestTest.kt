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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkRequestTest {
	private fun parse(url: String) = DeepLinkRequest.parse(Uri.parse(url))

	@Test
	fun `project only`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp")
		assertThat(request).isEqualTo(DeepLinkRequest(projectName = "MyApp"))
	}

	@Test
	fun `project and file`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = null, columnRaw = null),
				),
			)
	}

	@Test
	fun `project, file, and line`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/42")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "42", columnRaw = null),
				),
			)
	}

	@Test
	fun `project, file, line, and column`() {
		val request =
			parse(
				"https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/42/column/7",
			)
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "42", columnRaw = "7"),
				),
			)
	}

	@Test
	fun `multi-segment file path is rejoined with slashes`() {
		val request =
			parse(
				"https://www.appdevforall.org/device/open/project/MyApp/file/app/src/main/Main.kt/line/1",
			)
		assertThat(request?.fileRequest?.filePath).isEqualTo("app/src/main/Main.kt")
		assertThat(request?.fileRequest?.lineRaw).isEqualTo("1")
	}

	@Test
	fun `project name equal to a reserved keyword does not corrupt line parsing`() {
		// Regression test: a project literally named "line" used to make the parser latch onto the
		// project-name segment itself as the `line` keyword (the first occurrence in the whole path),
		// discarding the real line/42 suffix that follows `file`.
		val request = parse("https://www.appdevforall.org/device/open/project/line/file/Main.kt/line/42")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "line",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "42", columnRaw = null),
				),
			)
	}

	@Test
	fun `project name equal to a reserved keyword with no line suffix yields no line`() {
		val request = parse("https://www.appdevforall.org/device/open/project/line/file/Main.kt")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "line",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = null, columnRaw = null),
				),
			)
	}

	@Test
	fun `project name equal to the file keyword does not corrupt the file lookup`() {
		val request = parse("https://www.appdevforall.org/device/open/project/file/file/Main.kt")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "file",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = null, columnRaw = null),
				),
			)
	}

	@Test
	fun `a file path segment literally named 'line' is preserved when a real line suffix follows`() {
		// Regression test: line/column are now matched from the end of the path backward, not by the
		// keyword's first occurrence -- so a directory genuinely named "line" earlier in the file path
		// is kept as part of the filename as long as a real trailing line/{n} pair follows it.
		val request =
			parse("https://www.appdevforall.org/device/open/project/MyApp/file/line/Main.kt/line/42")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "line/Main.kt", lineRaw = "42", columnRaw = null),
				),
			)
	}

	@Test
	fun `a file path segment literally named 'column' is preserved when a real trailing pair follows`() {
		val request =
			parse(
				"https://www.appdevforall.org/device/open/project/MyApp/file/column/Main.kt/line/1/column/7",
			)
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "column/Main.kt", lineRaw = "1", columnRaw = "7"),
				),
			)
	}

	@Test
	fun `a file path that is only 'line' plus one segment names no file, so there is no file request`() {
		// The keyword ambiguity itself is still unresolved and still a known limitation: with nothing
		// else in the path, `file/line/Main.kt` is structurally identical to a real line suffix, and
		// this URL scheme has no delimiter to tell "a directory named line" from "the line keyword".
		// What changed is what the parser does once it has peeled the pair off and found nothing left
		// to open. It used to hand back a PendingFileRequest whose filePath was empty, which resolves
		// against nothing and reached the user as `File "" was not found in the project.` A link that
		// names no file is a plain project-open link, so the file request is dropped -- and the line
		// value goes with it, having nothing left to apply to.
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/line/Main.kt")
		assertThat(request).isEqualTo(DeepLinkRequest(projectName = "MyApp", fileRequest = null))
	}

	@Test
	fun `a numeric line with no file path is a plain project-open link`() {
		// The shape the review actually reported: `file/line/5` peels cleanly, leaves an empty path,
		// and must not become a file request for the empty string.
		assertThat(parse("https://www.appdevforall.org/device/open/project/MyApp/file/line/5"))
			.isEqualTo(DeepLinkRequest(projectName = "MyApp", fileRequest = null))
	}

	@Test
	fun `an empty file path with both line and column is a plain project-open link`() {
		assertThat(parse("https://www.appdevforall.org/device/open/project/MyApp/file/line/5/column/7"))
			.isEqualTo(DeepLinkRequest(projectName = "MyApp", fileRequest = null))
	}

	@Test
	fun `malformed line and column are carried through unparsed, not rejected`() {
		val request =
			parse(
				"https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/abc/column/xyz",
			)
		assertThat(request?.fileRequest?.lineRaw).isEqualTo("abc")
		assertThat(request?.fileRequest?.columnRaw).isEqualTo("xyz")
	}

	@Test
	fun `missing project segment yields null`() {
		assertThat(parse("https://www.appdevforall.org/device/open/MyApp")).isNull()
	}

	@Test
	fun `project segment with no name yields null`() {
		assertThat(parse("https://www.appdevforall.org/device/open/project")).isNull()
		assertThat(parse("https://www.appdevforall.org/device/open/project/")).isNull()
	}

	@Test
	fun `file keyword with no name yields no file request`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file")
		assertThat(request).isEqualTo(DeepLinkRequest(projectName = "MyApp", fileRequest = null))
	}

	@Test
	fun `an empty path segment yields null`() {
		// Uri.pathSegments drops empty segments, so ".../project//file/Main.kt" used to parse as a
		// project literally named "file" -- surfacing as `No project named "file" was found`, or
		// silently opening a real project of that name, instead of an honest invalid-link error.
		assertThat(parse("https://www.appdevforall.org/device/open/project//file/Main.kt")).isNull()
		assertThat(parse("https://www.appdevforall.org/device/open/project/MyApp//file/Main.kt")).isNull()
	}

	@Test
	fun `null uri yields null`() {
		assertThat(DeepLinkRequest.parse(null)).isNull()
	}

	@Test
	fun `wrong scheme yields null`() {
		// DeepLinkActivity is exported (required for App Links), so its intent-filter's data scoping
		// only constrains implicit intent matching -- an explicit intent from another app can carry
		// any Uri. This must be rejected here regardless of how the intent arrived.
		assertThat(parse("http://www.appdevforall.org/device/open/project/MyApp")).isNull()
	}

	@Test
	fun `wrong host yields null`() {
		assertThat(parse("https://evil.example/device/open/project/MyApp")).isNull()
	}

	@Test
	fun `apex host without the www subdomain also matches`() {
		// Both hosts serve an identical, verified assetlinks.json - see AndroidManifest.xml's matching
		// pair of <data> elements on DeepLinkActivity's intent-filter.
		val request = parse("https://appdevforall.org/device/open/project/MyApp")
		assertThat(request).isEqualTo(DeepLinkRequest(projectName = "MyApp"))
	}

	@Test
	fun `wrong path prefix yields null`() {
		assertThat(parse("https://www.appdevforall.org/some/other/path/project/MyApp")).isNull()
	}

	@Test
	fun `non-canonical scheme and host case still matches`() {
		// Scheme and host are case-insensitive per RFC 3986 -- an explicit intent from another app
		// (see the "wrong scheme" test's rationale) could carry either in non-canonical case, and a
		// semantically valid link must not be rejected over that alone.
		val request = parse("HTTPS://WWW.APPDEVFORALL.ORG/device/open/project/MyApp")
		assertThat(request).isEqualTo(DeepLinkRequest(projectName = "MyApp"))
	}

	@Test
	fun `a bare trailing 'column' keyword with no value is reported as invalid, not swallowed into the path`() {
		// Regression test: `.../column` with nothing after it can never match the keyword-at-
		// (size-2) pair check (there's no slot left for a value), so it used to silently fold into
		// the file path with no error at all -- unlike the equivalent dangling-line-before-column
		// case below, which was already reported.
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/column")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = null, columnRaw = ""),
				),
			)
	}

	@Test
	fun `a bare 'line' keyword immediately before a 'column' pair is reported as invalid, not swallowed into the path`() {
		// Regression test: `.../line/column/7` has no numeric value for "line" -- unlike the
		// swallowed-into-filename ambiguity documented above, "line" here sits directly in front of a
		// recognized "column" pair, so it must surface as an invalid line rather than silently
		// becoming part of the file path with no line requested and no error.
		val request =
			parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/column/7")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "", columnRaw = "7"),
				),
			)
	}

	@Test
	fun `a real line pair followed by a bare trailing 'column' is still parsed, not swallowed whole`() {
		// Regression test: a bare trailing "column" used to be checked independently against the
		// original, un-trimmed end -- missing it, then leaving the real "line/5" pair unexamined and
		// swallowed whole into the file path ("Main.kt/line/5") instead of peeling "column" off first
		// and re-checking what's left for the line pair it exposes.
		val request =
			parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/5/column")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "5", columnRaw = ""),
				),
			)
	}

	@Test
	fun `a bare trailing 'line' keyword with no value is reported as invalid, not swallowed into the path`() {
		// Regression test: symmetric to the bare-trailing-"column" case above, which was already
		// caught -- a bare trailing "line" used to silently fold into the file path with no line
		// number and no error at all.
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "", columnRaw = null),
				),
			)
	}
}
