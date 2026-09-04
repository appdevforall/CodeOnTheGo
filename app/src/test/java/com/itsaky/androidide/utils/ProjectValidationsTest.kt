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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Normalizer

class ProjectValidationsTest {
	@JvmField
	@Rule
	val tempFolder = TemporaryFolder()

	private fun makeValidProject(
		parent: File,
		name: String,
	): File {
		val project = File(parent, name).apply { mkdirs() }
		val appDir = File(project, "app").apply { mkdirs() }
		File(appDir, "build.gradle.kts").writeText("// stub")
		return project
	}

	@Test
	fun `resolves an existing project by name`() {
		val root = tempFolder.newFolder("projects")
		val project = makeValidProject(root, "MyApp")

		assertThat(findValidProjectByName(root, "MyApp")?.canonicalFile).isEqualTo(project.canonicalFile)
	}

	@Test
	fun `unknown project name yields null`() {
		val root = tempFolder.newFolder("projects")
		assertThat(findValidProjectByName(root, "DoesNotExist")).isNull()
	}

	@Test
	fun `NFC-normalized name matches an NFD on-disk project directory`() {
		// Regression test: a deep link URL is typically NFC-normalized by web tooling, but an
		// imported project directory (e.g. a git clone authored on macOS, which decomposes
		// accented filenames to NFD) may not codepoint-match it even though the two look identical.
		val root = tempFolder.newFolder("projects")
		val nfc = Normalizer.normalize("Café", Normalizer.Form.NFC)
		val nfd = Normalizer.normalize("Café", Normalizer.Form.NFD)
		assertThat(nfd).isNotEqualTo(nfc) // sanity check: the two forms really are distinct strings
		val project = makeValidProject(root, nfd)

		assertThat(findValidProjectByName(root, nfc)?.canonicalFile).isEqualTo(project.canonicalFile)
	}

	@Test
	fun `dot-dot traversal outside projectsRoot is rejected`() {
		// Regression test: a bare File(projectsRoot, name) join let `name` escape projectsRoot
		// entirely (e.g. name = "../outside"). A real deep link supplies this as a decoded URL
		// segment, so a project sitting just outside the configured projects root must never be
		// resolvable via a crafted project name.
		//
		// This exact input ("../outside" contains a "/") is actually short-circuited by
		// findValidProjectByName's own separate name.contains("/") guard, never reaching
		// resolveWithinDirectory's traversal logic -- see the test below for the single-segment
		// ".." case a real deep link's URL path segment can actually carry (Uri.pathSegments never
		// contains a literal "/" within one segment).
		val base = tempFolder.newFolder("base")
		val root = File(base, "projects").apply { mkdirs() }
		makeValidProject(base, "outside")

		assertThat(findValidProjectByName(root, "../outside")).isNull()
	}

	@Test
	fun `a single-segment 'dot-dot' name is rejected`() {
		// The reachable counterpart to the test above: a deep link's project-name URL segment can
		// never contain "/" (Uri.pathSegments splits on it), so name = ".." alone -- not "../x" --
		// is the actual traversal shape resolveWithinDirectory's lexical check must catch.
		//
		// base is made a *valid* project (not just a bare directory) so this test actually exercises
		// that lexical check: with a bare directory, findValidProjectByName would return null either
		// way -- via the traversal check working correctly, or via isValidProjectDirectory rejecting
		// an escaped-but-unmarked base -- so the assertion couldn't tell a traversal regression apart
		// from a passing test.
		val base = makeValidProject(tempFolder.root, "base")
		val root = File(base, "projects").apply { mkdirs() }

		assertThat(findValidProjectByName(root, "..")).isNull()
	}

	// The tri-state lookup findValidProjectByName now delegates to (ADFA-5067 review). These pin the
	// two outcomes a unit test can actually produce; Unverifiable needs a real EACCES/EIO from the
	// filesystem mid-call, which is not reliably provokable in a JVM test -- see the class docs.
	@Test
	fun `lookup reports Found for an existing project`() {
		val root = tempFolder.newFolder("projects")
		val project = makeValidProject(root, "MyApp")

		val lookup = lookupValidProjectByName(root, "MyApp")

		assertThat(lookup).isInstanceOf(ProjectNameLookup.Found::class.java)
		assertThat((lookup as ProjectNameLookup.Found).dir.canonicalFile).isEqualTo(project.canonicalFile)
	}

	@Test
	fun `lookup reports NotFound for a name with no project`() {
		val root = tempFolder.newFolder("projects")

		assertThat(lookupValidProjectByName(root, "DoesNotExist")).isEqualTo(ProjectNameLookup.NotFound)
	}

	// A traversal attempt is a definite "not this project", not an unknown -- callers are allowed to
	// remember a NotFound, and must not be handed something they have to treat as maybe-transient.
	@Test
	fun `lookup reports NotFound for a traversal attempt`() {
		val root = tempFolder.newFolder("projects")

		assertThat(lookupValidProjectByName(root, "../etc")).isEqualTo(ProjectNameLookup.NotFound)
		assertThat(lookupValidProjectByName(root, ".")).isEqualTo(ProjectNameLookup.NotFound)
		assertThat(lookupValidProjectByName(root, "")).isEqualTo(ProjectNameLookup.NotFound)
	}

	// findValidProjectByName is now a thin reduction of lookupValidProjectByName; this pins that the
	// refactor did not change what the many existing callers see.
	@Test
	fun `findValidProjectByName still agrees with the lookup it delegates to`() {
		val root = tempFolder.newFolder("projects")
		makeValidProject(root, "MyApp")

		for (name in listOf("MyApp", "DoesNotExist", "../etc", ".", "")) {
			val expected = (lookupValidProjectByName(root, name) as? ProjectNameLookup.Found)?.dir
			assertThat(findValidProjectByName(root, name)).isEqualTo(expected)
		}
	}

	@Test
	fun `the no-IO shortcut answers the same-parent case and defers the rest`() {
		val root = tempFolder.newFolder("projects")

		// Same parent by path: decidable with no filesystem call at all.
		assertThat(deepLinkTargetOfOpenProjectWithoutIo(File(root, "MyApp").path, "MyApp", root)).isTrue()

		// Definitively not this project, also decidable without I/O.
		assertThat(deepLinkTargetOfOpenProjectWithoutIo(File(root, "MyApp").path, "Other", root)).isFalse()
		assertThat(deepLinkTargetOfOpenProjectWithoutIo("", "MyApp", root)).isFalse()

		// Parent differs as text, so only canonicalisation can settle it -- the caller must go
		// off-thread rather than treat this as a "no".
		val elsewhere = tempFolder.newFolder("elsewhere")
		assertThat(deepLinkTargetOfOpenProjectWithoutIo(File(elsewhere, "MyApp").path, "MyApp", root)).isNull()
	}

	@Test
	fun `the full rule agrees with the shortcut wherever the shortcut commits`() {
		val root = tempFolder.newFolder("projects2")
		for (case in listOf(File(root, "MyApp").path to "MyApp", File(root, "MyApp").path to "Other", "" to "MyApp")) {
			val (path, name) = case
			val shortcut = deepLinkTargetOfOpenProjectWithoutIo(path, name, root)
			assertThat(shortcut).isNotNull()
			assertThat(isDeepLinkTargetOfOpenProject(path, name, root)).isEqualTo(shortcut)
		}
	}
}
