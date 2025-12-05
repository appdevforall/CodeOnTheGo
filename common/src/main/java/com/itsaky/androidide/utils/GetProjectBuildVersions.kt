package com.itsaky.androidide.utils

import java.io.File

fun readGradleVersion(root: File): String {
	val gradleWrapper = File(root, "gradle/wrapper/gradle-wrapper.properties")
	if (!gradleWrapper.exists()) return "Unknown"

	val text = gradleWrapper.readText()
	val match = Regex("distributionUrl=.*gradle-(.*)-").find(text)
	return match?.groupValues?.get(1) ?: "Unknown"
}

fun readKotlinVersion(root: File): String {
	val buildGradle = File(root, "build.gradle")
	val buildGradleKts = File(root, "build.gradle.kts")

	val file = when {
		buildGradle.exists() -> buildGradle
		buildGradleKts.exists() -> buildGradleKts
		else -> return "Unknown"
	}

	val text = file.readText()

	// 1. kotlin_version = "1.9.22"
	val varMatch = Regex("""kotlin_version\s*=\s*"([^"]+)"""").find(text)
	if (varMatch != null) return varMatch.groupValues[1]

	// 2. id("org.jetbrains.kotlin.jvm") version "1.9.22"
	val pluginMatch = Regex("""id\(["']org.jetbrains.kotlin[^"']+["']\)\s*version\s*"([^"]+)"""")
		.find(text)
	if (pluginMatch != null) return pluginMatch.groupValues[1]

	// 3. force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
	val forceMatch = Regex("""force\(["']org.jetbrains.kotlin:kotlin-stdlib:([^"']+)["']\)""")
		.find(text)
	if (forceMatch != null) return forceMatch.groupValues[1]

	return "Unknown"
}

fun readJavaVersion(root: File): String {
	val buildGradle = File(root, "build.gradle")
	val buildGradleKts = File(root, "build.gradle.kts")

	val file = when {
		buildGradle.exists() -> buildGradle
		buildGradleKts.exists() -> buildGradleKts
		else -> return "Unknown"
	}

	val text = file.readText()

	// Regex: sourceCompatibility = JavaVersion.VERSION_17
	val regex = Regex("""sourceCompatibility\s*=\s*JavaVersion\.VERSION_([0-9]+)""")
	val match = regex.find(text)

	return match?.groupValues?.get(1) ?: "Unknown"
}