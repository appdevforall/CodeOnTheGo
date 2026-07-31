package com.itsaky.androidide.utils

import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Regression tests for ResourceUtils, added when replacing com.blankj:utilcodex's ResourceUtils
 * with an in-house implementation (ADFA-4649). [BaseApplication.baseInstance] and its
 * [AssetManager] are mocked since this file's only external dependency is asset access.
 */
class ResourceUtilsTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private val assets = mockk<AssetManager>()

	@Before
	fun setup() {
		val app = mockk<BaseApplication> { every { assets } returns this@ResourceUtilsTest.assets }
		mockkObject(BaseApplication.Companion)
		every { BaseApplication.baseInstance } returns app
	}

	@After
	fun tearDown() {
		unmockkObject(BaseApplication.Companion)
	}

	@Test
	fun `copyFileFromAssets copies a single file`() {
		every { assets.list("icon.png") } returns emptyArray()
		every { assets.open("icon.png") } returns "png bytes".byteInputStream()

		val dest = tempFolder.root.resolve("copied/icon.png")
		val result = ResourceUtils.copyFileFromAssets("icon.png", dest.absolutePath)

		assertThat(result).isTrue()
		assertThat(dest.readText()).isEqualTo("png bytes")
	}

	@Test
	fun `copyFileFromAssets copies a directory recursively`() {
		every { assets.list("template") } returns arrayOf("a.txt", "nested")
		every { assets.list("template/a.txt") } returns emptyArray()
		every { assets.list("template/nested") } returns arrayOf("b.txt")
		every { assets.list("template/nested/b.txt") } returns emptyArray()
		every { assets.open("template/a.txt") } returns "a content".byteInputStream()
		every { assets.open("template/nested/b.txt") } returns "b content".byteInputStream()

		val destDir = tempFolder.newFolder("dest")
		val result = ResourceUtils.copyFileFromAssets("template", destDir.absolutePath)

		assertThat(result).isTrue()
		assertThat(File(destDir, "a.txt").readText()).isEqualTo("a content")
		assertThat(File(destDir, "nested/b.txt").readText()).isEqualTo("b content")
	}

	@Test
	fun `copyFileFromAssets returns false without throwing when the asset can't be opened`() {
		every { assets.list("missing.png") } returns emptyArray()
		every { assets.open("missing.png") } throws IOException("no such asset")

		val dest = tempFolder.root.resolve("copied/missing.png")
		val result = ResourceUtils.copyFileFromAssets("missing.png", dest.absolutePath)

		assertThat(result).isFalse()
	}

	@Test
	fun `readAssets2String returns the asset's content`() {
		every { assets.open("recipe.json.ftl") } returns "{\"key\":\"value\"}".byteInputStream()

		assertThat(ResourceUtils.readAssets2String("recipe.json.ftl")).isEqualTo("{\"key\":\"value\"}")
	}

	@Test
	fun `readAssets2String returns an empty string without throwing when the asset can't be read`() {
		every { assets.open("missing.ftl") } throws IOException("no such asset")

		assertThat(ResourceUtils.readAssets2String("missing.ftl")).isEmpty()
	}
}
