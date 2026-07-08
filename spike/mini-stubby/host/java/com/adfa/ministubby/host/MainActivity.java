package com.adfa.ministubby.host;

import android.app.Activity;
import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.graphics.Color;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

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
 */
public class MainActivity extends Activity {

    private static final String TAG = "MiniStubby";
    private static final String PAYLOAD_CLASS = "app.payload.Main";

    private FrameLayout container;
    private TextView status;
    private FileObserver observer;
    private ResourcesLoader currentLoader;
    private int generation = 0;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setText("mini-stubby: waiting for payload…");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setBackgroundColor(0xFF263238);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(24, 16, 24, 16);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

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
                status.setText("mini-stubby: " + msg);
                Log.i(TAG, "RELOADED " + msg);
            });
            Log.i(TAG, "payload gen " + gen + " loaded, awaiting first frame");
        } catch (Throwable t) {
            Log.e(TAG, "payload load failed", t);
            status.setText("mini-stubby: load FAILED — " + t);
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
