package com.itsaky.androidide.build.maven

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The cases of dev-assets' scripts/tests/test-cache-to-local-maven-repo.py (ADFA-7220). */
class ModuleUrlNamesTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun module(vararg variants: List<Pair<String, String>>): String =
		variants.withIndex().joinToString(",", "{\"formatVersion\": \"1.1\", \"variants\": [", "]}") { (i, files) ->
			files.joinToString(",", "{\"name\": \"v$i\", \"files\": [", "]}") { (name, url) ->
				"{\"name\": \"$name\", \"url\": \"$url\"}"
			}
		}

	private fun dir(path: String): File = File(tmp.root, path).apply { mkdirs() }

	private fun File.put(
		name: String,
		text: String = "x",
	): File = File(this, name).apply { writeText(text) }

	private fun File.listing(): List<String> = list()!!.sorted()

	@Test
	fun `a file takes its module url name`() {
		val d = dir("androidx/lifecycle/lifecycle-viewmodel-android/2.9.4")
		val pair = "lifecycle-viewmodel-release.aar" to "lifecycle-viewmodel-android-2.9.4.aar"
		d.put("lifecycle-viewmodel-android-2.9.4.module", module(listOf(pair), listOf(pair)))
		d.put("lifecycle-viewmodel-android-2.9.4.pom", "<project/>")
		d.put("lifecycle-viewmodel-release.aar", "aar bytes")

		renameFilesToModuleUrls(tmp.root.toPath())

		assertThat(d.listing())
			.containsExactly(
				"lifecycle-viewmodel-android-2.9.4.aar",
				"lifecycle-viewmodel-android-2.9.4.module",
				"lifecycle-viewmodel-android-2.9.4.pom",
			).inOrder()
		assertThat(File(d, "lifecycle-viewmodel-android-2.9.4.aar").readText()).isEqualTo("aar bytes")
	}

	@Test
	fun `matching names are untouched`() {
		val d = dir("androidx/core/core/1.13.1")
		d.put("core-1.13.1.module", module(listOf("core-1.13.1.aar" to "core-1.13.1.aar")))
		d.put("core-1.13.1.aar")

		assertThat(renameFilesToModuleUrls(tmp.root.toPath())).isEmpty()
		assertThat(d.listing()).containsExactly("core-1.13.1.aar", "core-1.13.1.module").inOrder()
	}

	@Test
	fun `a name another variant serves is kept`() {
		val d = dir("g/x/lib/1")
		d.put("lib-1.module", module(listOf("lib.jar" to "lib-1.jar"), listOf("lib.jar" to "lib.jar")))
		d.put("lib.jar", "jar")

		renameFilesToModuleUrls(tmp.root.toPath())

		assertThat(d.listing()).containsExactly("lib-1.jar", "lib-1.module", "lib.jar").inOrder()
	}

	@Test
	fun `one name serves two urls`() {
		val d = dir("g/x/lib/4")
		d.put("lib-4.module", module(listOf("lib.aar" to "lib-4.aar"), listOf("lib.aar" to "lib-4-debug.aar")))
		d.put("lib.aar", "aar")

		renameFilesToModuleUrls(tmp.root.toPath())

		assertThat(d.listing()).containsExactly("lib-4-debug.aar", "lib-4.aar", "lib-4.module").inOrder()
	}

	@Test
	fun `a missing file and an existing url are left alone`() {
		val d = dir("g/x/lib/2")
		d.put("lib-2.module", module(listOf("absent.aar" to "lib-2.aar"), listOf("lib-old.jar" to "lib-2.jar")))
		d.put("lib-old.jar", "old")
		d.put("lib-2.jar", "new")

		renameFilesToModuleUrls(tmp.root.toPath())

		assertThat(d.listing()).containsExactly("lib-2.jar", "lib-2.module", "lib-old.jar").inOrder()
		assertThat(File(d, "lib-2.jar").readText()).isEqualTo("new")
	}

	@Test
	fun `a url outside the directory is ignored`() {
		val d = dir("g/x/kmp/3")
		d.put("kmp-3.module", module(listOf("kmp.jar" to "../../kmp-jvm/3/kmp-jvm-3.jar")))
		d.put("kmp.jar")

		renameFilesToModuleUrls(tmp.root.toPath())

		assertThat(d.listing()).containsExactly("kmp-3.module", "kmp.jar").inOrder()
	}

	@Test
	fun `odd module shapes are skipped`() {
		dir("g/x/odd/0").put("odd-0.module", "[]")
		dir("g/x/odd/1").put("odd-1.module", "{\"variants\": null}")
		val d = dir("g/x/odd/2")
		d.put("odd-2.module", "{\"variants\": [{\"files\": null}, {\"files\": [\"x\"]}, 3]}")

		assertThat(renameFilesToModuleUrls(tmp.root.toPath())).isEmpty()
		assertThat(d.listing()).containsExactly("odd-2.module")
	}
}
