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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkRequestTest {
	private fun parse(url: String) = DeepLinkRequest.parse(Uri.parse(url))

	@Test
	fun `project only`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp")
		assertEquals(DeepLinkRequest(projectName = "MyApp"), request)
	}

	@Test
	fun `project and file`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt")
		assertEquals(
			DeepLinkRequest(
				projectName = "MyApp",
				fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = null, columnRaw = null),
			),
			request,
		)
	}

	@Test
	fun `project, file, and line`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/42")
		assertEquals(
			DeepLinkRequest(
				projectName = "MyApp",
				fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "42", columnRaw = null),
			),
			request,
		)
	}

	@Test
	fun `project, file, line, and column`() {
		val request =
			parse(
				"https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/42/column/7",
			)
		assertEquals(
			DeepLinkRequest(
				projectName = "MyApp",
				fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "42", columnRaw = "7"),
			),
			request,
		)
	}

	@Test
	fun `multi-segment file path is rejoined with slashes`() {
		val request =
			parse(
				"https://www.appdevforall.org/device/open/project/MyApp/file/app/src/main/Main.kt/line/1",
			)
		assertEquals("app/src/main/Main.kt", request?.fileRequest?.filePath)
		assertEquals("1", request?.fileRequest?.lineRaw)
	}

	@Test
	fun `project name equal to a reserved keyword does not corrupt line parsing`() {
		// Regression test: a project literally named "line" used to make the parser latch onto the
		// project-name segment itself as the `line` keyword (the first occurrence in the whole path),
		// discarding the real line/42 suffix that follows `file`.
		val request = parse("https://www.appdevforall.org/device/open/project/line/file/Main.kt/line/42")
		assertEquals(
			DeepLinkRequest(
				projectName = "line",
				fileRequest = PendingFileRequest(filePath = "Main.kt", lineRaw = "42", columnRaw = null),
			),
			request,
		)
	}

	@Test
	fun `malformed line and column are carried through unparsed, not rejected`() {
		val request =
			parse(
				"https://www.appdevforall.org/device/open/project/MyApp/file/Main.kt/line/abc/column/xyz",
			)
		assertEquals("abc", request?.fileRequest?.lineRaw)
		assertEquals("xyz", request?.fileRequest?.columnRaw)
	}

	@Test
	fun `missing project segment yields null`() {
		assertNull(parse("https://www.appdevforall.org/device/open/MyApp"))
	}

	@Test
	fun `project segment with no name yields null`() {
		assertNull(parse("https://www.appdevforall.org/device/open/project"))
		assertNull(parse("https://www.appdevforall.org/device/open/project/"))
	}

	@Test
	fun `file keyword with no name yields no file request`() {
		val request = parse("https://www.appdevforall.org/device/open/project/MyApp/file")
		assertEquals(DeepLinkRequest(projectName = "MyApp", fileRequest = null), request)
	}

	@Test
	fun `null uri yields null`() {
		assertNull(DeepLinkRequest.parse(null))
	}
}
