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

/**
 * [DeepLinkRequest.buildUrl] -- the write side of the deep-link scheme, checked mostly by
 * round-tripping through [DeepLinkRequest.parse], which is the only consumer that matters.
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkBuildUrlTest {
	private fun roundTrip(url: String?) = DeepLinkRequest.parse(Uri.parse(url!!))

	@Test
	fun `project only`() {
		val url = DeepLinkRequest.buildUrl(projectName = "MyApp")
		assertThat(url).isEqualTo("https://appdevforall.org/device/open/project/MyApp")
		assertThat(roundTrip(url)).isEqualTo(DeepLinkRequest(projectName = "MyApp"))
	}

	@Test
	fun `file with line and column`() {
		val url = DeepLinkRequest.buildUrl("MyApp", "src/main/Main.kt", line = 7, column = 3)
		assertThat(url)
			.isEqualTo("https://appdevforall.org/device/open/project/MyApp/file/src/main/Main.kt/line/7/column/3")
		assertThat(roundTrip(url))
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "src/main/Main.kt", lineRaw = "7", columnRaw = "3"),
				),
			)
	}

	@Test
	fun `path separators stay separators, they are not percent-encoded away`() {
		// Uri.Builder.appendPath encodes '/' inside a single segment, so the whole relative path must
		// be appended one component at a time. Getting this wrong still round-trips -- it just emits
		// an unreadable ".../file/src%2Fmain%2FMain.kt" -- so assert the emitted shape, not just parse.
		val url = DeepLinkRequest.buildUrl("MyApp", "src/main/Main.kt")
		assertThat(url).doesNotContain("%2F")
		assertThat(url).endsWith("/file/src/main/Main.kt")
	}

	@Test
	fun `space in project name is encoded and survives the round trip`() {
		val url = DeepLinkRequest.buildUrl("My App", "Main.kt", line = 1, column = 1)
		assertThat(url).contains("/project/My%20App/")
		assertThat(roundTrip(url)?.projectName).isEqualTo("My App")
	}

	@Test
	fun `hash in a filename is encoded rather than truncating the path into a fragment`() {
		val url = DeepLinkRequest.buildUrl("MyApp", "notes/Draft#1.md", line = 4, column = 2)
		assertThat(url).contains("Draft%231.md")
		assertThat(roundTrip(url)?.fileRequest?.filePath).isEqualTo("notes/Draft#1.md")
	}

	@Test
	fun `question mark in a filename is encoded rather than starting a query`() {
		val url = DeepLinkRequest.buildUrl("MyApp", "Why?.txt")
		assertThat(url).contains("Why%3F.txt")
		assertThat(roundTrip(url)?.fileRequest?.filePath).isEqualTo("Why?.txt")
	}

	@Test
	fun `decomposed accented name survives the round trip byte for byte`() {
		// NFD: "Cafe" + COMBINING ACUTE ACCENT, the form a directory cloned from macOS carries.
		val nfd = "Cafe\u0301"
		val url = DeepLinkRequest.buildUrl(nfd, "Main.kt")
		assertThat(url).contains("Cafe%CC%81")
		assertThat(roundTrip(url)?.projectName).isEqualTo(nfd)
	}

	@Test
	fun `a directory named line does not swallow the file path when line and column are written`() {
		// The shape parse() documents as unresolvable, defused: because a real trailing line/column
		// pair always follows, peelTrailingKeyword consumes that pair and never reaches the "line"
		// directory in the path itself.
		val url = DeepLinkRequest.buildUrl("MyApp", "src/line/5", line = 7, column = 3)
		assertThat(roundTrip(url))
			.isEqualTo(
				DeepLinkRequest(
					projectName = "MyApp",
					fileRequest = PendingFileRequest(filePath = "src/line/5", lineRaw = "7", columnRaw = "3"),
				),
			)
	}

	@Test
	fun `refuses the same path with no line, because parse would read it as metadata`() {
		// Without a trailing line/column pair to consume, parse() peels "line/5" off the path itself
		// and reads this back as file "src" at line 5. buildUrl's round-trip check catches that and
		// returns null rather than emitting a link that opens the wrong file.
		assertThat(DeepLinkRequest.buildUrl("MyApp", "src/line/5")).isNull()
		assertThat(DeepLinkRequest.buildUrl("MyApp", "src/column/5")).isNull()

		// Not over-refusing: the same path IS expressible once a real pair follows it.
		assertThat(DeepLinkRequest.buildUrl("MyApp", "src/line/5", line = 7, column = 3)).isNotNull()
	}

	@Test
	fun `rejects dot segments the reader would refuse or an intermediary would rewrite`() {
		// isProjectCandidateDir() refuses any name starting with '.', so none of these can name a
		// project; "." and ".." are worse than unopenable, since a URL-normalizing browser or
		// messenger rewrites them into a different path in transit.
		assertThat(DeepLinkRequest.buildUrl(".")).isNull()
		assertThat(DeepLinkRequest.buildUrl("..")).isNull()
		assertThat(DeepLinkRequest.buildUrl(".hidden")).isNull()

		assertThat(DeepLinkRequest.buildUrl("MyApp", "../../etc/passwd")).isNull()
		assertThat(DeepLinkRequest.buildUrl("MyApp", "src/./Main.kt")).isNull()

		// A hidden FILE is legitimate, unlike a hidden project directory -- resolveWithinDirectory
		// rejects traversal, not dot-prefixed names.
		assertThat(DeepLinkRequest.buildUrl("MyApp", ".gitignore", line = 1, column = 1)).isNotNull()
	}

	@Test
	fun `never returns a url that parses back as something else`() {
		// The contract the round-trip check exists to hold. Anything buildUrl returns must read back
		// as exactly the arguments it was given.
		val cases =
			listOf(
				Triple("MyApp", "src/main/Main.kt", 7 to 3),
				Triple("My App", "a b/c d.kt", 1 to 1),
				Triple("Cafe\u0301", "notes/Draft#1.md", 12 to 40),
				Triple("MyApp", "src/line/5", 7 to 3),
				Triple("MyApp", "src/column/9/line/2", 4 to 6),
			)
		for ((project, path, coords) in cases) {
			val (line, column) = coords
			val url = DeepLinkRequest.buildUrl(project, path, line, column)
			assertThat(url).isNotNull()
			assertThat(roundTrip(url))
				.isEqualTo(
					DeepLinkRequest(
						projectName = project,
						fileRequest =
							PendingFileRequest(
								filePath = path,
								lineRaw = line.toString(),
								columnRaw = column.toString(),
							),
					),
				)
		}
	}

	@Test
	fun `column without a line round-trips`() {
		val url = DeepLinkRequest.buildUrl("MyApp", "Main.kt", line = null, column = 3)
		assertThat(roundTrip(url)?.fileRequest)
			.isEqualTo(PendingFileRequest(filePath = "Main.kt", lineRaw = null, columnRaw = "3"))
	}

	@Test
	fun `rejects a project name that cannot name a direct child of the projects root`() {
		assertThat(DeepLinkRequest.buildUrl("")).isNull()
		assertThat(DeepLinkRequest.buildUrl("nested/MyApp")).isNull()
		assertThat(DeepLinkRequest.buildUrl("nested\\MyApp")).isNull()
	}

	@Test
	fun `rejects an empty path component, which would emit a rejected double slash`() {
		assertThat(DeepLinkRequest.buildUrl("MyApp", "src//Main.kt")).isNull()
		assertThat(DeepLinkRequest.buildUrl("MyApp", "/Main.kt")).isNull()
		assertThat(DeepLinkRequest.buildUrl("MyApp", "")).isNull()
	}

	@Test
	fun `rejects a line or column that parse would report back as invalid`() {
		assertThat(DeepLinkRequest.buildUrl("MyApp", "Main.kt", line = 0)).isNull()
		assertThat(DeepLinkRequest.buildUrl("MyApp", "Main.kt", line = -1)).isNull()
		assertThat(DeepLinkRequest.buildUrl("MyApp", "Main.kt", line = 1, column = 0)).isNull()
	}

	@Test
	fun `rejects a line with no file to apply it to`() {
		assertThat(DeepLinkRequest.buildUrl("MyApp", filePath = null, line = 7)).isNull()
	}

	@Test
	fun `rejects a path over the length parse enforces`() {
		val tooLong = "a".repeat(600)
		assertThat(DeepLinkRequest.buildUrl("MyApp", tooLong)).isNull()

		// And is not simply refusing everything long: a path just inside the ceiling still builds.
		val insideCeiling = "a".repeat(400)
		assertThat(DeepLinkRequest.buildUrl("MyApp", insideCeiling)).isNotNull()
	}
}
