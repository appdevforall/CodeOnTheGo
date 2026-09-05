package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the rule that a hot-swapped generation stays blamable until a frame of it has been drawn.
 *
 * The defect this covers: the runtime used to ack the generation, clear the pending slot and write good.json from onResume, which precedes the first traversal. A payload that resumed and then threw in measure, layout or draw therefore reached the crash guard with nothing pending and nothing unproven, so {@link BootProbation#generationToBlame} returned -1, {@link PayloadPersistence#quarantine} then refused to name a generation already recorded good, and every relaunch adopted it and died the same way.
 *
 * What these tests deliberately do NOT pin is the call site - that {@code onActivityResumed} installs a draw listener rather than completing inline needs an Activity, a Window and a live ViewTreeObserver, so it is checked on device. What they do pin is that the class now owning the pending slot holds it across a resume and releases it only on a drawn frame.
 */
class FirstFrameGateTest {

	/** Once the frame is drawn the generation is vouched for, so a later crash is no longer blamed on it. */
	@Test
	void aDrawnFrameReleasesTheGenerationAndStopsTheBlame() {
		FirstFrameGate gate = new FirstFrameGate();
		BootProbation probation = new BootProbation();
		gate.arm(7);

		assertThat(gate.drawn(7)).isEqualTo(7);
		assertThat(gate.pending()).isEqualTo(-1);
		assertThat(probation.generationToBlame(gate.pending(), 7)).isEqualTo(-1);
	}

	/** A pending generation the store has already moved past is stale, so its frame acks nothing. */
	@Test
	void aFrameForASupersededGenerationAcksNothing() {
		FirstFrameGate gate = new FirstFrameGate();
		gate.arm(7);

		assertThat(gate.drawn(8)).isEqualTo(-1);
		// Still pending, so gen 7 keeps taking the blame until its own frame or a disarm.
		assertThat(gate.pending()).isEqualTo(7);
	}

	/** A backgrounded apply arms with -1, which must clear an older generation rather than leave it to be blamed. */
	@Test
	void armingWithNoPendingGenerationClearsTheSlot() {
		FirstFrameGate gate = new FirstFrameGate();
		gate.arm(7);

		gate.arm(Generations.pendingAfterApply(false, 8));

		assertThat(gate.pending()).isEqualTo(-1);
	}

	/**
	 * The regression: a crash after the resume but before the first drawn frame still names a generation to quarantine.
	 *
	 * Goes red if the gate stops holding the generation across the undrawn window - which is the pre-fix behaviour, expressed in the class that now owns it.
	 */
	@Test
	void crashBetweenResumeAndFirstFrameStillQuarantines() {
		FirstFrameGate gate = new FirstFrameGate();
		BootProbation probation = new BootProbation();
		gate.arm(7);

		// The activity has resumed. Nothing releases the gate here, which is the fix:
		// the crash guard runs with the generation still pending.
		assertThat(gate.pending()).isEqualTo(7);
		assertThat(probation.generationToBlame(gate.pending(), 7)).isEqualTo(7);
	}

	/** A rolled-back reload releases the slot without acking, since there is no frame to report. */
	@Test
	void disarmReleasesWithoutAcking() {
		FirstFrameGate gate = new FirstFrameGate();
		gate.arm(7);

		gate.disarm();

		assertThat(gate.pending()).isEqualTo(-1);
		assertThat(gate.drawn(7)).isEqualTo(-1);
	}

	/** The ack fires once: every frame after the first finds the slot already released. */
	@Test
	void onlyTheFirstDrawnFrameAcks() {
		FirstFrameGate gate = new FirstFrameGate();
		gate.arm(7);

		assertThat(gate.drawn(7)).isEqualTo(7);
		assertThat(gate.drawn(7)).isEqualTo(-1);
		assertThat(gate.drawn(7)).isEqualTo(-1);
	}
}
