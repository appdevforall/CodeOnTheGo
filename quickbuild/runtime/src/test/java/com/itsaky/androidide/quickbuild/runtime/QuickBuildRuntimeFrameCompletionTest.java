package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins that a drawn frame's verdict - does it prove the live generation's resources - is fixed on the frame's own draw pass, not when its posted completion runs.
 *
 * The ordering under test is the main looper's: the draw listener fires inside the traversal, an async message that runs ahead of the sync barrier; the completion is posted behind it; and a boot restore's swap message, posted earlier by the restore thread, runs in between. So a frame drawn against the baseline table can have its completion run after the restore has landed and cleared {@code bootRestoreInFlight}. A completion that read the flag when it ran would then record good a generation whose table never rendered, leaving a table that fails to render unblamable on the next boot.
 *
 * The generation is under the same rule. A deploy on a binder thread can persist, apply and arm a newer generation between the draw and the posted completion; a completion that read the live generation when it ran would ack that generation and record it good off a frame that drew the previous one.
 *
 * {@link QuickBuildRuntime#frameCompletion} is the seam: it takes both probes and must read them before returning. Moving either read into the returned runnable - the pre-fix shape, where {@code markLiveGenerationGood} read the field at completion time - turns the matching test red.
 */
class QuickBuildRuntimeFrameCompletionTest {

	private static final QuickBuildRuntime.LiveGenerationProbe GEN_5 = new QuickBuildRuntime.LiveGenerationProbe() {

		@Override
		public long generation() {
			return 5;
		}
	};

	private static final QuickBuildRuntime.BootRestoreProbe NO_RESTORE = new QuickBuildRuntime.BootRestoreProbe() {

		@Override
		public boolean inFlight() {
			return false;
		}
	};

	/** A deploy landing between the draw and the completion must not have the completion claim the frame drew the newer generation. */
	@Test
	void aDeployLandingBetweenTheDrawAndTheCompletionDoesNotMoveTheDrawnGeneration() {
		final long[] live = {5};
		final long[] drawn = {-1};

		// The draw pass: gen 5 is live and is what this frame rendered.
		Runnable completion = QuickBuildRuntime.frameCompletion(NO_RESTORE, new QuickBuildRuntime.LiveGenerationProbe() {

			@Override
			public long generation() {
				return live[0];
			}
		}, new QuickBuildRuntime.FrameCompletion() {

			@Override
			public void complete(boolean frameProvesResources, long drawnGeneration) {
				drawn[0] = drawnGeneration;
			}
		});
		// A gen-6 payload persists, applies and arms on a binder thread.
		live[0] = 6;
		// The posted completion runs last.
		completion.run();

		assertThat(drawn[0]).isEqualTo(5);
	}

	/** The frame after the restore's recreate is the one that vouches: drawn with the flag clear, it proves the resources. */
	@Test
	void aFrameDrawnAfterTheRestoreLandedVouches() {
		final Boolean[] verdict = new Boolean[1];

		Runnable completion = QuickBuildRuntime.frameCompletion(new QuickBuildRuntime.BootRestoreProbe() {

			@Override
			public boolean inFlight() {
				return false;
			}
		}, GEN_5, new QuickBuildRuntime.FrameCompletion() {

			@Override
			public void complete(boolean frameProvesResources, long drawnGeneration) {
				verdict[0] = frameProvesResources;
			}
		});
		completion.run();

		assertThat(verdict[0]).isTrue();
	}

	/** A restore that lands between the draw and the completion must not turn a baseline frame into a vouching one. */
	@Test
	void aRestoreLandingBetweenTheDrawAndTheCompletionDoesNotMakeTheFrameVouch() {
		final boolean[] inFlight = {true};
		final Boolean[] verdict = new Boolean[1];

		// The draw pass: the swap has not committed, so this frame drew the baseline table.
		Runnable completion = QuickBuildRuntime.frameCompletion(new QuickBuildRuntime.BootRestoreProbe() {

			@Override
			public boolean inFlight() {
				return inFlight[0];
			}
		}, GEN_5, new QuickBuildRuntime.FrameCompletion() {

			@Override
			public void complete(boolean frameProvesResources, long drawnGeneration) {
				verdict[0] = frameProvesResources;
			}
		});
		// The swap message lands and onBootRestoreLanded clears the flag.
		inFlight[0] = false;
		// The posted completion runs last.
		completion.run();

		assertThat(verdict[0]).isFalse();
	}

	/** Nothing runs until the post lands: the completion is deferred, not executed on the draw pass, so measure, layout and draw failures still precede it. */
	@Test
	void theCompletionRunsOnlyWhenPosted() {
		final int[] completions = {0};

		Runnable completion = QuickBuildRuntime.frameCompletion(new QuickBuildRuntime.BootRestoreProbe() {

			@Override
			public boolean inFlight() {
				return false;
			}
		}, GEN_5, new QuickBuildRuntime.FrameCompletion() {

			@Override
			public void complete(boolean frameProvesResources, long drawnGeneration) {
				completions[0]++;
			}
		});

		assertThat(completions[0]).isEqualTo(0);
		completion.run();
		assertThat(completions[0]).isEqualTo(1);
	}
}
