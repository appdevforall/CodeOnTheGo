package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the startup sweep of the API 28/29 relinked-apk cache.
 *
 * A mounted asset path can never be unmounted, so a relinked apk has to stay on disk for the life of the process that mounted it - which is why nothing deletes one during a session. Nothing survives that process's death either, so at the next startup every apk in the directory is garbage; without the sweep the cache grows by one relinked apk per deploy, forever, on the low-storage devices this whole path exists to serve.
 */
class LegacyResourceSwapSweepTest {

	@TempDir
	File tempDir;

	@Test
	void deletesEveryGenerationApk() throws IOException {
		write("gen-1.zip");
		write("gen-2.zip");
		write("gen-17.zip");

		assertThat(LegacyResourceSwap.deleteStaleApks(tempDir)).isEqualTo(3);

		assertThat(tempDir.listFiles()).isEmpty();
	}

	@Test
	void ignoresDirectoriesThatLookLikeApks() throws IOException {
		File lookalike = new File(tempDir, "gen-3.zip");
		assertThat(lookalike.mkdirs()).isTrue();

		assertThat(LegacyResourceSwap.deleteStaleApks(tempDir)).isEqualTo(0);

		assertThat(lookalike.isDirectory()).isTrue();
	}

	@Test
	void isBestEffortOverAnApkItCannotDelete() throws IOException {
		// Cache space is the only thing at stake, so an undeletable file must not stop the
		// sweep or the swap that follows it.
		write("gen-1.zip");
		write("probe.txt");
		File probe = new File(tempDir, "probe.txt");
		assertThat(tempDir.setWritable(false)).isTrue();
		try {
			// A root worker unlinks regardless of the directory mode, which would sweep
			// gen-1.zip away and fail this test for a reason it is not about. The effective
			// uid is the wrong question and user.name is not even tied to it, so ask the
			// filesystem for the capability at stake: a probe that deletes means this
			// worker cannot be denied one, and there is no undeletable apk to test with.
			assumeFalse(probe.delete(), "this worker deletes despite the directory mode");

			assertThat(LegacyResourceSwap.deleteStaleApks(tempDir)).isEqualTo(0);

			assertThat(new File(tempDir, "gen-1.zip").isFile()).isTrue();
		} finally {
			// Or the temp-dir teardown inherits the problem.
			tempDir.setWritable(true);
			probe.delete();
		}
	}

	@Test
	void leavesEveryOtherFileAlone() throws IOException {
		// The sweep runs over a shared cache subdirectory, so an over-broad delete would
		// take out whatever else ends up beside the apks.
		write("gen-1.zip");
		write("something-else.zip");
		write("gen-1.zip.partial");
		write("notes.txt");

		assertThat(LegacyResourceSwap.deleteStaleApks(tempDir)).isEqualTo(1);

		assertThat(new File(tempDir, "something-else.zip").isFile()).isTrue();
		assertThat(new File(tempDir, "gen-1.zip.partial").isFile()).isTrue();
		assertThat(new File(tempDir, "notes.txt").isFile()).isTrue();
	}

	@Test
	void onAMissingDirectoryItIsANoOp() {
		// API 30+ never creates the directory, and neither does a first run.
		assertThat(LegacyResourceSwap.deleteStaleApks(new File(tempDir, "never-created")))
				.isEqualTo(0);
	}

	@Test
	void sweepsExactlyWhatWriteResourceApkProduces() throws IOException {
		// Pins the two halves together: a rename of the written file that the sweep's
		// prefix/suffix did not follow would leak every apk silently.
		File written = LegacyResourceSwap.writeResourceApk(
				new java.io.ByteArrayInputStream("apk".getBytes(StandardCharsets.UTF_8)), tempDir, 9);

		assertThat(LegacyResourceSwap.deleteStaleApks(tempDir)).isEqualTo(1);

		assertThat(written.exists()).isFalse();
	}

	@Test
	void theCacheDirNameMatchesTheOneResourceStoreWritesTo() throws Exception {
		// The sweep is driven from the runtime, which cannot see ResourceStore's private
		// constant; a drift between the two would silently sweep nothing.
		Field field = ResourceStore.class.getDeclaredField("LEGACY_TABLE_DIR");
		field.setAccessible(true);

		assertThat(LegacyResourceSwap.TABLE_DIR).isEqualTo(field.get(null));
	}

	private void write(String name) throws IOException {
		Files.write(new File(tempDir, name).toPath(), "x".getBytes(StandardCharsets.UTF_8));
	}
}
