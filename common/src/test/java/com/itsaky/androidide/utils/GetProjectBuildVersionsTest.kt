package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GetProjectBuildVersionsTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `readProjectLanguage identifies Java project with java files`() =
		runBlocking {
			val root = tempFolder.newFolder("JavaProject")
			val srcDir = File(root, "app/src/main/java/com/example")
			srcDir.mkdirs()
			File(srcDir, "MainActivity.java").writeText("package com.example; public class MainActivity {}")

			val gradleToml = File(root, "gradle")
			gradleToml.mkdirs()
			File(gradleToml, "libs.versions.toml").writeText(
				"""
				[versions]
				agp = "8.2.0"
				appcompat = "1.6.1"
				[libraries]
				androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
				""".trimIndent(),
			)

			val language = readProjectLanguage(root)
			assertThat(language).isEqualTo("Java")
		}

	@Test
	fun `readProjectLanguage identifies Kotlin project with kt files`() =
		runBlocking {
			val root = tempFolder.newFolder("KotlinProject")
			val srcDir = File(root, "app/src/main/java/com/example")
			srcDir.mkdirs()
			File(srcDir, "MainActivity.kt").writeText("package com.example\nclass MainActivity")

			val language = readProjectLanguage(root)
			assertThat(language).isEqualTo("Kotlin")
		}

	@Test
	fun `readKotlinVersion returns Unknown when libs toml has no kotlin version`() =
		runBlocking {
			val root = tempFolder.newFolder("TomlProject")
			val gradleToml = File(root, "gradle")
			gradleToml.mkdirs()
			File(gradleToml, "libs.versions.toml").writeText(
				"""
				[versions]
				agp = "8.2.0"
				appcompat = "1.6.1"
				[libraries]
				androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
				""".trimIndent(),
			)

			val kotlinVer = readKotlinVersion(root)
			assertThat(kotlinVer).isEqualTo("Unknown")
		}

	@Test
	fun `readKotlinVersion parses kotlin version correctly from libs toml`() =
		runBlocking {
			val root = tempFolder.newFolder("KotlinTomlProject")
			val gradleToml = File(root, "gradle")
			gradleToml.mkdirs()
			File(gradleToml, "libs.versions.toml").writeText(
				"""
				[versions]
				agp = "8.2.0"
				kotlin = "1.9.20"
				""".trimIndent(),
			)

			val kotlinVer = readKotlinVersion(root)
			assertThat(kotlinVer).isEqualTo("1.9.20")
		}

	@Test
	fun `readKotlinVersion resolves kotlin version through version ref`() =
		runBlocking {
			val root = tempFolder.newFolder("KotlinRefProject")
			val gradleToml = File(root, "gradle")
			gradleToml.mkdirs()
			File(gradleToml, "libs.versions.toml").writeText(
				"""
				[versions]
				agp = "8.2.0"
				kgp = "1.9.20"
				[plugins]
				kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kgp" }
				""".trimIndent(),
			)

			val kotlinVer = readKotlinVersion(root)
			assertThat(kotlinVer).isEqualTo("1.9.20")
		}

	@Test
	fun `readKotlinVersion ignores shorthand kotlin library coordinates`() =
		runBlocking {
			val root = tempFolder.newFolder("KotlinShorthandProject")
			val gradleToml = File(root, "gradle")
			gradleToml.mkdirs()
			File(gradleToml, "libs.versions.toml").writeText(
				"""
				[libraries]
				org-jetbrains-kotlin-stdlib = "org.jetbrains.kotlin:kotlin-stdlib:1.9.24"
				[versions]
				kotlin = "2.0.0"
				""".trimIndent(),
			)

			val kotlinVer = readKotlinVersion(root)
			assertThat(kotlinVer).isEqualTo("2.0.0")
		}
}
