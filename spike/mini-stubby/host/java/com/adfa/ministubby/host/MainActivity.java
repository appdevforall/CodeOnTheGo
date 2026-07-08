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
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

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

    /** Last idle status-bar text (reload info); re-rendered with the busy prefix. */
    private String idleStatus = "waiting for payload…";
    private volatile boolean askInFlight = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            ClassLoader cl = new DexClassLoader(
                    copy.getAbsolutePath(), null, null, getClassLoader());
            Class<?> entry = cl.loadClass(PAYLOAD_CLASS);
            Method render = entry.getMethod("render", Activity.class);
            View view = (View) render.invoke(null, this);

            container.removeAllViews();
            container.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            final int gen = generation;
            view.post(() -> {
                long elapsed = SystemClock.uptimeMillis() - detectedAt;
                String msg = "gen " + gen + " · reload " + elapsed + " ms (detect→rendered)";
                idleStatus = msg;
                renderStatus();
                Log.i(TAG, "RELOADED " + msg);
                notifyReloaded(gen, elapsed);
            });
            Log.i(TAG, "payload gen " + gen + " loaded, awaiting first frame");
        } catch (Throwable t) {
            Log.e(TAG, "payload load failed", t);
            idleStatus = "load FAILED — " + t;
            renderStatus();
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
    private void notifyReloaded(int gen, long reloadMs) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("gen", gen);
                body.put("reloadMs", reloadMs);
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

    // ---------------------------------------------------------------- misc

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
