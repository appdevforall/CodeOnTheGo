package com.itsaky.androidide.quickbuild.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * On-disk store of the NEWEST payload generation (component-proxying design, section 3;
 * revises plan D1's "nothing on disk"). A fresh process boots the persisted generation
 * instead of the baked gen-0 baseline - without this, providers and a custom Application
 * (which instantiate before the binder connects and are never re-instantiated) would be
 * pinned to baseline code after any process death, and the restart-based swap for
 * services/providers could not work at all.
 *
 * Layout under the store dir: {@code payload.dex}, {@code resources.arsc},
 * {@code assets.zip} (each optional - a deploy carries only what changed, and the store
 * keeps the newest file of each kind), plus {@code meta.json} holding the generation and
 * the baseline fingerprint. Files are written temp-then-rename with {@code meta.json}
 * LAST, so a crash mid-persist leaves the old meta pointing at possibly-newer payload
 * files - the store then claims an OLDER generation than it serves, which is the safe
 * direction (the host redeploys anything newer than the claimed generation; claiming
 * newer than served would be a stale-code lie).
 *
 * The fingerprint is a digest of the baked gen-0 baseline dex: a rebaseline/reinstall
 * changes it, and {@link #load} deletes a mismatching store and falls back to gen-0 -
 * a persisted payload must never outlive the baseline it was compiled against.
 *
 * Pure Java + java.io only, so the whole store is JVM-unit-testable.
 */
final class PayloadPersistence {

	static final String DEX_FILE = "payload.dex";
	static final String ARSC_FILE = "resources.arsc";
	static final String ASSETS_FILE = "assets.zip";
	static final String META_FILE = "meta.json";

	/**
	 * Hex SHA-256 of the baseline dex bytes - the key that ties a persisted payload to
	 * the exact baseline APK it was deployed onto.
	 */
	static String fingerprint(byte[] baselineDex) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(baselineDex);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException error) {
			// SHA-256 is mandatory on every Android/JVM release; treat absence as fatal
			// for persistence only (callers degrade to gen-0 boots).
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	private final File dir;

	PayloadPersistence(File dir) {
		this.dir = dir;
	}

	File dir() {
		return dir;
	}

	/** Deletes the whole store; best-effort, used when the store is untrusted. */
	void clear() {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (!file.delete()) {
					RuntimeLog.w("could not delete " + file);
				}
			}
		}
		if (dir.exists() && !dir.delete()) {
			RuntimeLog.w("could not delete " + dir);
		}
	}

	/**
	 * Loads the persisted payload when it exists, parses, and matches
	 * {@code expectedFingerprint}. Any mismatch or corruption deletes the store and
	 * returns null - the caller then boots the gen-0 baseline, which is always current
	 * for a fresh install/rebaseline.
	 */
	Loaded load(String expectedFingerprint) {
		File meta = new File(dir, META_FILE);
		if (!meta.isFile()) {
			return null;
		}
		try {
			Map<String, Object> obj = MiniJson.parseObject(readText(meta));
			Object fp = obj.get("fingerprint");
			Object gen = obj.get("generation");
			if (!(fp instanceof String) || !(gen instanceof String)) {
				throw new IOException("meta.json missing fingerprint/generation");
			}
			if (!fp.equals(expectedFingerprint)) {
				RuntimeLog.i("persisted payload is for another baseline; discarding");
				clear();
				return null;
			}
			long generation = Long.parseLong((String) gen);
			File dexFile = new File(dir, DEX_FILE);
			byte[] dex = dexFile.isFile() ? readBytes(dexFile) : null;
			File arsc = new File(dir, ARSC_FILE);
			File assets = new File(dir, ASSETS_FILE);
			return new Loaded(generation, dex,
					arsc.isFile() ? arsc : null,
					assets.isFile() ? assets : null);
		} catch (Throwable error) {
			RuntimeLog.e("unreadable persisted payload; discarding", error);
			clear();
			return null;
		}
	}

	/**
	 * Persists {@code generation} as the newest payload. Null byte arrays keep the
	 * previously persisted file of that kind (deploys are deltas per payload kind; the
	 * store is cumulative so a boot always has the newest of everything). Throws on any
	 * IO failure so the caller can refuse the deploy LOUDLY instead of leaving a boot
	 * path that would silently serve older code.
	 *
	 * @return the store's current payload files after the write, for callers that apply
	 *   resources from the persisted copies.
	 */
	Persisted persist(long generation, String fingerprint, byte[] dex, byte[] arsc,
			byte[] assetsZip) throws IOException {
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("cannot create " + dir);
		}
		if (dex != null) {
			writeAtomic(new File(dir, DEX_FILE), dex);
		}
		if (arsc != null) {
			writeAtomic(new File(dir, ARSC_FILE), arsc);
		}
		if (assetsZip != null) {
			writeAtomic(new File(dir, ASSETS_FILE), assetsZip);
		}
		// Meta last: see the class doc for why this ordering is the safe crash window.
		String meta = "{\"generation\":\"" + generation + "\",\"fingerprint\":\""
				+ fingerprint + "\"}";
		writeAtomic(new File(dir, META_FILE), meta.getBytes(StandardCharsets.UTF_8));
		File arscFile = new File(dir, ARSC_FILE);
		File assetsFile = new File(dir, ASSETS_FILE);
		return new Persisted(
				arscFile.isFile() ? arscFile : null,
				assetsFile.isFile() ? assetsFile : null);
	}

	private static byte[] readBytes(File file) throws IOException {
		InputStream in = new FileInputStream(file);
		try {
			return Streams.readFully(in);
		} finally {
			Streams.closeQuietly(in);
		}
	}

	private static String readText(File file) throws IOException {
		return new String(readBytes(file), StandardCharsets.UTF_8);
	}

	private static void writeAtomic(File target, byte[] bytes) throws IOException {
		File temp = new File(target.getParentFile(), target.getName() + ".tmp");
		FileOutputStream out = new FileOutputStream(temp);
		try {
			out.write(bytes);
			out.getFD().sync();
		} finally {
			Streams.closeQuietly(out);
		}
		if (!temp.renameTo(target)) {
			// rename over an existing file is atomic on POSIX; a failure here is a
			// filesystem oddity - fall back to delete+rename before giving up.
			if (!target.delete() || !temp.renameTo(target)) {
				throw new IOException("cannot rename " + temp + " to " + target);
			}
		}
	}

	/** The payload files currently in the store (post-persist view). */
	static final class Persisted {

		final File arscFile;
		final File assetsFile;

		Persisted(File arscFile, File assetsFile) {
			this.arscFile = arscFile;
			this.assetsFile = assetsFile;
		}
	}

	/** A successfully loaded persisted payload. [dex] null = no code deploy persisted. */
	static final class Loaded {

		final long generation;
		final byte[] dex;
		final File arscFile;
		final File assetsFile;

		Loaded(long generation, byte[] dex, File arscFile, File assetsFile) {
			this.generation = generation;
			this.dex = dex;
			this.arscFile = arscFile;
			this.assetsFile = assetsFile;
		}
	}
}
