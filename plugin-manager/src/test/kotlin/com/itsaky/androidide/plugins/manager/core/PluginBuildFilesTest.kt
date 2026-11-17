package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

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
	private val files by lazy { newFiles() }

	private fun newFiles() = PluginBuildFiles(pluginsDir, nativeLibsDir) { it.readText() }

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
		val a = installed(build("a.cgp", "libfoo.so" to "A"))
		val dirA = files.extractNativeLibs(ID, a, ABI)
		a.delete()

		val dirB = files.extractNativeLibs(ID, installed(build("b.cgp", "libfoo.so" to "B")), ABI)

		assertWithMessage("the new build reuses the old build's library path").that(dirB).isNotEqualTo(dirA)
		assertThat(File(dirB, "libfoo.so").readText()).isEqualTo("B")
		assertWithMessage("the replaced build's folder is left behind").that(dirA!!.exists()).isFalse()
	}

	@Test
	fun anotherInstalledBuildKeepsItsFolder() {
		// Two files with one ID both load, in parallel: neither may delete the other's libraries.
		val dirA = files.extractNativeLibs(ID, installed(build("a.cgp", "libfoo.so" to "A")), ABI)
		files.extractNativeLibs(ID, installed(build("b.cgp", "libfoo.so" to "B")), ABI)

		assertThat(File(dirA, "libfoo.so").readText()).isEqualTo("A")
	}

	@Test
	fun anOlderCoGoBuildFindsNoFolderToReuse() {
		val dataDir = temp.newFolder("data")
		val build = installed(build("a.cgp", "libfoo.so" to "A"))

		val dir = PluginBuildFiles.inDataDir(pluginsDir, dataDir) { ID }.extractNativeLibs(ID, build, ABI)

		assertThat(File(dir, "libfoo.so").readText()).isEqualTo("A")
		// Before per-build folders, CoGo loaded a plugin's libraries from getDir("plugin_native_libs")/<id>
		// whenever that folder existed, whatever it held.
		assertWithMessage("an older CoGo build would find this build's folder and no libraries in it")
			.that(File(dataDir, "app_plugin_native_libs/$ID").exists())
			.isFalse()
	}

	@Test
	fun librariesAnOlderCoGoBuildExtractedAreDeleted() {
		val dataDir = temp.newFolder("data")
		File(dataDir, "app_plugin_native_libs/$ID").mkdirs()
		File(dataDir, "app_plugin_native_libs/$ID/libfoo.so").writeText("old")
		val legacy = build("$ID.cgp", "libfoo.so" to "new").copyTo(File(pluginsDir, "$ID.cgp"))

		val dir = PluginBuildFiles.inDataDir(pluginsDir, dataDir) { ID }.extractNativeLibs(ID, legacy, ABI)

		assertThat(File(dir, "libfoo.so").readText()).isEqualTo("new")
		assertWithMessage("an older CoGo build's libraries are left behind")
			.that(File(dataDir, "app_plugin_native_libs").exists())
			.isFalse()
	}

	@Test
	fun aFileUnderTheOldNameRewrittenInPlaceGetsItsOwnLibraries() {
		val legacy = build("x.cgp", "libfoo.so" to "X").copyTo(File(pluginsDir, "$ID.cgp"))
		legacy.setLastModified(1_000_000_000_000)
		files.extractNativeLibs(ID, legacy, ABI)
		// An older CoGo build updates the plugin under the same name.
		build("y.cgp", "libfoo.so" to "Y").copyTo(legacy, overwrite = true)
		legacy.setLastModified(1_000_000_001_000)

		val dir = newFiles().extractNativeLibs(ID, legacy, ABI)

		assertWithMessage("the rewritten file ran against the previous content's libraries")
			.that(File(dir, "libfoo.so").readText())
			.isEqualTo("Y")
	}

	@Test
	fun anInstalledBuildUnderTheOldNameOfAnIdWithATildeKeepsItsFolder() {
		val id = "org.example~plugin"
		val legacy = build("old.cgp", "libfoo.so" to "old").copyTo(File(pluginsDir, "$id.cgp"))
		val legacyDir = files.extractNativeLibs(id, legacy, ABI)
		val hashed = build("new.cgp", "libfoo.so" to "new")

		files.extractNativeLibs(id, hashed.copyTo(File(pluginsDir, "${PluginBuildFiles.fileStem(id, hashed)}.cgp")), ABI)

		assertWithMessage("loading one build deleted the folder of another installed build")
			.that(File(legacyDir, "libfoo.so").exists())
			.isTrue()
	}

	@Test
	fun aLeftoverScratchFolderIsNotReused() {
		val build = installed(build("a.cgp", "libfoo.so" to "A"))
		val scratch = File(nativeLibsDir, "$ID/.${PluginBuildFiles.buildKey(ID, build)}.partial")
		scratch.mkdirs()
		File(scratch, "libstale.so").writeText("from an interrupted extraction")

		val dir = files.extractNativeLibs(ID, build, ABI)

		assertThat(dir!!.list()!!.toList()).containsExactly("libfoo.so")
		assertThat(scratch.exists()).isFalse()
	}

	@Test
	fun aBuildReinstalledInTheSameProcessGetsANewFolder() {
		val build = build("a.cgp", "libfoo.so" to "A")
		val first = files.extractNativeLibs(ID, installed(build), ABI)
		files.delete(ID)

		val second = files.extractNativeLibs(ID, installed(build), ABI)

		// The old class loader may still hold the first path, and the runtime refuses to open it again.
		assertWithMessage("the reinstalled build got the path the old class loader opened").that(second).isNotEqualTo(first)
		assertThat(File(second, "libfoo.so").readText()).isEqualTo("A")
		assertWithMessage("the next start extracts again instead of reusing the folder")
			.that(newFiles().extractNativeLibs(ID, File(pluginsDir, "${PluginBuildFiles.fileStem(ID, build)}.cgp"), ABI))
			.isEqualTo(second)
	}

	@Test
	fun overlappingExtractionsOfABuildAllGetItsLibraries() {
		val content = Random(7).nextBytes(4 shl 20)
		repeat(ROUNDS) { round ->
			val id = "$ID.r$round"
			val build = build("r$round.cgp", "libbig.so" to content)
			val installed = build.copyTo(File(pluginsDir, "${PluginBuildFiles.fileStem(id, build)}.cgp"))
			val start = CountDownLatch(1)
			val pool = Executors.newFixedThreadPool(THREADS)
			val results =
				(1..THREADS).map {
					pool.submit<File?> {
						start.await()
						files.extractNativeLibs(id, installed, ABI)
					}
				}
			start.countDown()
			val dirs = results.map { it.get(60, TimeUnit.SECONDS) }
			pool.shutdown()

			assertWithMessage("round $round: loads got different folders").that(dirs.toSet()).hasSize(1)
			assertWithMessage("round $round: a library is incomplete")
				.that(File(dirs.first(), "libbig.so").readBytes().contentEquals(content))
				.isTrue()
		}
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

	@Test
	fun aPluginIdThatIsAPathDeletesNothingOutsideTheLibrariesFolder() {
		val victim = File(nativeLibsDir.parentFile, "victim").apply { mkdirs() }
		File(victim, "keep.txt").writeText("not a plugin's")
		val build = installed(build("a.cgp", "libfoo.so" to "A"))

		val deleted = runCatching { files.delete("../victim") }
		val extracted = runCatching { files.extractNativeLibs("../victim", build, ABI) }

		assertWithMessage("a folder outside the libraries folder lost files").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertThat(deleted.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
		assertThat(extracted.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun aPluginFolderThatLinksElsewhereIsNotFollowed() {
		val victim = victim()
		val link = File(nativeLibsDir, ID).also { Files.createSymbolicLink(it.toPath(), victim.toPath()) }

		val deleted = runCatching { files.delete(ID) }

		assertWithMessage("the link's target lost files").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertThat(deleted.exceptionOrNull()).isNull()
		assertWithMessage("the link is still there").that(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS)).isFalse()
	}

	@Test
	fun aPluginFolderThatLinksElsewhereIsReplacedByAFolderOfLibraries() {
		val victim = victim()
		Files.createSymbolicLink(File(nativeLibsDir, ID).toPath(), victim.toPath())

		val dir = files.extractNativeLibs(ID, installed(build("a.cgp", "libfoo.so" to "A")), ABI)

		assertWithMessage("the link's target lost files").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertThat(File(dir, "libfoo.so").readText()).isEqualTo("A")
		assertWithMessage("the libraries went through a link").that(Files.isSymbolicLink(File(nativeLibsDir, ID).toPath())).isFalse()
	}

	@Test
	fun aPluginFolderLinkThatCannotBeDeletedFailsExtractionInsteadOfEmptyingItsTarget() {
		val victim = victim()
		val link = File(nativeLibsDir, ID).also { Files.createSymbolicLink(it.toPath(), victim.toPath()) }
		val build = installed(build("a.cgp", "libfoo.so" to "A"))

		// The link can't be unlinked from a read-only folder.
		nativeLibsDir.setWritable(false)
		val extracted =
			try {
				runCatching { files.extractNativeLibs(ID, build, ABI) }
			} finally {
				nativeLibsDir.setWritable(true)
			}

		assertThat(extracted.exceptionOrNull()).isInstanceOf(IOException::class.java)
		assertThat(extracted.exceptionOrNull()).hasMessageThat().contains(link.absolutePath)
		assertWithMessage("extraction went through the link").that(victim.list()?.toList()).containsExactly("keep.txt")
	}

	@Test
	fun aLinkInThePluginFolderIsNotFollowedByUninstall() {
		val victim = victim()
		File(nativeLibsDir, ID).mkdirs()
		Files.createSymbolicLink(File(nativeLibsDir, "$ID/some-build").toPath(), victim.toPath())

		files.delete(ID)

		assertWithMessage("the link's target lost files").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertThat(File(nativeLibsDir, ID).exists()).isFalse()
	}

	@Test
	fun aLinkInThePluginFolderIsNotFollowedByTheCleanupOfOldBuilds() {
		val victim = victim()
		File(nativeLibsDir, ID).mkdirs()
		Files.createSymbolicLink(File(nativeLibsDir, "$ID/stale").toPath(), victim.toPath())

		val dir = files.extractNativeLibs(ID, installed(build("a.cgp", "libfoo.so" to "A")), ABI)

		assertWithMessage("the link's target lost files").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertThat(File(dir, "libfoo.so").readText()).isEqualTo("A")
		assertThat(File(nativeLibsDir, "$ID/stale").exists()).isFalse()
	}

	@Test
	fun aLinkAtTheBuildsFolderIsNotReused() {
		val victim = victim()
		val build = installed(build("a.cgp", "libfoo.so" to "A"))
		File(nativeLibsDir, ID).mkdirs()
		Files.createSymbolicLink(File(nativeLibsDir, "$ID/${PluginBuildFiles.buildKey(ID, build)}").toPath(), victim.toPath())

		val dir = files.extractNativeLibs(ID, build, ABI)

		assertThat(File(dir, "libfoo.so").readText()).isEqualTo("A")
		assertWithMessage("the build's libraries load through a link").that(Files.isSymbolicLink(dir!!.toPath())).isFalse()
		assertWithMessage("the link's target changed").that(victim.list()?.toList()).containsExactly("keep.txt")
	}

	// Outside the folders the code under test deletes, with a file that must survive.
	private fun victim(): File =
		File(nativeLibsDir.parentFile, "victim").apply { mkdirs() }.also { File(it, "keep.txt").writeText("not a plugin's") }

	// As PluginRepositoryImpl.installPluginFromFile names and copies it.
	private fun installed(build: File): File {
		val name = "${PluginBuildFiles.fileStem(ID, build)}.cgp"
		return build.copyTo(File(pluginsDir, name), overwrite = true)
	}

	private fun build(
		name: String,
		vararg libs: Pair<String, Any>,
		abi: String = ABI,
	): File =
		File(temp.newFolder(), name).also { file ->
			ZipOutputStream(file.outputStream()).use { zip ->
				for ((lib, content) in libs) {
					zip.putNextEntry(ZipEntry("lib/$abi/$lib"))
					zip.write(if (content is ByteArray) content else content.toString().toByteArray())
					zip.closeEntry()
				}
			}
		}

	private companion object {
		const val ID = "org.example.plugin"
		const val ABI = "arm64-v8a"
		const val ROUNDS = 10
		const val THREADS = 6
	}
}
