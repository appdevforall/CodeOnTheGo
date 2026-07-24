package org.appdevforall.cotg.quickbuild.daemon

import java.io.File

/**
 * Locates a host Android SDK for the d8/aapt2 tests. Those tests are assumption-guarded
 * (`@EnabledIf`) because CI/dev hosts without an SDK can't run them - the daemon itself
 * never uses this; on device the paths arrive in the configure request.
 */
object TestSdk {
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
	fun dexToolchainAvailable(): Boolean = d8Jar() != null && androidJar() != null

	@JvmStatic
	fun aapt2ToolchainAvailable(): Boolean = aapt2() != null && androidJar() != null

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
	fun composeToolchainAvailable(): Boolean = composePluginJar() != null && composeRuntimeJar() != null

	private fun fileProperty(name: String): File? = System.getProperty(name)?.let(::File)?.takeIf { it.isFile }
}
