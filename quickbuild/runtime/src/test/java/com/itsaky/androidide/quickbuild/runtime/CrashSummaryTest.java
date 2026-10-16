package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the split between what a crash tells CoGo and what it tells the user's own screen.
 *
 * The banner is a few lines of sp text pinned over someone else's app, and it grows with the system font scale, so it carries no stack at all - it names the pane that has one. The report has no such limit and must go on carrying the detail; these tests fail if either half drifts toward the other.
 */
class CrashSummaryTest {

	/**
	 * Characters the banner fits on a rendered line at its worst measured width.
	 *
	 * Measured on an A56 at font scale 2.0, where the banner wrapped at 25-32 characters. The low end is the one worth testing against: it is the width at which the message needs the most lines.
	 */
	private static final int NARROWEST_MEASURED_LINE_CHARS = 25;

	/** A crash of the shape the deploy path actually reports: deep stack, real cause. */
	private static Throwable deepError() {
		RuntimeException error = new RuntimeException("outer failure",
				new IllegalStateException("the underlying cause"));
		StackTraceElement[] frames = new StackTraceElement[60];
		for (int i = 0; i < frames.length; i++) {
			frames[i] = new StackTraceElement("com.example.app.ProxyAppScreen" + i, "onCreate",
					"ProxyAppScreen.java", 100 + i);
		}
		error.setStackTrace(frames);
		return error;
	}

