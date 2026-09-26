package org.appdevforall.cotg.quickbuild.daemon

import java.io.File

/**
 * Locates a host Android SDK for the d8/aapt2 tests, which are assumption-guarded (`@EnabledIf`)
 * because hosts without an SDK can't run them. On device the paths arrive in the configure request;
 * the daemon never uses this. `REQUIRE_BUILD_TOOLCHAIN=1` / `-PrequireBuildToolchain` (both wired
 * to `quickbuild.test.requireToolchain`) turn an absent toolchain from a silent skip into a test
 * error, so CI can never skip the aapt2/d8/Compose regressions (ADFA-4128 bugs 5/6/8).
 */
object TestSdk {
	private fun toolchainRequired(): Boolean = System.getProperty("quickbuild.test.requireToolchain").toBoolean()

	private fun requireOrSkip(
		available: Boolean,
		what: String,
	): Boolean {
		check(available || !toolchainRequired()) {
			"REQUIRE_BUILD_TOOLCHAIN is set but the $what is unavailable on this host - " +
				"these tests must run, not skip (SDK roots tried: ANDROID_HOME, ANDROID_SDK_ROOT, " +
				"~/Android/Sdk, ~/Library/Android/sdk; Compose jars are staged by the build)."
		}
		return available
	}

	private val sdkRoot: File? by lazy {
		sequenceOf(
			System.getenv("ANDROID_HOME"),
			System.getenv("ANDROID_SDK_ROOT"),
			System.getProperty("user.home") + "/Android/Sdk",
			System.getProperty("user.home") + "/Library/Android/sdk",
		).filterNotNull()
			.map(::File)
			.firstOrNull { it.isDirectory }
	}

	/**
	 * Orders an SDK directory name by its numeric components, so `35.0.0` beats `9.0.0` and
	 * `android-36` beats `android-9`. A lexical max gets both backwards, and picks a toolchain
	 * old enough that the failure reads as a daemon bug rather than a test-helper one.
	 */
	private fun versionKey(name: String): List<Int> = Regex("\\d+").findAll(name).map { it.value.toInt() }.toList()

	private val byVersion: Comparator<File> =
		Comparator { left, right ->
			val a = versionKey(left.name)
			val b = versionKey(right.name)
			var result = 0
			for (i in 0 until maxOf(a.size, b.size)) {
				result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
				if (result != 0) break
			}
			result
		}

	private fun newestBuildTools(): File? =
		sdkRoot
			?.resolve("build-tools")
			?.listFiles { file -> file.isDirectory }
			?.maxWithOrNull(byVersion)

	fun d8Jar(): File? = newestBuildTools()?.resolve("lib/d8.jar")?.takeIf { it.isFile }

	fun aapt2(): File? = newestBuildTools()?.resolve("aapt2")?.takeIf { it.canExecute() }

	fun androidJar(): File? =
		sdkRoot
			?.resolve("platforms")
			?.listFiles { file -> file.isDirectory && file.name.startsWith("android-") }
			?.maxWithOrNull(byVersion)
			?.resolve("android.jar")
			?.takeIf { it.isFile }

	@JvmStatic
	fun dexToolchainAvailable(): Boolean = requireOrSkip(d8Jar() != null && androidJar() != null, "d8/android.jar toolchain")

	@JvmStatic
	fun aapt2ToolchainAvailable(): Boolean = requireOrSkip(aapt2() != null && androidJar() != null, "aapt2/android.jar toolchain")

	/** The kotlin-stdlib jar the test JVM itself runs against; compile-test classpath. */
	fun kotlinStdlib(): File =
		System
			.getProperty("java.class.path")
			.split(File.pathSeparator)
			.map(::File)
			.first { it.name.startsWith("kotlin-stdlib") && it.extension == "jar" }

	/** The Compose compiler plugin jar; staged by the build (see build.gradle.kts). */
	fun composePluginJar(): File? = fileProperty("quickbuild.test.composePluginJar")

	/** Compose runtime classes.jar extracted from the AAR by the build. */
	fun composeRuntimeJar(): File? = fileProperty("quickbuild.test.composeRuntimeJar")

	@JvmStatic
	fun composeToolchainAvailable(): Boolean =
		requireOrSkip(composePluginJar() != null && composeRuntimeJar() != null, "staged Compose compiler/runtime")

	private fun fileProperty(name: String): File? = System.getProperty(name)?.let(::File)?.takeIf { it.isFile }
}
