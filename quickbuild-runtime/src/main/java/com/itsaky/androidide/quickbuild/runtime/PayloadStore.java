package com.itsaky.androidide.quickbuild.runtime;

import android.content.Context;
import android.os.Build;
import dalvik.system.InMemoryDexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Process-wide owner of the CURRENT payload generation and its classloader.
 *
 * Why a singleton: {@link QuickBuildAppComponentFactory} (instantiated by the framework) and the deploy path both need the same loader; there is exactly one live generation per process, so one volatile snapshot is the natural shape. Generation + loader travel together in an immutable {@link Payload} swapped atomically - a reader can never observe generation N with generation N-1's classes.
 *
 * The dex is read fully into a ByteBuffer and loaded through {@link InMemoryDexClassLoader} with the APK classloader as parent: framework/androidx resolve from the APK, user classes exist ONLY in the payload, so parent-first delegation cannot serve a stale user class.
 *
 * Boot: {@link #ensureBaseline} loads the baked gen-0 baseline, then swaps in the NEWEST persisted generation ({@link PayloadPersistence}, component-proxying design section 3 - revising plan D1's "nothing on disk"). Without that, a killed-and-relaunched process would pin its providers and custom Application (instantiated before any binder catch-up, never re-instantiated) to baseline code - silently stale. The persisted store is fingerprint-keyed to the baseline dex, so a rebaseline/reinstall discards it.
 */
final class PayloadStore {

	static final PayloadStore INSTANCE = new PayloadStore();

	/** Where the setup build bakes the baseline payload into the test APK. */
	static final String BASELINE_ASSET = "assets/quickbuild/gen-0.dex";

	/** Store dir for the persisted newest payload, relative to the app's filesDir. */
	static final String PERSIST_DIR = "quickbuild/payload";

	static final long BASELINE_GENERATION = 0L;

	/**
	 * The app's files dir derived WITHOUT a Context (none exists when the factory first runs): package name from /proc/self/cmdline - the default process name IS the applicationId, and the manifest transformer rejects android:process - and the user id from the uid. Null when the derivation fails; {@link #attachPersistence} heals that later.
	 */
	private static File defaultPersistDir() {
		InputStream in = null;
		try {
			in = new FileInputStream("/proc/self/cmdline");
			String cmdline = new String(Streams.readFully(in), "UTF-8");
			int nul = cmdline.indexOf('\0');
			String pkg = (nul >= 0 ? cmdline.substring(0, nul) : cmdline).trim();
			if (pkg.isEmpty()) {
				return null;
			}
			int userId = android.os.Process.myUid() / 100000;
			File dataDir = new File("/data/user/" + userId + "/" + pkg);
			if (!dataDir.isDirectory()) {
				return null;
			}
			return new File(dataDir, "files/" + PERSIST_DIR);
		} catch (Throwable error) {
			RuntimeLog.w("cmdline data-dir derivation failed: " + error);
			return null;
		} finally {
			Streams.closeQuietly(in);
		}
	}

	private volatile Payload current;
	private ClassLoader apkClassLoader;

	private boolean baselineAttempted;
	private volatile PayloadPersistence persistence;

	private volatile String baselineFingerprint;

	/** Persisted resource payloads found at boot, pending application once a Context exists. */
	private volatile PayloadPersistence.Loaded pendingBootResources;

	private PayloadStore() {}

	/** The APK classloader captured at baseline load; null until {@link #ensureBaseline}. */
	ClassLoader apkClassLoader() {
		return apkClassLoader;
	}

	/**
	 * Applies a new payload atomically. Accepted only when {@code generation} is strictly newer than the running one; a null {@code dex} (resources/assets-only deploy) keeps the current classes and advances the generation.
	 *
	 * @return true when the payload was accepted and is now current.
	 */
	synchronized boolean apply(long generation, ByteBuffer dex) {
		Payload previous = current;
		if (previous == null) {
			RuntimeLog.w("rejecting payload gen " + generation + ": no baseline loaded");
			return false;
		}
		if (!Generations.accepts(previous.generation, generation)) {
			RuntimeLog.w("rejecting stale payload gen " + generation
					+ " (running gen " + previous.generation + ")");
			return false;
		}
		ClassLoader loader = dex == null
				? previous.classLoader
				: new InMemoryDexClassLoader(dex, apkClassLoader);
		current = new Payload(generation, loader);
		return true;
	}

	/**
	 * Late-binds the persistence dir from a real Context (first activity). Heals a boot whose pre-Context dir derivation failed; a no-op when boot already resolved it.
	 */
	synchronized void attachPersistence(Context context) {
		if (persistence != null || baselineFingerprint == null) {
			return;
		}
		try {
			persistence = new PayloadPersistence(new File(context.getFilesDir(), PERSIST_DIR));
		} catch (Throwable error) {
			RuntimeLog.e("cannot attach payload persistence", error);
		}
	}

	/** The baseline's fingerprint, or null while no baseline is loaded. */
	String baselineFingerprint() {
		return baselineFingerprint;
	}

	/** The current payload classloader, or null when no payload is live (runtime inert). */
	ClassLoader classLoader() {
		Payload payload = current;
		return payload == null ? null : payload.classLoader;
	}

	/**
	 * Loads the baseline (generation 0) from the APK, once, then swaps in the newest persisted generation when one matches this baseline (see the class doc). Reads the asset through the classloader instead of a Context because the factory runs before any Context exists. A missing baseline leaves the store inert (every lookup falls back to the default classloader) - the runtime must never crash an app it was wrongly injected into.
	 */
	synchronized void ensureBaseline(ClassLoader apkLoader) {
		if (baselineAttempted || apkLoader == null) {
			return;
		}
		baselineAttempted = true;
		this.apkClassLoader = apkLoader;
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			// InMemoryDexClassLoader is API 26+. Quick Build is gated far above this,
			// but the AAR must stay inert, not crash, wherever it lands.
			RuntimeLog.w("quick build runtime inert below API 26");
			return;
		}
		InputStream in = null;
		try {
			in = apkLoader.getResourceAsStream(BASELINE_ASSET);
			if (in == null) {
				RuntimeLog.w("no baseline payload at " + BASELINE_ASSET + "; runtime inert");
				return;
			}
			byte[] dex = Streams.readFully(in);
			current = new Payload(BASELINE_GENERATION,
					new InMemoryDexClassLoader(ByteBuffer.wrap(dex), apkLoader));
			RuntimeLog.i("baseline payload loaded (" + dex.length + " bytes, gen 0)");
			baselineFingerprint = PayloadPersistence.fingerprint(dex);
			loadPersisted(apkLoader);
		} catch (Throwable error) {
			RuntimeLog.e("failed to load baseline payload; runtime inert", error);
			current = null;
		} finally {
			Streams.closeQuietly(in);
		}
	}

	/** The generation the app currently runs; baseline (0) when nothing was deployed yet. */
	long generation() {
		Payload payload = current;
		return payload == null ? BASELINE_GENERATION : payload.generation;
	}

	/** The persisted-payload store, or null when unavailable (deploys must then fail loudly on restart). */
	PayloadPersistence persistence() {
		return persistence;
	}

	/**
	 * Rolls back to a {@link #snapshot}. Keeps the never-silently-stale invariant honest: a failed reload leaves the app VISIBLY on the old generation (the host is told via reportCrash), instead of claiming a generation whose classes never rendered.
	 */
	synchronized void restore(Payload payload) {
		current = payload;
	}

	/** Snapshot for rollback: pair with {@link #restore} when a reload fails. */
	synchronized Payload snapshot() {
		return current;
	}

	/** Persisted resource payloads found at boot; null after the first call (one consumer). */
	synchronized PayloadPersistence.Loaded takePendingBootResources() {
		PayloadPersistence.Loaded pending = pendingBootResources;
		pendingBootResources = null;
		return pending;
	}

	/**
	 * Boot half of the persisted-generation contract: if a persisted payload matches this baseline, adopt its generation + classes NOW - before any provider/Application instantiates. Resource payloads cannot apply without a Context, so they are stashed for {@link #takePendingBootResources}. Best-effort: any failure keeps the gen-0 baseline (always safe for a fresh install).
	 */
	private void loadPersisted(ClassLoader apkLoader) {
		try {
			File dir = defaultPersistDir();
			if (dir == null) {
				RuntimeLog.w("cannot derive data dir pre-Context; booting baseline gen 0");
				return;
			}
			PayloadPersistence store = new PayloadPersistence(dir);
			persistence = store;
			PayloadPersistence.Loaded loaded = store.load(baselineFingerprint);
			if (loaded == null || !Generations.accepts(BASELINE_GENERATION, loaded.generation)) {
				return;
			}
			ClassLoader loader = loaded.dex == null
					// Resource-only generations persisted with no code deploy: the
					// baseline classes ARE current, only the generation label advances.
					? current.classLoader
					: new InMemoryDexClassLoader(ByteBuffer.wrap(loaded.dex), apkLoader);
			current = new Payload(loaded.generation, loader);
			pendingBootResources = loaded;
			RuntimeLog.i("booting persisted generation " + loaded.generation);
		} catch (Throwable error) {
			RuntimeLog.e("persisted payload unusable; booting baseline gen 0", error);
		}
	}

	/** Immutable generation snapshot; swapped as one unit. */
	static final class Payload {

		final long generation;
		final ClassLoader classLoader;

		Payload(long generation, ClassLoader classLoader) {
			this.generation = generation;
			this.classLoader = classLoader;
		}
	}
}
