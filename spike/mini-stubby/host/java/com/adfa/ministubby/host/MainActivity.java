package com.adfa.ministubby.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.graphics.Color;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.json.JSONObject;

import dalvik.system.DexClassLoader;

/**
 * Mini-Stubby spike host ("shell app"). Installed ONCE. Loads the user app's
 * code + resources from an UNSIGNED, UNINSTALLED payload apk dropped into
 * filesDir/payload/payload.apk, and hot-reloads whenever the file changes.
 *
 * Payload contract (reflection, no shared jar):
 *   class app.payload.Main { public static View render(Activity host); }
 *
 * Code    -> DexClassLoader over the payload apk (read-only copy in codeCache)
 * Res     -> ResourcesLoader/ResourcesProvider (API 30+) added to the
 *            Activity's Resources; payload is linked with --package-id 0x80
 *            so its ids never collide with the host's 0x7f table.
 * Assets  -> served through the same loader apk (getAssets()).
 *
 * Phase 2 additions (ADFA-4128 component B):
 *  - Floating "Ask Claude" button overlaying the payload container. Tap opens a
 *    dialog (multiline EditText + Mic + Send/Cancel). Mic uses RecognizerIntent.
 *  - Send POSTs {"prompt":...} to the Mac orchestrator at
 *    http://127.0.0.1:8377/ask (reachable via `adb reverse`). While in flight
 *    the status bar prepends "Claude is building… ".
 *  - After every successful payload reload render, fire-and-forget
 *    POST /reloaded {"gen":N,"reloadMs":M} — the SAME numbers shown on screen —
 *    so the Mac side can compute save→rendered on one clock.
 *
 * Phase 3 additions (ADFA-4128 component E, shell v3):
 *  - Payload-classloader-aware LayoutInflater. A custom View referenced BY CLASS
 *    NAME in payload layout XML (e.g. &lt;app.payload.ui.SparklineView/&gt;) would
 *    otherwise throw ClassNotFoundException: LayoutInflater resolves view names
 *    against its Context's classloader — the HOST's — which cannot see payload
 *    dex (same wall as CoGo's PluginFragmentHelper.getPluginInflater lesson).
 *    Fix: one stable {@link PayloadViewFactory} (a LayoutInflater.Factory2) is
 *    installed ONCE per Activity instance on the Activity's own window inflater
 *    in onCreate. setFactory2 may only be called once per inflater, but the
 *    payload's classloader CHANGES every reload — so the factory itself is
 *    immutable and delegates to a mutable "current payload classloader" field
 *    the shell swaps on each reload. The payload just calls
 *    LayoutInflater.from(host): Activity.getSystemService(LAYOUT_INFLATER_SERVICE)
 *    returns that same window inflater, so the factory is already there. Dialog
 *    contexts get a cloneInContext copy of the inflater, and clones copy the
 *    factory REFERENCE — payload dialogs inflate correctly too.
 *    Tradeoff vs the ContextThemeWrapper-overriding-getSystemService pattern
 *    (CoGo plugins): the wrapper would scope the factory to payload-only
 *    inflation, but render(Activity) types the arg as a real Activity, and a
 *    wrapper's bare theme also loses the Activity theme's values (see the Maps
 *    ThemedInflater lesson). Installing on the Activity inflater is global to
 *    this Activity, so the factory MUST be a no-op passthrough (return null)
 *    for anything not defined by the payload dex — which it is: it only
 *    intercepts names whose defining classloader is the current payload loader.
 *  - State-preserving reload: before tearing down the old payload the shell
 *    calls optional {@code public static Bundle saveState()} on the OLD entry
 *    class, then prefers {@code render(Activity, Bundle)} on the new one,
 *    falling back to phase-1/2 {@code render(Activity)}.
 *  - Status bar + /reloaded body gain payload apk size and dex count
 *    ({"apkBytes":N,"dexCount":N}); multidex payloads load unchanged because
 *    ART's DexFile opens ALL classesN.dex entries of an apk (API 21+).
 */
