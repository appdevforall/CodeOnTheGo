package app.payload;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

/**
 * FieldSurvey — Mini-Stubby phase-3 stress payload (ADFA-4128, component D).
 *
 * A plausible offline field-data-collection app: survey questions come from
 * assets/questions.json, visits are recorded per site, scores trend on a
 * custom sparkline, and everything persists in SharedPreferences.
 *
 * Shell entry contract (CONTRACTS-v3.md):
 *   1. {@code public static View render(Activity host, Bundle savedState)} — preferred
 *   2. {@code public static View render(Activity host)}                    — fallback
 *   3. {@code public static Bundle saveState()} — called on the OLD generation
 *      right before teardown; the Bundle is handed to the NEW generation's
 *      render(host, savedState).
 *
 * IMPORTANT — the saveState Bundle must contain ONLY framework types
 * (String/long/int[]/…). The Bundle object crosses payload generations
 * in-process without parceling, so any payload-defined class inside it would
 * carry the OLD DexClassLoader's class into the new generation and blow up
 * with a ClassCastException on first cast.
 */
public final class Main {

    private static final String TAG = "FieldSurvey";

    /** The live controller of the CURRENT generation, so saveState() can reach it. */
    private static App current;

    private Main() {}

    /** Phase-1/2 fallback entry point. */
    public static View render(Activity host) {
        return render(host, null);
    }

    /** Preferred entry point: rebuilds the app, restoring saved UI state if given. */
    public static View render(Activity host, Bundle savedState) {
        App app = new App(host);
        current = app;
        return app.start(savedState);
    }

    /** Never throws — the shell calls this best-effort before tearing us down. */
    public static Bundle saveState() {
        try {
            App app = current;
            return app == null ? new Bundle() : app.captureState();
        } catch (Throwable t) {
            Log.w(TAG, "saveState failed; returning empty state", t);
            return new Bundle();
        }
    }
}
