package org.appdevforall.cotg.quickbuild

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.appdevforall.cotg.quickbuild.testfixtures.OfflineGuard
import org.junit.jupiter.api.Test

/**
 * Offline guard (ADFA-4128 offline-test-plan touchpoints 7-10) for the IDE-side
 * session/orchestration/deploy code: scans this module's compiled production classes for
 * network-API references in their constant pools, naming any offender.
 *
 * No allowed exceptions here, unlike :quickbuild:daemon. `java/net/URL`/`URI`/
 * `URLClassLoader` are not in [OfflineGuard.BANNED], so a local `file:` URI would pass.
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
}
