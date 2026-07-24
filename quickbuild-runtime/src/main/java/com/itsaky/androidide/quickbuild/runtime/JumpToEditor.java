package com.itsaky.androidide.quickbuild.runtime;

import android.content.Context;
import android.content.Intent;

/**
 * Sends the tap-to-jump intent back to CoGo: an explicit activity intent handled by CoGo's {@code QuickBuildJumpActivity} trampoline, which opens the failing file at the error line in the editor. Explicit (action + package) so no other app can intercept it; best-effort - an older CoGo without the trampoline just logs, never crashes the test app.
 */
final class JumpToEditor {

	/** Must match the intent filter of CoGo's QuickBuildJumpActivity. */
	static final String ACTION_JUMP_TO_ERROR = "com.itsaky.androidide.quickbuild.action.JUMP_TO_ERROR";

	static final String EXTRA_FILE = "com.itsaky.androidide.quickbuild.extra.FILE";

	/** 1-based, as reported by the compiler. */
	static final String EXTRA_LINE = "com.itsaky.androidide.quickbuild.extra.LINE";

	/** 1-based, as reported by the compiler. */
	static final String EXTRA_COLUMN = "com.itsaky.androidide.quickbuild.extra.COLUMN";

	/** Never throws; the overlay tap must not be able to crash the app under test. */
	static void open(Context context, String file, int line, int column) {
		if (context == null || file == null) {
			return;
		}
		try {
			Intent intent = new Intent(ACTION_JUMP_TO_ERROR);
			intent.setPackage(QuickBuildClient.IDE_PACKAGE);
			intent.putExtra(EXTRA_FILE, file);
			intent.putExtra(EXTRA_LINE, line);
			intent.putExtra(EXTRA_COLUMN, column);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(intent);
		} catch (Throwable error) {
			RuntimeLog.w("jump to editor failed (older CoGo without the trampoline?)", error);
		}
	}

	private JumpToEditor() {}
}
