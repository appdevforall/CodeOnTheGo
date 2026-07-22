package org.appdevforall.cotg.quickbuild.daemon

import java.io.File

/**
 * Self-contained scanner shared by this module's guard tests. Locates the module build
 * dir from the running test's own code source (no hardcoded absolute paths), enumerates
 * production class dirs (main / non-test variants), and searches raw `.class` bytes for
 * banned network-API constants.
 *
 * The max-line-length suppression below is for a phantom lint: no line here
 * exceeds 140, but ktlint under spotless reports L1 max-line-length on this file
 * regardless (formatter interaction bug) -- suppressed narrowly, not repo-wide.
 */
@Suppress("ktlint:standard:max-line-length")
internal object OfflineGuard {
	/** Constant-pool / UTF8 substrings of network APIs that must never appear. */
	val BANNED: List<String> =
		listOf(
			"java/net/Socket",
			"java/net/ServerSocket",
			"java/net/HttpURLConnection",
			"java/net/InetAddress",
			"javax/net/ssl",
			"okhttp3/",
			"java/nio/channels/SocketChannel",
			"android/net/ConnectivityManager",
		)

	/**
	 * Walks up from the test's code-source location to the module `build` dir. An optional
	 * `quickbuild.offlineGuard.buildDir` system property overrides it for unusual layouts.
	 */
	fun moduleBuildDir(fromClass: Class<*>): File {
		System.getProperty("quickbuild.offlineGuard.buildDir")?.let { return File(it) }
		val location =
			File(
				fromClass.protectionDomain.codeSource.location
					.toURI(),
			)
		var dir: File? = location
		while (dir != null && dir.name != "build") dir = dir.parentFile
		requireNotNull(dir) { "could not locate module build dir from test location $location" }
		return dir
	}

	fun productionClassFiles(buildDir: File): List<File> =
		buildDir
			.walkTopDown()
			.filter { it.isFile && it.extension == "class" }
			.filter { isProductionClassPath(it.relativeTo(buildDir).invariantSeparatorsPath.split("/")) }
			.toList()

	fun scanForBannedReferences(
		buildDir: File,
		classFiles: List<File>,
	): List<String> {
		val violations = mutableListOf<String>()
		for (file in classFiles) {
			val bytes = file.readBytes()
			val rel = file.relativeTo(buildDir).invariantSeparatorsPath
			for (banned in BANNED) {
				if (containsAscii(bytes, banned)) violations += "$rel references $banned"
			}
		}
		return violations
	}

	private fun isProductionClassPath(segments: List<String>): Boolean {
		if (segments.contains(".cache")) return false // jacoco/expanded-zip agent classes
		return when {
			// JVM module: build/classes/{kotlin,java}/main/...
			segments.size >= 3 && segments[0] == "classes" && segments[2] == "main" -> true
			// Android Kotlin: build/tmp/kotlin-classes/<variant>/...
			segments.size >= 3 && segments[0] == "tmp" && segments[1] == "kotlin-classes" ->
				!isTestVariant(segments[2])
			// Android Java: build/intermediates/javac/<variant>/.../classes/...
			segments.size >= 3 && segments[0] == "intermediates" && segments[1] == "javac" ->
				!isTestVariant(segments[2])
			else -> false
		}
	}

	private fun isTestVariant(variant: String): Boolean =
		variant == "test" || variant.endsWith("UnitTest") || variant.endsWith("AndroidTest")

	fun containsAscii(
		haystack: ByteArray,
		needle: String,
	): Boolean {
		val pattern = needle.toByteArray(Charsets.US_ASCII)
		if (pattern.isEmpty() || haystack.size < pattern.size) return pattern.isEmpty()
		outer@ for (i in 0..haystack.size - pattern.size) {
			for (j in pattern.indices) if (haystack[i + j] != pattern[j]) continue@outer
			return true
		}
		return false
	}
}