public class MainActivity extends Activity {

    private static final String TAG = "MiniStubby";
    private static final String PAYLOAD_CLASS = "app.payload.Main";
    private static final String ORCHESTRATOR = "http://127.0.0.1:8377";
    private static final int REQ_SPEECH = 42;

    private FrameLayout container;
    private TextView status;
    private Button askButton;
    private EditText askInput;   // non-null while the Ask dialog is open
    private FileObserver observer;
    private ResourcesLoader currentLoader;
    private int generation = 0;

    /** Stable Factory2; installed once per Activity, retargeted every reload. */
    private final PayloadViewFactory payloadFactory = new PayloadViewFactory();
    /** Entry class of the CURRENTLY rendered payload (for saveState() on reload). */
    private Class<?> currentEntry;

    /** Last idle status-bar text (reload info); re-rendered with the busy prefix. */
    private String idleStatus = "waiting for payload…";
    private volatile boolean askInFlight = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Install the payload-aware Factory2 on THIS Activity's window inflater —
        // the exact instance LayoutInflater.from(host) / getLayoutInflater()
        // returns. setFactory2 throws on a second call, but each Activity
        // instance gets a fresh window inflater, so once per onCreate is safe
        // (plain android.app.Activity installs no factory of its own; the
        // fragment private-factory uses a separate slot). Reloads retarget the
        // factory's classloader field instead of reinstalling.
        getLayoutInflater().setFactory2(payloadFactory);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setBackgroundColor(0xFF263238);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(24, 16, 24, 16);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        renderStatus();

        // Overlay area: payload container fills it; the Ask-Claude button floats
        // bottom-right ON TOP of (not inside) the payload UI.
        FrameLayout overlay = new FrameLayout(this);
        root.addView(overlay, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        container = new FrameLayout(this);
        overlay.addView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        askButton = new Button(this);
        askButton.setText("Ask Claude");
        askButton.setAllCaps(false);
        askButton.setOnClickListener(v -> showAskDialog());
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        fabLp.setMargins(0, 0, 32, 32);
        overlay.addView(askButton, fabLp);

        setContentView(root);

        File payloadDir = new File(getFilesDir(), "payload");
        //noinspection ResultOfMethodCallIgnored
        payloadDir.mkdirs();
        File payload = new File(payloadDir, "payload.apk");

        // MOVED_TO: the push script writes a temp file then renames — one event
        // per complete payload. CLOSE_WRITE covers direct writes.
        observer = new FileObserver(payloadDir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (!"payload.apk".equals(path)) return;
                final long detectedAt = SystemClock.uptimeMillis();
                main.post(() -> loadPayload(payload, detectedAt));
            }
        };
        observer.startWatching();

