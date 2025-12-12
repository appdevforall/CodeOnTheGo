package com.itsaky.androidide.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun readGradleVersion(root: File): String = withContext(Dispatchers.IO) {
	val gradleWrapper = File(root, "gradle/wrapper/gradle-wrapper.properties")
	if (!gradleWrapper.exists()) return@withContext "Unknown"

	val text = gradleWrapper.readText()
	val match = Regex("distributionUrl=.*gradle-(.*)-").find(text)
	return@withContext match?.groupValues?.get(1) ?: "Unknown"
}

suspend fun readKotlinVersion(root: File): String = withContext(Dispatchers.IO) {
	val kotlinVarRegex =
		Regex("""kotlin_version\s*=\s*"([^"]+)"""")

	val kotlinPluginRegex =
		Regex("""id\(["']org.jetbrains.kotlin[^"']+["']\)\s*version\s*"([^"]+)"""")

	val kotlinForceRegex =
		Regex("""force\(["']org.jetbrains.kotlin:kotlin-stdlib:([^"']+)["']\)""")

	val tomlDirectRegex =
		Regex("""kotlin\s*=\s*"([^"]+)"""")

	val tomlRefRegex =
		Regex("""version\.ref\s*=\s*"([^"]+)"""")

	val gradleFiles = sequenceOf(
		File(root, "app/build.gradle"),
		File(root, "app/build.gradle.kts"),
		File(root, "build.gradle"),
		File(root, "build.gradle.kts"),
	).filter { it.exists() }

	for (file in gradleFiles) {
		val text = file.readText()

		kotlinVarRegex.find(text)?.groupValues?.get(1)?.let { return@withContext it }
		kotlinPluginRegex.find(text)?.groupValues?.get(1)?.let { return@withContext it }
		kotlinForceRegex.find(text)?.groupValues?.get(1)?.let { return@withContext it }
	}

	val libsToml = File(root, "gradle/libs.versions.toml")
	if (!libsToml.exists()) return@withContext "Unknown"

	val toml = libsToml.readText()

	tomlDirectRegex.find(toml)?.groupValues?.get(1)?.let { return@withContext it }

	val refName = tomlRefRegex.find(toml)?.groupValues?.get(1)
		?: return@withContext "Unknown"

	Regex("""$refName\s*=\s*"([^"]+)"""")
		.find(toml)
		?.groupValues
		?.get(1)
		?: "Unknown"
}


suspend fun readJavaVersion(root: File): String = withContext(Dispatchers.IO) {
	val buildGradle = File(root, "build.gradle")
	val buildGradleKts = File(root, "build.gradle.kts")

	val file = when {
		buildGradle.exists() -> buildGradle
		buildGradleKts.exists() -> buildGradleKts
		else -> return@withContext "Unknown"
	}

	val text = file.readText()

	// Regex: sourceCompatibility = JavaVersion.VERSION_17 | JavaVersion.VERSION_1_8
	val regex = Regex("""sourceCompatibility\s*=\s*JavaVersion\.VERSION_([0-9_]+)""")
	val match = regex.find(text)

	return@withContext match?.groupValues?.get(1) ?: "Unknown"
}