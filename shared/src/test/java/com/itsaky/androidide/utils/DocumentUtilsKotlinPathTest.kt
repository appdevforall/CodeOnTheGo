package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentUtilsKotlinPathTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	@Test
	fun deletedKotlinPathStillCountsAsKotlin() {
		val deleted = tempDir.root.toPath().resolve("Gone.kt")

		assertThat(DocumentUtils.hasKotlinExtension(deleted)).isTrue()
	}

	@Test
	fun ktsCounts() {
		val script = tempDir.newFile("build.gradle.kts").toPath()

		assertThat(DocumentUtils.hasKotlinExtension(script)).isTrue()
	}

	@Test
	fun javaDoesNot() {
		val java = tempDir.newFile("Main.java").toPath()

		assertThat(DocumentUtils.hasKotlinExtension(java)).isFalse()
	}
}
