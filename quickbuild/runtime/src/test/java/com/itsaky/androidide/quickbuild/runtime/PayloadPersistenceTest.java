package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PayloadPersistenceTest {

	private static final String FP = PayloadPersistence.fingerprint(bytes("baseline"));

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static File payload(PayloadPersistence store, String kind, long generation) {
		return new File(store.dir(), PayloadPersistence.payloadFileName(kind, generation));
	}

	@TempDir
	File temp;

	@Test
	void anOrphanPayloadFileNoMetaNamesIsIgnored() throws IOException {
		// What a crash mid-persist leaves behind: a newer generation's payload file with
		// no meta referencing it. The store must keep serving the older complete
		// generation (host catch-up redeploys), and must not pick the orphan up.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		try (FileOutputStream out = new FileOutputStream(payload(store, PayloadPersistence.KIND_DEX, 2))) {
			out.write(bytes("dex2"));
		}

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(1);
		assertThat(loaded.dex).isEqualTo(bytes("dex1"));
	}

	@Test
	void clearOnEmptyDirIsHarmless() throws IOException {
		PayloadPersistence store = store();

		store.clear();

		// Teardown runs clear() whether or not anything was ever persisted, so the
		// no-payload case must delete nothing, create nothing, and leave a store that
		// still works.
		assertThat(store.dir().exists()).isFalse();
		assertThat(store.load(FP)).isNull();
		store.persist(1, FP, bytes("dex1"), null, null);
		assertThat(store.load(FP).generation).isEqualTo(1);
	}

	@Test
	void clearRemovesNestedEntriesToo() throws IOException {
		// An untrusted store can hold a directory where a payload file belonged (the
		// rename-fallback path proves the filesystem allows it). A non-recursive clear
		// would leave it there and the next load would keep tripping over it.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		assertThat(new File(new File(store.dir(), "nested"), "deep").mkdirs()).isTrue();

		store.clear();

		assertThat(store.dir().exists()).isFalse();
	}

	@Test
	void corruptMetaDeletesTheStore() throws IOException {
		PayloadPersistence store = store();
		store.persist(5, FP, bytes("dex5"), null, null);
		try (FileOutputStream out = new FileOutputStream(new File(store.dir(), PayloadPersistence.META_FILE))) {
			out.write(bytes("not json"));
		}

		assertThat(store.load(FP)).isNull();
		assertThat(payload(store, PayloadPersistence.KIND_DEX, 5).exists()).isFalse();
	}

	@Test
	void emptyStoreLoadsNull() {
		assertThat(store().load(FP)).isNull();
	}

	@Test
	void fingerprintIsStableAndContentSensitive() {
		assertThat(PayloadPersistence.fingerprint(bytes("a")))
				.isEqualTo(PayloadPersistence.fingerprint(bytes("a")));
		assertThat(PayloadPersistence.fingerprint(bytes("a")))
				.isNotEqualTo(PayloadPersistence.fingerprint(bytes("b")));
	}

	@Test
	void fingerprintMismatchDeletesTheStore() throws IOException {
		// A rebaseline/reinstall changed the baseline: the persisted payload was
		// compiled against the OLD baseline and must never boot on the new one.
		PayloadPersistence store = store();
		store.persist(5, FP, bytes("dex5"), null, null);

		assertThat(store.load(PayloadPersistence.fingerprint(bytes("new-baseline")))).isNull();
		assertThat(new File(store.dir(), PayloadPersistence.META_FILE).exists()).isFalse();
		assertThat(payload(store, PayloadPersistence.KIND_DEX, 5).exists()).isFalse();
		// And the original fingerprint finds nothing either - the store is gone.
		assertThat(store.load(FP)).isNull();
	}

	@Test
	void keepsNewestFilePerKindAcrossDeltaDeploys() throws IOException {
		// Deploys carry only what changed; the store must stay cumulative so a boot
		// gets the newest dex AND the newest resources even when they shipped apart.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		store.persist(2, FP, null, bytes("arsc2"), null);
		store.persist(3, FP, bytes("dex3"), null, bytes("assets3"));

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(3);
		assertThat(loaded.dex).isEqualTo(bytes("dex3"));
		assertThat(Files.readAllBytes(loaded.arscFile.toPath())).isEqualTo(bytes("arsc2"));
		assertThat(Files.readAllBytes(loaded.assetsFile.toPath())).isEqualTo(bytes("assets3"));
	}

	@Test
	void metaWithoutFingerprintDeletesTheStore() throws IOException {
		PayloadPersistence store = store();
		store.persist(5, FP, bytes("dex5"), null, null);
		try (FileOutputStream out = new FileOutputStream(new File(store.dir(), PayloadPersistence.META_FILE))) {
			out.write(bytes("{\"layout\":\"" + PayloadPersistence.LAYOUT + "\",\"generation\":\"5\"}"));
		}

		assertThat(store.load(FP)).isNull();
	}

	@Test
	void persistReturnsTheCumulativeResourceFiles() throws IOException {
		PayloadPersistence store = store();
		store.persist(1, FP, null, bytes("arsc1"), null);
		PayloadPersistence.Persisted persisted = store.persist(2, FP, bytes("dex2"), null, null);

		// The dex-only deploy still sees the previously persisted arsc.
		assertThat(persisted.arscFile).isNotNull();
		assertThat(Files.readAllBytes(persisted.arscFile.toPath())).isEqualTo(bytes("arsc1"));
		assertThat(persisted.assetsFile).isNull();
	}

	@Test
	void resourceOnlyHistoryLoadsWithNullDex() throws IOException {
		PayloadPersistence store = store();
		store.persist(1, FP, null, bytes("arsc1"), null);

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(1);
		assertThat(loaded.dex).isNull();
		assertThat(loaded.arscFile).isNotNull();
	}

	@Test
	void roundTripsAFullPayload() throws IOException {
		PayloadPersistence store = store();
		store.persist(3, FP, bytes("dex3"), bytes("arsc3"), bytes("assets3"));

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(3);
		assertThat(loaded.dex).isEqualTo(bytes("dex3"));
		assertThat(Files.readAllBytes(loaded.arscFile.toPath())).isEqualTo(bytes("arsc3"));
		assertThat(Files.readAllBytes(loaded.assetsFile.toPath())).isEqualTo(bytes("assets3"));
	}

	@Test
	void supersededPayloadFilesAreCollected() throws IOException {
		// Generation-stamped names would otherwise accumulate one dex per deploy in an
		// app-private dir on a device with 1.8 GB of storage.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		store.persist(2, FP, bytes("dex2"), null, null);

		assertThat(payload(store, PayloadPersistence.KIND_DEX, 1).exists()).isFalse();
		assertThat(payload(store, PayloadPersistence.KIND_DEX, 2).isFile()).isTrue();
		assertThat(store.load(FP).dex).isEqualTo(bytes("dex2"));
	}

	private PayloadPersistence store() {
		return new PayloadPersistence(new File(temp, "payload"));
	}
}
