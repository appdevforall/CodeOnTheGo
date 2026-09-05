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

	/** An out-of-order abandon must not lower the mark and let a refused swap through. */
	@Test
	void abandoningAnOlderGenerationDoesNotUndoANewerAbandon() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(9);
		store.abandon(4);

		assertThat(store.refusesSwap(9)).isTrue();
		assertThat(store.refusesSwap(10)).isFalse();
	}

	/** Abandoning an older generation does not retroactively refuse a newer one's swap. */
	@Test
	void aSwapForAGenerationNewerThanTheAbandonedOneStillCommits() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(7);

		assertThat(store.refusesSwap(8)).isFalse();
	}

	/** A generation older than the abandoned one is abandoned too: its deploy cannot have outlived the newer one's failure. */
	@Test
	void aSwapForAGenerationOlderThanTheAbandonedOneIsRefused() {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.RESOURCES_LOADER);

		store.abandon(7);

		assertThat(store.refusesSwap(6)).isTrue();
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
