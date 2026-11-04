package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

	private static InputStream stream(String text) {
		return new ByteArrayInputStream(bytes(text));
	}

	@TempDir
	File temp;

	@Test
	void aGenerationThatAlreadyRanIsNotQuarantined() throws IOException {
		// It reached the screen once, so a fresh process booting it does not repeat whatever
		// failed later - and quarantining it would throw away the fallback along with the
		// fault, which is how the good generations got swept up on device.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.markGood(7);

		store.quarantine(7);

		assertThat(new File(store.dir(), PayloadPersistence.QUARANTINE_FILE).exists()).isFalse();
		assertThat(store.load(FP).generation).isEqualTo(7);
	}

	@Test
	void aLastGoodSetForAnotherBaselineIsNotBooted() throws IOException {
		// A reinstall or rebaseline changes the fingerprint; the fallback must not outlive
		// the baseline its classes were compiled against any more than the published set does.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.markGood(7);
		store.persist(8, FP, bytes("dex8"), null, null);
		store.quarantine(8);

		assertThat(store.load("a-different-baseline")).isNull();
		assertThat(store.dir().exists()).isFalse();
	}

	@Test
	void aLastGoodSetNamingAMissingFileDiscardsTheStore() throws IOException {
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.markGood(7);
		store.persist(8, FP, bytes("dex8"), null, null);
		store.quarantine(8);
		assertThat(new File(store.dir(),
				PayloadPersistence.payloadFileName(PayloadPersistence.KIND_DEX, 7)).delete()).isTrue();

		// A meta claiming a generation it cannot serve is corruption, not a plain absence.
		assertThat(store.load(FP)).isNull();
		assertThat(store.dir().exists()).isFalse();
	}

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
	void aQuarantinedGenerationWithNothingGoodBehindItDiscardsTheStore() throws IOException {
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), stream("arsc7"), null);

		store.quarantine(7);

		// Nothing ever reached the screen, so there is nothing to fall back to: the process
		// boots the gen-0 baseline - the code the installed APK already carries - and reports
		// generation 0, which is what makes CoGo redeploy instead of leaving the app dead.
		assertThat(store.load(FP)).isNull();
		assertThat(store.dir().exists()).isFalse();
	}

	@Test
	void aQuarantineFallsBackToTheLastGenerationThatRan() throws IOException {
		// The measured cascade: generation 14 crashed, the app rebooted on install-time code
		// six saves behind, CoGo re-sent its retained payload onto that baseline, and the
		// second crash ended at the system's "app keeps stopping" dialog. Landing on 7
		// instead means the app comes back where the session already is, so nothing is
		// re-sent and nothing crashes twice.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), stream("arsc7"), null);
		store.markGood(7);
		store.persist(8, FP, bytes("dex8"), null, null);

		store.quarantine(8);

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(7);
		assertThat(loaded.dex).isEqualTo(bytes("dex7"));
		// Its resources come back with it, not generation 8's.
		assertThat(loaded.arscFile.getName())
				.isEqualTo(PayloadPersistence.payloadFileName(PayloadPersistence.KIND_ARSC, 7));
	}

	@Test
	void aQuarantineThatBeatMarkGoodLeavesTheEarlierFallbackIntact() throws IOException {
		// The race the guard exists for, in the order that used to lose. Booting from a
		// persisted generation leaves it unproven, so the first resume starts the good
		// write while the crash guard may already be quarantining that same generation.
		// With both markers naming 8, the next boot finds the published set quarantined
		// AND the fallback quarantined, reads that as corruption and clears the whole
		// store - the app drops to install-time code and every save since goes with it.
		// Landing on 7 is the entire point of the fallback file.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.markGood(7);
		store.persist(8, FP, bytes("dex8"), null, null);

		store.quarantine(8);
		assertThat(store.markGood(8)).isFalse();

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(7);
		assertThat(store.dir().exists()).isTrue();
	}

	@Test
	void aRestartedGenerationCounterDropsTheFallbackFromTheOldSequence() throws IOException {
		// The project's state dir was wiped while the app stayed installed, so numbering
		// restarts. Falling back to 13 from the old sequence would boot an older build under
		// a higher number - the one mismatch direction the store cannot make safe.
		PayloadPersistence earlier = store();
		earlier.persist(13, FP, bytes("dex13"), null, null);
		earlier.markGood(13);

		// A fresh store object over the same directory, because the wipe happens between
		// app processes: gen 3 always arrives at a store that has published nothing yet.
		PayloadPersistence store = new PayloadPersistence(earlier.dir());
		store.persist(3, FP, bytes("dex3"), null, null);

		assertThat(new File(store.dir(), PayloadPersistence.GOOD_FILE).exists()).isFalse();
		store.quarantine(3);
		assertThat(store.load(FP)).isNull();
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
	void markGoodCanSucceedSeparatesAFailedWriteFromAStoreThatMovedOn() throws IOException {
		// markGood answers three situations with one false, and the caller may retry only
		// the transient one. Reading a bare false as retryable starts a write thread on
		// every resume; reading it as permanent strands the generation unproven for the
		// process lifetime, which leaves the crash guard blaming it.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);

		assertThat(store.markGoodCanSucceed(7)).isTrue();

		store.persist(8, FP, bytes("dex8"), null, null);
		assertThat(store.markGoodCanSucceed(7)).isFalse();

		store.quarantine(8);
		assertThat(store.markGoodCanSucceed(8)).isFalse();
	}

	@Test
	void markGoodIgnoresAGenerationTheStoreNoLongerPublishes() throws IOException {
		// A late confirmation for a superseded generation must not record 7's files as the
		// fallback while the store publishes 8 - the two would disagree about what is live.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.persist(8, FP, bytes("dex8"), null, null);

		assertThat(store.markGood(7)).isFalse();

		assertThat(new File(store.dir(), PayloadPersistence.GOOD_FILE).exists()).isFalse();
	}

	@Test
	void markGoodNeverThrowsWhenTheStoreCannotBeWritten() throws IOException {
		File blocked = new File(temp, "payload");
		Files.write(blocked.toPath(), bytes("not a dir"));
		PayloadPersistence store = new PayloadPersistence(blocked);

		assertDoesNotThrow(new org.junit.jupiter.api.function.Executable() {

			@Override
			public void execute() {
				// Reported as a failure rather than swallowed: the caller keeps treating the
				// generation as unproven, since nothing was written for a quarantine to reach.
				assertThat(store.markGood(3)).isFalse();
			}
		});
	}

	@Test
	void markGoodReportsWhetherTheFallbackNowNamesTheGeneration() throws IOException {
		// The crash guard stops blaming a generation exactly when this says yes, so "recorded"
		// and "already recorded" have to answer the same way - a second confirmation writes
		// nothing and must still mean the fallback is in place.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);

		assertThat(store.markGood(7)).isTrue();
		assertThat(store.markGood(7)).isTrue();

		assertThat(new File(store.dir(), PayloadPersistence.GOOD_FILE).isFile()).isTrue();
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

	@Test
	void theFallbackIsRepublishedSoLaterBootsAgreeWithThisOne() throws IOException {
		// Loading 7 while the store still claims 8 would make every later boot walk the
		// fallback again, and the next deploy inherit files from the quarantined set.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.markGood(7);
		store.persist(8, FP, bytes("dex8"), null, null);
		store.quarantine(8);

		assertThat(store.load(FP).generation).isEqualTo(7);

		PayloadPersistence reopened = store();
		PayloadPersistence.Loaded loaded = reopened.load(FP);
		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(7);
		assertThat(loaded.dex).isEqualTo(bytes("dex7"));
	}

	@Test
	void theLastGoodSetSurvivesTheOrphanSweepOfLaterGenerations() throws IOException {
		// persist() collects every payload file the published meta does not name. The
		// fallback's files are named only by good.json, so without that being consulted the
		// fallback would resolve to a meta pointing at files that are gone.
		PayloadPersistence store = store();
		store.persist(7, FP, bytes("dex7"), null, null);
		store.markGood(7);
		store.persist(8, FP, bytes("dex8"), null, null);
		store.persist(9, FP, bytes("dex9"), null, null);

		assertThat(new File(store.dir(),
				PayloadPersistence.payloadFileName(PayloadPersistence.KIND_DEX, 7)).isFile()).isTrue();
		// Generation 8's is not the fallback and not published, so it still goes.
		assertThat(new File(store.dir(),
				PayloadPersistence.payloadFileName(PayloadPersistence.KIND_DEX, 8)).isFile()).isFalse();

		store.quarantine(9);
		assertThat(store.load(FP).generation).isEqualTo(7);
	}

	private PayloadPersistence store() {
		return new PayloadPersistence(new File(temp, "payload"));
	}
}
