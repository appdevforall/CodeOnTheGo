package com.itsaky.androidide.quickbuild.runtime;

/**
 * Turns a payload crash into the text CoGo is told about it, and owns the height its banner may reach.
 *
 * One summary, one reader. {@link #forReport} crosses binder to CoGo, which has a screen, a scrollback and the developer's attention, so it carries enough frames to place the fault. The banner over the user's own app carries none of it - it names Build Output and stops - so what remains here for the banner is {@link #MAX_BANNER_LINES} alone.
 */
final class CrashSummary {

	/**
	 * Lines {@link StatusOverlay}'s banner shows before it ellipsizes, sized against the whole rendered banner.
	 *
	 * The crash banner: a headline of 56 characters and {@code OverlayState.FULL_OUTPUT_POINTER} at 50. Wrapped at 25 characters per line - the narrowest width measured on an A56 at 2x font scale - the headline costs 3 rendered lines and the pointer 2, so the crash banner is 5 at its tallest.
	 *
	 * Five is needed across the 25-to-27 band; from 28 characters up the headline folds into two as well and the banner fits in 4. The cap is left one line above the worst measured case rather than tightened onto it, because the line it would drop is the tail of the pointer - the reader is left told to look somewhere, without the name of the place - and because a font or locale wider than anything measured here should cost a blank line, not a truncated instruction.
	 *
	 * An earlier version also put a stack summary on the banner and needed 14 lines to fit it. Dropping the summary is what buys this back, so putting any detail on the banner again means recomputing here rather than raising the cap. {@code StatusOverlay} reads this instead of carrying a number of its own that could drift from it.
	 *
	 * {@code BUILD_FAILED} is the tallest state, at exactly this cap: a 54-character headline (3 lines at the narrowest measure), one detail line clamped to {@link #BUILD_FAILED_DETAIL_CHARS}, and the pointer's 2.
	 */
	static final int MAX_BANNER_LINES = 6;

	/**
	 * Characters of {@code BUILD_FAILED} diagnostic detail the banner shows: one wrapped line.
	 *
	 * Of the {@link #MAX_BANNER_LINES} cap, the build-failed headline takes 3 lines (54 characters at the narrowest measured 25 per line) and the pointer takes 2, which leaves one line - 25 characters - for the diagnostic. An unclamped detail would push the pointer off the banner, and the pointer is the line that tells the user where to look.
	 */
	static final int BUILD_FAILED_DETAIL_CHARS = 25;

	/** Frames a report to CoGo names; enough to place the fault, short enough to read. */
	private static final int MAX_REPORT_FRAMES = 5;

	/** Hard cap on the report form, since it crosses binder. */
	private static final int MAX_REPORT_LENGTH = 2000;

	/**
	 * Causes the report walks past the top throwable.
	 *
	 * An Android lifecycle crash always arrives wrapped - the framework rethrows as "Unable to start activity" - so the top throwable's frames are ActivityThread's and the frame naming the developer's bug is one level down. Reporting the top alone spends the whole budget on frames no reader can act on. Three levels covers a wrapped cause and the two rewraps a build pipeline tends to add; {@link #truncate} is the backstop, and it cuts from the deepest cause, which is the end worth losing.
	 */
	private static final int MAX_REPORT_CAUSES = 3;

	/**
	 * The full form reported to CoGo.
	 *
	 * @param error
	 *            the failure to summarize; must be non-null
	 * @return the exception and up to {@link #MAX_REPORT_CAUSES} causes, each with up to {@link #MAX_REPORT_FRAMES} frames, truncated to {@link #MAX_REPORT_LENGTH} chars
	 */
	static String forReport(Throwable error) {
		StringBuilder sb = new StringBuilder();
		sb.append(error.toString());
		appendFrames(sb, error, MAX_REPORT_FRAMES);
		// Each cause gets its frames too, not just its toString. The frame a developer needs is
		// almost never in the top throwable: the framework wraps a lifecycle crash, so the top
		// five frames are ActivityThread's every time and the one line naming the bug sits in
		// the cause. Reporting the message alone named the exception without ever placing it.
		Throwable cause = error.getCause();
		Throwable previous = error;
		for (int depth = 0; cause != null && cause != previous && depth < MAX_REPORT_CAUSES; depth++) {
			sb.append("\nCaused by: ").append(cause.toString());
			appendFrames(sb, cause, MAX_REPORT_FRAMES);
			previous = cause;
			cause = cause.getCause();
		}
		return truncate(sb, MAX_REPORT_LENGTH);
	}

	/**
	 * Appends at most {@code limit} of {@code error}'s frames, one per line.
	 *
	 * @param sb
	 *            the summary under construction
	 * @param error
	 *            the failure whose trace to read; a trace stripped by the VM is simply empty
	 * @param limit
	 *            the most frames to append
	 */
	private static void appendFrames(StringBuilder sb, Throwable error, int limit) {
		StackTraceElement[] frames = error.getStackTrace();
		int count = Math.min(frames.length, limit);
		for (int i = 0; i < count; i++) {
			sb.append("\n at ").append(frames[i]);
		}
	}

	/**
	 * @param sb
	 *            the summary under construction
	 * @param limit
	 *            the most characters to keep
	 * @return the summary, cut to {@code limit} characters
	 */
	private static String truncate(StringBuilder sb, int limit) {
		if (sb.length() > limit) {
			sb.setLength(limit);
		}
		return sb.toString();
	}

	private CrashSummary() {}
}
