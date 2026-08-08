package com.itsaky.androidide.quickbuild.runtime;

/**
 * Immutable description of what the status overlay currently shows.
 *
 * The overlay is error-only: it tells the user when a build fails or a payload crashes, plus a one-time hint for the return gesture. {@link #building} is the one narrow exception, a neutral in-flight line so a slow compile does not read as silence. Success renders nothing. Every terminal event installs a new state and the overlay always renders the latest, so a transient state cannot get stuck on screen.
 */
final class OverlayState {

	/**
	 * State for a compile error, carrying the location and message for tap-to-jump.
	 *
	 * @param status
	 *            a parsed {@link BuildStatus#KIND_BUILD_FAILED} message; must be non-null, and its already-defaulted fields are copied as they are
	 * @return the state to render, tappable only when the status named a file
	 */
	static OverlayState buildFailed(BuildStatus status) {
		return new OverlayState(Kind.BUILD_FAILED, status.file, status.line, status.column,
				status.message, status.moreErrors, -1);
	}

	/**
	 * State for a build in flight, with the app on screen still running {@code runningGeneration}.
	 *
	 * Never offers tap-to-jump; there is no error location yet.
	 *
	 * @param runningGeneration
	 *            the generation the screen still shows, named in the banner text; -1 when unknown, which drops that clause
	 * @return the neutral in-flight state
	 */
	static OverlayState building(long runningGeneration) {
		return new OverlayState(Kind.BUILDING, null, -1, -1, null, 0, runningGeneration);
	}

	/**
	 * State for a payload that crashed and was rolled back, with a stack summary as {@code detail}.
	 *
	 * @param detail
	 *            one-line summary of the crash, appended to the banner; null renders the headline alone
	 * @return the crash state, never tappable since a crash carries no source location
	 */
	static OverlayState crashed(String detail) {
		return new OverlayState(Kind.CRASHED, null, -1, -1, detail, 0, -1);
	}

	/**
	 * State that renders nothing, the resting state.
	 *
	 * @return the state that makes {@link StatusOverlay#render} remove the banner
	 */
	static OverlayState hidden() {
		return new OverlayState(Kind.HIDDEN, null, -1, -1, null, 0, -1);
	}

	/**
	 * State for the one-time hint about the 3-finger return gesture.
	 *
	 * @return the hint state; the caller owns showing it only once per install
	 */
	static OverlayState hint() {
		return new OverlayState(Kind.HINT, null, -1, -1, null, 0, -1);
	}

	/** Which of the five overlay states this is; decides the color and the text. */
	final Kind kind;

	/** Failing file (host-side absolute path) for tap-to-jump, or null. */
	final String file;

	/** 1-based line for tap-to-jump, or -1. */
	final int line;

	/** 1-based column for tap-to-jump, or -1. */
	final int column;

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
	 * @param file
	 *            failing file for tap-to-jump, or null
	 * @param line
	 *            1-based line for tap-to-jump, or -1
	 * @param column
	 *            1-based column for tap-to-jump, or -1
	 * @param detail
	 *            first diagnostic line or crash summary, or null
	 * @param moreErrors
	 *            further error count beyond the first, >= 0
	 * @param runningGeneration
	 *            generation still on screen for BUILDING, else -1
	 */
	private OverlayState(Kind kind, String file, int line, int column, String detail,
			int moreErrors, long runningGeneration) {
		this.kind = kind;
		this.file = file;
		this.line = line;
		this.column = column;
		this.detail = detail;
		this.moreErrors = moreErrors;
		this.runningGeneration = runningGeneration;
	}

	/**
	 * True when tapping the overlay should jump to the failing file in CoGo.
	 *
	 * @return whether both a BUILD_FAILED kind and a file are present; a crash never qualifies
	 */
	boolean canJumpToEditor() {
		return kind == Kind.BUILD_FAILED && file != null;
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
	 * @return whether this state is BUILD_FAILED or CRASHED; the hint is not an error and survives a successful build
	 */
	boolean isError() {
		return kind == Kind.BUILD_FAILED || kind == Kind.CRASHED;
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
			String location = location();
			if (location != null || detail != null) {
				sb.append('\n');
				if (location != null) {
					sb.append(location);
					if (detail != null) {
						sb.append(": ");
					}
				}
				if (detail != null) {
					sb.append(detail);
				}
				if (moreErrors > 0) {
					sb.append(" (+").append(moreErrors).append(" more)");
				}
			}
			if (canJumpToEditor()) {
				sb.append("\nTap to open in Code on the Go");
			}
			return sb.toString();
		case CRASHED:
			return "New code crashed - app is running the last working version"
					+ (detail == null ? "" : "\n" + detail);
		case HINT:
			return "Quick Build: tap with 3 fingers to return to Code on the Go";
		case BUILDING:
			return runningGeneration >= 0
					? "Quick Build is compiling - this screen is running gen " + runningGeneration
							+ " (one reload behind)"
					: "Quick Build is compiling - this screen is one reload behind";
		default:
			return "";
		}
	}

	/**
	 * Short display location, e.g. "Foo.kt:12"; the full path stays in {@link #file} for the jump.
	 *
	 * @return the file name with the line appended, the bare name when the line is unknown, or null when there is no file
	 */
	private String location() {
		if (file == null) {
			return null;
		}
		int slash = file.lastIndexOf('/');
		String name = slash >= 0 ? file.substring(slash + 1) : file;
		return line > 0 ? name + ":" + line : name;
	}

	enum Kind {
		HIDDEN,
		/** CoGo reported a compile error; the app keeps running the last-good code. */
		BUILD_FAILED,
		/** A delivered payload crashed in render/lifecycle; rolled back to last-good. */
		CRASHED,
		/** One-time discoverability hint for the 3-finger return gesture. */
		HINT,
		/** A build is compiling; the app keeps running its last-deployed generation. */
		BUILDING
	}
}
