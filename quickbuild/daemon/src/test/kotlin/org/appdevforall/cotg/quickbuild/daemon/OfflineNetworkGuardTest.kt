package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.appdevforall.cotg.quickbuild.testfixtures.OfflineGuard
import org.junit.jupiter.api.Test

/**
 * Offline guard (ADFA-4128 offline-test-plan touchpoints 7-10): the hot loop must make zero network
 * calls, so this scans the module's compiled production classes for constant-pool references to a
 * network API and fails naming the offending class and constant. Running in the normal `test` task
 * catches e.g. a new OkHttp call in CI, not on a device walk. `java.net.URL`/`URI`/`URLClassLoader`
 * are allowed: the daemon loads the bundled local `d8.jar` from a `file:` URI (see [dex.DexTool]).
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
		).that(violations)
			.isEmpty()
	}

	/**
	 * Proves the detector would genuinely fail if a banned reference appeared, and that
	 * the allow-listed local-URL APIs do NOT trip it -- so a green result above is a real
	 * signal, not a scanner that can never fire.
	 */
	@Test
	fun detectorFiresOnBannedBytesAndNotOnAllowedBytes() {
		val banned =
			"prefix Lokhttp3/OkHttpClient; and java/net/Socket suffix"
				.toByteArray(Charsets.US_ASCII)
		assertThat(OfflineGuard.BANNED.filter { OfflineGuard.containsAscii(banned, it) })
			.containsExactly("okhttp3/", "java/net/Socket")

		val allowed =
			"Ljava/net/URL; Ljava/net/URLClassLoader; Ljava/net/URI;"
				.toByteArray(Charsets.US_ASCII)
		assertThat(OfflineGuard.BANNED.filter { OfflineGuard.containsAscii(allowed, it) })
			.isEmpty()
	}

	/**
	 * The daemon really does load d8 via a `file:` `URLClassLoader`, so the allow-listed
	 * constant is present in production bytes. Asserting it doubles as proof the scanner
	 * reads real class bytes (not an empty set) for this module.
	 */
	@Test
	fun documentedLocalUrlClassLoaderExceptionIsPresentInProductionBytes() {
		val buildDir = OfflineGuard.moduleBuildDir(javaClass)
		val hasUrlClassLoader =
			OfflineGuard.productionClassFiles(buildDir).any { f ->
				OfflineGuard.containsAscii(f.readBytes(), "java/net/URLClassLoader")
			}
		assertThat(hasUrlClassLoader).isTrue()
	}
}
