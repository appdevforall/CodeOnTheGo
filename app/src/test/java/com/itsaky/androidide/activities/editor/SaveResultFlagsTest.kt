package com.itsaky.androidide.activities.editor

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.SaveResult
import org.junit.Test

/**
 * How a saved file folds into [SaveResult]'s flags.
 *
 * The property worth pinning: `resourceXmlSaved` - the flag the post-save `generateSources()`
 * gates read - is set only for a modified XML file the project manager recognizes as an Android
 * resource. Any other save (manifest-style non-resource XML, sources, unmodified files) must
 * leave it false so no Gradle run fires for a save that cannot change `R`.
 */
class SaveResultFlagsTest {
	@Test
	fun `a modified resource xml save sets both xml flags`() {
		val result = SaveResult()
		accumulateSaveFlags(result, "strings.xml", modified = true) { true }
		assertThat(result.xmlSaved).isTrue()
		assertThat(result.resourceXmlSaved).isTrue()
		assertThat(result.gradleSaved).isFalse()
	}

	@Test
	fun `a non-resource xml save sets xmlSaved only`() {
		val result = SaveResult()
		accumulateSaveFlags(result, "AndroidManifest.xml", modified = true) { false }
		assertThat(result.xmlSaved).isTrue()
		assertThat(result.resourceXmlSaved).isFalse()
	}

	@Test
	fun `an unmodified xml file sets nothing and skips the resource lookup`() {
		val result = SaveResult()
		var consulted = false
		accumulateSaveFlags(result, "strings.xml", modified = false) {
			consulted = true
			true
		}
		assertThat(result.xmlSaved).isFalse()
		assertThat(result.resourceXmlSaved).isFalse()
		assertThat(consulted).isFalse()
	}

	@Test
	fun `a source file sets nothing and skips the resource lookup`() {
		val result = SaveResult()
		var consulted = false
		accumulateSaveFlags(result, "Main.kt", modified = true) {
			consulted = true
			true
		}
		assertThat(result.gradleSaved).isFalse()
		assertThat(result.xmlSaved).isFalse()
		assertThat(result.resourceXmlSaved).isFalse()
		assertThat(consulted).isFalse()
	}

	@Test
	fun `groovy and kts gradle files set gradleSaved`() {
		val groovy = SaveResult()
		accumulateSaveFlags(groovy, "build.gradle", modified = true) { false }
		assertThat(groovy.gradleSaved).isTrue()

		val kts = SaveResult()
		accumulateSaveFlags(kts, "build.gradle.kts", modified = true) { false }
		assertThat(kts.gradleSaved).isTrue()
	}

	@Test
	fun `an unmodified gradle file does not set gradleSaved`() {
		val result = SaveResult()
		accumulateSaveFlags(result, "build.gradle", modified = false) { false }
		assertThat(result.gradleSaved).isFalse()
	}

	@Test
	fun `flags latch across files and the lookup is not re-consulted`() {
		val result = SaveResult()
		accumulateSaveFlags(result, "strings.xml", modified = true) { true }

		var consulted = false
		accumulateSaveFlags(result, "colors.xml", modified = true) {
			consulted = true
			false
		}
		assertThat(result.resourceXmlSaved).isTrue()
		assertThat(consulted).isFalse()
	}

	@Test
	fun `a later resource save upgrades a latched non-resource result`() {
		val result = SaveResult()
		accumulateSaveFlags(result, "AndroidManifest.xml", modified = true) { false }
		assertThat(result.resourceXmlSaved).isFalse()

		accumulateSaveFlags(result, "strings.xml", modified = true) { true }
		assertThat(result.resourceXmlSaved).isTrue()
	}
}
