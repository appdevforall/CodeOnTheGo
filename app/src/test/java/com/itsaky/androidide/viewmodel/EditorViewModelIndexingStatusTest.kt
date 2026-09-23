package com.itsaky.androidide.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.service.IndexingState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class EditorViewModelIndexingStatusTest {
	@get:Rule
	var rule: TestRule = InstantTaskExecutorRule()

	private lateinit var viewModel: EditorViewModel

	@Before
	fun setUp() {
		viewModel = EditorViewModel()
	}

	@Test
	fun whileIndexingWithNoBuild_theSlotShowsTheIndexingText() {
		val next =
			resolveIndexingStatus(
				isSlotOwnedElsewhere = false,
				indexingStatus = INDEXING,
				currentStatus = "",
				shownIndexingStatus = null,
			)

		assertThat(next).isEqualTo(INDEXING)
	}

	@Test
	fun whileABuildRuns_theBuildKeepsTheSlot() {
		val next =
			resolveIndexingStatus(
				isSlotOwnedElsewhere = true,
				indexingStatus = INDEXING,
				currentStatus = BUILDING,
				shownIndexingStatus = null,
			)

		assertThat(next).isNull()
	}

	@Test
	fun whenTheBuildEndsWhileIndexing_theIndexingTextReturns() {
		val next =
			resolveIndexingStatus(
				isSlotOwnedElsewhere = false,
				indexingStatus = INDEXING,
				currentStatus = BUILD_SUCCESSFUL,
				shownIndexingStatus = "old",
			)

		assertThat(next).isEqualTo(INDEXING)
	}

	@Test
	fun whenTheSlotAlreadyShowsTheIndexingText_nothingIsWritten() {
		val next =
			resolveIndexingStatus(
				isSlotOwnedElsewhere = false,
				indexingStatus = INDEXING,
				currentStatus = INDEXING,
				shownIndexingStatus = INDEXING,
			)

		assertThat(next).isNull()
	}

	@Test
	fun whenIndexingEndsWhileTheSlotShowsIndexingText_theSlotIsCleared() {
		val next =
			resolveIndexingStatus(
				isSlotOwnedElsewhere = false,
				indexingStatus = null,
				currentStatus = INDEXING,
				shownIndexingStatus = INDEXING,
			)

		assertThat(next.toString()).isEmpty()
	}

	@Test
	fun whenIndexingEndsWhileTheSlotShowsAnotherMessage_theMessageStays() {
		val next =
			resolveIndexingStatus(
				isSlotOwnedElsewhere = false,
				indexingStatus = null,
				currentStatus = BUILD_SUCCESSFUL,
				shownIndexingStatus = INDEXING,
			)

		assertThat(next).isNull()
	}

	@Test
	fun whenIndexingEndsWithNoIndexingTextEverShown_theSlotIsLeftAlone() {
		val next =
			resolveIndexingStatus(
				isSlotOwnedElsewhere = false,
				indexingStatus = null,
				currentStatus = "",
				shownIndexingStatus = null,
			)

		assertThat(next).isNull()
	}

	@Test
	fun isIndexingFollowsTheObservedState() {
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 0, total = 3))
		assertThat(viewModel.isIndexing).isTrue()

		viewModel.onIndexingStateChanged(IndexingState.Idle)
		assertThat(viewModel.isIndexing).isFalse()
	}

	@Test
	fun theIndexingTextIsFormattedFromDoneThenTotal() {
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 123, total = 538))

		update(isSlotOwnedElsewhere = false)

		assertThat(viewModel.statusText.toString()).isEqualTo("Indexing libraries (123/538)")
	}

	@Test
	fun whenIndexingEnds_theIndexingTextTheViewModelWroteIsCleared() {
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 1, total = 2))
		update(isSlotOwnedElsewhere = false)

		viewModel.onIndexingStateChanged(IndexingState.Idle)
		update(isSlotOwnedElsewhere = false)

		assertThat(viewModel.statusText.toString()).isEmpty()
	}

	@Test
	fun whenIndexingEndsDuringABuild_theBuildStatusIsKeptAfterTheBuildEnds() {
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 1, total = 2))
		update(isSlotOwnedElsewhere = false)
		viewModel.statusText = BUILDING

		viewModel.onIndexingStateChanged(IndexingState.Idle)
		update(isSlotOwnedElsewhere = true)
		viewModel.statusText = BUILD_SUCCESSFUL
		update(isSlotOwnedElsewhere = false)

		assertThat(viewModel.statusText.toString()).isEqualTo(BUILD_SUCCESSFUL)
	}

	@Test
	fun whenACancelledBuildWritesItsStatusAfterClearingTheFlag_theDeferredUpdateRestoresTheIndexingText() {
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 1, total = 2))
		viewModel.statusText = BUILDING

		// The flag is already clear when the cancel text lands; the deferred update runs after it.
		viewModel.statusText = BUILD_CANCELLED
		update(isSlotOwnedElsewhere = false)

		assertThat(viewModel.statusText.toString()).isEqualTo(INDEXING)
	}

	@Test
	fun anIndexingTickWhileTheProjectInitializes_leavesTheInitializationTextAlone() {
		viewModel.statusText = INITIALIZING
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 1, total = 2))

		update(isSlotOwnedElsewhere = true)

		assertThat(viewModel.statusText.toString()).isEqualTo(INITIALIZING)
	}

	@Test
	fun anIndexingTickWhileInitializationFailureIsShown_leavesTheFailureTextAlone() {
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 1, total = 2))
		update(isSlotOwnedElsewhere = false)
		viewModel.statusText = INIT_FAILED

		update(isSlotOwnedElsewhere = true)

		assertThat(viewModel.statusText.toString()).isEqualTo(INIT_FAILED)
	}

	@Test
	fun whenTheDebuggerStopsStarting_theIndexingTextReturns() {
		viewModel.onIndexingStateChanged(IndexingState.Indexing(done = 1, total = 2))
		viewModel.statusText = DEBUGGER_STARTING
		update(isSlotOwnedElsewhere = true)
		assertThat(viewModel.statusText.toString()).isEqualTo(DEBUGGER_STARTING)

		update(isSlotOwnedElsewhere = false)

		assertThat(viewModel.statusText.toString()).isEqualTo(INDEXING)
	}

	private fun update(isSlotOwnedElsewhere: Boolean) {
		viewModel.updateIndexingStatus(isSlotOwnedElsewhere, ::format)
	}

	private fun format(
		done: Int,
		total: Int,
	): CharSequence = "Indexing libraries ($done/$total)"

	private companion object {
		const val INDEXING = "Indexing libraries (1/2)"
		const val BUILDING = "Building..."
		const val BUILD_SUCCESSFUL = "BUILD SUCCESSFUL in 3s"
		const val BUILD_CANCELLED = "Build was cancelled by the user."
		const val INITIALIZING = "Initializing project..."
		const val INIT_FAILED = "Failed to initialize project"
		const val DEBUGGER_STARTING = "Starting debugger..."
	}
}
