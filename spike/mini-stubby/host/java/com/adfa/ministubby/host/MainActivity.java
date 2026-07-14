package com.adfa.ministubby.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
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
import android.view.WindowManager;
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
 *  - The payload OWNS the Activity content (loadPayload -> setContentView), so it
 *    behaves like a normal app and may call setContentView itself. The shell's
 *    "Ask Claude" button + reload-status strip live in WindowManager SUB-WINDOWS
 *    layered over the Activity (addChrome), so the payload can never wipe them.
 *  - The Ask button opens a dialog (multiline EditText + Mic + Send/Cancel). Mic
 *    uses RecognizerIntent.
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
    /** On-device compile daemon (also the Mac harness via adb reverse) — the /payload
     *  long-poll deploy channel. See {@link #startPayloadPull}. */
    private static final String COMPILE_SERVICE = "http://127.0.0.1:8378";
    private static final int REQ_SPEECH = 42;

    private TextView status;
    private Button askButton;
    private EditText askInput;   // non-null while the Ask dialog is open
    /**
     * Shell chrome (the reload-info status strip + the Ask-Claude button) lives in
     * TWO WindowManager SUB-WINDOWS layered over the Activity, NOT inside the
     * Activity's content view. That is what lets the payload be a NORMAL app that
     * owns its own content (it may call {@code host.setContentView(...)}, host its
     * own Activities, etc.) without ever wiping the shell's controls. The
     * sub-windows attach to this Activity's window token, so they cost no
     * permission (unlike {@code DevJumpService}'s system overlay) and are torn down
     * with the Activity.
     */
    private View statusChrome;
    private View buttonChrome;
    private boolean chromeAdded = false;
    private FileObserver observer;
    private ResourcesLoader currentLoader;
    private int generation = 0;

    /** Payload-pull channel state (on-device deploy). */
    private volatile int pulledGen = 0;
    private volatile boolean pulling = false;
    private Thread pullThread;
    /** Daemon-reported build time (compile+dex+pack) behind the last pulled payload,
     *  from the /payload X-Build-Ms header. Surfaced on the banner so the demo is
     *  HONEST about total edit→render cost — not just the fast detect→render reload. */
    private volatile long lastBuildMs = 0;

    /** Stable Factory2; installed once per Activity, retargeted every reload. */
    private final PayloadViewFactory payloadFactory = new PayloadViewFactory();
    /** Entry class of the CURRENTLY rendered payload (for saveState() on reload). */
    private Class<?> currentEntry;

    /** Last idle status-bar text (reload info); re-rendered with the busy prefix. */
    private String idleStatus = "waiting for payload…";
    private volatile boolean askInFlight = false;

    /**
     * Visible demo state, surfaced BY THE APP on the status banner so a viewer
     * always knows which phase we're in — the whole point of the live-reload demo:
     *   WORKING  — Claude is generating the code change (the long amber wait).
     *   RELOADED — the new payload just hot-loaded and rendered (green flash).
     *   IDLE     — nothing in flight; the thin dark strip auto-hides.
     */
    private enum Phase { IDLE, WORKING, RELOADED }
    private volatile Phase phase = Phase.IDLE;

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

        // The payload OWNS the Activity content view. The shell puts the payload's
        // View there (see loadPayload -> setContentView) and never wraps it, so the
        // payload behaves like a normal app — it can even call setContentView on its
        // own. Start with an empty placeholder until the first payload loads.
        setContentView(new FrameLayout(this));

        // Build the shell chrome (status strip + Ask button) as standalone views;
        // they get hosted in sub-windows over the Activity (see addChrome), NOT in
        // the content view, so the payload can never wipe them.
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setBackgroundColor(0xF0263238);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        // Taller strip so the phase banner reads clearly on-camera.
        status.setPadding(24, 28, 24, 28);
        renderStatus();

        askButton = new Button(this);
        askButton.setText("Ask Claude");
        askButton.setAllCaps(false);
        askButton.setOnClickListener(v -> showAskDialog());

        // Attach the chrome once the Activity window has a token (needed for the
        // sub-window). Retried from onResume as a safety net.
        getWindow().getDecorView().post(this::addChrome);

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
        startPayloadPull(payload);   // on-device deploy channel (Mac push mode: harmless)

        attachHotSwapAgent();
        startDevJump();

        if (payload.isFile()) {
            loadPayload(payload, SystemClock.uptimeMillis());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        addChrome();    // safety net: attach the chrome sub-windows if not yet up
        startDevJump(); // self-heal: re-add the overlay if its service was killed
    }

    /**
     * Attach the shell chrome (status strip + Ask button) as two sub-windows layered
     * over the Activity. Sub-windows use this Activity's window token, so they need
     * no permission and float above WHATEVER the payload draws — including after the
     * payload calls {@code setContentView} itself. Idempotent; a no-op until the
     * window has a token and once the chrome is already attached.
     */
    private void addChrome() {
        if (chromeAdded) return;
        IBinder token = getWindow().getDecorView().getWindowToken();
        if (token == null) {
            getWindow().getDecorView().post(this::addChrome);   // token not ready yet
            return;
        }
        try {
            WindowManager wm = getWindowManager();

            // Status strip: top, full width, display-only (lets touches pass through).
            WindowManager.LayoutParams sp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            sp.token = token;
            sp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(status, sp);
            statusChrome = status;

            // Ask button: bottom-end, tappable (not FLAG_NOT_TOUCHABLE). The rest of
            // the screen isn't covered by this WRAP_CONTENT window, and
            // FLAG_NOT_TOUCH_MODAL sends any touch outside it to the payload behind.
            WindowManager.LayoutParams bp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            bp.token = token;
            bp.gravity = Gravity.BOTTOM | Gravity.END;
            bp.x = 32;
            bp.y = 48;
            wm.addView(askButton, bp);
            buttonChrome = askButton;

            chromeAdded = true;
            Log.i(TAG, "shell chrome attached (status + Ask button sub-windows)");
        } catch (Throwable t) {
            Log.w(TAG, "addChrome failed", t);
        }
    }

    /** Tear the chrome sub-windows down (onDestroy). Safe to call if not attached. */
    private void removeChrome() {
        if (!chromeAdded) return;
        WindowManager wm = getWindowManager();
        if (statusChrome != null) {
            try { wm.removeView(statusChrome); } catch (Throwable ignored) {}
            statusChrome = null;
        }
        if (buttonChrome != null) {
            try { wm.removeView(buttonChrome); } catch (Throwable ignored) {}
            buttonChrome = null;
        }
        chromeAdded = false;
    }

    /** Show the floating App⇄CoGo quick-jump overlay (dev speed). No-op without the
     *  overlay grant; startService re-runs the service's onCreate only if it died. */
    private void startDevJump() {
        try {
            startService(new Intent(this, DevJumpService.class));
        } catch (Throwable t) {
            Log.w(TAG, "dev-jump overlay not started", t);
        }
    }

    /**
     * Tier 1 hardening: the shell attaches its OWN JVMTI hot-swap agent at startup
     * via {@link android.os.Debug#attachJvmtiAgent} (API 28+, debuggable app),
     * instead of relying on an external {@code am attach-agent} + symlink dance.
     * Self-attach passes the path and options as SEPARATE args, sidestepping the
     * {@code =}-in-path problem the CLI hit, and survives reinstalls (the agent is
     * bundled in the app's nativeLibraryDir). Harmless no-op if the agent isn't
     * present; Tier 1 just stays unavailable and the service falls back to Tier 2.
     */
    /** Process-wide guard: a JVMTI agent attaches once per PROCESS, not per
     *  Activity. Without this, an Activity recreation (rotation, config change)
     *  would attach a second agent → two watch threads racing on the trigger. */
    private static boolean sAgentAttached = false;

    private void attachHotSwapAgent() {
        if (sAgentAttached) return;
        try {
            File real = new File(getApplicationInfo().nativeLibraryDir, "libhotswap.so");
            if (!real.isFile()) {
                Log.i(TAG, "hot-swap agent not bundled; Tier 1 unavailable");
                return;
            }
            File dir = new File(getFilesDir(), "hotswap");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // Debug.attachJvmtiAgent REJECTS '=' in the agent path, and the
            // nativeLibraryDir path contains '==' (base64 padding). Attach via a
            // '='-free symlink in files/ whose target is the real .so in the
            // exec-allowed lib dir (avoids the W^X restriction on loading from files/).
            File link = new File(dir, "agent.so");
            //noinspection ResultOfMethodCallIgnored
            link.delete();
            android.system.Os.symlink(real.getAbsolutePath(), link.getAbsolutePath());
            android.os.Debug.attachJvmtiAgent(
                    link.getAbsolutePath(), dir.getAbsolutePath(), getClassLoader());
            sAgentAttached = true;
            Log.i(TAG, "hot-swap agent attached (Tier 1 available)");
        } catch (Throwable t) {
            Log.w(TAG, "hot-swap agent attach failed; Tier 1 unavailable", t);
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

        final ClassLoader previousLoader = PayloadRuntime.getClassLoader();
        final ResourcesLoader previousResLoader = currentLoader;
        final int previousThemeRes = PayloadRuntime.getThemeRes();
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
            // Publish to the shared runtime so a later ProxyActivity attaches the
            // SAME 0x80 table to its own Resources.
            PayloadRuntime.setResourcesLoader(loader);

            // ---- theme ----
            // Material3/AppCompat widgets call ThemeEnforcement.checkTheme() and
            // throw unless the Activity theme descends from Theme.MaterialComponents.
            // The shell's own theme is Theme.DeviceDefault, so adopt the payload's
            // declared theme. We read it from the payload apk's manifest
            // (applicationInfo.theme) — works for any app that declares
            // android:theme, no special payload method required — and merge it into
            // the Activity theme. The style id is a 0x80 id, resolvable now that the
            // payload table is merged via ResourcesLoader above.
            applyPayloadTheme(copy);

            // ---- code ----
            // Native libs: /sdcard and the read-only apk are non-exec / not a
            // valid nativeLibraryDir, so extract this generation's
            // lib/<abi>/*.so into a private per-gen dir and hand that to the
            // DexClassLoader as its library search path (System.loadLibrary
            // then resolves them). Covers ticket step 4.
            String nativeLibPath = extractNativeLibs(copy, generation);

            // A multidex payload apk (classes.dex + classes2.dex + …) needs no
            // special handling: ART's DexFile opens EVERY classesN.dex entry in
            // the zip (API 21+ DexPathList), so one DexClassLoader covers all.
            ClassLoader cl = new DexClassLoader(
                    copy.getAbsolutePath(), null, nativeLibPath, getClassLoader());
            // Retarget the shared runtime at the NEW generation before render()
            // inflates anything. This bumps PayloadRuntime's generation, so every
            // PayloadViewFactory (this Activity's and any ProxyActivity's) drops
            // its old-gen constructor cache — old-gen Constructor objects must
            // never leak across classloaders.
            PayloadRuntime.setClassLoader(cl);
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

            // The payload's View IS the Activity content. Set it only after a
            // successful render() (if render threw we're in catch and the OLD
            // content stays up — see the rollback below). The shell chrome lives in
            // sub-windows, so it survives this setContentView untouched.
            setContentView(view);

            final int gen = generation;
            final long apkBytes = copy.length();
            final int dexCount = countDexEntries(copy);
            view.post(() -> {
                long elapsed = SystemClock.uptimeMillis() - detectedAt;
                long b = lastBuildMs;
                // Honest total: the daemon's compile+dex+pack (b) PLUS the shell's
                // detect→render (elapsed). "built X + reload Y" so a viewer sees the
                // real cost, not just the flattering ~65 ms reload.
                String buildPart = b > 0 ? "built " + fmtMs(b) + " + " : "";
                String msg = "gen " + gen + " · " + buildPart + "reload " + elapsed + " ms · "
                        + formatBytes(apkBytes) + " · " + dexCount + " dex";
                idleStatus = msg;
                // EVERY reload — whether from an in-flight Ask or from a plain
                // source edit picked up by the warm compile service — flashes the
                // green "Live-reloaded!" banner the instant the new UI appears. That
                // green pop, with the "reload NN ms" metric, is the whole point of
                // the demo: a file save shows up in the running app in ~1 s.
                phase = Phase.RELOADED;
                // For a direct edit (no Ask in flight) there is no /ask reply to
                // settle the banner, so auto-settle it back to the thin "live reload
                // enabled" strip after a beat. During an Ask, sendAsk's reply owns
                // the settle, so we leave it pinned.
                if (!askInFlight) {
                    main.removeCallbacks(settleToIdle);
                    main.postDelayed(settleToIdle, 2500);
                }
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
            PayloadRuntime.setClassLoader(previousLoader);
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
                PayloadRuntime.setResourcesLoader(previousResLoader);
            }
            // Roll the THEME back too (finding #10): applyPayloadTheme already
            // published the NEW gen's style id and rebuilt the Activity theme
            // before render() threw; leaving it means a later ProxyActivity applies
            // a 0x80 id from the new table against the rolled-back old table.
            try {
                PayloadRuntime.setThemeRes(previousThemeRes);
                PayloadRuntime.applyThemeTo(this);
            } catch (Throwable themeRollback) {
                Log.w(TAG, "theme rollback after failed load also failed", themeRollback);
            }
        }

        // Old generations pile up in codeCache during a session; a real
        // implementation would prune and would also recycle classloaders.
        trimOldGenerations();
    }

    /**
     * Adopt the payload's declared application theme so Material3/AppCompat
     * widgets pass ThemeEnforcement. Reads {@code applicationInfo.theme} from the
     * payload apk's manifest (a 0x80 style id) and applies it onto a FRESH copy of
     * the Activity's base theme, so successive reloads of different payloads don't
     * accumulate each other's theme attributes.
     */
    private void applyPayloadTheme(File payloadApk) {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(
                    payloadApk.getAbsolutePath(),
                    android.content.pm.PackageManager.GET_META_DATA);
            int themeRes = (pi != null && pi.applicationInfo != null)
                    ? pi.applicationInfo.theme : 0;
            // Publish the payload theme to the shared runtime (so a ProxyActivity
            // adopts the SAME theme) and apply it to this Activity. The actual
            // fresh-base + applyStyle logic lives in PayloadRuntime.applyThemeTo,
            // which both activities share; themeRes == 0 keeps the shell theme.
            PayloadRuntime.setThemeRes(themeRes);
            PayloadRuntime.applyThemeTo(this);
        } catch (Throwable t) {
            Log.w(TAG, "applyPayloadTheme failed (continuing with shell theme)", t);
        }
    }

    /**
     * Extract native libs for the device's primary ABI from the payload apk into
     * a private per-generation directory, returning its path (or null if none).
     * The apk itself can't be a nativeLibraryDir (it's a read-only zip), and
     * /sdcard is mounted noexec, so a private-dir copy is the only loadable route.
     */
    private String extractNativeLibs(File payloadApk, int gen) {
        try {
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            File outDir = new File(getCodeCacheDir(), "nativelib-gen" + gen);
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
            String canonicalOut = outDir.getCanonicalPath() + File.separator;
            int count = 0;
            try (ZipFile zip = new ZipFile(payloadApk)) {
                String prefix = "lib/" + abi + "/";
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !name.startsWith(prefix)
                            || !name.endsWith(".so")) {
                        continue;
                    }
                    File out = new File(outDir, name.substring(prefix.length()));
                    // Zip-slip guard (#12): reject entries that escape outDir, and
                    // skip one bad entry rather than aborting the WHOLE extraction
                    // (which would drop every .so → UnsatisfiedLinkError).
                    if (!out.getCanonicalPath().startsWith(canonicalOut)) {
                        Log.w(TAG, "skipping unsafe native entry: " + name);
                        continue;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    try (InputStream in = zip.getInputStream(e);
                         OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        count++;
                    } catch (Throwable one) {
                        Log.w(TAG, "failed to extract native entry " + name + " (continuing)", one);
                    }
                }
            }
            if (count == 0) return null;
            Log.i(TAG, "extracted " + count + " native lib(s) for " + abi);
            return outDir.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "extractNativeLibs failed", t);
            return null;
        }
    }

    private void trimOldGenerations() {
        File[] files = getCodeCacheDir().listFiles();
        if (files == null) return;
        // Keep current + previous gen's apk (its loader may still back live
        // Resources). Also trim the per-gen native-lib dirs (finding #14): they
        // were never cleaned, growing codeCache unbounded over a long session.
        String keepApk = "payload-gen" + generation + ".apk";
        String keepApkPrev = "payload-gen" + (generation - 1) + ".apk";
        String keepLib = "nativelib-gen" + generation;
        String keepLibPrev = "nativelib-gen" + (generation - 1);
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("payload-gen") && n.endsWith(".apk")
                    && !n.equals(keepApk) && !n.equals(keepApkPrev)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else if (n.startsWith("nativelib-gen")
                    && !n.equals(keepLib) && !n.equals(keepLibPrev)) {
                deleteDir(f);
            }
        }
    }

    private static void deleteDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) { if (k.isDirectory()) deleteDir(k); else k.delete(); }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------------------------------------------------------------- status

    /**
     * Re-renders the status banner from the current {@link Phase}. The banner is
     * deliberately PROMINENT (full width, large text, colour-coded per phase) so a
     * viewer of the live-reload demo can always tell what's happening WITHOUT any
     * post-hoc captions:
     *   WORKING  — big amber "Claude is writing your code…" (the long generate wait)
     *   RELOADED — green "Live-reloaded!" flash the instant the new UI appears
     *   IDLE     — thin dark strip with reload stats; auto-hides after 3.5 s.
     * Main thread only.
     */
    private void renderStatus() {
        final String text;
        final int bg;
        final float sizeSp;
        switch (phase) {
            case WORKING:
                // ⏳ = hourglass
                text = "⏳  Claude is writing your code…";
                bg = 0xF0E65100;   // deep amber
                sizeSp = 17f;
                break;
            case RELOADED:
                // ✅ = check mark
                text = "✅  Live-reloaded!   " + idleStatus;
                bg = 0xF01B5E20;   // deep green
                sizeSp = 15f;
                break;
            default:
                // 🟢 = green circle — resting proof that live reload is armed.
                text = "🟢  Live reload enabled · " + idleStatus;
                bg = 0xF0263238;   // dark slate
                sizeSp = 12f;
        }
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        status.setText(text);
        status.setBackgroundColor(bg);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideStatus);
        // Only the idle strip gets out of the way; WORKING/RELOADED stay pinned so
        // the demo phase is unmistakable on camera.
        if (phase == Phase.IDLE) {
            main.postDelayed(hideStatus, 3500);
        }
    }

    /**
     * Settles the green RELOADED flash back to the thin "live reload enabled" idle
     * strip after a direct edit (no Ask in flight to own the transition).
     */
    private final Runnable settleToIdle = () -> {
        if (!askInFlight) {
            phase = Phase.IDLE;
            renderStatus();
        }
    };

    /** Hides the status strip so it stops covering the payload's top edge. */
    private final Runnable hideStatus = () -> {
        if (phase == Phase.IDLE && status != null) {
            status.setVisibility(View.GONE);
        }
    };

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
        phase = Phase.WORKING;
        askButton.setEnabled(false);
        renderStatus();
        Log.i(TAG, "ASK sent: " + prompt);

        new Thread(() -> {
            String message;
            boolean ok;
            try {
                JSONObject body = new JSONObject();
                body.put("prompt", prompt);
                // Claude runs 30–180 s (an initial full-app build can be long); keep
                // the read timeout well above that so the in-app result toast still
                // lands even on a slow build. The reload itself happens regardless.
                String resp = postJson("/ask", body.toString(), 300_000);
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
                phase = Phase.IDLE;
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

    /**
     * On-device deploy channel — the doc's Step 4, Option C (app-private dir + digest
     * handshake). A background thread long-polls the compile daemon's {@code GET
     * /payload?have=<gen>}; when a newer build exists the daemon returns the apk bytes
     * plus {@code X-Gen} and {@code X-Digest} (SHA-256). We verify the bytes against the
     * digest — refusing anything that doesn't match, so the shell never loads code it
     * can't attribute to the daemon (mitigates risk D7) — then drop the apk into our OWN
     * private {@code files/payload/payload.apk} (temp + rename). The existing FileObserver
     * fires the normal reload path; nothing else changes.
     *
     * Unified with the Mac harness: there the shell reaches the Mac daemon via
     * {@code adb reverse tcp:8378}. In Mac push mode the daemon doesn't serve /payload
     * (serveGen stays 0), so this just long-polls 204 forever and adb-push drives reloads.
     */
    private void startPayloadPull(File payload) {
        if (pulling) return;
        pulling = true;
        pullThread = new Thread(() -> {
            while (pulling) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(
                            COMPILE_SERVICE + "/payload?have=" + pulledGen).openConnection();
                    conn.setConnectTimeout(3_000);
                    conn.setReadTimeout(30_000);   // > the server's 25 s long-poll hold
                    int code = conn.getResponseCode();
                    if (code != 200) { conn.disconnect(); continue; }   // 204 no-change → re-poll

                    String genHdr = conn.getHeaderField("X-Gen");
                    String digest = conn.getHeaderField("X-Digest");
                    String buildHdr = conn.getHeaderField("X-Build-Ms");
                    if (buildHdr != null) {
                        try { lastBuildMs = Long.parseLong(buildHdr.trim()); }
                        catch (NumberFormatException ignore) {}
                    }
                    byte[] bytes;
                    try (InputStream in = conn.getInputStream()) { bytes = in.readAllBytes(); }
                    int gen = genHdr != null ? Integer.parseInt(genHdr.trim()) : pulledGen + 1;

                    if (digest != null && !sha256Hex(bytes).equalsIgnoreCase(digest.trim())) {
                        Log.w(TAG, "payload digest MISMATCH — refusing gen " + gen);
                        pulledGen = gen;   // don't re-pull the same bad gen in a tight loop
                        continue;
                    }

                    // Atomic drop into our own watched dir → FileObserver fires loadPayload.
                    File tmp = new File(payload.getParentFile(), ".incoming.tmp");
                    try (OutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
                    if (!tmp.renameTo(payload)) { copyFile(tmp, payload); tmp.delete(); }
                    pulledGen = gen;
                    Log.i(TAG, "pulled payload gen " + gen + " (" + bytes.length + " B)");
                } catch (Throwable t) {
                    // Daemon unreachable (not up yet / no reverse) → back off and retry.
                    if (conn != null) conn.disconnect();
                    try { Thread.sleep(1_000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "ministubby-payload-pull");
        pullThread.setDaemon(true);
        pullThread.start();
    }

    private static String sha256Hex(byte[] b) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
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
    //
    // The payload-classloader-aware LayoutInflater.Factory2 now lives in its own
    // top-level PayloadViewFactory (shared with ProxyActivity); it reads the
    // current payload classloader + generation from PayloadRuntime. MainActivity
    // installs its own instance in onCreate (`payloadFactory`).

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

    /** Human ms: "2.1 s" for ≥1 s, else "650 ms". Used on the honest banner. */
    private static String fmtMs(long ms) {
        if (ms >= 1000) return String.format(java.util.Locale.US, "%.1f s", ms / 1000.0);
        return ms + " ms";
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
        removeChrome();
        if (observer != null) observer.stopWatching();
        pulling = false;
        if (pullThread != null) pullThread.interrupt();
    }
}
