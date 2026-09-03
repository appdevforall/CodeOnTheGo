package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

	/** A payload stream that yields a few bytes and then fails, like a read off a dying fd. */
	private static InputStream failingStream() {
		return new InputStream() {

			private int served;

			@Override
			public int read() throws IOException {
				if (served++ < 8) {
					return 0x41;
				}
				throw new IOException("payload stream died mid-copy");
			}
		};
	}

	@TempDir
	Path tempDir;

	@Test
	void anUndeletableRenameTargetFailsThePersistLoudly() throws IOException {
		File dir = tempDir.resolve("store").toFile();
		File inTheWay = new File(dir, PayloadPersistence.payloadFileName(PayloadPersistence.KIND_DEX, 1));
		// A NON-empty directory: rename over it fails, delete fails, retry impossible.
		assertThat(new File(inTheWay, "child").mkdirs()).isTrue();
		PayloadPersistence store = new PayloadPersistence(dir);

		IOException error = assertThrows(IOException.class,
				() -> store.persist(1, FP, new byte[]{1}, null, null));

		assertThat(error).hasMessageThat().contains("cannot rename");
	}

	/**
	 * A stream payload that dies mid-copy leaves no {@code .tmp} behind in the store dir.
	 *
	 * Goes red without the fix: writeAtomic deleted its temp only from the rename fallback, so a throw out of the copy - an oversize payload, a full disk - left the partial temp in the store directory, which nothing sweeps.
	 */
	@Test
	void aStreamThatFailsMidCopyLeavesNoTempFile() throws IOException {
		File dir = tempDir.resolve("store").toFile();
		PayloadPersistence store = new PayloadPersistence(dir);

		assertThrows(IOException.class,
				() -> store.persist(1, FP, new byte[]{1}, failingStream(), null));

		String[] leftovers = dir.list((unusedDir, name) -> name.endsWith(".tmp"));
		assertThat(leftovers).isNotNull();
		assertThat(leftovers).isEmpty();
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
		File child = new File(stubborn, "child");
		assertThat(child.mkdirs()).isTrue();
		// A read-only parent is the portable way to make a child undeletable.
		assertThat(stubborn.setWritable(false)).isTrue();
		PayloadPersistence store = new PayloadPersistence(dir);
		try {
			assertDoesNotThrow(store::clear);

			// Undeletable entries survive; clear reported and moved on instead of throwing.
			assertThat(child.exists()).isTrue();
			assertThat(dir.isDirectory()).isTrue();
		} finally {
			// Or the temp-dir teardown inherits the problem.
			stubborn.setWritable(true);
		}
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
				("{\"layout\":\"" + PayloadPersistence.LAYOUT + "\",\"fingerprint\":\"" + FP + "\"}")
						.getBytes("UTF-8"));
		PayloadPersistence store = new PayloadPersistence(dir);

		assertThat(store.load(FP)).isNull();
		assertThat(meta.exists()).isFalse();
	}

	@Test
	void renameFallbackReplacesAnEmptyDirectoryInTheWay() throws IOException {
		File dir = tempDir.resolve("store").toFile();
		File inTheWay = new File(dir, PayloadPersistence.payloadFileName(PayloadPersistence.KIND_DEX, 3));
		assertThat(inTheWay.mkdirs()).isTrue();
		PayloadPersistence store = new PayloadPersistence(dir);

		store.persist(3, FP, new byte[]{9, 9}, null, null);

		assertThat(inTheWay.isFile()).isTrue();
		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(3);
		assertThat(loaded.dex).isEqualTo(new byte[]{9, 9});
	}
}
