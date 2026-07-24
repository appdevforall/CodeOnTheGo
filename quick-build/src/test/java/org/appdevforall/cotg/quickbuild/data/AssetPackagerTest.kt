package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class AssetPackagerTest {
	@TempDir lateinit var tempDir: File

	private val packager = AssetPackager()
	private lateinit var assetsRoot: File

	@BeforeEach
	fun setUp() {
		assetsRoot = File(tempDir, "app/src/main/assets").apply { mkdirs() }
	}

	private fun asset(
		relative: String,
		content: String = "content",
	): File =
		File(assetsRoot, relative).apply {
			parentFile!!.mkdirs()
			writeText(content)
		}

	@Test
	fun `relativeAssetPath resolves nested paths with forward slashes`() {
		val file = asset("data/levels.json")
		assertThat(packager.relativeAssetPath(file, listOf(assetsRoot)))
			.isEqualTo("data/levels.json")
	}

	@Test
	fun `relativeAssetPath is null for files outside the roots`() {
		val source = File(tempDir, "app/src/main/java/Foo.kt")
		assertThat(packager.relativeAssetPath(source, listOf(assetsRoot))).isNull()
	}

	@Test
	fun `packageAssets zips only the asset files from a mixed changed-set`() {
		val levels = asset("data/levels.json", "levels")
		val source =
			File(tempDir, "app/src/main/java/Foo.kt").apply {
				parentFile!!.mkdirs()
				writeText("class Foo")
			}

		val out = File(tempDir, "payload.zip")
		val packaged = packager.packageAssets(listOf(levels, source), listOf(assetsRoot), out)

		assertThat(packaged).isNotNull()
		assertThat(packaged!!.relativePaths).containsExactly("data/levels.json")
		ZipFile(out).use { zip ->
			val entry = zip.getEntry("data/levels.json")
			assertThat(entry).isNotNull()
			assertThat(zip.getInputStream(entry).readBytes().decodeToString()).isEqualTo("levels")
		}
	}

	@Test
	fun `packageAssets returns null when no asset changed`() {
		val source = File(tempDir, "Foo.kt").apply { writeText("class Foo") }
		val out = File(tempDir, "payload.zip")
		assertThat(packager.packageAssets(listOf(source), listOf(assetsRoot), out)).isNull()
		assertThat(out.exists()).isFalse()
	}

	@Test
	fun `packageAssets skips deleted files but keeps existing ones`() {
		val kept = asset("kept.txt", "kept")
		val deleted = File(assetsRoot, "deleted.txt")

		val out = File(tempDir, "payload.zip")
		val packaged = packager.packageAssets(listOf(kept, deleted), listOf(assetsRoot), out)

		assertThat(packaged).isNotNull()
		ZipFile(out).use { zip ->
			assertThat(zip.getEntry("kept.txt")).isNotNull()
			assertThat(zip.getEntry("deleted.txt")).isNull()
		}
	}
}
