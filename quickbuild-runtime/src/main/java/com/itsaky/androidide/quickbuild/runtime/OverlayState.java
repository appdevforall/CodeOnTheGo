package com.itsaky.androidide.quickbuild.runtime;

/**
 * What the status overlay currently shows. ERROR-ONLY by design (plan A1): success and in-progress states render NOTHING - the overlay exists solely to keep the never-stale invariant honest when a build fails or a payload crashes, plus a one-time gesture discoverability hint. Immutable and DERIVED-then-rendered - the overlay always renders the latest state object and every terminal event installs a new state, so a transient state can never get stuck on screen (the prototype's stuck banner is a named regression, plan 1.4).
 */
final class OverlayState {

	static OverlayState buildFailed(BuildStatus status) {
		return new OverlayState(Kind.BUILD_FAILED, status.file, status.line, status.column,
				status.message, status.moreErrors);
	}

	static OverlayState crashed(String detail) {
		return new OverlayState(Kind.CRASHED, null, -1, -1, detail, 0);
	}

	static OverlayState hidden() {
		return new OverlayState(Kind.HIDDEN, null, -1, -1, null, 0);
	}

	static OverlayState hint() {
		return new OverlayState(Kind.HINT, null, -1, -1, null, 0);
	}

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

	private OverlayState(Kind kind, String file, int line, int column, String detail,
			int moreErrors) {
		this.kind = kind;
		this.file = file;
		this.line = line;
		this.column = column;
		this.detail = detail;
		this.moreErrors = moreErrors;
	}

	/** True when tapping the overlay should jump to the failing file in CoGo. */
	boolean canJumpToEditor() {
		return kind == Kind.BUILD_FAILED && file != null;
	}

	/** True for the states a successful reload / build must clear. */
	boolean isError() {
		return kind == Kind.BUILD_FAILED || kind == Kind.CRASHED;
	}

	/** Banner text. Failure copy always says the app still runs the last-good code. */
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
		default:
			return "";
		}
	}

	/** "Foo.kt:12" (short name; the full path stays in {@link #file} for the jump). */
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
		/** One-time discoverability hint for the 3-finger return gesture (plan A3). */
		HINT
	}
}
