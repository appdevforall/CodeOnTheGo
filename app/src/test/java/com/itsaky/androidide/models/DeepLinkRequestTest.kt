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
	fun `a file path that is only 'line' plus one segment is read as the keyword -- known limitation`() {
		// Documents, rather than fixes, a case the previous test's approach can't resolve: with
		// nothing else in the path, `file/line/Main.kt` is structurally identical to a real line
		// suffix -- there's no delimiter in this URL scheme to tell "a directory named line" apart
		// from "the line keyword" when it's the only content after `file`. Locking in current
		// behavior so a future change doesn't alter it silently.
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/line/Main.kt")
		assertThat(request)
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "", lineRaw = "Main.kt", columnRaw = null),
				),
			)
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
	fun `wrong path prefix yields null`() {
		assertThat(parse("https://www.appdevforall.org/some/other/path/project/MyApp")).isNull()
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
}
