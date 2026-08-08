package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the atomic-write and best-effort-clear edges of the payload store.
 *
 * Persist must fail loudly when the store cannot be written, since a swallowed write would let a later boot silently serve older code. The writeAtomic rename fallback must recover when only the first rename fails, and clear() must stay best-effort over entries it cannot delete.
 */
class PayloadPersistenceAtomicWriteTest {

	private static final String FP = "fp";

	@TempDir
	Path tempDir;

	@Test
	void anUndeletableRenameTargetFailsThePersistLoudly() throws IOException {
		File dir = tempDir.resolve("store").toFile();
		File inTheWay = new File(dir, PayloadPersistence.DEX_FILE);
		// A NON-empty directory: rename over it fails, delete fails, retry impossible.
		assertThat(new File(inTheWay, "child").mkdirs()).isTrue();
		PayloadPersistence store = new PayloadPersistence(dir);

		IOException error = assertThrows(IOException.class,
				() -> store.persist(1, FP, new byte[]{1}, null, null));

		assertThat(error).hasMessageThat().contains("cannot rename");
	}

	@Test
	void aStorePathBlockedByAFileFailsThePersistLoudly() throws IOException {
		File blocked = tempDir.resolve("store").toFile();
		Files.write(blocked.toPath(), "not a dir".getBytes("UTF-8"));
		PayloadPersistence store = new PayloadPersistence(blocked);

		IOException error = assertThrows(IOException.class,
				() -> store.persist(1, FP, new byte[]{1}, null, null));

		assertThat(error).hasMessageThat().contains("cannot create");
	}

	@Test
	void clearIsBestEffortWhenAnEntryCannotBeDeleted() throws IOException {
		File dir = tempDir.resolve("store").toFile();
		File stubborn = new File(dir, "stubborn");
		assertThat(new File(stubborn, "child").mkdirs()).isTrue();
		PayloadPersistence store = new PayloadPersistence(dir);

		assertDoesNotThrow(store::clear);

		// Undeletable entries survive; clear reported and moved on instead of throwing.
		assertThat(stubborn.isDirectory()).isTrue();
		assertThat(dir.isDirectory()).isTrue();
	}

	@Test
	void fingerprintsAreLowercaseHexOfTheExpectedLength() {
		// Pins the on-disk key format: 64 hex chars for SHA-256, stable across runs.
		String fingerprint = PayloadPersistence.fingerprint(new byte[]{0, 1, 2});

		assertThat(fingerprint).hasLength(64);
		assertThat(fingerprint).matches("[0-9a-f]{64}");
	}

	@Test
	void metaWithAFingerprintButNoGenerationDeletesTheStore() throws IOException {
		File dir = tempDir.resolve("store").toFile();
		assertThat(dir.mkdirs()).isTrue();
		File meta = new File(dir, PayloadPersistence.META_FILE);
		Files.write(meta.toPath(),
				("{\"fingerprint\":\"" + FP + "\"}").getBytes("UTF-8"));
		PayloadPersistence store = new PayloadPersistence(dir);

		assertThat(store.load(FP)).isNull();
		assertThat(meta.exists()).isFalse();
	}

	@Test
	void renameFallbackReplacesAnEmptyDirectoryInTheWay() throws IOException {
		File dir = tempDir.resolve("store").toFile();
		File inTheWay = new File(dir, PayloadPersistence.DEX_FILE);
		assertThat(inTheWay.mkdirs()).isTrue();
		PayloadPersistence store = new PayloadPersistence(dir);

		store.persist(3, FP, new byte[]{9, 9}, null, null);

		assertThat(inTheWay.isFile()).isTrue();
		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(3);
		assertThat(loaded.dex).isEqualTo(new byte[]{9, 9});
	}
}
