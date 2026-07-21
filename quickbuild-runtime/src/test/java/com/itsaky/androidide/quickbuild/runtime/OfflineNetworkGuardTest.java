package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline guard (ADFA-4128 offline-test-plan touchpoints 7-10) for the
 * `:quickbuild-runtime` AAR -- the code embedded in generated test apps that binds to
 * CoGo and hot-reloads payloads over binder IPC. Quick Build must be provably
 * network-free; this runtime opens no sockets. This test scans the module's compiled
 * production classes for any network-API reference in their constant pools and fails,
 * naming the offending class + constant, if one appears. It runs in the normal `test`
 * task, so a regression is caught in CI, not just by a device walk.
 *
 * The runtime is Java-only with no allowed network exceptions.
 * `java/net/URL`/`URI`/`URLClassLoader` are allow-listed for consistency but absent today.
 */
class OfflineNetworkGuardTest {

	/** Constant-pool / UTF8 substrings of network APIs that must never appear. */
	private static final List<String> BANNED = Arrays.asList(
			"java/net/Socket",
			"java/net/ServerSocket",
			"java/net/HttpURLConnection",
			"java/net/InetAddress",
			"javax/net/ssl",
			"okhttp3/",
			"java/nio/channels/SocketChannel",
			"android/net/ConnectivityManager");

	@Test
	void productionClassesReferenceNoNetworkApis() throws IOException {
		File buildDir = moduleBuildDir(getClass());
		List<File> classFiles = productionClassFiles(buildDir);

		// Anti-vacuous: a mis-location must fail loudly, never pass by scanning nothing.
		assertWithMessage("no production .class files found under " + buildDir + " -- guard self-location is broken")
				.that(classFiles)
				.isNotEmpty();

		List<String> violations = new ArrayList<>();
		for (File file : classFiles) {
			byte[] bytes = Files.readAllBytes(file.toPath());
			String rel = relativePosix(buildDir, file);
			for (String banned : BANNED) {
				if (containsAscii(bytes, banned)) {
					violations.add(rel + " references " + banned);
				}
			}
		}
		assertWithMessage(
				"Quick Build must be network-free offline, but production classes reference banned"
						+ " network APIs:\n  - "
						+ String.join("\n  - ", violations)
						+ "\n(scanned " + classFiles.size() + " classes under " + buildDir + ")")
				.that(violations)
				.isEmpty();
	}

	/**
	 * Proves the detector would genuinely fail if a banned reference appeared, and that
	 * the allow-listed local-URL APIs do NOT trip it -- so a green result above is a real
	 * signal, not a scanner that can never fire.
	 */
	@Test
	void detectorFiresOnBannedBytesAndNotOnAllowedBytes() {
		byte[] banned = "prefix Lokhttp3/OkHttpClient; and java/net/Socket suffix".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		List<String> bannedHits = new ArrayList<>();
		for (String s : BANNED) {
			if (containsAscii(banned, s)) {
				bannedHits.add(s);
			}
		}
		assertThat(bannedHits).containsExactly("okhttp3/", "java/net/Socket");

		byte[] allowed = "Ljava/net/URL; Ljava/net/URLClassLoader; Ljava/net/URI;".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		List<String> allowedHits = new ArrayList<>();
		for (String s : BANNED) {
			if (containsAscii(allowed, s)) {
				allowedHits.add(s);
			}
		}
		assertThat(allowedHits).isEmpty();
	}

	/**
	 * Walks up from the test's code-source location to the module `build` dir. An optional
	 * `quickbuild.offlineGuard.buildDir` system property overrides it for unusual layouts.
	 */
	private static File moduleBuildDir(Class<?> fromClass) {
		String override = System.getProperty("quickbuild.offlineGuard.buildDir");
		if (override != null) {
			return new File(override);
		}
		File location;
		try {
			location = new File(fromClass.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (java.net.URISyntaxException e) {
			throw new IllegalStateException("could not resolve test code-source location", e);
		}
		File dir = location;
		while (dir != null && !dir.getName().equals("build")) {
			dir = dir.getParentFile();
		}
		if (dir == null) {
			throw new IllegalStateException("could not locate module build dir from test location " + location);
		}
		return dir;
	}

	private static List<File> productionClassFiles(File buildDir) {
		List<File> out = new ArrayList<>();
		collectClassFiles(buildDir, buildDir, out);
		return out;
	}

	private static void collectClassFiles(File buildDir, File dir, List<File> out) {
		File[] children = dir.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (child.isDirectory()) {
				collectClassFiles(buildDir, child, out);
			} else if (child.getName().endsWith(".class")
					&& isProductionClassPath(splitRelative(buildDir, child))) {
				out.add(child);
			}
		}
	}

	private static boolean isProductionClassPath(List<String> segments) {
		if (segments.contains(".cache")) {
			return false; // jacoco/expanded-zip agent classes
		}
		if (segments.size() >= 3 && segments.get(0).equals("classes") && segments.get(2).equals("main")) {
			// JVM module: build/classes/{kotlin,java}/main/...
			return true;
		}
		if (segments.size() >= 3 && segments.get(0).equals("tmp") && segments.get(1).equals("kotlin-classes")) {
			// Android Kotlin: build/tmp/kotlin-classes/<variant>/...
			return !isTestVariant(segments.get(2));
		}
		if (segments.size() >= 3 && segments.get(0).equals("intermediates") && segments.get(1).equals("javac")) {
			// Android Java: build/intermediates/javac/<variant>/.../classes/...
			return !isTestVariant(segments.get(2));
		}
		return false;
	}

	private static boolean isTestVariant(String variant) {
		return variant.equals("test") || variant.endsWith("UnitTest") || variant.endsWith("AndroidTest");
	}

	private static List<String> splitRelative(File buildDir, File file) {
		return Arrays.asList(relativePosix(buildDir, file).split("/"));
	}

	private static String relativePosix(File buildDir, File file) {
		String rel = buildDir.toPath().relativize(file.toPath()).toString();
		return rel.replace(File.separatorChar, '/');
	}

	private static boolean containsAscii(byte[] haystack, String needle) {
		byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		if (pattern.length == 0) {
			return true;
		}
		if (haystack.length < pattern.length) {
			return false;
		}
		outer:
		for (int i = 0; i <= haystack.length - pattern.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (haystack[i + j] != pattern[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}
}
