package com.itsaky.androidide.quickbuild.runtime;

/**
 * Immutable description of what the status overlay currently shows.
 *
 * The overlay is error-only: it tells the user when a build fails or a payload crashes. {@link #building} is the one narrow exception, a neutral in-flight line so a slow compile does not read as silence. Success renders nothing. Every terminal event installs a new state and the overlay always renders the latest, so a transient state cannot get stuck on screen.
 */
final class OverlayState {

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
	 * State for a payload that crashed and was rolled back, with a stack summary as {@code detail}.
	 *
	 * @param detail
	 *            one-line summary of the crash, appended to the banner; null renders the headline alone
	 * @return the crash state
	 */
	static OverlayState crashed(String detail) {
		return new OverlayState(Kind.CRASHED, detail, 0, -1);
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
	 * @return whether this state is BUILD_FAILED, CRASHED or REINSTALL_PENDING
	 */
	boolean isError() {
		return kind == Kind.BUILD_FAILED || kind == Kind.CRASHED || kind == Kind.REINSTALL_PENDING;
	}

	/**
	 * Builds the banner text for this state; failure copy always says the app still runs the last working code.
	 *
	 * @return the multi-line banner text, empty for {@link Kind#HIDDEN}
	 */
	String text() {
		switch (kind) {
		case BUILD_FAILED:
			StringBuilder sb = new StringBuilder(
					"Build failed - app is running the last working version");
			if (detail != null) {
				sb.append('\n').append(detail);
				if (moreErrors > 0) {
					sb.append(" (+").append(moreErrors).append(" more)");
				}
			}
			return sb.toString();
		case CRASHED:
			return "New code crashed - app is running the last working version"
					+ (detail == null ? "" : "\n" + detail);
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
		/** A build is compiling; the app keeps running its last-deployed generation. */
		BUILDING,
		/** An update's reinstall awaits a confirm only CoGo can show; the user must switch back. */
		REINSTALL_PENDING
	}
}
