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

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
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
		assertThat(resolved).isEqualTo(File(baseDir, "src/Main.kt").absoluteFile)
	}

	@Test
	fun `literal dot-dot is rejected`() {
		assertThat(resolveWithinDirectory(baseDir, "../../etc/passwd")).isNull()
	}

	@Test
	fun `empty relative path is rejected instead of resolving to baseDir itself`() {
		// Regression test: java.nio.file.Path.resolve("") is a documented no-op, returning the base
		// path unchanged -- without an explicit empty-string check, the containment check below
		// would trivially pass and this function would violate its own "returns null" contract,
		// silently returning baseDir. DeepLinkRequest.parse's own documented "known limitation" (a
		// file path whose entire content is just the "line" keyword) produces exactly this shape.
		assertThat(resolveWithinDirectory(baseDir, "")).isNull()
	}

	@Test
	fun `dot-dot buried in the middle of a path is rejected`() {
		// The shape produced once android.net.Uri decodes a single raw segment containing an
		// encoded slash, e.g. the URL segment "foo%2f..%2f..%2fetc%2fpasswd" -- decoded to one
		// string, but still containing ".." once decoded.
		assertThat(resolveWithinDirectory(baseDir, "foo/../../etc/passwd")).isNull()
	}

	@Test
	fun `leading slash is rejected`() {
		assertThat(resolveWithinDirectory(baseDir, "/etc/passwd")).isNull()
	}

	@Test
	fun `leading backslash is rejected`() {
		assertThat(resolveWithinDirectory(baseDir, "\\Windows\\System32")).isNull()
	}

	@Test
	fun `embedded NUL character is rejected instead of throwing`() {
		// android.net.Uri.pathSegments percent-decodes before this function ever sees the string, so
		// a URL's "%00" arrives here as a literal NUL character. java.nio.file.Path throws
		// InvalidPathException for that -- must be caught, not left to crash the caller.
		assertThat(resolveWithinDirectory(baseDir, "foo" + nulCharacter + ".txt")).isNull()
	}

	// This used to be rejected, on the reasoning that project files never legitimately need
	// consecutive dots. They do -- and a deep link to one failing with no explanation is a bug, not
	// a safe trade-off. Nothing is given up: only a literal ".." *segment* can name a parent, and
	// the tests below cover every way of writing one.
	@Test
	fun `a filename containing dot-dot is resolved, not rejected`() {
		assertThat(resolveWithinDirectory(baseDir, "notes..txt"))
			.isEqualTo(File(baseDir, "notes..txt").absoluteFile)
		assertThat(resolveWithinDirectory(baseDir, "a..b/c.kt"))
			.isEqualTo(File(baseDir, "a..b/c.kt").absoluteFile)
		assertThat(resolveWithinDirectory(baseDir, "....gitignore"))
			.isEqualTo(File(baseDir, "....gitignore").absoluteFile)
	}

	// The segment itself, in every position, is still refused.
	@Test
	fun `a dot-dot segment is rejected wherever it appears`() {
		assertThat(resolveWithinDirectory(baseDir, "..")).isNull()
		assertThat(resolveWithinDirectory(baseDir, "../x")).isNull()
		assertThat(resolveWithinDirectory(baseDir, "a/../b")).isNull()
		assertThat(resolveWithinDirectory(baseDir, "a/..")).isNull()
		assertThat(resolveWithinDirectory(baseDir, "a\\..\\b")).isNull()
	}

	// Percent-decoding happens in Uri.pathSegments before this function sees the string, so an
	// encoded traversal arrives as a literal ".." segment and is caught above. A double-encoded one
	// arrives as the harmless filename "%2e%2e", which cannot name a parent directory.
	@Test
	fun `a double-encoded dot-dot is an ordinary filename`() {
		assertThat(resolveWithinDirectory(baseDir, "%2e%2e/x"))
			.isEqualTo(File(baseDir, "%2e%2e/x").absoluteFile)
	}

	@Test
	fun `multi-segment path resolves and normalizes redundant separators`() {
		// Actually redundant: a doubled separator and a "." segment, which the old input had neither
		// of -- so the normalization this test is named for went unpinned.
		assertThat(resolveWithinDirectory(baseDir, "app//src/./main/Main.kt"))
			.isEqualTo(File("/project/root/app/src/main/Main.kt"))

		val resolved = resolveWithinDirectory(baseDir, "app/src/main/Main.kt")
		assertThat(resolved).isEqualTo(File("/project/root/app/src/main/Main.kt"))
	}

	@Test
	fun `plain file inside a real base directory still resolves`() {
		val root = tempFolder.newFolder("real-project")
		File(root, "src").mkdirs()
		val target = File(root, "src/Main.kt").apply { writeText("fun main() {}") }

		val resolved = resolveWithinDirectory(root, "src/Main.kt")
		assertThat(resolved?.canonicalFile).isEqualTo(target.canonicalFile)
	}

	@Test
	fun `symlink inside base pointing outside it is rejected`() {
		// Regression test: the lexical/normalize check alone doesn't catch a symlink physically
		// present inside the project directory (e.g. from a git clone, which supports symlinks) that
		// points outside it -- resolveWithinDirectory must also verify the real, on-disk path.
		val root = tempFolder.newFolder("real-project")
		val outside = tempFolder.newFolder("outside")
		File(outside, "secret.txt").writeText("secret")

		val symlinkCreated =
			try {
				Files.createSymbolicLink(File(root, "evil").toPath(), outside.toPath())
				true
			} catch (e: UnsupportedOperationException) {
				// The filesystem itself doesn't support symlinks (e.g. FAT32).
				false
			} catch (e: FileSystemException) {
				// Windows NTFS supports symlinks but requires an elevated/Developer Mode privilege to
				// create them -- without it, creation fails with this specific reason (a permission
				// error), not UnsupportedOperationException. Any other reason is a real, unexpected
				// failure and must not be silently swallowed into a skipped test, which would take
				// the symlink-escape assertion out of CI without anyone noticing.
				if (e.reason?.contains("privilege", ignoreCase = true) != true) throw e
				false
			}
		// Report as skipped, not silently passed, when this environment can't create symlinks.
		Assume.assumeTrue("Symlinks are not supported/permitted on this filesystem", symlinkCreated)

		assertThat(resolveWithinDirectory(root, "evil/secret.txt")).isNull()
	}

	// A containment check must fail closed. Resolving the base used to happen once in the constructor,
	// catching the IOException and nulling the field, which permanently downgraded every later
	// resolve() to lexical containment alone -- weaker than the canonical-prefix check this class
	// replaced, and silent about it.
	@Test
	fun `a base that cannot be resolved rejects everything`() {
		val root = tempFolder.newFolder("unresolvable-root")
		val base = File(root, "base").apply { mkdirs() }
		val resolver = ContainedPathResolver(base)
		assertThat(resolver.resolve("child.txt")).isNotNull()

		// Make the base unresolvable by removing traverse permission on its parent, then check that
		// this environment actually produced the failure -- as root, or on a filesystem that ignores
		// the mode, it will not, and the test has nothing to assert.
		root.setExecutable(false, false)
		try {
			val reallyUnresolvable =
				try {
					base.toPath().toRealPath()
					false
				} catch (_: IOException) {
					true
				}
			Assume.assumeTrue("This environment still resolves a base under a non-traversable parent", reallyUnresolvable)

			// Both the resolver built while the base was fine and a fresh one: the check is per call.
			assertThat(resolver.resolve("child.txt")).isNull()
			assertThat(ContainedPathResolver(base).resolve("child.txt")).isNull()
		} finally {
			root.setExecutable(true, false)
		}
	}

	// Layer 3 is skipped only when the base is *confirmed* absent. Pinning the base at construction
	// meant a resolver built before its directory existed -- which is exactly how the asset installer
	// builds one -- skipped the symlink check forever, including for the tree extraction then created.
	@Test
	fun `a symlink planted after the resolver was built is still caught`() {
		val root = tempFolder.newFolder("late-symlink")
		val base = File(root, "base")
		val resolver = ContainedPathResolver(base)

		val outside = File(root, "outside").apply { mkdirs() }
		File(outside, "secret.txt").writeText("secret")
		base.mkdirs()
		Files.createSymbolicLink(File(base, "link").toPath(), outside.toPath())

		assertThat(resolver.resolve("link/secret.txt")).isNull()
	}

	// The narrowing this class deliberately makes over ZipUtils' old canonical-prefix check: an entry
	// that normalizes back inside the base is still refused, because a ".." segment is refused before
	// anything is resolved. Stated here so the behaviour change is pinned rather than incidental.
	@Test
	fun `a dot-dot segment is refused even when it normalizes back inside`() {
		assertThat(resolveWithinDirectory(baseDir, "a/../b.txt")).isNull()
	}
}
