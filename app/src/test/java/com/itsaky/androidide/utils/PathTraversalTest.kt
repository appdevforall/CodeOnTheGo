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

package com.itsaky.androidide.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PathTraversalTest {
	private val baseDir = File("/project/root")
	private val nulCharacter = 0.toChar()

	@JvmField
	@Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `plain relative path resolves inside base`() {
		val resolved = resolveWithinDirectory(baseDir, "src/Main.kt")
		assertEquals(File("/project/root/src/Main.kt"), resolved)
	}

	@Test
	fun `literal dot-dot is rejected`() {
		assertNull(resolveWithinDirectory(baseDir, "../../etc/passwd"))
	}

	@Test
	fun `empty relative path is rejected instead of resolving to baseDir itself`() {
		// Regression test: java.nio.file.Path.resolve("") is a documented no-op, returning the base
		// path unchanged -- without an explicit empty-string check, the containment check below
		// would trivially pass and this function would violate its own "returns null" contract,
		// silently returning baseDir. DeepLinkRequest.parse's own documented "known limitation" (a
		// file path whose entire content is just the "line" keyword) produces exactly this shape.
		assertNull(resolveWithinDirectory(baseDir, ""))
	}

	@Test
	fun `dot-dot buried in the middle of a path is rejected`() {
		// The shape produced once android.net.Uri decodes a single raw segment containing an
		// encoded slash, e.g. the URL segment "foo%2f..%2f..%2fetc%2fpasswd" -- decoded to one
		// string, but still containing ".." once decoded.
		assertNull(resolveWithinDirectory(baseDir, "foo/../../etc/passwd"))
	}

	@Test
	fun `leading slash is rejected`() {
		assertNull(resolveWithinDirectory(baseDir, "/etc/passwd"))
	}

	@Test
	fun `leading backslash is rejected`() {
		assertNull(resolveWithinDirectory(baseDir, "\\Windows\\System32"))
	}

	@Test
	fun `embedded NUL character is rejected instead of throwing`() {
		// android.net.Uri.pathSegments percent-decodes before this function ever sees the string, so
		// a URL's "%00" arrives here as a literal NUL character. java.nio.file.Path throws
		// InvalidPathException for that -- must be caught, not left to crash the caller.
		assertNull(resolveWithinDirectory(baseDir, "foo" + nulCharacter + ".txt"))
	}

	@Test
	fun `a filename merely containing dot-dot as a substring is rejected too`() {
		// Intentionally the stricter, simpler substring reject rather than a proper per-segment
		// check -- project files never legitimately need consecutive dots in a name, so treating
		// "a..b.txt" the same as an actual ".." traversal segment is an acceptable, safe trade-off.
		assertNull(resolveWithinDirectory(baseDir, "a..b.txt"))
	}

	@Test
	fun `multi-segment path resolves and normalizes redundant separators`() {
		val resolved = resolveWithinDirectory(baseDir, "app/src/main/Main.kt")
		assertEquals(File("/project/root/app/src/main/Main.kt"), resolved)
	}

	@Test
	fun `plain file inside a real base directory still resolves`() {
		val root = tempFolder.newFolder("real-project")
		File(root, "src").mkdirs()
		val target = File(root, "src/Main.kt").apply { writeText("fun main() {}") }

		val resolved = resolveWithinDirectory(root, "src/Main.kt")
		assertEquals(target.canonicalFile, resolved?.canonicalFile)
	}

	@Test
	fun `symlink inside base pointing outside it is rejected`() {
		// Regression test: the lexical/normalize check alone doesn't catch a symlink physically
		// present inside the project directory (e.g. from a git clone, which supports symlinks) that
		// points outside it -- resolveWithinDirectory must also verify the real, on-disk path.
		val root = tempFolder.newFolder("real-project")
		val outside = tempFolder.newFolder("outside")
		File(outside, "secret.txt").writeText("secret")
		Files.createSymbolicLink(File(root, "evil").toPath(), outside.toPath())

		assertNull(resolveWithinDirectory(root, "evil/secret.txt"))
	}
}
