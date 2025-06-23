package org.adfa.cogo.debug;

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

/**
 * Handles debugger integration in remote VMs (applications being debugged).
 * @noinspection unused
 */
public class DebugManager {

    private static final String TAG = "DebugManager";

    private DebugManager() {
        throw new UnsupportedOperationException();
    }

    /**
     * Initializes the debugger.
     *
     * @param app         The application instance.
     * @param jdwpLib     The name of the JDWP library.
     * @param jdwpOptions The JDWP options.
     */
    public static void init(
            Application app,
            String jdwpLib,
            String jdwpOptions
    ) {
        Log.d(TAG, "init");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "Debugger agent is only supported on API >= 28");
            return;
        }

        try {
            checkDebuggerActive(app);
        } catch (Throwable err) {
            Log.e(TAG, "Not attaching debugger because: " + err.getMessage());
            return;
        }

        try {
            Log.i(TAG, "Attaching debugger agent: libName=" + jdwpLib + ", options=" + jdwpOptions);
            Debug.attachJvmtiAgent("lib" + jdwpLib + ".so", jdwpOptions, app.getClassLoader());
        } catch (Throwable err) {
            Log.e(TAG, "Failed to attach JVM TI agent", err);
        }
    }

    private static void checkDebuggerActive(Application app) {
        final Uri uri = Uri.parse("content://org.adfa.cogo.debugger/status");
        try (final Cursor cursor = app.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IllegalStateException("Debugger is not active");
            }

            final String status = cursor.getString(0);
            if (!"active".equals(status)) {
                throw new IllegalStateException("Debugger is not active");
            }
        }
    }
}
