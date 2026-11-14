package com.itsaky.androidide.plugins.manager.loaders

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdeVersionMetaDataTest {
	@Test
	fun aVersionWrittenAsTextIsShownAsWritten() {
		assertThat(Bundle().apply { putString(KEY, "26.42") }.ideVersion(KEY)).isEqualTo("26.42")
		assertThat(Bundle().apply { putString(KEY, "26.38.1") }.ideVersion(KEY)).isEqualTo("26.38.1")
		assertThat(Bundle().apply { putString(KEY, "99.0.0") }.ideVersion(KEY)).isEqualTo("99.0.0")
	}

	@Test
	fun aVersionWrittenAsANumberIsShownAsWritten() {
		assertThat(Bundle().apply { putFloat(KEY, 26.42f) }.ideVersion(KEY)).isEqualTo("26.42")
		assertThat(Bundle().apply { putFloat(KEY, 26.05f) }.ideVersion(KEY)).isEqualTo("26.05")
		assertThat(Bundle().apply { putFloat(KEY, 26.99f) }.ideVersion(KEY)).isEqualTo("26.99")
	}

	@Test
	fun aWeekEndingInZeroKeepsItsZero() {
		assertThat(Bundle().apply { putFloat(KEY, 26.30f) }.ideVersion(KEY)).isEqualTo("26.30")
		assertThat(Bundle().apply { putFloat(KEY, 26.10f) }.ideVersion(KEY)).isEqualTo("26.10")
	}

	@Test
	fun aVersionWrittenAsAWholeNumberIsShownAsWritten() {
		assertThat(Bundle().apply { putInt(KEY, 26) }.ideVersion(KEY)).isEqualTo("26")
	}

	@Test
	fun aNumberIsShownWithADotWhateverTheDeviceLocale() {
		val original = Locale.getDefault()
		try {
			Locale.setDefault(Locale.GERMANY)
			assertThat(Bundle().apply { putFloat(KEY, 26.42f) }.ideVersion(KEY)).isEqualTo("26.42")
		} finally {
			Locale.setDefault(original)
		}
	}

	@Test
	fun anAbsentVersionIsNull() {
		assertThat(Bundle().ideVersion(KEY)).isNull()
	}

	@Test
	fun aJsonManifestWritingTheVersionAsANumberKeepsItsText() {
		val manifest =
			PluginManifestParser.parseFromString(
				"""
				{
					"id": "com.example.plugin",
					"name": "Example",
					"version": "1.0.0",
					"description": "An example",
					"author": "Example Author",
					"main_class": "com.example.plugin.ExamplePlugin",
					"min_ide_version": 26.30,
					"max_ide_version": 26.42
				}
				""".trimIndent(),
			)

		assertThat(manifest?.minIdeVersion).isEqualTo("26.30")
		assertThat(manifest?.maxIdeVersion).isEqualTo("26.42")
	}

	private companion object {
		const val KEY = "plugin.min_ide_version"
	}
}
