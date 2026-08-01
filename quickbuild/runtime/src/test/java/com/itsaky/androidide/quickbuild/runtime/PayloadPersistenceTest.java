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

	@TempDir
	File temp;

	@Test
	void clearOnEmptyDirIsHarmless() {
		store().clear();
	}

	@Test
	void corruptMetaDeletesTheStore() throws IOException {
		PayloadPersistence store = store();
		store.persist(5, FP, bytes("dex5"), null, null);
		try (FileOutputStream out = new FileOutputStream(new File(store.dir(), PayloadPersistence.META_FILE))) {
			out.write(bytes("not json"));
		}

		assertThat(store.load(FP)).isNull();
		assertThat(new File(store.dir(), PayloadPersistence.DEX_FILE).exists()).isFalse();
	}

	@Test
	void crashBeforeMetaWriteClaimsTheOlderGeneration() throws IOException {
		// The crash window the meta-last ordering allows: newer payload files, older
		// meta. The store must claim the OLDER generation (host catch-up redeploys),
		// never the newer one.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		// Simulate a crash mid-persist of gen 2: dex written, meta not.
		try (FileOutputStream out = new FileOutputStream(new File(store.dir(), PayloadPersistence.DEX_FILE))) {
			out.write(bytes("dex2"));
		}

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(1);
		assertThat(loaded.dex).isEqualTo(bytes("dex2"));
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
		assertThat(new File(store.dir(), PayloadPersistence.DEX_FILE).exists()).isFalse();
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
			out.write(bytes("{\"generation\":\"5\"}"));
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

	private PayloadPersistence store() {
		return new PayloadPersistence(new File(temp, "payload"));
	}
}