	/**
	 * A crash of the shape the device actually produced: framework wrapper, developer's frame in the cause.
	 *
	 * Captured from an A56 on 2026-08-27, where a proxy-app reload crash reported five ActivityThread frames and the cause's message, and not one frame of the app under edit.
	 *
	 * @return the wrapped failure
	 */
	private static Throwable lifecycleCrash() {
		RuntimeException bug = new RuntimeException("QB-CRASH-PROBE");
		bug.setStackTrace(new StackTraceElement[]{
				new StackTraceElement("com.example.mybasic.MainActivity", "onCreate", "MainActivity.kt",
						22),
				new StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 8305)});
		RuntimeException wrapper = new RuntimeException(
				"Unable to start activity ComponentInfo{com.example.mybasic/...Proxy0Activity}", bug);
		StackTraceElement[] framework = new StackTraceElement[8];
		for (int i = 0; i < framework.length; i++) {
			framework[i] = new StackTraceElement("android.app.ActivityThread", "performLaunchActivity",
					"ActivityThread.java", 5040 + i);
		}
		wrapper.setStackTrace(framework);
		return wrapper;
	}

	private static String veryLongMessage() {
		StringBuilder sb = new StringBuilder();
		while (sb.length() < 5000) {
			sb.append("a message long enough to run past both caps. ");
		}
		return sb.toString();
	}

	/**
	 * Rendered lines {@code text} takes on a view that wraps at {@code width} characters.
	 *
	 * Every logical line costs at least one rendered line and a partial one is not shared with the next, which is what makes the worst case worse than dividing the total length.
	 */
	/**
	 * Rendered lines {@code text} occupies at {@code width} characters, wrapping at word boundaries.
	 *
	 * Dividing the length by the width instead would model a renderer that breaks mid-word, which under-counts every time a word straddles the boundary: the copy this guards needs 6 lines at width 25 and character division claims 5. That is the overflow the test exists to catch, so the arithmetic has to be the one TextView actually performs.
	 *
	 * @param text
	 *            the banner text, newlines separating its logical lines
	 * @param width
	 *            characters per rendered line
	 * @return the rendered line count
	 */
	private static int wrappedLineCount(String text, int width) {
		int lines = 0;
		for (String logical : text.split("\n", -1)) {
			int used = 0;
			boolean open = false;
			for (String word : logical.split(" +")) {
				if (word.isEmpty()) {
					continue;
				}
				if (!open) {
					lines += 1;
					used = 0;
					open = true;
				} else if (used + 1 + word.length() <= width) {
					used += 1 + word.length();
					continue;
				} else {
					lines += 1;
					used = 0;
				}
				// A word wider than the line wraps mid-word wherever it lands; the remainder
				// after the last full line is what the next word has to fit beside.
				if (word.length() > width) {
					lines += (word.length() - 1) / width;
					used = word.length() - ((word.length() - 1) / width) * width;
				} else {
					used = word.length();
				}
			}
			if (!open) {
				lines += 1;
			}
		}
		return lines;
	}

	@Test
	void aFramelessThrowableStillReports() {
		// A VM that stripped the trace, or a throwable built with writableStackTrace off,
		// must not turn a crash report into a second crash.
		RuntimeException error = new RuntimeException("no frames");
		error.setStackTrace(new StackTraceElement[0]);

		assertThat(CrashSummary.forReport(error)).contains("no frames");
	}

	@Test
	void aReportIsCappedForBinder() {
		RuntimeException error = new RuntimeException(veryLongMessage());

		assertThat(CrashSummary.forReport(error).length()).isAtMost(2000);
	}

	@Test
	void aReportKeepsTheDetailTheBannerNeverShows() {
		String report = CrashSummary.forReport(deepError());

		// Why the banner can afford to name a pane instead of a stack: CoGo still gets the
		// frames, and so does logcat.
		assertThat(report).contains("ProxyAppScreen4");
		assertThat(report).contains("the underlying cause");
	}

	@Test
	void aReportPlacesTheFaultInTheCauseNotJustNamesIt() {
		// The whole argument for taking the stack off the banner is that CoGo still gets one
		// worth reading. A wrapped lifecycle crash is the common case and its top frames are
		// all framework, so a report that stops at the cause's message spends its budget
		// naming an exception it never locates. This is the line the developer needs.
		String report = CrashSummary.forReport(lifecycleCrash());

		assertThat(report).contains("Caused by");
		assertThat(report).contains("MainActivity.kt:22");
	}

	@Test
	void aSelfCausedThrowableDoesNotRepeatItself() {
		// getCause() returning the throwable itself is legal, and appending it would print
		// the same line twice.
		RuntimeException error = new RuntimeException("self caused") {

			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		assertThat(CrashSummary.forReport(error)).doesNotContain("Caused by");
	}

	@Test
	void theBootRestoreReportSaysNothingWasRolledBack() {
		// A boot restore has no snapshot to roll back to, so its report must not borrow the
		// deploy-time wording that says the code was rolled back.
		String report = CrashSummary.forBootRestoreReport(lifecycleCrash());

		assertThat(report).startsWith(CrashSummary.BOOT_RESTORE_PREFIX);
		assertThat(report).doesNotContain("Rolled back");
		assertThat(report).contains("MainActivity.kt:22");
	}

	@Test
	void theCrashBannerCarriesNoStackAtAll() {
		// The property the banner's whole size argument rests on. If a summary is ever put
		// back on it, MAX_BANNER_LINES stops describing what renders and this goes red
		// before the arithmetic silently becomes wrong.
		String rendered = OverlayState.crashed().text();

		assertThat(rendered).contains("Build Output");
		assertThat(rendered).doesNotContain("Exception");
		assertThat(rendered).doesNotContain(" at ");
		assertThat(rendered).doesNotContain("Caused by");
	}

	@Test
	void theCrashBannerFitsTheOverlayLineBudgetAtTwoTimesFontScale() {
		// Measured over what the overlay actually renders, not over any one part of it:
		// measuring one piece against a budget several pieces spend is how the headline's
		// own wrap was once free to push text below the ellipsis unnoticed.
		String rendered = OverlayState.crashed().text();

		assertThat(wrappedLineCount(rendered, NARROWEST_MEASURED_LINE_CHARS))
				.isAtMost(CrashSummary.MAX_BANNER_LINES);
	}

	@Test
	void theMixedBannerFitsTheOverlayLineBudgetAtTwoTimesFontScale() {
		// MIXED has its own headline, so it has to be measured against the same budget the
		// crash headline was, not assumed to fit because that one does.
		String rendered = OverlayState.mixed().text();

		assertThat(wrappedLineCount(rendered, NARROWEST_MEASURED_LINE_CHARS))
				.isAtMost(CrashSummary.MAX_BANNER_LINES);
		assertThat(rendered).doesNotContain("Exception");
	}

	@Test
	void theMixedReportIsCappedAsAWholeNotAfterThePrefix() {
		// The prefix must not push the report past the binder cap forReport holds to.
		String plain = CrashSummary.forReport(new RuntimeException(veryLongMessage()));
		String mixed = CrashSummary.forMixedReport(new RuntimeException(veryLongMessage()));

		assertThat(mixed.length()).isEqualTo(plain.length());
	}

	@Test
	void theMixedReportLeadsWithTheRestartInstructionAndKeepsTheFrames() {
		// Build Output is where the banner sends the reader, so the instruction has to be
		// the first thing there, ahead of the same frames a plain crash reports.
		String report = CrashSummary.forMixedReport(lifecycleCrash());

		assertThat(report).startsWith(CrashSummary.MIXED_STATE_PREFIX);
		assertThat(report).contains("restarted");
		assertThat(report).contains("MainActivity.kt:22");
		assertThat(report).contains("Caused by");
	}
}
