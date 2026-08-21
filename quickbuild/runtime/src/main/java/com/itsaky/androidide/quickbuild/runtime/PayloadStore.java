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
 * A singleton, since there is exactly one live generation per process. Generation and loader travel together in an immutable {@link Payload} swapped atomically, so a reader can never see generation N with generation N-1's classes.
 *
 * The dex loads through {@link InMemoryDexClassLoader} with the APK classloader as parent: framework and androidx classes resolve from the APK while user classes exist only in the payload, so parent-first delegation cannot serve a stale user class.
 *
 * At boot {@link #ensureBaseline} loads the baked baseline dex at its stamped generation ({@link BaselineGeneration}), then swaps in a newer persisted generation ({@link PayloadPersistence}); otherwise a relaunched process would pin its providers and custom Application to baseline code.
 */
final class PayloadStore {

	/** The process-wide store; the factory and the deploy path must see the same loader. */
	static final PayloadStore INSTANCE = new PayloadStore();

	/** Where the proxy app build bakes the baseline payload into the proxy app APK. */
	static final String BASELINE_ASSET = "assets/quickbuild/gen-0.dex";

	/** Sibling of {@link BASELINE_ASSET}: the baked baseline's stamped generation. */
	static final String BASELINE_GENERATION_ASSET = "assets/quickbuild/baseline-generation.txt";

	/** Store dir for the persisted newest payload, relative to the app's filesDir. */
	static final String PERSIST_DIR = "quickbuild/payload";

	/** Generation of an UNSTAMPED baked baseline; a stamped one boots at its stamp instead. */
	static final long BASELINE_GENERATION = BaselineGeneration.UNSTAMPED;

	/**
	 * Derives the persist dir without a Context, because none exists when the factory first runs.
	 *
	 * Takes the package name from /proc/self/cmdline - the default process name is the applicationId, and the manifest transformer rejects android:process - and the user id from the uid.
	 *
	 * @return the store directory, or null when the derivation fails; {@link #attachPersistence} heals that later
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

	/** The live generation and its loader; volatile so binder and main threads see swaps at once. */
	private volatile Payload current;

	/** The base APK's loader, the parent of every payload loader. */
	private ClassLoader apkClassLoader;

	/** Latches {@link #ensureBaseline} so the baseline loads once, even after a failure. */
	private boolean baselineAttempted;

	/** The persisted-payload store, resolved at boot or late-bound from a Context. */
	private volatile PayloadPersistence persistence;

	/** Fingerprint of the loaded baseline dex, the key a persisted payload must match. */
	private volatile String baselineFingerprint;

	/** Persisted resource payloads found at boot, pending application once a Context exists. */
	private volatile PayloadPersistence.Loaded pendingBootResources;

	/** The persisted generation this process adopted at boot, or -1 when it booted the baked baseline. */
	private volatile long bootedPersistedGeneration = -1;

	private PayloadStore() {}

	/**
	 * Swaps in a new payload atomically, if it is strictly newer than the running one.
	 *
	 * A null {@code dex}, meaning a resources or assets-only deploy, keeps the current classes and only advances the generation.
	 *
	 * @param generation
	 *            the incoming generation; only a strictly newer one is accepted
	 * @param dex
	 *            the payload dex, or null for a resources or assets-only deploy
	 * @return true when the payload was accepted and is now current; false for a stale generation or when no baseline was ever loaded, in which case nothing changed
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
	 *
	 * @param context
	 *            any context with a real filesDir, normally the first activity's; also a no-op before a baseline exists, since there would be no fingerprint to gate a load
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

	/**
	 * The baseline's fingerprint, or null while no baseline is loaded.
	 *
	 * @return the key a persisted payload must match to be adopted
	 */
	String baselineFingerprint() {
		return baselineFingerprint;
	}

	/**
	 * The generation this process took from the store rather than from the APK.
	 *
	 * A restart deploy leaves no reload pending in the process that boots its work, so this is the only handle the crash guard has on what a startup crash is about. The baked baseline is excluded deliberately: it is the code the installed APK carries, so refusing it would leave the app nothing at all to boot.
	 *
	 * @return the adopted persisted generation, or -1 when the process booted the baked baseline
	 */
	long bootedPersistedGeneration() {
		return bootedPersistedGeneration;
	}

	/**
	 * The current payload classloader, or null when no payload is live (runtime inert).
	 *
	 * @return the loader every component should be instantiated through, or null to fall back to the framework default
	 */
	ClassLoader classLoader() {
		Payload payload = current;
		return payload == null ? null : payload.classLoader;
	}

	/**
	 * Loads the baked baseline from the APK once, at its stamped generation, then swaps in a newer persisted generation if one matches it.
	 *
	 * Reads the asset through the classloader, not a Context, since the factory runs before any Context exists. A missing baseline leaves the store inert, so lookups fall back to the default classloader instead of crashing an app the AAR was wrongly injected into.
	 *
	 * @param apkLoader
	 *            the base APK's classloader, retained as the parent of every payload loader; null is ignored, and only the first non-null call has any effect
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
			// The sibling stamp asset carries the generation the host allocated for this
			// baseline; without it (older plugin) the baseline is generation 0 as before.
			long baselineGeneration = BaselineGeneration.read(apkLoader.getResourceAsStream(BASELINE_GENERATION_ASSET));
			current = new Payload(baselineGeneration,
					new InMemoryDexClassLoader(ByteBuffer.wrap(dex), apkLoader));
			RuntimeLog.i("baseline payload loaded (" + dex.length + " bytes, gen "
					+ baselineGeneration + ")");
			baselineFingerprint = PayloadPersistence.fingerprint(dex);
			loadPersisted(apkLoader, baselineGeneration);
		} catch (Throwable error) {
			RuntimeLog.e("failed to load baseline payload; runtime inert", error);
			current = null;
		} finally {
			Streams.closeQuietly(in);
		}
	}

	/**
	 * The generation the app currently runs: the baked baseline's stamped generation until a newer payload lands, or 0 while the store is inert.
	 *
	 * @return the running generation, which the client reports to CoGo on every connect
	 */
	long generation() {
		Payload payload = current;
		return payload == null ? BASELINE_GENERATION : payload.generation;
	}

	/**
	 * The persisted-payload store, or null when unavailable (deploys must then fail loudly on restart).
	 *
	 * @return the store to persist through, or null when neither boot nor {@link #attachPersistence} could resolve a directory
	 */
	PayloadPersistence persistence() {
		return persistence;
	}

	/**
	 * Rolls back to a {@link #snapshot} after a failed reload.
	 *
	 * The app then visibly runs the old generation, and the host hears about it via reportCrash, rather than claiming a generation whose classes never rendered.
	 *
	 * @param payload
	 *            the value {@link #snapshot} returned before the failed apply; restored verbatim, null included
	 */
	synchronized void restore(Payload payload) {
		current = payload;
	}

	/**
	 * Snapshot for rollback: pair with {@link #restore} when a reload fails.
	 *
	 * @return the live payload, or null when none is; safe to hold because it is immutable
	 */
	synchronized Payload snapshot() {
		return current;
	}

	/**
	 * Persisted resource payloads found at boot; null after the first call (one consumer).
	 *
	 * @return the boot-time payload whose resources still need applying, or null when there was none or it has already been taken
	 */
	synchronized PayloadPersistence.Loaded takePendingBootResources() {
		PayloadPersistence.Loaded pending = pendingBootResources;
		pendingBootResources = null;
		return pending;
	}

	/**
	 * Adopts a matching persisted payload's generation and classes now, before any provider or Application instantiates.
	 *
	 * Resource payloads cannot apply without a Context, so they are stashed for {@link #takePendingBootResources}. Any failure keeps the baked baseline, which is always safe.
	 *
	 * @param apkLoader
	 *            the base APK's classloader, the parent of the loader built from the persisted dex; must be the same one the baseline was read through
	 * @param baselineGeneration
	 *            the baked baseline's stamped generation; only a strictly newer persisted payload is adopted
	 */
	private void loadPersisted(ClassLoader apkLoader, long baselineGeneration) {
		try {
			File dir = defaultPersistDir();
			if (dir == null) {
				RuntimeLog.w("cannot derive data dir pre-Context; booting the baked baseline");
				return;
			}
			PayloadPersistence store = new PayloadPersistence(dir);
			persistence = store;
			// The stamped-generation gate lives in PersistedSelection so it stays JVM-tested.
			PayloadPersistence.Loaded loaded = PersistedSelection.selectPersisted(baselineGeneration,
					store, baselineFingerprint);
			if (loaded == null) {
				return;
			}
			ClassLoader loader = loaded.dex == null
					// Resource-only generations persisted with no code deploy: the
					// baseline classes ARE current, only the generation label advances.
					? current.classLoader
					: new InMemoryDexClassLoader(ByteBuffer.wrap(loaded.dex), apkLoader);
			current = new Payload(loaded.generation, loader);
			pendingBootResources = loaded;
			// The crash guard's only handle on a startup crash: this generation arrived from a
			// restart deploy, so nothing in this process is pending to pin the blame on.
			bootedPersistedGeneration = loaded.generation;
			RuntimeLog.i("booting persisted generation " + loaded.generation);
		} catch (Throwable error) {
			RuntimeLog.e("persisted payload unusable; booting the baked baseline", error);
		}
	}

	/** Immutable generation snapshot; swapped as one unit. */
	static final class Payload {

		/** The generation these classes came from. */
		final long generation;

		/**
		 * The loader serving that generation's classes; shared with the previous payload when the deploy carried no dex.
		 */
		final ClassLoader classLoader;

		/**
		 * @param generation
		 *            the generation these classes came from; the APK baseline boots at its stamped generation (0 when unstamped), and later deploys must only ever increase it
		 * @param classLoader
		 *            the loader to instantiate components through; never null in practice, since an inert store holds no Payload at all
		 */
		Payload(long generation, ClassLoader classLoader) {
			this.generation = generation;
			this.classLoader = classLoader;
		}
	}
}
