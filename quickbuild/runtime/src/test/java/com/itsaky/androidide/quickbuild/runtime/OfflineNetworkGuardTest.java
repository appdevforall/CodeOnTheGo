package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.File;
import java.util.List;
import org.appdevforall.cotg.quickbuild.testfixtures.OfflineGuard;
import org.junit.jupiter.api.Test;

/**
 * Fails if any production class in the `:quickbuild:runtime` AAR references a network API.
 *
 * Covers ADFA-4128 offline-test-plan touchpoints 7-10. The AAR is what generated proxy apps
 * embed to bind to CoGo and hot-reload payloads over binder IPC, so it must be provably
 * network-free. The scan reads compiled constant pools and names the offending class plus
 * constant; it runs in the normal `test` task, so a regression is caught in CI, not on a device.
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
	 * Proves the detector fires on banned bytes and stays quiet on local-URL APIs.
	 *
	 * Without it, a green scan could be a scanner that can never fire. `java/net/URL`, `URI` and
	 * `URLClassLoader` are absent from this module today and absent from
	 * {@link OfflineGuard#BANNED}, so adding one for a local `file:` URI would not trip the test.
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
