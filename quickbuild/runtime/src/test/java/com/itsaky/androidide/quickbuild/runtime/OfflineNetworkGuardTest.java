package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.File;
import java.util.List;
import org.appdevforall.cotg.quickbuild.testfixtures.OfflineGuard;
import org.junit.jupiter.api.Test;

/**
 * Offline guard (ADFA-4128 offline-test-plan touchpoints 7-10) for the `:quickbuild:runtime` AAR -- the code embedded in generated proxy apps that binds to CoGo and hot-reloads payloads over binder IPC. Quick Build must be provably network-free; this runtime opens no sockets. This test scans the module's compiled production classes for any network-API reference in their constant pools and fails, naming the offending class + constant, if one appears. It runs in the normal `test` task, so a regression is caught in CI, not just by a device walk.
 *
 * The runtime is Java-only with no allowed network exceptions. `java/net/URL`/`URI`/`URLClassLoader` are absent from this module's bytes today; none of them is in {@link OfflineGuard#BANNED} either, so adding one for a local `file:` URI would not trip this test.
 */
class OfflineNetworkGuardTest {

	private static List<String> bannedHits(byte[] bytes) {
		List<String> hits = new java.util.ArrayList<>();
		for (String banned : OfflineGuard.INSTANCE.getBANNED()) {
			if (OfflineGuard.INSTANCE.containsAscii(bytes, banned)) {
				hits.add(banned);
			}
		}
		return hits;
	}

	/**
	 * Proves the detector would genuinely fail if a banned reference appeared, and that the allow-listed local-URL APIs do NOT trip it -- so a green result above is a real signal, not a scanner that can never fire.
	 */
	@Test
	void detectorFiresOnBannedBytesAndNotOnAllowedBytes() {
		byte[] banned = "prefix Lokhttp3/OkHttpClient; and java/net/Socket suffix".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		assertThat(bannedHits(banned)).containsExactly("okhttp3/", "java/net/Socket");

		byte[] allowed = "Ljava/net/URL; Ljava/net/URLClassLoader; Ljava/net/URI;".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		assertThat(bannedHits(allowed)).isEmpty();
	}

	@Test
	void productionClassesReferenceNoNetworkApis() {
		File buildDir = OfflineGuard.INSTANCE.moduleBuildDir(getClass());
		List<File> classFiles = OfflineGuard.INSTANCE.productionClassFiles(buildDir);

		// Anti-vacuous: a mis-location must fail loudly, never pass by scanning nothing.
		assertWithMessage("no production .class files found under " + buildDir + " -- guard self-location is broken")
				.that(classFiles)
				.isNotEmpty();

		List<String> violations = OfflineGuard.INSTANCE.scanForBannedReferences(buildDir, classFiles);
		assertWithMessage(
				"Quick Build must be network-free offline, but production classes reference banned"
						+ " network APIs:\n  - "
						+ String.join("\n  - ", violations)
						+ "\n(scanned " + classFiles.size() + " classes under " + buildDir + ")")
				.that(violations)
				.isEmpty();
	}
}
