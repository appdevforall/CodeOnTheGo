package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins that an apply failure is reported once per deploy, however many ways that deploy manages to fail.
 *
 * The defect: {@code handlePayload}'s catch and {@code restoreBootResources}' catch both called {@code SwapAckGate.failed()}, discarded the answer and reported regardless. A persisted generation carrying both a table and assets then reported twice for one boot - the table swap fails on the main looper and reports, the applying thread throws in applyAssets, and the catch reports again. What the user saw was a second mixed banner and a duplicate crash report for one save, the exact double-signal {@link SwapAckGate} exists to prevent.
 *
 * {@link QuickBuildRuntime#reportApplyFailureOnce} is the seam both catches now go through. It owns the guard AND the report it gates, so deleting the guard cannot leave these tests green - which a bare boolean helper, still tested apart from its call sites, would.
 *
 * What these tests do NOT pin is what each catch puts in its report body - the rollback, the abandon, the banner. Reaching either catch for real needs a binder thread, a main looper and a Context, so those bodies are checked on device.
 */
class QuickBuildRuntimeApplyFailureReportTest {

	/** The regression: the swap already failed on main and reported, so the catch that follows must stay quiet. */
	@Test
	void aCatchAfterASwapAlreadyFailedDoesNotReportAgain() {
		SwapAckGate gate = new SwapAckGate(2);
		ReportCounter report = new ReportCounter();

		// The table swap failed on the main looper; onSwapFailed owned that report.
		assertThat(gate.failed()).isTrue();
		// The applying thread then throws in applyAssets and reaches its catch.
		QuickBuildRuntime.reportApplyFailureOnce(gate, report);

		assertThat(report.runs).isEqualTo(0);
	}

	/** A deploy whose swaps all committed has already acked success; the catch must not turn that into a crash report. */
	@Test
	void aCatchAfterTheSwapsCommittedDoesNotReport() {
		SwapAckGate gate = new SwapAckGate(1);
		ReportCounter report = new ReportCounter();

		// The one posted swap committed inline, which settled the gate by acking.
		assertThat(gate.committed()).isTrue();
		QuickBuildRuntime.reportApplyFailureOnce(gate, report);

		assertThat(report.runs).isEqualTo(0);
	}

	/** The failure nothing else has reported is the one this catch owes, and it owes it exactly once. */
	@Test
	void aCatchOnAnUnsettledGateReportsOnce() {
		SwapAckGate gate = new SwapAckGate(1);
		ReportCounter report = new ReportCounter();

		QuickBuildRuntime.reportApplyFailureOnce(gate, report);

		assertThat(report.runs).isEqualTo(1);
	}

	/** And having reported, the gate is settled: a later failure on the same deploy finds the report already owned. */
	@Test
	void aSecondCatchOnTheSameGateDoesNotReportAgain() {
		SwapAckGate gate = new SwapAckGate(2);
		ReportCounter report = new ReportCounter();

		QuickBuildRuntime.reportApplyFailureOnce(gate, report);
		QuickBuildRuntime.reportApplyFailureOnce(gate, report);

		assertThat(report.runs).isEqualTo(1);
	}

	/** Counts report bodies actually run, so "reported twice" is distinguishable from "reported once". */
	private static final class ReportCounter implements Runnable {

		int runs;

		@Override
		public void run() {
			runs++;
		}
	}
}
