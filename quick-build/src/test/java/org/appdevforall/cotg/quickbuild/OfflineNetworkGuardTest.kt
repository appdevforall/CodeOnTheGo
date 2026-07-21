package org.appdevforall.cotg.quickbuild

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Offline guard (ADFA-4128 offline-test-plan touchpoints 7-10) for the `:quick-build`
 * module -- the IDE-side session/orchestration/deploy code. Quick Build must be provably
 * network-free; the deploy leg is binder IPC with no sockets. This test scans this
 * module's compiled production classes for any network-API reference in their constant
 * pools and fails, naming the offending class + constant, if one appears. It runs in the
 * normal `test` task, so a regression is caught in CI, not just by a device walk.
 *
 * This module has no allowed network exceptions (unlike :quickbuild-daemon, which loads
 * a local `d8.jar` via a `file:` URLClassLoader). `java/net/URL`/`URI`/`URLClassLoader`
 * are allow-listed here for consistency but are absent today.
 */
class OfflineNetworkGuardTest {

	@Test
	fun productionClassesReferenceNoNetworkApis() {
		val buildDir = OfflineGuard.moduleBuildDir(javaClass)
		val classFiles = OfflineGuard.productionClassFiles(buildDir)

		// Anti-vacuous: a mis-location must fail loudly, never pass by scanning nothing.
		assertWithMessage("no production .class files found under $buildDir -- guard self-location is broken")
			.that(classFiles)
			.isNotEmpty()

		val violations = OfflineGuard.scanForBannedReferences(buildDir, classFiles)
		assertWithMessage(
			"Quick Build must be network-free offline, but production classes reference banned network APIs:\n" +
				violations.joinToString("\n") { "  - $it" } +
				"\n(scanned ${classFiles.size} classes under $buildDir)",
		)
			.that(violations)
			.isEmpty()
	}

	/**
	 * Proves the detector would genuinely fail if a banned reference appeared, and that
	 * the allow-listed local-URL APIs do NOT trip it -- so a green result above is a real
	 * signal, not a scanner that can never fire.
	 */
	@Test
	fun detectorFiresOnBannedBytesAndNotOnAllowedBytes() {
		val banned = "prefix Lokhttp3/OkHttpClient; and java/net/Socket suffix"
			.toByteArray(Charsets.US_ASCII)
		assertThat(OfflineGuard.BANNED.filter { OfflineGuard.containsAscii(banned, it) })
			.containsExactly("okhttp3/", "java/net/Socket")

		val allowed = "Ljava/net/URL; Ljava/net/URLClassLoader; Ljava/net/URI;"
			.toByteArray(Charsets.US_ASCII)
		assertThat(OfflineGuard.BANNED.filter { OfflineGuard.containsAscii(allowed, it) })
			.isEmpty()
	}
}

/**
 * Self-contained scanner shared by this module's guard tests. Locates the module build
 * dir from the running test's own code source (no hardcoded absolute paths), enumerates
 * production class dirs (non-test variants), and searches raw `.class` bytes for banned
 * network-API constants.
 */
internal object OfflineGuard {

	/** Constant-pool / UTF8 substrings of network APIs that must never appear. */
	val BANNED: List<String> = listOf(
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
		val location = File(fromClass.protectionDomain.codeSource.location.toURI())
		var dir: File? = location
		while (dir != null && dir.name != "build") dir = dir.parentFile
		requireNotNull(dir) { "could not locate module build dir from test location $location" }
		return dir
	}

	fun productionClassFiles(buildDir: File): List<File> =
		buildDir.walkTopDown()
			.filter { it.isFile && it.extension == "class" }
			.filter { isProductionClassPath(it.relativeTo(buildDir).invariantSeparatorsPath.split("/")) }
			.toList()

	fun scanForBannedReferences(buildDir: File, classFiles: List<File>): List<String> {
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

	fun containsAscii(haystack: ByteArray, needle: String): Boolean {
		val pattern = needle.toByteArray(Charsets.US_ASCII)
		if (pattern.isEmpty() || haystack.size < pattern.size) return pattern.isEmpty()
		outer@ for (i in 0..haystack.size - pattern.size) {
			for (j in pattern.indices) if (haystack[i + j] != pattern[j]) continue@outer
			return true
		}
		return false
	}
}
