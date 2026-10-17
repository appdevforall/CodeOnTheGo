package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the CALL SITE of the first-frame gate: a resume with a frame still coming completes nothing, so a generation that crashes between the resume and its first drawn frame is still blamed and quarantined.
 *
 * {@link FirstFrameGateTest} pins the gate class, which holds under any caller. This pins the caller. {@link QuickBuildRuntime#onActivityResumed} needs an Activity, a Window and a live ViewTreeObserver, so the routing is exercised through {@link QuickBuildRuntime#completeOnResume}, the seam that method delegates to. Reverting the routing - completing inline at resume, which is the pre-fix behaviour - turns the first test here red.
 */
class QuickBuildRuntimeResumeCompletionTest {

	/** No live view tree means no frame is coming, so the completion runs inline rather than stranding the deploy unacked. */
	@Test
	void anActivityWithNoLiveViewTreeCompletesInline() {
		FirstFrameGate gate = new FirstFrameGate();
		gate.arm(7);
		final boolean[] completed = new boolean[1];

		QuickBuildRuntime.completeOnResume(new QuickBuildRuntime.FrameCallbackInstaller() {

			@Override
			public boolean install() {
				return false;
			}
		}, new Runnable() {

			@Override
			public void run() {
				gate.drawn(7);
				completed[0] = true;
			}
		});

		assertThat(completed[0]).isTrue();
		assertThat(gate.pending()).isEqualTo(-1);
	}

	/**
	 * The regression: with a frame coming, the resume itself must not ack, clear the pending slot or mark the generation good.
	 *
	 * Goes red if the resume completes inline again, because the crash guard would then find nothing pending and {@link BootProbation#generationToBlame} would return -1 for a crash that happened during the very first traversal.
	 */
	@Test
	void aResumeWithAFrameComingCompletesNothingAndKeepsTheGenerationBlamable() {
		FirstFrameGate gate = new FirstFrameGate();
		BootProbation probation = new BootProbation();
		gate.arm(7);
		final boolean[] completed = new boolean[1];

		QuickBuildRuntime.completeOnResume(new QuickBuildRuntime.FrameCallbackInstaller() {

			@Override
			public boolean install() {
				return true;
			}
		}, new Runnable() {

			@Override
			public void run() {
				gate.drawn(7);
				completed[0] = true;
			}
		});

		assertThat(completed[0]).isFalse();
		assertThat(gate.pending()).isEqualTo(7);
		assertThat(probation.generationToBlame(gate.pending(), 7)).isEqualTo(7);
	}
}
