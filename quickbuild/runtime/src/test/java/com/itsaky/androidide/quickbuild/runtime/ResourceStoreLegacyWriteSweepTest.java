package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins when the API 28/29 apk cache is swept: once per process, immediately before this process writes its first relinked apk, on the writer's own thread.
 *
 * The sweep used to run on the main thread inside the first activity's creation - a readdir plus one unlink per apk the previous process wrote, on every cold start, on the low-end devices the legacy path serves. It has to stay ahead of the first mount, because a mounted path deleted underneath the AssetManager cannot be recovered; running it from the first write gives that ordering without a second thread or a latch.
 */
class ResourceStoreLegacyWriteSweepTest {

	private static ByteArrayInputStream apk() {
		return new ByteArrayInputStream("apk".getBytes(StandardCharsets.UTF_8));
	}

	@TempDir
	File cacheDir;

	@Test
	void anEmptyCacheStillTakesTheWrite() throws IOException {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.LEGACY_ASSET_PATH);

		File written = store.writeLegacyApk(apk(), new File(cacheDir, "fresh"), 2);

		assertThat(written.isFile()).isTrue();
	}

	/** The regression the ordering guards against: an apk this process wrote is never swept, so its mount stays valid. */
	@Test
	void laterWritesDoNotSweepThisProcessesOwnApks() throws IOException {
		stale("gen-1.zip");
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.LEGACY_ASSET_PATH);

		store.writeLegacyApk(apk(), cacheDir, 5);
		store.writeLegacyApk(apk(), cacheDir, 6);

		assertThat(names()).containsExactly("gen-5.zip", "gen-6.zip");
	}

	@Test
	void theFirstWriteSweepsThePreviousProcessesApks() throws IOException {
		stale("gen-1.zip");
		stale("gen-2.zip");
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.LEGACY_ASSET_PATH);

		File written = store.writeLegacyApk(apk(), cacheDir, 5);

		assertThat(written.isFile()).isTrue();
		assertThat(names()).containsExactly("gen-5.zip");
	}

	private List<String> names() {
		List<String> names = new ArrayList<>();
		for (File entry : cacheDir.listFiles()) {
			names.add(entry.getName());
		}
		return names;
	}

	private void stale(String name) throws IOException {
		Files.write(new File(cacheDir, name).toPath(), "old".getBytes(StandardCharsets.UTF_8));
	}
}
