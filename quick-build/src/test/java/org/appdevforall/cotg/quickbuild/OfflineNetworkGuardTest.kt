package org.appdevforall.cotg.quickbuild

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

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
