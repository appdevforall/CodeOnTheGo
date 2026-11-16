package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Each installed plugin build has paths of its own. Android caches resources, assets and native
 * libraries by path, so a build at the previous build's paths ran against the previous build's
 * files until the app restarted.
 */
class PluginBuildFilesTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val pluginsDir by lazy { temp.newFolder("plugins") }
	private val nativeLibsDir by lazy { temp.newFolder("plugin_native_libs") }

	// Uninstall tests write the plugin ID as the file's text; a real file has it in its manifest.
	private val files by lazy { PluginBuildFiles(pluginsDir, nativeLibsDir) { it.readText() } }

	@Test
	fun eachBuildIsNamedAfterItsContent() {
		val a = build("a.cgp", "libfoo.so" to "A")
		val b = build("b.cgp", "libfoo.so" to "B")
		val aAgain = a.copyTo(File(temp.root, "a-again.cgp"))

		val stemA = PluginBuildFiles.fileStem(ID, a)

		assertThat(stemA).matches("${Regex.escape(ID)}-[0-9a-f]{16}")
		assertWithMessage("two builds got one name").that(PluginBuildFiles.fileStem(ID, b)).isNotEqualTo(stemA)
		assertThat(PluginBuildFiles.fileStem(ID, aAgain)).isEqualTo(stemA)
	}

	@Test
	fun aNewBuildLoadsItsLibrariesFromANewFolder() {
		val dirA = files.extractNativeLibs(ID, installed(build("a.cgp", "libfoo.so" to "A")), ABI)
		val dirB = files.extractNativeLibs(ID, installed(build("b.cgp", "libfoo.so" to "B")), ABI)

		assertWithMessage("the new build reuses the old build's library path").that(dirB).isNotEqualTo(dirA)
		assertThat(File(dirB, "libfoo.so").readText()).isEqualTo("B")
		assertWithMessage("the old build's folder is left behind").that(dirA!!.exists()).isFalse()
	}

	@Test
	fun librariesExtractedFromAnotherFileAreNotReused() {
		// The layout before per-build folders: the libraries straight in the plugin's folder.
		File(nativeLibsDir, ID).mkdirs()
		File(nativeLibsDir, "$ID/libfoo.so").writeText("old")
		val legacy = build("$ID.cgp", "libfoo.so" to "new").copyTo(File(pluginsDir, "$ID.cgp"))

		val dir = files.extractNativeLibs(ID, legacy, ABI)

		assertThat(File(dir, "libfoo.so").readText()).isEqualTo("new")
		assertThat(File(nativeLibsDir, "$ID/libfoo.so").exists()).isFalse()
	}

	@Test
	fun aBuildWithoutLibrariesForTheAbiGetsNoFolder() {
		File(nativeLibsDir, "$ID/0123456789abcdef").mkdirs()
		val otherAbi = build("x.cgp", "libfoo.so" to "x", abi = "x86_64")

		assertThat(files.extractNativeLibs(ID, installed(otherAbi), ABI)).isNull()
		assertThat(File(nativeLibsDir, ID).exists()).isFalse()
	}

	@Test
	fun uninstallDeletesTheLibrariesOfAPluginWithNoFileLeft() {
		File(nativeLibsDir, "$ID/0123456789abcdef").mkdirs()
		File(nativeLibsDir, "$ID/0123456789abcdef/libfoo.so").writeText("A")

		assertThat(files.delete(ID)).isFalse()
		assertThat(File(nativeLibsDir, ID).exists()).isFalse()
	}

	@Test
	fun uninstallDeletesEveryFileOfThePluginAndNoOther() {
		val legacy = File(pluginsDir, "$ID.cgp").apply { writeText(ID) }
		val hashed = File(pluginsDir, "$ID-0123456789abcdef.cgp").apply { writeText(ID) }
		// An ID that starts with this one, so only the manifest tells the files apart.
		val other = File(pluginsDir, "$ID.extra-0123456789abcdef.cgp").apply { writeText("$ID.extra") }

		assertThat(files.filesOf(ID)).containsExactly(legacy, hashed)
		assertThat(files.delete(ID)).isTrue()
		assertWithMessage("a second file of the plugin survived").that(pluginsDir.listFiles()!!.toList()).containsExactly(other)
	}

	// As PluginRepositoryImpl.installPluginFromFile names and copies it.
	private fun installed(build: File): File {
		val name = "${PluginBuildFiles.fileStem(ID, build)}.cgp"
		return build.copyTo(File(pluginsDir, name), overwrite = true)
	}

	private fun build(
		name: String,
		vararg libs: Pair<String, String>,
		abi: String = ABI,
	): File =
		File(temp.newFolder(), name).also { file ->
			ZipOutputStream(file.outputStream()).use { zip ->
				for ((lib, content) in libs) {
					zip.putNextEntry(ZipEntry("lib/$abi/$lib"))
					zip.write(content.toByteArray())
					zip.closeEntry()
				}
			}
		}

	private companion object {
		const val ID = "org.example.plugin"
		const val ABI = "arm64-v8a"
	}
}
