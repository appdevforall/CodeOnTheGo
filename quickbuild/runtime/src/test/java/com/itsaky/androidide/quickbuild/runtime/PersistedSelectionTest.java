package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The S7 fix's decision, against a real on-disk store: which persisted payload, if any, a boot at a given STAMPED baseline generation adopts. Reverting the gate to a constant 0 makes the first test go green on the previous epoch's payload, which is exactly the on-device S7 hole.
 */
class PersistedSelectionTest {

	private static final byte[] BASELINE_DEX = "baseline-dex".getBytes(StandardCharsets.UTF_8);

	@TempDir
	File dir;

	/**
	 * A dex-only generation has no boot restore to run. Stashing it as pending used to start a restore thread that swapped nothing and then recreated the first activity for it, on every cold start of a project that never deployed resources.
	 */
	@Test
	void aDexOnlyGenerationHasNoResourcesToRestore() {
		PayloadPersistence.Loaded loaded = new PayloadPersistence.Loaded(3,
				"dex".getBytes(StandardCharsets.UTF_8), null, null);

		assertThat(loaded.hasResources()).isFalse();
	}

	@Test
	void aGenerationWithATableOrAssetsHasResourcesToRestore() {
		byte[] dex = "dex".getBytes(StandardCharsets.UTF_8);

		assertThat(new PayloadPersistence.Loaded(3, dex, new File(dir, "res.zip"), null)
				.hasResources()).isTrue();
		assertThat(new PayloadPersistence.Loaded(3, dex, null, new File(dir, "assets.zip"))
				.hasResources()).isTrue();
		assertThat(new PayloadPersistence.Loaded(3, null, new File(dir, "res.zip"), null)
				.hasResources()).isTrue();
	}

	@Test
	void anEmptyStoreBootsTheBakedBaseline() {
		assertThat(PersistedSelection.selectPersisted(8, store(), fingerprint())).isNull();
	}

	@Test
	void anUnstampedBaselineKeepsItsOldBehaviorAndAnyPersistedDeployWins() throws Exception {
		PayloadPersistence store = store();
		persist(store, 1);

		PayloadPersistence.Loaded loaded = PersistedSelection.selectPersisted(BaselineGeneration.UNSTAMPED, store, fingerprint());

		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(1);
	}

	@Test
	void aPayloadDeployedOnTopOfTheStampedBaselineIsAdoptedAtBoot() throws Exception {
		PayloadPersistence store = store();
		persist(store, 9);

		PayloadPersistence.Loaded loaded = PersistedSelection.selectPersisted(8, store, fingerprint());

		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(9);
	}

	@Test
	void aPersistedPayloadEqualToTheStampIsARejectedReplay() throws Exception {
		PayloadPersistence store = store();
		persist(store, 8);

		assertThat(PersistedSelection.selectPersisted(8, store, fingerprint())).isNull();
	}

	@Test
	void aRejectedPersistedPayloadIsClearedFromDisk() throws Exception {
		// Skipping the superseded epoch's files is not enough: the store is keyed on the
		// baseline dex alone, so the next persist would read them as its own history and
		// inherit the old epoch's meta.
		PayloadPersistence store = store();
		persist(store, 7);

		PersistedSelection.selectPersisted(8, store, fingerprint());

		assertThat(store.load(fingerprint())).isNull();
		assertThat(store.dir().exists()).isFalse();
	}

	@Test
	void aStampedRebaselineRejectsThePreviousEpochsPersistedPayload() throws Exception {
		// A manifest-only rebaseline leaves the baseline dex byte-identical, so the
		// fingerprint matches; only the stamp says gen 7 is from the superseded epoch.
		PayloadPersistence store = store();
		persist(store, 7);

		assertThat(PersistedSelection.selectPersisted(8, store, fingerprint())).isNull();
	}

	@Test
	void aStoreKeyedToAnotherBaselineIsNotAdopted() throws Exception {
		PayloadPersistence store = store();
		persist(store, 9);

		String otherFingerprint = PayloadPersistence.fingerprint("other-dex".getBytes(StandardCharsets.UTF_8));

		assertThat(PersistedSelection.selectPersisted(8, store, otherFingerprint)).isNull();
	}

	private String fingerprint() {
		return PayloadPersistence.fingerprint(BASELINE_DEX);
	}

	private void persist(PayloadPersistence store, long generation) throws Exception {
		store.persist(generation, fingerprint(), "dex".getBytes(StandardCharsets.UTF_8), null, null);
	}

	private PayloadPersistence store() {
		return new PayloadPersistence(new File(dir, "payload"));
	}
}
