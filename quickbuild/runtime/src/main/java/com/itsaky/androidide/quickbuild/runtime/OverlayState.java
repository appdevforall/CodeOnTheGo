package com.itsaky.androidide.quickbuild.runtime;

/**
 * Immutable description of what the status overlay currently shows.
 *
 * The overlay is error-only: it tells the user when a build fails or a payload crashes. {@link #building} is the one narrow exception, a neutral in-flight line so a slow compile does not read as silence. Success renders nothing. Every terminal event installs a new state and the overlay always renders the latest, so a transient state cannot get stuck on screen.
 *
 * The banner copy is inline English rather than a string resource, against the repo rule, because it cannot be one: this AAR is injected into the user's own app and carries no {@code res/}, so any id it referenced would resolve against that app's table - or against the very payload table a crash banner is reporting on. {@link CrashSummary#MAX_BANNER_LINES} is computed from these literals' character counts, so translating or lengthening them means recomputing there.
 */
final class OverlayState {

	/**
	 * Where a reader goes for the whole failure, named on the crash banner.
	 *
	 * The banner is a strip over the user's own app and deliberately cannot scroll, so it names the surface that holds the failure rather than carrying any of it. Build Output is the pane CoGo already writes the reported summary to, so this points at something that exists rather than something we would have to build. Its length is half of {@link CrashSummary#MAX_BANNER_LINES}' arithmetic - lengthening it without revisiting that clips the pointer itself, which is the one line the banner cannot afford to lose.
	 */
	static final String FULL_OUTPUT_POINTER = "For more info, see Build Output in Code on the Go.";

	/**
	 * State for a compile error, carrying the message summary the banner names. The banner is position-free by design: the error location is CoGo's to show, so it never crosses the deploy channel.
	 *
	 * @param status
	 *            a parsed {@link BuildStatus#KIND_BUILD_FAILED} message; must be non-null, and its already-defaulted fields are copied as they are
	 * @return the state to render
	 */
	static OverlayState buildFailed(BuildStatus status) {
		return new OverlayState(Kind.BUILD_FAILED, status.message, status.moreErrors, -1);
	}

	/**
	 * State for a build in flight, with the app on screen still running {@code runningGeneration}.
	 *
	 * @param runningGeneration
	 *            the generation the screen still shows, named in the banner text; -1 when unknown, which drops that clause
	 * @return the neutral in-flight state
	 */
	static OverlayState building(long runningGeneration) {
		return new OverlayState(Kind.BUILDING, null, 0, runningGeneration);
	}

	/**
	 * State for a payload that crashed and was rolled back.
	 *
	 * The banner takes no stack summary. It covers the user's own app, so it says what happened and names where the detail is, and stops; {@link CrashSummary#forReport} still ships the whole thing to CoGo. A summary here would be text nobody can scroll, on a surface that ellipsizes, in front of an app the reader did not ask us to cover.
	 *
	 * @return the crash state
	 */
	static OverlayState crashed() {
		return new OverlayState(Kind.CRASHED, null, 0, -1);
	}

	/**
	 * State that renders nothing, the resting state.
	 *
	 * @return the state that makes {@link StatusOverlay#render} remove the banner
	 */
	static OverlayState hidden() {
		return new OverlayState(Kind.HIDDEN, null, 0, -1);
	}

	/**
	 * State for a reload that failed after its resource swap had already committed, so the rollback restored the code half only.
	 *
	 * Says restart rather than "last working version": the screen resolves the failed generation's table over the previous generation's classes, and only a resources-carrying deploy or a process restart clears that. Restart is the remedy the user has: after a deploy-time failure the generation is quarantined, so the next boot adopts the last good one whole, and after a boot-time restore failure the restart re-runs the restore.
	 *
	 * @return the mixed-versions crash state
	 */
	static OverlayState mixed() {
		return new OverlayState(Kind.MIXED, null, 0, -1);
	}

	/**
	 * State for an update whose reinstall is waiting on a confirm dialog only CoGo can show. The user watching this app is the one person the CoGo-side signals cannot reach, so this banner is the recovery instruction.
	 *
	 * @return the state to render
	 */
	static OverlayState reinstallPending() {
		return new OverlayState(Kind.REINSTALL_PENDING, null, 0, -1);
	}

	/** Which overlay state this is; decides the color and the text. */
	final Kind kind;

