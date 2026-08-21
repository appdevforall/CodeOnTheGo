package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the property that makes the store safe to boot from: one generation's dex, resources and assets are published together or not at all.
 *
 * The failure this guards is not a lost deploy but a mixed one - generation N's dex paired with generation N-1's resources - which installs code against a table that never matched it. The app then throws Resources$NotFoundException during startup, before the runtime can bind, so it cannot be reported and CoGo cannot correct it. Every test here forces an IO failure part-way through a persist and asserts the store still serves exactly one whole generation.
 */
class PayloadPersistenceAtomicSetTest {

	private static final String FP = "baseline-fp";

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@TempDir
	File temp;

	@Test
	void aLaterPersistCollectsWhatATornWriteLeftBehind() throws IOException {
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		blockWrite(store, PayloadPersistence.KIND_ARSC, 2);
		assertThrows(IOException.class, () -> store.persist(2, FP, bytes("dex2"), bytes("arsc2"), null));

		store.persist(3, FP, bytes("dex3"), null, null);

		// gen 2's orphan dex is unreferenced and older than the published generation.
		assertThat(payload(store, PayloadPersistence.KIND_DEX, 2).exists()).isFalse();
		assertThat(store.load(FP).dex).isEqualTo(bytes("dex3"));
	}

	@Test
	void aMetaNamingAMissingFileIsCorruptionNotAnAbsentKind() throws IOException {
		// Serving the subset that happens to be present is exactly the mixed store this
		// layout exists to prevent, so a dangling reference must discard the store.
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), bytes("arsc1"), null);
		assertThat(payload(store, PayloadPersistence.KIND_ARSC, 1).delete()).isTrue();

		assertThat(store.load(FP)).isNull();
		assertThat(store.dir().exists()).isFalse();
	}

	@Test
	void anOldFlatLayoutStoreIsDiscardedRatherThanAdopted() throws IOException {
		// What an already-installed proxy app has on disk: fixed filenames and a
		// meta.json with no layout tag. Its cross-kind consistency was never
		// guaranteed, so it cannot be trusted; absent is safe, since the app then runs
		// the code its installed APK carries.
		File dir = new File(temp, "payload");
		assertThat(dir.mkdirs()).isTrue();
		Files.write(new File(dir, "payload.dex").toPath(), bytes("old dex"));
		Files.write(new File(dir, "resources.arsc").toPath(), bytes("old arsc"));
		Files.write(new File(dir, PayloadPersistence.META_FILE).toPath(),
				bytes("{\"generation\":\"7\",\"fingerprint\":\"" + FP + "\"}"));
		PayloadPersistence store = new PayloadPersistence(dir);

		assertThat(store.load(FP)).isNull();
		assertThat(dir.exists()).isFalse();
	}

	@Test
	void aStoreClaimingANewerGenerationIsNotInheritedFrom() throws IOException {
		// The host's counter restarts if the project's state dir is wiped while the app
		// stays installed, so a low generation can arrive at a store claiming a high one.
		// Carrying gen 40's resources forward would pair this dex with a LATER build's
		// table - the one direction cumulative deltas do not make safe.
		PayloadPersistence store = store();
		store.persist(40, FP, bytes("dex40"), bytes("arsc40"), null);

		store.persist(1, FP, bytes("dex1"), null, null);

		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded.generation).isEqualTo(1);
		assertThat(loaded.dex).isEqualTo(bytes("dex1"));
		assertThat(loaded.arscFile).isNull();
		assertThat(payload(store, PayloadPersistence.KIND_ARSC, 40).exists()).isFalse();
	}

	@Test
	void aTornPersistNeverPairsOneGenerationsDexWithAnothersResources() throws IOException {
		PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), bytes("arsc1"), null);
		blockWrite(store, PayloadPersistence.KIND_ARSC, 2);

		assertThrows(IOException.class, () -> store.persist(2, FP, bytes("dex2"), bytes("arsc2"), null));

		// The whole of generation 1, or nothing. Not gen 2's dex against gen 1's table,
		// and not a discarded store either - gen 1 is still complete and bootable.
		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isEqualTo(1);
		assertThat(loaded.dex).isEqualTo(bytes("dex1"));
		assertThat(Files.readAllBytes(loaded.arscFile.toPath())).isEqualTo(bytes("arsc1"));
	}

	@Test
	void aTornPersistOfTheFirstEverGenerationLeavesNoStoreAtAll() throws IOException {
		PayloadPersistence store = store();
		blockWrite(store, PayloadPersistence.KIND_ASSETS, 1);

		assertThrows(IOException.class,
				() -> store.persist(1, FP, bytes("dex1"), bytes("arsc1"), bytes("assets1")));

		// No meta was ever published, so there is nothing to adopt - the boot falls back
		// to the baseline rather than to a dex with no matching resources.
		assertThat(store.load(FP)).isNull();
	}

	@Test
	void concurrentDeploysAlwaysLeaveOneWholeLoadableGeneration() throws Exception {
		// onPayload arrives on a oneway binder callback, whose thread pool can dispatch
		// two calls at once. Interleaved inheritance reads and orphan collection would
		// publish a meta naming a file the other thread had just collected.
		final PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), bytes("arsc1"), bytes("assets1"));
		final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		Thread dexDeploys = new Thread(persister(store, failure, 2, 40, true));
		Thread resourceDeploys = new Thread(persister(store, failure, 3, 41, false));

		dexDeploys.start();
		resourceDeploys.start();
		dexDeploys.join(TimeUnit.SECONDS.toMillis(30));
		resourceDeploys.join(TimeUnit.SECONDS.toMillis(30));

		assertThat(failure.get()).isNull();
		// load() discards the store and answers null the moment the published meta names
		// a file that is not there - which is exactly what an inheritance read
		// interleaved with the other thread's orphan collection produces. So a non-null
		// load here IS the consistency assertion.
		PayloadPersistence.Loaded loaded = store.load(FP);
		assertThat(loaded).isNotNull();
		assertThat(loaded.generation).isAtLeast(2L);
	}

	@Test
	void persistSerialisesOnTheStoreMonitor() throws Exception {
		// The behavioural test above can only catch an interleaving it happens to hit;
		// this one is deterministic. Holding the store's monitor must be enough to stop
		// a persist, which is only true while persist takes that same monitor.
		final PayloadPersistence store = store();
		store.persist(1, FP, bytes("dex1"), null, null);
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch finished = new CountDownLatch(1);
		final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		Thread other = new Thread(new Runnable() {

			@Override
			public void run() {
				started.countDown();
				try {
					store.persist(2, FP, bytes("dex2"), null, null);
				} catch (Throwable error) {
					failure.set(error);
				}
				finished.countDown();
			}
		});

		synchronized (store) {
			other.start();
			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isFalse();
		}

		assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).isNull();
		assertThat(store.load(FP).generation).isEqualTo(2);
	}

	/**
	 * Makes the write of one kind of one generation fail the way a full disk does.
	 *
	 * A non-empty directory at the target path cannot be renamed over, deleted, or retried, so writeAtomic exhausts its fallback and throws.
	 *
	 * @param store
	 *            the store whose write to block
	 * @param kind
	 *            the payload kind to block
	 * @param generation
	 *            the generation whose write to block
	 */
	private void blockWrite(PayloadPersistence store, String kind, long generation)
			throws IOException {
		File target = payload(store, kind, generation);
		assertThat(new File(target, "child").mkdirs()).isTrue();
	}

	private File payload(PayloadPersistence store, String kind, long generation) {
		return new File(store.dir(), PayloadPersistence.payloadFileName(kind, generation));
	}

	/**
	 * A thread body that hammers the store with one kind of delta deploy. The two threads take opposite {@code dex} values, so inheritance is exercised in both directions.
	 *
	 * @param store
	 *            the store under test
	 * @param failure
	 *            where an unexpected throwable is recorded for the main thread to assert on
	 * @param from
	 *            first generation this thread publishes
	 * @param to
	 *            last generation this thread publishes, exclusive
	 * @param dex
	 *            true to deploy dex only, false to deploy resources and assets only
	 * @return the runnable to hand to a Thread
	 */
	private Runnable persister(final PayloadPersistence store,
			final AtomicReference<Throwable> failure, final int from, final int to,
			final boolean dex) {
		return new Runnable() {

			@Override
			public void run() {
				try {
					for (int generation = from; generation < to; generation += 2) {
						if (dex) {
							store.persist(generation, FP, bytes("dex" + generation), null, null);
						} else {
							store.persist(generation, FP, null, bytes("arsc" + generation),
									bytes("assets" + generation));
						}
					}
				} catch (Throwable error) {
					failure.set(error);
				}
			}
		};
	}

	private PayloadPersistence store() {
		return new PayloadPersistence(new File(temp, "payload"));
	}
}
