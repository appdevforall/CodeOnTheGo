package com.itsaky.androidide.quickbuild.runtime;

import android.content.Context;
import android.os.Build;
import dalvik.system.InMemoryDexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Owns the current payload generation and its classloader, process-wide.
 *
 * A singleton because {@link QuickBuildAppComponentFactory}, which the framework instantiates, and the deploy path need the same loader, and there is exactly one live generation per process. Generation and loader travel together in an immutable {@link Payload} swapped atomically, so a reader can never see generation N with generation N-1's classes.
 *
 * The dex loads through {@link InMemoryDexClassLoader} with the APK classloader as parent: framework and androidx classes resolve from the APK while user classes exist only in the payload, so parent-first delegation cannot serve a stale user class.
 *
 * At boot {@link #ensureBaseline} loads the baked gen-0 dex, then swaps in the newest persisted generation ({@link PayloadPersistence}); otherwise a relaunched process would pin its providers and custom Application to baseline code.
 */
final class PayloadStore {

	static final PayloadStore INSTANCE = new PayloadStore();

	/** Where the proxy app build bakes the baseline payload into the proxy app APK. */
	static final String BASELINE_ASSET = "assets/quickbuild/gen-0.dex";

	/** Store dir for the persisted newest payload, relative to the app's filesDir. */
	static final String PERSIST_DIR = "quickbuild/payload";

	static final long BASELINE_GENERATION = 0L;

	/**
	 * Derives the persist dir without a Context, because none exists when the factory first runs.
	 *
	 * Takes the package name from /proc/self/cmdline - the default process name is the applicationId, and the manifest transformer rejects android:process - and the user id from the uid.
	 *
	 * @return null when the derivation fails; {@link #attachPersistence} heals that later.
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
	 * Swaps in a new payload atomically, if it is strictly newer than the running one.
	 *
	 * A null {@code dex}, meaning a resources or assets-only deploy, keeps the current classes and only advances the generation.
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
	 * Late-binds the persistence dir from a real Context, at the first activity.
	 *
	 * Heals a boot whose pre-Context dir derivation failed; a no-op when boot already resolved it.
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
	 * Loads the gen-0 baseline from the APK once, then swaps in a newer persisted generation if one matches it.
	 *
	 * Reads the asset through the classloader rather than a Context, because the factory runs before any Context exists. A missing baseline leaves the store inert, with every lookup falling back to the default classloader, so the runtime never crashes an app it was wrongly injected into.
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
	 * Rolls back to a {@link #snapshot} after a failed reload.
	 *
	 * The app then visibly runs the old generation, and the host hears about it via reportCrash, rather than claiming a generation whose classes never rendered.
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
	 * Adopts a matching persisted payload's generation and classes now, before any provider or Application instantiates.
	 *
	 * Resource payloads cannot apply without a Context, so they are stashed for {@link #takePendingBootResources}. Any failure keeps the gen-0 baseline, which is always safe.
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
