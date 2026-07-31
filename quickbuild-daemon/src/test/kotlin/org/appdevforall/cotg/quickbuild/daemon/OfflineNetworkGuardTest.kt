package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * Offline guard (ADFA-4128 offline-test-plan touchpoints 7-10). Quick Build must be
 * provably network-free: the hot loop (compile -> dex -> relink -> deploy) makes zero
 * network calls. This test scans THIS module's compiled production classes for any
 * reference to a network API in their constant pools and fails, naming the offending
 * class + constant, if one appears. It runs in the normal `test` task, so a future
 * change that adds e.g. an OkHttp call is caught in CI, not just by a device walk.
 *
 * The one documented, allowed exception is `java.net.URLClassLoader` -- the daemon loads
 * the bundled local `d8.jar` via a `file:` URI `URLClassLoader` (see [dex.DexTool]); that
 * is a local class loader, not a network client. `java.net.URL`/`URI` are allowed for
 * the same reason (they carry `file:` URIs here). None of those is a substring of any
 * banned entry, so the ban check never trips on them; the allow-list is documentation.
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
