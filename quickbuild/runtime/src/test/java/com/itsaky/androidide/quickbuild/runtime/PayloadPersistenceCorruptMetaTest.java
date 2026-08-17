package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers what the store does with a meta.json it did not write.
 *
 * Every case here is reachable on a real device: an interrupted write, a downgrade that wrote an older layout, or a store rolled forward by a build that is gone. The contract is that anything the store cannot fully understand counts as absent - the boot then serves the installed APK's baseline, which is always self-consistent - never as a partially readable set worth serving.
 */
class PayloadPersistenceCorruptMetaTest {

	private static final String FP = "baseline-fp";

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@TempDir
	File temp;

	@Test
	void aMetaWithANonStringKindNameDiscardsTheStore() throws IOException {
		// An array where a filename belongs is corruption, not "this kind was absent":
		// treating it as absent would serve the remaining kinds as if they were whole.
		File dir = seed("{\"layout\":\"" + PayloadPersistence.LAYOUT
				+ "\",\"generation\":\"1\",\"fingerprint\":\"" + FP + "\",\"dex\":[\"dex-1.bin\"]}");
		PayloadPersistence store = new PayloadPersistence(dir);

		assertThat(store.load(FP)).isNull();
		assertThat(dir.exists()).isFalse();
	}

	@Test
	void aQuarantineMarkerWithANonStringGenerationIsIgnored() throws IOException {
		// The marker refuses a boot, so an unreadable one must fail open. Failing closed
		// would strand the app on its baseline with no way back.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		Files.write(new File(store.dir(), PayloadPersistence.QUARANTINE_FILE).toPath(),
				bytes("{\"generation\":5}"));

		assertThat(store.load(FP).generation).isEqualTo(1);
	}

	@Test
	void filesThatAreNotGenerationStampedPayloadsAreLeftAlone() throws IOException {
		// Orphan collection may only claim names it can prove it owns. Anything else in
		// the directory belongs to some other part of the runtime.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		File noDash = new File(store.dir(), "payload.bin");
		File noNumber = new File(store.dir(), "dex-x.bin");
		Files.write(noDash.toPath(), bytes("not ours"));
		Files.write(noNumber.toPath(), bytes("not ours either"));

		store.persist(2, FP, bytes("dex2"), null, null);

		assertThat(noDash.isFile()).isTrue();
		assertThat(noNumber.isFile()).isTrue();
	}

	@Test
	void persistDoesNotCarryForwardANameWhoseFileIsGone() throws IOException {
		// Carrying the name forward regardless would publish a meta pointing at nothing,
		// which load() has to treat as corruption - turning a survivable delta deploy
		// into a discarded store.
		File dir = seed("{\"layout\":\"" + PayloadPersistence.LAYOUT
				+ "\",\"generation\":\"1\",\"fingerprint\":\"" + FP
				+ "\",\"arsc\":\"arsc-1.bin\"}");
		PayloadPersistence store = new PayloadPersistence(dir);

		store.persist(2, FP, bytes("dex2"), null, null);

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(2);
		assertThat(loaded.arscFile).isNull();
	}

	@Test
	void persistInheritsNothingFromAMetaWithANonStringGeneration() throws IOException {
		File dir = seed("{\"layout\":\"" + PayloadPersistence.LAYOUT
				+ "\",\"generation\":1,\"fingerprint\":\"" + FP + "\",\"arsc\":\"arsc-1.bin\"}");
		Files.write(new File(dir, "arsc-1.bin").toPath(), bytes("arsc1"));
		PayloadPersistence store = new PayloadPersistence(dir);

		store.persist(2, FP, bytes("dex2"), null, null);

		// Unreadable generation means the ordering check cannot run, and inheriting from
		// a set that might be NEWER is the one direction cumulative deltas do not survive.
		assertThat(store.load(FP).arscFile).isNull();
	}

	@Test
	void persistInheritsNothingFromAMetaWithANonStringKindName() throws IOException {
		File dir = seed("{\"layout\":\"" + PayloadPersistence.LAYOUT
				+ "\",\"generation\":\"1\",\"fingerprint\":\"" + FP + "\",\"arsc\":[\"arsc-1.bin\"]}");
		PayloadPersistence store = new PayloadPersistence(dir);

		store.persist(2, FP, bytes("dex2"), null, null);

		// Nothing to carry forward, so the new set is dex-only rather than a dex paired
		// with a resource file the meta could not name.
		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(2);
		assertThat(loaded.arscFile).isNull();
	}

	@Test
	void persistInheritsNothingFromAMetaWithNoFingerprint() throws IOException {
		File dir = seed("{\"layout\":\"" + PayloadPersistence.LAYOUT
				+ "\",\"generation\":\"1\",\"arsc\":\"arsc-1.bin\"}");
		Files.write(new File(dir, "arsc-1.bin").toPath(), bytes("arsc1"));
		PayloadPersistence store = new PayloadPersistence(dir);

		store.persist(2, FP, bytes("dex2"), null, null);

		// Without a fingerprint there is no evidence those resources were linked against
		// this baseline, and a table from another APK is what crashes startup.
		assertThat(store.load(FP).arscFile).isNull();
	}

	@Test
	void persistInheritsNothingFromAnOldLayoutStore() throws IOException {
		// The upgrade path: an already-installed proxy app with the flat layout on disk.
		File dir = seed("{\"generation\":\"1\",\"fingerprint\":\"" + FP + "\"}");
		Files.write(new File(dir, "resources.arsc").toPath(), bytes("old arsc"));
		PayloadPersistence store = new PayloadPersistence(dir);

		store.persist(2, FP, bytes("dex2"), null, null);

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(2);
		assertThat(loaded.arscFile).isNull();
	}

	@Test
	void persistInheritsNothingFromAnUnparseableMeta() throws IOException {
		File dir = seed("{not json at all");

		new PayloadPersistence(dir).persist(2, FP, bytes("dex2"), null, null);

		// Publishing over the garbage is the recovery: the next boot gets a whole,
		// readable generation instead of a store nothing can ever adopt.
		PayloadPersistence.Loaded loaded = new PayloadPersistence(dir).load(FP);
		assertThat(loaded.generation).isEqualTo(2);
		assertThat(loaded.arscFile).isNull();
	}

	@Test
	void persistInheritsNothingFromAStoreLeftByAnotherBaseline() throws IOException {
		// A Standard Run reinstall changes the baseline dex and so the fingerprint. Its
		// old resource table was linked against code that is no longer installed, and
		// pairing it with the new dex is precisely the startup crash the set-atomicity
		// work exists to prevent.
		PayloadPersistence store = store();
		store.persist(1, "an-older-baseline", bytes("dex1"), bytes("arsc1"), null);

		store.persist(2, FP, bytes("dex2"), null, null);

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(2);
		assertThat(loaded.arscFile).isNull();
	}

	private File seed(String metaJson) throws IOException {
		File dir = new File(temp, "payload");
		assertThat(dir.mkdirs()).isTrue();
		Files.write(new File(dir, PayloadPersistence.META_FILE).toPath(), bytes(metaJson));
		return dir;
	}

	private PayloadPersistence store() {
		return new PayloadPersistence(new File(temp, "payload"));
	}
}
