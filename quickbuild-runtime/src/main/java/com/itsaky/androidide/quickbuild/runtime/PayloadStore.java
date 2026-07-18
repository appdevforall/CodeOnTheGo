package com.itsaky.androidide.quickbuild.runtime;

import android.os.Build;
import dalvik.system.InMemoryDexClassLoader;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Process-wide owner of the CURRENT payload generation and its classloader.
 *
 * Why a singleton: {@link QuickBuildAppComponentFactory} (instantiated by the framework) and the deploy path both need the same loader; there is exactly one live generation per process, so one volatile snapshot is the natural shape. Generation + loader travel together in an immutable {@link Payload} swapped atomically - a reader can never observe generation N with generation N-1's classes.
 *
 * The dex is read fully into a ByteBuffer and loaded through {@link InMemoryDexClassLoader} with the APK classloader as parent: framework/androidx resolve from the APK, user classes exist ONLY in the payload, so parent-first delegation cannot serve a stale user class. Nothing is ever written to disk (plan D1).
 */
final class PayloadStore {

	static final PayloadStore INSTANCE = new PayloadStore();

	/** Where the setup build bakes the baseline payload into the test APK. */
	static final String BASELINE_ASSET = "assets/quickbuild/gen-0.dex";

	static final long BASELINE_GENERATION = 0L;

	private volatile Payload current;

	private ClassLoader apkClassLoader;
	private boolean baselineAttempted;

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

	/** The current payload classloader, or null when no payload is live (runtime inert). */
	ClassLoader classLoader() {
		Payload payload = current;
		return payload == null ? null : payload.classLoader;
	}

	/**
	 * Loads the baseline (generation 0) from the APK, once. Reads the asset through the classloader instead of a Context because the factory runs before any Context exists. A missing baseline leaves the store inert (every lookup falls back to the default classloader) - the runtime must never crash an app it was wrongly injected into.
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
