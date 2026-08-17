package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the guard against a failed generation becoming the sticky boot generation.
 *
 * The payload is persisted before it is applied, so when the apply or the render throws, the store already claims the generation that just failed. A fresh process would adopt it, fail the same way during startup, and report nothing - no reload is pending in a new process, so the crash guard stays silent and the app crash-loops with CoGo none the wiser. A marker naming the failed generation is what breaks that loop, and it is a marker rather than a rollback of the store because it also survives a crash part-way through the rollback itself.
 */
class PayloadPersistenceQuarantineTest {

	private static final String FP = "baseline-fp";

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@TempDir
	File temp;

	@Test
	void anUnreadableMarkerIsIgnoredRatherThanBlockingEveryBoot() throws IOException {
		PayloadPersistence store = store();
		store.persist(4, FP, bytes("dex4"), null, null);
		Files.write(new File(store.dir(), PayloadPersistence.QUARANTINE_FILE).toPath(),
				bytes("not json"));

		// Failing closed here would strand the app on the baseline forever over a
		// corrupt side file; failing open only costs the guard for one generation.
		assertThat(store.load(FP).generation).isEqualTo(4);
	}

	@Test
	void aQuarantinedGenerationIsRefusedAndTheStoreDiscarded() throws IOException {
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), bytes("arsc7"), null);

		store.quarantine(7);

		// Nothing to adopt, so the process boots the gen-0 baseline - the code the
		// installed APK already carries - and reports generation 0, which is what makes
		// CoGo redeploy instead of leaving the app dead.
		assertThat(store.load(FP)).isNull();
		assertThat(store.dir().exists()).isFalse();
	}

	@Test
	void aSuccessfulPersistClearsTheMarker() throws IOException {
		// The generation counter restarts if the project's state dir is wiped, so a
		// marker naming 7 must not be able to refuse a later, different generation 7.
		// Publishing a complete set is the event that supersedes the claim.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.quarantine(7);

		store.persist(8, FP, bytes("dex8"), null, null);

		assertThat(new File(store.dir(), PayloadPersistence.QUARANTINE_FILE).exists()).isFalse();
		assertThat(store.load(FP).generation).isEqualTo(8);
	}

	@Test
	void quarantineNeverThrowsWhenTheStoreCannotBeWritten() throws IOException {
		// Called from the reload failure path and from the uncaught-exception guard,
		// neither of which can handle a throw.
		File blocked = new File(temp, "payload");
		Files.write(blocked.toPath(), bytes("not a dir"));
		PayloadPersistence store = new PayloadPersistence(blocked);

		assertDoesNotThrow(new org.junit.jupiter.api.function.Executable() {

			@Override
			public void execute() {
				store.quarantine(3);
			}
		});
	}

	@Test
	void quarantineOnlyBlocksTheGenerationItNames() throws IOException {
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.quarantine(6);

		// Generation 6 failed; 7 was never tried and must still boot.
		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(7);
	}

	@Test
	void quarantineSurvivesAStoreThatWasNeverWritten() {
		// persist() threw before publishing anything, so the marker names a generation
		// the store does not claim. It must be inert, not a blanket refusal.
		PayloadPersistence store = store();

		store.quarantine(9);

		assertThat(store.load(FP)).isNull();
		assertThat(new File(store.dir(), PayloadPersistence.QUARANTINE_FILE).isFile()).isTrue();
	}

	private PayloadPersistence store() {
		return new PayloadPersistence(new File(temp, "payload"));
	}
}