        if (payload.isFile()) {
            loadPayload(payload, SystemClock.uptimeMillis());
        }
    }

    private void loadPayload(File src, long detectedAt) {
        // ---- salvage state from the OLD payload BEFORE tearing it down ----
        // Optional contract: public static Bundle saveState(). Absence is fine;
        // a throw must never abort the reload.
        Bundle savedState = null;
        if (currentEntry != null) {
            try {
                Method save = currentEntry.getMethod("saveState");
                Object state = save.invoke(null);
                if (state instanceof Bundle) savedState = (Bundle) state;
            } catch (NoSuchMethodException absent) {
                Log.d(TAG, "old payload has no saveState() — stateless reload");
            } catch (Throwable t) {
                Log.w(TAG, "saveState() failed — reloading without state", t);
            }
        }

        final ClassLoader previousLoader = payloadFactory.getPayloadClassLoader();
        final ResourcesLoader previousResLoader = currentLoader;
        try {
            generation++;
            // Read-only private copy: API 34+ blocks loading writable dex, and we
            // don't want the writer racing the reader.
            File copy = new File(getCodeCacheDir(), "payload-gen" + generation + ".apk");
            // A previous process left gen-N behind, marked read-only — reopening it
            // for write fails with EACCES. Clear it before copying.
            if (copy.exists() && !copy.delete()) {
                throw new IllegalStateException("cannot clear stale " + copy);
            }
            copyFile(src, copy);
            //noinspection ResultOfMethodCallIgnored
            copy.setReadOnly();

            // ---- resources ----
            ResourcesLoader loader = new ResourcesLoader();
            try (ParcelFileDescriptor pfd =
                         ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)) {
                loader.addProvider(ResourcesProvider.loadFromApk(pfd));
            }
            if (currentLoader != null) {
                getResources().removeLoaders(currentLoader);
            }
            getResources().addLoaders(loader);
            currentLoader = loader;

            // ---- code ----
            // A multidex payload apk (classes.dex + classes2.dex + …) needs no
            // special handling: ART's DexFile opens EVERY classesN.dex entry in
            // the zip (API 21+ DexPathList), so one DexClassLoader covers all.
            ClassLoader cl = new DexClassLoader(
                    copy.getAbsolutePath(), null, null, getClassLoader());
            // Retarget the inflater factory at the NEW generation before render()
            // inflates anything (this also clears the constructor cache — old-gen
            // Constructor objects must never leak across classloaders).
            payloadFactory.setPayloadClassLoader(cl);
            Class<?> entry = cl.loadClass(PAYLOAD_CLASS);

            // Entry contract v3: prefer render(Activity, Bundle); fall back to
            // the phase-1/2 render(Activity).
            View view;
            Method render2 = null;
            try {
                render2 = entry.getMethod("render", Activity.class, Bundle.class);
            } catch (NoSuchMethodException legacyPayload) {
                // fine — phase-1/2 payload
            }
            if (render2 != null) {
                view = (View) render2.invoke(null, this, savedState);
            } else {
                view = (View) entry.getMethod("render", Activity.class).invoke(null, this);
            }
            currentEntry = entry;

            container.removeAllViews();
            container.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            final int gen = generation;
            final long apkBytes = copy.length();
            final int dexCount = countDexEntries(copy);
            view.post(() -> {
                long elapsed = SystemClock.uptimeMillis() - detectedAt;
                String msg = "gen " + gen + " · reload " + elapsed + " ms · "
                        + formatBytes(apkBytes) + " · " + dexCount + " dex (detect→rendered)";
                idleStatus = msg;
                renderStatus();
                Log.i(TAG, "RELOADED " + msg);
                notifyReloaded(gen, elapsed, apkBytes, dexCount);
            });
            Log.i(TAG, "payload gen " + gen + " loaded, awaiting first frame");
        } catch (Throwable t) {
            Log.e(TAG, "payload load failed", t);
            idleStatus = "load FAILED — " + t;
            renderStatus();
            // The NEW load failed and the OLD payload's view tree is still on
            // screen; point the factory back at the loader that built it, so a
            // lazy inflate by the old payload (e.g. a ListView row) resolves
            // classes from its OWN generation instead of the half-loaded one.
            payloadFactory.setPayloadClassLoader(previousLoader);
            // Roll the RESOURCES back too: if the failure happened after the
            // loader swap (e.g. the new payload's render() threw), the old view
            // tree would otherwise keep resolving its 0x80 ids against the NEW
            // generation's table — wrong resources or Resources$NotFoundException
            // if ids shifted between builds. Same-gen code + resources, or bust.
            if (currentLoader != previousResLoader) {
                try {
                    if (currentLoader != null) {
                        getResources().removeLoaders(currentLoader);
                    }
                    if (previousResLoader != null) {
                        getResources().addLoaders(previousResLoader);
                    }
                } catch (Throwable rollback) {
                    Log.w(TAG, "resource rollback after failed load also failed", rollback);
                }
                currentLoader = previousResLoader;
            }
        }

        // Old generations pile up in codeCache during a session; a real
        // implementation would prune and would also recycle classloaders.
        trimOldGenerations();
    }

    private void trimOldGenerations() {
        File[] files = getCodeCacheDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("payload-gen") && n.endsWith(".apk")
                    && !n.equals("payload-gen" + generation + ".apk")
                    // keep the previous gen: its loader may still back live Resources
                    && !n.equals("payload-gen" + (generation - 1) + ".apk")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ---------------------------------------------------------------- status

    /** Re-renders the status bar from idleStatus + the in-flight flag. Main thread only. */
    private void renderStatus() {
        String prefix = askInFlight ? "Claude is building… " : "";
        status.setText("mini-stubby: " + prefix + idleStatus);
    }

    // ------------------------------------------------------------ ask dialog

    private void showAskDialog() {
        final EditText input = new EditText(this);
        input.setHint("What should Claude change?");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);

        Button mic = new Button(this);
        mic.setText("Mic");
        mic.setAllCaps(false);

        Button send = new Button(this);
        send.setText("Send");
        send.setAllCaps(false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(mic, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(send, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ask Claude")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnDismissListener(d -> askInput = null);

        mic.setOnClickListener(v -> startSpeechRecognition());
        send.setOnClickListener(v -> {
            String prompt = input.getText().toString().trim();
            if (prompt.isEmpty()) {
                Toast.makeText(this, "Type or dictate a request first", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            sendAsk(prompt);
        });

        askInput = input;
        dialog.show();
    }

    private void startSpeechRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the change you want");
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "No speech recognizer available — type instead",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        //noinspection deprecation
        startActivityForResult(intent, REQ_SPEECH);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        EditText input = askInput;
        if (input != null) {
            input.setText(results.get(0));
            input.setSelection(input.getText().length());
        }
    }

    // ------------------------------------------------------------- ask flow

    private void sendAsk(String prompt) {
        askInFlight = true;
        askButton.setEnabled(false);
        renderStatus();
        Log.i(TAG, "ASK sent: " + prompt);

        new Thread(() -> {
            String message;
            boolean ok;
            try {
                JSONObject body = new JSONObject();
                body.put("prompt", prompt);
                // Claude runs 30–120 s; be generous on the read timeout.
                String resp = postJson("/ask", body.toString(), 180_000);
                JSONObject json = new JSONObject(resp);
                ok = "ok".equals(json.optString("status"));
                message = json.optString("message", ok ? "done" : "unknown error");
            } catch (Throwable t) {
                ok = false;
                message = "ask failed — " + t;
                Log.e(TAG, "ASK failed", t);
            }
            final boolean fOk = ok;
            final String fMsg = message;
            main.post(() -> {
                askInFlight = false;
                askButton.setEnabled(true);
                idleStatus = (fOk ? "claude: " : "claude ERROR: ") + fMsg;
                renderStatus();
                Toast.makeText(this, fMsg, Toast.LENGTH_LONG).show();
                Log.i(TAG, "ASK reply ok=" + fOk + ": " + fMsg);
            });
        }, "ministubby-ask").start();
    }

    /**
     * Fire-and-forget POST /reloaded with the SAME gen + reloadMs shown in the
     * status bar, so the Mac side can compute save→rendered end-to-end.
     * Never blocks or crashes the UI on network failure.
     */
    private void notifyReloaded(int gen, long reloadMs, long apkBytes, int dexCount) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("gen", gen);
                body.put("reloadMs", reloadMs);
                body.put("apkBytes", apkBytes);
                body.put("dexCount", dexCount);
                postJson("/reloaded", body.toString(), 5_000);
            } catch (Throwable t) {
                Log.w(TAG, "POST /reloaded failed (orchestrator down?): " + t);
            }
        }, "ministubby-reloaded").start();
    }

    /** Blocking JSON POST to the orchestrator. Call from a background thread. */
    private static String postJson(String path, String json, int readTimeoutMs) throws Exception {
        HttpURLConnection conn =
                (HttpURLConnection) new URL(ORCHESTRATOR + path).openConnection();
        try {
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (stream != null) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                }
            }
            if (code >= 400) {
                throw new Exception("HTTP " + code + ": " + sb);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------- payload-aware view inflation

    /**
     * LayoutInflater.Factory2 that resolves custom-View class names in payload
     * layout XML against the CURRENT payload DexClassLoader.
     *
     * Design constraints it satisfies:
     *  - setFactory2 can be called only once per inflater, but the payload's
     *    classloader changes every hot reload → this object is installed once
     *    and never replaced; {@link #setPayloadClassLoader} swaps the volatile
     *    target (and clears the constructor cache) on each reload. Inflater
     *    clones (dialogs) copy the factory REFERENCE, so they stay current too.
     *  - Framework widgets must keep inflating through the default path →
     *    return null for anything this factory doesn't own: unqualified names
     *    ("TextView"), classes the payload loader can't find, non-Views, and —
     *    crucially — classes whose DEFINING loader is not the payload loader
     *    (parent-first delegation means the payload loader "finds"
     *    android.widget.* too; deferring those to the inflater keeps the
     *    shell's behavior for host/framework classes byte-identical).
     *  - Constructor lookups are cached per generation (ConcurrentHashMap:
     *    inflation is main-thread, but cloned inflaters make no such promise).
     */
    private static final class PayloadViewFactory implements LayoutInflater.Factory2 {

        private volatile ClassLoader payloadClassLoader;
        private final Map<String, Constructor<? extends View>> ctorCache =
                new ConcurrentHashMap<>();

        ClassLoader getPayloadClassLoader() {
            return payloadClassLoader;
        }

        void setPayloadClassLoader(ClassLoader cl) {
            payloadClassLoader = cl;
            ctorCache.clear(); // old-gen constructors must not outlive their loader
        }

        @Override
        public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
            // Unqualified names are framework widgets ("TextView") — the default
            // path prepends android.widget./android.view. etc. Not ours.
            if (name.indexOf('.') < 0) return null;
            ClassLoader cl = payloadClassLoader;
            if (cl == null) return null;

            Constructor<? extends View> ctor = ctorCache.get(name);
            if (ctor == null) {
                Class<?> clazz;
                try {
                    clazz = cl.loadClass(name);
                } catch (ClassNotFoundException notPayload) {
                    return null; // let the default path try (and produce its own error)
                }
                // Parent-first delegation resolves framework/host classes too;
                // only intercept classes the payload dex actually DEFINES.
                if (clazz.getClassLoader() != cl || !View.class.isAssignableFrom(clazz)) {
                    return null;
                }
                try {
                    ctor = clazz.asSubclass(View.class)
                            .getConstructor(Context.class, AttributeSet.class);
                } catch (NoSuchMethodException e) {
                    // A payload View without the XML (Context, AttributeSet)
                    // constructor is a payload bug — surface it clearly instead
                    // of the default path's misleading ClassNotFoundException.
                    throw new RuntimeException("payload view " + name
                            + " lacks the (Context, AttributeSet) constructor required"
                            + " for XML inflation", e);
                }
                ctorCache.put(name, ctor);
            }
            try {
                return ctor.newInstance(context, attrs);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("payload view " + name + " failed to instantiate", e);
            }
        }

        @Override
        public View onCreateView(String name, Context context, AttributeSet attrs) {
            return onCreateView(null, name, context, attrs);
        }
    }

    // ---------------------------------------------------------------- misc

    /** Counts classes.dex, classes2.dex, … entries in the payload apk. */
    private static int countDexEntries(File apk) {
        int n = 0;
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().matches("classes[0-9]*\\.dex")) n++;
            }
        } catch (Exception e) {
            Log.w(TAG, "dex count failed for " + apk, e);
        }
        return n;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_048_576) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0);
        }
        return ((bytes + 512) >> 10) + " KB";
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (observer != null) observer.stopWatching();
    }
}
