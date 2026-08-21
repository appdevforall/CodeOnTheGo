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
