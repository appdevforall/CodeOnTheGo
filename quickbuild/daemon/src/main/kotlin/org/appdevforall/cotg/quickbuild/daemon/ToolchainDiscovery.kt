package org.appdevforall.cotg.quickbuild.daemon

import java.io.File

/**
 * Finds aapt2, d8.jar and android.jar under `$ANDROID_HOME` when a `configure` request omits
 * them, so a caller need not know CoGo's toolchain layout. Highest build-tools and platform
 * version wins. [env] is injectable so discovery unit-tests without the real process
 * environment.
 *
 * @property env reads one environment variable by name, returning null when it is unset;
 *   defaults to the real process environment.
 */
class ToolchainDiscovery(
	private val env: (String) -> String? = System::getenv,
) {
	/** One resolved toolchain path, or the reason it could not be found. */
	sealed interface Resolution {
		/**
		 * The tool was located on disk.
		 *
		 * @property path absolute path to the resolved tool; the caller may use it as-is.
		 */
		data class Found(
			val path: String,
		) : Resolution

		/**
		 * The tool could not be located, and why.
		 *
		 * @property message caller-facing reason, naming the missing field and where discovery
		 *   looked; `configure` reports it verbatim as an error diagnostic.
		 */
		data class Missing(
			val message: String,
		) : Resolution
	}

	/**
	 * Locates the aapt2 binary in the newest build-tools version that has one.
	 *
	 * @return [Resolution.Found] with the binary's absolute path, or [Resolution.Missing] when
	 *   `ANDROID_HOME` is unset or no build-tools version ships aapt2.
	 */
	fun resolveAapt2(): Resolution = resolveFromBuildTools("aapt2", "build-tools/<version>/aapt2") { File(it, "aapt2") }

	/**
	 * Locates d8.jar in the newest build-tools version that has one.
	 *
	 * @return [Resolution.Found] with the jar's absolute path, or [Resolution.Missing] when
	 *   `ANDROID_HOME` is unset or no build-tools version ships `lib/d8.jar`.
	 */
	fun resolveD8Jar(): Resolution = resolveFromBuildTools("d8Jar", "build-tools/<version>/lib/d8.jar") { File(it, "lib/d8.jar") }

	/**
	 * Locates android.jar for the highest installed platform API level.
	 *
	 * @return [Resolution.Found] with the jar's absolute path, or [Resolution.Missing] when
	 *   `ANDROID_HOME` is unset or no installed platform carries an android.jar.
	 */
	fun resolveAndroidJar(): Resolution {
		val androidHome = androidHomeValue() ?: return Resolution.Missing(unsetAndroidHome("androidJar"))
		val platformsDir = File(androidHome, "platforms")
		val platformDir =
			platformsDir
				.listFiles { file -> file.isDirectory }
				?.filter { it.name.startsWith("android-") && File(it, "android.jar").isFile }
				?.maxWithOrNull(compareBy { platformApiLevel(it.name) })
		return platformDir?.let { Resolution.Found(File(it, "android.jar").absolutePath) }
			?: Resolution.Missing(notFound("androidJar", androidHome, "platforms/android-<level>/android.jar"))
	}

	/**
	 * Picks the highest-versioned build-tools dir in which [toolFile] exists.
	 *
	 * @param field the `configure` request field this resolves, quoted back in the failure text.
	 * @param relativeHint the `$ANDROID_HOME`-relative layout searched, for the failure text.
	 * @param toolFile maps a build-tools version dir to the tool's expected location inside it.
	 * @return [Resolution.Found] with the tool's absolute path, or [Resolution.Missing].
	 */
	private fun resolveFromBuildTools(
		field: String,
		relativeHint: String,
		toolFile: (buildToolsVersionDir: File) -> File,
	): Resolution {
		val androidHome = androidHomeValue() ?: return Resolution.Missing(unsetAndroidHome(field))
		val buildToolsRoot = File(androidHome, "build-tools")
		val versionDir =
			buildToolsRoot
				.listFiles { file -> file.isDirectory && toolFile(file).isFile }
				?.maxWithOrNull(compareBy(VERSION_COMPARATOR) { it.name })
		return versionDir?.let { Resolution.Found(toolFile(it).absolutePath) }
			?: Resolution.Missing(notFound(field, androidHome, relativeHint))
	}

	private fun androidHomeValue(): String? = env("ANDROID_HOME")?.takeIf { it.isNotBlank() }

	private fun unsetAndroidHome(field: String) = "$field not supplied and ANDROID_HOME is unset - cannot discover the toolchain"

	private fun notFound(
		field: String,
		androidHome: String,
		relativeHint: String,
	) = "$field not supplied and not found under $androidHome/$relativeHint"

	companion object {
		private fun platformApiLevel(dirName: String): Int = dirName.removePrefix("android-").toIntOrNull() ?: -1

		/** Numeric, dot-component comparison so "35.0.0" sorts above "9.0.0". */
		private val VERSION_COMPARATOR =
			Comparator<String> { a, b ->
				val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
				val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
				for (i in 0 until maxOf(partsA.size, partsB.size)) {
					val diff = partsA.getOrElse(i) { 0 } - partsB.getOrElse(i) { 0 }
					if (diff != 0) return@Comparator diff
				}
				0
			}
	}
}
