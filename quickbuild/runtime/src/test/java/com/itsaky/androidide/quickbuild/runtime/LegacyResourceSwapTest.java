package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the JVM-testable half of the API 28/29 shim: writing the relinked resource apk to disk.
 *
 * The payload is already the full relinked apk, so persisting it is a plain byte copy and must not be re-wrapped into a synthetic zip. The reflective addAssetPath and the resource cache flush are device-only and are not exercised here.
 */
class LegacyResourceSwapTest {

	@TempDir
	File tempDir;

	@Test
	void createsMissingDirectories() throws IOException {
		File nested = new File(new File(tempDir, "a"), "b");
		byte[] apk = "apk-bytes".getBytes("UTF-8");

		File zip = LegacyResourceSwap.writeResourceApk(new ByteArrayInputStream(apk), nested, 0);

		assertThat(zip.isFile()).isTrue();
		assertThat(Files.readAllBytes(zip.toPath())).isEqualTo(apk);
	}

	@Test
	void distinctFilePerGeneration() throws IOException {
		byte[] first = "gen one apk".getBytes("UTF-8");
		byte[] second = "gen two apk - different".getBytes("UTF-8");

		File zipOne = LegacyResourceSwap.writeResourceApk(new ByteArrayInputStream(first), tempDir, 1);
		File zipTwo = LegacyResourceSwap.writeResourceApk(new ByteArrayInputStream(second), tempDir, 2);

		assertThat(zipOne.getAbsolutePath()).isNotEqualTo(zipTwo.getAbsolutePath());
		assertThat(Files.readAllBytes(zipOne.toPath())).isEqualTo(first);
		assertThat(Files.readAllBytes(zipTwo.toPath())).isEqualTo(second);
	}

	@Test
	void uncreatableDirectoryThrowsInsteadOfSilentlyDropping() throws IOException {
		// A dir path shadowed by an existing FILE cannot be created; the shim must throw
		// (deploy rolls back) rather than lose the resource payload (never-stale).
		File shadow = new File(tempDir, "shadow");
		assertThat(shadow.createNewFile()).isTrue();

		assertThrows(IOException.class, () -> LegacyResourceSwap
				.writeResourceApk(new ByteArrayInputStream(new byte[]{1}), shadow, 1));
	}

	@Test
	void writesTheApkBytesUnmodified() throws IOException {
		// A wrapping path would re-encode the input into a
		// synthetic zip entry, so a naive "it produced *a* zip" assertion would not have
		// caught the content being wrong. This asserts byte-for-byte identity with what
		// aapt2 link actually produced.
		byte[] apk = new byte[64 * 1024];
		new Random(7).nextBytes(apk);

		File zip = LegacyResourceSwap.writeResourceApk(new ByteArrayInputStream(apk), tempDir, 3);

		assertThat(zip.getName()).isEqualTo("gen-3.zip");
		assertThat(Files.readAllBytes(zip.toPath())).isEqualTo(apk);
	}
}
