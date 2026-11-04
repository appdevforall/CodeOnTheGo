package com.itsaky.androidide.actions.build

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The tap's save/sample ordering (F7/S6): `wroteSomething` must be sampled from the dirty
 * state BEFORE the awaited save-all flushes it. Moving the read below the save is a
 * natural-looking tidy-up that makes every dirty tap read false - the user is then switched
 * into a STALE proxy app before their build starts, strictly worse than the original F7 bug.
 */
class QuickBuildActionSaveOrderTest {
	@Test
	fun `the dirty state is sampled before the save-all flushes it`() =
		runTest {
			// Models the real activity: the save-all clears the modified flag, so a
			// post-save sample can only ever read false.
			var dirty = true

			val wroteSomething =
				QuickBuildAction.sampleDirtyThenSaveAll(
					areFilesModified = { dirty },
					saveAll = { dirty = false },
				)

			assertThat(wroteSomething).isTrue()
			assertThat(dirty).isFalse()
		}

	@Test
	fun `the sample happens exactly once and strictly before the save`() =
		runTest {
			val order = mutableListOf<String>()

			QuickBuildAction.sampleDirtyThenSaveAll(
				areFilesModified = {
					order += "sample"
					false
				},
				saveAll = { order += "save" },
			)

			assertThat(order).containsExactly("sample", "save").inOrder()
		}

	@Test
	fun `a clean editor still saves - the flush is unconditional`() =
		runTest {
			var saved = false

			val wroteSomething =
				QuickBuildAction.sampleDirtyThenSaveAll(
					areFilesModified = { false },
					saveAll = { saved = true },
				)

			assertThat(wroteSomething).isFalse()
			assertThat(saved).isTrue()
		}
}
