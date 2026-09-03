package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the rule that a backgrounded deploy's ack waits for its resource swaps.
 *
 * The defect this covers: the ack fired at apply time, while the swap it depended on was still queued to the main looper. A swap that then failed rolled the store back, quarantined the generation and reported a crash - all after CoGo had been told the generation reloaded. CoGo's deploy resolves on the first report naming the generation, so the ack won and the build was recorded as a successful reload with a timing number, while the session manager's separate collector still raised a reload crash from the same save.
 *
 * What these tests do NOT pin is the call site - that {@code handlePayload} counts its posted swaps and hangs the ack off the last commit needs a binder thread, a main looper and a Context, so it is checked on device. What they do pin is the rule the call site delegates to.
 */
class SwapAckGateTest {

	/**
	 * The regression: with a swap still queued, nothing owes the ack yet.
	 *
	 * Goes red if the gate ever lets the deploy ack before its swap has committed, which is the pre-fix behaviour expressed in the class that now owns the decision.
	 */
	@Test
	void aDeployWithAPostedSwapDoesNotAckBeforeItCommits() {
		SwapAckGate gate = new SwapAckGate(1);

		// The apply has returned and the swap is still on the main looper's queue.
		assertThat(gate.noSwapPosted()).isFalse();

		assertThat(gate.committed()).isTrue();
	}

	/** A dex-only deploy posts no swap, so the ack is owed as soon as the applies are done. */
	@Test
	void aDeployWithNoSwapAcksImmediately() {
		SwapAckGate gate = new SwapAckGate(0);

		assertThat(gate.noSwapPosted()).isTrue();
	}

	/** A swap that fails cancels the ack for good, so the failure path is the only thing that reports. */
	@Test
	void aFailedSwapNeverAcks() {
		SwapAckGate gate = new SwapAckGate(1);

		gate.failed();

		assertThat(gate.committed()).isFalse();
		assertThat(gate.noSwapPosted()).isFalse();
	}

	/** One swap of a pair failing cancels the ack, even though the other one lands. */
	@Test
	void aFailureCancelsTheAckWhenTheOtherSwapStillCommits() {
		SwapAckGate gate = new SwapAckGate(2);

		assertThat(gate.committed()).isFalse();
		gate.failed();

		assertThat(gate.committed()).isFalse();
	}

	/** And once claimed by the no-swap path, a stray commit cannot claim it again. */
	@Test
	void aStrayCommitAfterTheNoSwapAckIsIgnored() {
		SwapAckGate gate = new SwapAckGate(0);

		assertThat(gate.noSwapPosted()).isTrue();
		assertThat(gate.committed()).isFalse();
	}

	/** With a table and an assets swap, only the second commit owes the ack. */
	@Test
	void bothSwapsMustCommitBeforeTheAckIsOwed() {
		SwapAckGate gate = new SwapAckGate(2);

		assertThat(gate.committed()).isFalse();
		assertThat(gate.committed()).isTrue();
	}

	/** The ack is owed once: a late or duplicated commit finds the gate settled. */
	@Test
	void theAckIsOwedOnlyOnce() {
		SwapAckGate gate = new SwapAckGate(1);

		assertThat(gate.committed()).isTrue();
		assertThat(gate.committed()).isFalse();
		assertThat(gate.noSwapPosted()).isFalse();
	}
}
