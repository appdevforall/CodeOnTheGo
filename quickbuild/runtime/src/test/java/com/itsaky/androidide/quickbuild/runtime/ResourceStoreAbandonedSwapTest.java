package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the rule that a swap belonging to an abandoned generation is refused rather than committed.
 *
 * The failure this covers: a resource swap is queued on the main thread and commits after the deploy method that queued it has returned. A deploy that then fails a later step - applyTable posts before applyAssets can throw - has its rollback run while its own table swap is still queued, so the abandoned generation's table used to commit over the dex the rollback had just restored. Undoing a committed swap is not available here: the store keeps single provider slots and closes the previous provider after each swap, and the API 28/29 path cannot unmount an added asset path at all. Refusing the commit is the remedy.
 *
 * The three swap bodies that consult {@link ResourceStore#refusesSwap} run on the main looper, so their wiring is not exercised here; what is pinned is the decision they all take.
 */
class ResourceStoreAbandonedSwapTest {

	/**
	 * The regression: an older generation still in flight survives a newer one's failure.
	 *
	 * A cold start restores persisted gen 10 on its own thread while CoGo's catch-up gen 11 arrives and fails. Abandonment used to be a high-water mark, so gen 11's failure refused gen 10's queued swap; a refused swap reports committed, and the restore logged success over the baseline table.
	 */
	@Test
	void abandoningANewerGenerationDoesNotRefuseAnOlderOnesQueuedSwap() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(11);

		assertThat(store.refusesSwap(10)).isFalse();
		assertThat(store.refusesSwap(11)).isTrue();
	}

	/** Abandoning a second generation must not release the first one's refusal. */
	@Test
	void abandoningAnOlderGenerationDoesNotUndoANewerAbandon() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(9);
		store.abandon(4);

		assertThat(store.refusesSwap(9)).isTrue();
		assertThat(store.refusesSwap(10)).isFalse();
	}

	/** A committed swap prunes the abandoned set below it, and the overtaken rule takes over the refusal. */
	@Test
	void aCommittedSwapForgetsAbandonedGenerationsItOvertook() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(5);
		store.abandon(9);
		store.recordSwapped(7);

		assertThat(store.refusesSwap(5)).isTrue();
		assertThat(store.refusesSwap(9)).isTrue();
		assertThat(store.refusesSwap(8)).isFalse();
	}

	/** Abandoning an older generation does not retroactively refuse a newer one's swap. */
	@Test
	void aSwapForAGenerationNewerThanTheAbandonedOneStillCommits() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(7);

		assertThat(store.refusesSwap(8)).isFalse();
	}

	/** The regression: after the deploy is abandoned, its own queued swap is refused. */
	@Test
	void aSwapForAnAbandonedGenerationIsRefused() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(7);

		assertThat(store.refusesSwap(7)).isTrue();
	}

	/** With nothing abandoned the guard refuses nothing, so the normal deploy path is untouched. */
	@Test
	void withNothingAbandonedEverySwapStillCommits() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		assertThat(store.refusesSwap(0)).isFalse();
		assertThat(store.refusesSwap(1)).isFalse();
		assertThat(store.refusesSwap(99)).isFalse();
	}
}
