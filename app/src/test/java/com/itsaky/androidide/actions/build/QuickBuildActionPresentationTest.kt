package com.itsaky.androidide.actions.build

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.domain.QuickBuildTone
import org.junit.Test

/**
 * Behaviour 1 of Bryan's button spec: while a quick build runs, the button IS the standard
 * build's stop button. The mapping is the whole of that behaviour that can be checked off a
 * device - the repaint itself is device-only - so it is pinned here.
 */
class QuickBuildActionPresentationTest {
	@Test
	fun `a running build shows the standard build's stop icon, not a bolt variant`() {
		// Specifically ic_stop_daemons, the drawable AbstractCancellableRunAction swaps in:
		// the point is that the two buttons look the same while stopping the same kind of
		// thing. Any bolt variant here (the previous ic_quick_build_outline) fails the spec,
		// because it did not communicate "a build is running" to anyone.
		assertThat(QuickBuildAction.iconResFor(QuickBuildTone.BUILDING))
			.isEqualTo(R.drawable.ic_stop_daemons)
	}

	@Test
	fun `an idle button shows the bolt and a failure shows the error bolt`() {
		assertThat(QuickBuildAction.iconResFor(QuickBuildTone.READY))
			.isEqualTo(R.drawable.ic_quick_build)
		assertThat(QuickBuildAction.iconResFor(QuickBuildTone.ERROR))
			.isEqualTo(R.drawable.ic_quick_build_error)
	}

	/**
	 * The regression this split exists to prevent: a full rebuild during ordinary editing and a
	 * daemon respawn are not failures, and painting them with the error tint said "something
	 * broke" when nothing had.
	 */
	@Test
	fun `only a failure is tinted as an error`() {
		assertThat(QuickBuildAction.colorAttrFor(QuickBuildTone.ERROR))
			.isEqualTo(R.attr.colorError)

		listOf(
			QuickBuildTone.READY,
			QuickBuildTone.BUILDING,
			QuickBuildTone.SLOW,
			QuickBuildTone.RECONNECTING,
		).forEach { tone ->
			assertThat(QuickBuildAction.colorAttrFor(tone)).isNotEqualTo(R.attr.colorError)
		}
	}

	@Test
	fun `a slow build keeps a bolt - it is still Quick Build, just not the fast path`() {
		assertThat(QuickBuildAction.iconResFor(QuickBuildTone.SLOW))
			.isEqualTo(R.drawable.ic_quick_build_outline)
	}

	@Test
	fun `each tone gets its own icon - status is never carried by color alone`() {
		// The plan A2 colorblind constraint: the three tones must be distinguishable with the
		// color filter ignored entirely.
		val icons = QuickBuildTone.entries.map { QuickBuildAction.iconResFor(it) }

		assertThat(icons).containsNoDuplicates()
	}

	@Test
	fun `the label moves with the icon so the button never offers two different actions`() {
		// The label is what the overflow menu and the long-press dropdown read. A stop icon
		// labelled "Quick Build" would name the wrong operation.
		assertThat(QuickBuildAction.labelResFor(QuickBuildTone.BUILDING))
			.isEqualTo(R.string.title_cancel_build)
		assertThat(QuickBuildAction.labelResFor(QuickBuildTone.READY))
			.isEqualTo(R.string.quick_build_action_label)
		assertThat(QuickBuildAction.labelResFor(QuickBuildTone.ERROR))
			.isEqualTo(R.string.quick_build_action_label)
	}

	/**
	 * Only BUILDING makes a tap cancel (QuickBuildAction.execAction keys off exactly this), so
	 * it is also the only tone allowed to claim the cancel label - a state with nothing to
	 * cancel must not offer to.
	 */
	@Test
	fun `only the building tone offers to cancel`() {
		QuickBuildTone.entries
			.filter { it != QuickBuildTone.BUILDING }
			.forEach { tone ->
				assertThat(QuickBuildAction.labelResFor(tone))
					.isNotEqualTo(R.string.title_cancel_build)
			}
	}
}
