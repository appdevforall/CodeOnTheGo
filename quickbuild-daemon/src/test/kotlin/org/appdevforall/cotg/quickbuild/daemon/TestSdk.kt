package org.appdevforall.cotg.quickbuild.daemon

import java.io.File

/**
 * Locates a host Android SDK for the d8/aapt2 tests. Those tests are assumption-guarded
 * (`@EnabledIf`) because CI/dev hosts without an SDK can't run them - the daemon itself
 * never uses this; on device the paths arrive in the configure request.
 *
 * Fail-if-skipped switch: setting `REQUIRE_BUILD_TOOLCHAIN=1` (env) or
 * `-PrequireBuildToolchain` (both wired to the `quickbuild.test.requireToolchain` system
 * property by build.gradle.kts) turns an absent toolchain from a silent skip into a hard
 * failure - the `@EnabledIf` predicate throws, which JUnit reports as a test error. CI
 * sets it so the aapt2/d8/Compose regression tests (ADFA-4128 bugs 5/6/8) can never be
 * skipped without going red.
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

	private fun newestBuildTools(): File? =
		sdkRoot
			?.resolve("build-tools")
			?.listFiles { file -> file.isDirectory }
			?.maxByOrNull { it.name }

	fun d8Jar(): File? = newestBuildTools()?.resolve("lib/d8.jar")?.takeIf { it.isFile }

	fun aapt2(): File? = newestBuildTools()?.resolve("aapt2")?.takeIf { it.canExecute() }

	fun androidJar(): File? =
		sdkRoot
			?.resolve("platforms")
			?.listFiles { file -> file.isDirectory && file.name.startsWith("android-") }
			?.maxByOrNull { it.name }
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