	/** First diagnostic line / crash stack summary, or null. */
	final String detail;

	/** Further error count beyond the first, >= 0. */
	final int moreErrors;

	/** For {@link Kind#BUILDING}: the generation still on screen, or -1 otherwise. */
	final long runningGeneration;

	/**
	 * Stores one state; only the factory methods above construct these.
	 *
	 * @param kind
	 *            which state this is
	 * @param detail
	 *            first diagnostic line or crash summary, or null
	 * @param moreErrors
	 *            further error count beyond the first, >= 0
	 * @param runningGeneration
	 *            generation still on screen for BUILDING, else -1
	 */
	private OverlayState(Kind kind, String detail, int moreErrors, long runningGeneration) {
		this.kind = kind;
		this.detail = detail;
		this.moreErrors = moreErrors;
		this.runningGeneration = runningGeneration;
	}

	/**
	 * True while a build compiles; a terminal build_ok/build_failed must clear this too.
	 *
	 * @return whether this is the BUILDING state
	 */
	boolean isBuilding() {
		return kind == Kind.BUILDING;
	}

	/**
	 * True for the states a successful reload / build must clear.
	 *
	 * @return whether this state is BUILD_FAILED, CRASHED, MIXED or REINSTALL_PENDING
	 */
	boolean isError() {
		return kind == Kind.BUILD_FAILED || kind == Kind.CRASHED || kind == Kind.MIXED
				|| kind == Kind.REINSTALL_PENDING;
	}

	/**
	 * Builds the banner text for this state; failure copy says the app still runs the last working code, except {@link Kind#MIXED}, where that would be false.
	 *
	 * @return the multi-line banner text, empty for {@link Kind#HIDDEN}
	 */
	String text() {
		switch (kind) {
		case BUILD_FAILED:
			StringBuilder sb = new StringBuilder(
					"Build failed - app is running the last working version");
			if (detail != null) {
				StringBuilder line = new StringBuilder(detail);
				if (moreErrors > 0) {
					line.append(" (+").append(moreErrors).append(" more)");
				}
				// The detail is a diagnostic line CoGo sends and its length is unbounded,
				// so clamp it to the one line CrashSummary budgets for it; anything longer
				// would push FULL_OUTPUT_POINTER off the banner. The explicit "..." matters:
				// a hard cut mid-word reads as the whole message.
				if (line.length() > CrashSummary.BUILD_FAILED_DETAIL_CHARS) {
					line.setLength(CrashSummary.BUILD_FAILED_DETAIL_CHARS - 3);
					line.append("...");
				}
				sb.append('\n').append(line);
			}
			sb.append('\n').append(FULL_OUTPUT_POINTER);
			return sb.toString();
		case CRASHED:
			// "Live reload crashed", not "new code crashed": this state is set only from
			// failReload, so it is the reload machinery that failed, never the user's own
			// code. The old wording named the one event this banner cannot observe.
			return "Live reload crashed. App is on the last working version." + "\n"
					+ FULL_OUTPUT_POINTER;
		case MIXED:
			// Same length band as CRASHED, so MAX_BANNER_LINES still holds; a longer line
			// here means recomputing it.
			return "Live reload crashed. Restart the app - it is running mixed versions." + "\n"
					+ FULL_OUTPUT_POINTER;
		case REINSTALL_PENDING:
			return "Update needs your OK in Code on the Go - switch back to approve it\n"
					+ "This app is running the last working version";
		case BUILDING:
			return runningGeneration >= 0
					? "Quick Build is compiling - this screen is running gen " + runningGeneration
							+ " (one reload behind)"
					: "Quick Build is compiling - this screen is one reload behind";
		default:
			return "";
		}
	}

	/** The states the banner can be in; each one fixes its color and its copy. */
	enum Kind {
		/** Nothing to say, so the banner is removed. */
		HIDDEN,
		/** CoGo reported a compile error; the app keeps running the last-good code. */
		BUILD_FAILED,
		/** A delivered payload crashed in render/lifecycle; rolled back to last-good. */
		CRASHED,
		/** A payload failed after its resources swapped in; the code rolled back, the resources could not. */
		MIXED,
		/** A build is compiling; the app keeps running its last-deployed generation. */
		BUILDING,
		/** An update's reinstall awaits a confirm only CoGo can show; the user must switch back. */
		REINSTALL_PENDING
	}
}
