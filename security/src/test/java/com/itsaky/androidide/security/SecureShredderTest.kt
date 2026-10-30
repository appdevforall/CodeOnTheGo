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

package com.itsaky.androidide.security

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Random

class SecureShredderTest {
	@get:Rule
	val tmp = TemporaryFolder()

	// Fixed seed so the overwrite path is exercised deterministically.
	private val random = Random(1234)

	@Test
	fun `shredFile removes an existing file`() {
		val f = tmp.newFile("secret.kt").apply { writeText("api_key = 42") }
		assertThat(SecureShredder.shredFile(f, random)).isTrue()
		assertThat(f.exists()).isFalse()
	}

	@Test
	fun `shredFile treats a missing file as already gone`() {
		val f = File(tmp.root, "does-not-exist")
		assertThat(SecureShredder.shredFile(f, random)).isTrue()
	}

	@Test
	fun `shredFile handles a zero-length file`() {
		val f = tmp.newFile("empty")
		assertThat(f.length()).isEqualTo(0)
		assertThat(SecureShredder.shredFile(f, random)).isTrue()
		assertThat(f.exists()).isFalse()
	}

	@Test
	fun `shredFile handles a file larger than one chunk`() {
		// > 4096 bytes so the chunked overwrite loop runs multiple times.
		val f = tmp.newFile("big.bin").apply { writeBytes(ByteArray(10_000) { 0x5A }) }
		assertThat(SecureShredder.shredFile(f, random)).isTrue()
		assertThat(f.exists()).isFalse()
	}

	@Test
	fun `shredTree counts files and removes the whole tree`() {
		val root = tmp.newFolder("project")
		File(root, "a.kt").writeText("one")
		File(root, "b.kt").writeText("two")
		val sub = File(root, "src/main").apply { mkdirs() }
		File(sub, "c.kt").writeText("three")

		val count = SecureShredder.shredTree(root, random)

		assertThat(count).isEqualTo(3)
		assertThat(root.exists()).isFalse()
	}

	@Test
	fun `shredTree on a single file returns one`() {
		val f = tmp.newFile("lonely.kt").apply { writeText("x") }
		assertThat(SecureShredder.shredTree(f, random)).isEqualTo(1)
		assertThat(f.exists()).isFalse()
	}

	@Test
	fun `shredTree on a missing path is a no-op`() {
		assertThat(SecureShredder.shredTree(File(tmp.root, "nope"), random)).isEqualTo(0)
	}

	@Test
	fun `shredTree fast-deletes skipped cache dirs but still removes them, and shreds the rest`() {
		val project = tmp.newFolder("MyApp")
		File(project, "Main.kt").writeText("secret source")
		val build = File(project, "build").apply { mkdirs() }
		File(build, "classes.bin").writeBytes(ByteArray(2048))
		val gradle = File(project, ".gradle").apply { mkdirs() }
		File(gradle, "cache.lock").writeText("lock")

		val count = SecureShredder.shredTree(project, random, skipDirNames = setOf("build", ".gradle"))

		// Only the source file is shredded (counted); the cache dirs are fast-deleted (not counted).
		assertThat(count).isEqualTo(1)
		// Everything is gone either way.
		assertThat(project.exists()).isFalse()
	}

	/** A folder outside the wipe holding one file, as a link in a target might point to. */
	private fun outsideFolder(): File = tmp.newFolder("outside").also { File(it, "photo.jpg").writeText("keep") }

	private fun link(
		at: File,
		to: File,
	): File = Files.createSymbolicLink(at.toPath(), to.toPath()).toFile()

	@Test
	fun `shredTree removes a link to a file without touching the file`() {
		val outside = tmp.newFile("photo.jpg").apply { writeText("keep") }
		val project = tmp.newFolder("project")
		link(File(project, "linked.jpg"), outside)

		SecureShredder.shredTree(project)

		assertThat(project.exists()).isFalse()
		assertThat(outside.readText()).isEqualTo("keep")
	}

	@Test
	fun `shredTree removes a link to a folder without entering it`() {
		val outside = outsideFolder()
		val project = tmp.newFolder("project")
		link(File(project, "assets"), outside)

		SecureShredder.shredTree(project)

		assertThat(project.exists()).isFalse()
		assertThat(File(outside, "photo.jpg").readText()).isEqualTo("keep")
	}

	@Test
	fun `a skipped cache folder is deleted without following a link inside it`() {
		val outside = outsideFolder()
		val build = tmp.newFolder("project", "build")
		link(File(build, "intermediates"), outside)

		SecureShredder.shredTree(build.parentFile!!, skipDirNames = setOf("build"))

		assertThat(build.exists()).isFalse()
		assertThat(File(outside, "photo.jpg").readText()).isEqualTo("keep")
	}

	@Test
	fun `deleteTree removes a tree and its links but not what they point to`() {
		val outside = outsideFolder()
		val tree = tmp.newFolder("gradle-build", "App-1a2b")
		File(tree, "app.apk").writeText("apk")
		link(File(tree, "linked"), outside)

		assertThat(SecureShredder.deleteTree(tree.parentFile!!)).isTrue()

		assertThat(tree.parentFile!!.exists()).isFalse()
		assertThat(File(outside, "photo.jpg").readText()).isEqualTo("keep")
	}
}
