package org.adfa.cogo.debug;

import android.app.Application;
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
        Log.d(TAG, "init: app=" + app);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "Debugger agent is only supported on API >= 28");
            return;
        }

        try {
            Log.i(TAG, "Attaching debugger agent: libName=" + jdwpLib + ", options=" + jdwpOptions);
            Debug.attachJvmtiAgent("lib" + jdwpLib + ".so", jdwpOptions, app.getClassLoader());
        } catch (Throwable err) {
            Log.e(TAG, "Failed to attach JVM TI agent", err);
        }
    }
}
