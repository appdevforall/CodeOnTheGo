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
 * Keeps the newest payload generation on disk so a fresh process boots it instead of the baked gen-0 baseline.
 *
 * Without it, providers and a custom Application - which instantiate before the binder connects and are never re-instantiated - would be pinned to baseline code after any process death, and the restart-based swap for services and providers could not work. Pure java.io, so the store is JVM-unit-testable.
 *
 * The dir holds {@code payload.dex}, {@code resources.arsc} and {@code assets.zip}, each optional because a deploy carries only what changed, plus {@code meta.json} with the generation and baseline fingerprint. Everything is written temp-then-rename with {@code meta.json} last, so a crash mid-persist leaves the store claiming an older generation than it serves. That is the safe direction: the host redeploys anything newer than the claimed generation, while claiming newer than served would serve stale code.
 */
final class PayloadPersistence {

	static final String DEX_FILE = "payload.dex";
	static final String ARSC_FILE = "resources.arsc";
	static final String ASSETS_FILE = "assets.zip";
	static final String META_FILE = "meta.json";

	/**
	 * Computes the key that ties a persisted payload to the baseline APK it was deployed onto: hex SHA-256 of the baseline dex bytes.
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

	private final File dir;

	PayloadPersistence(File dir) {
		this.dir = dir;
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

	File dir() {
		return dir;
	}

	/**
	 * Loads the persisted payload, or discards the store when it cannot be trusted.
	 *
	 * A fingerprint mismatch means a rebaseline or reinstall, so the payload must not outlive the baseline it was compiled against. Any mismatch or corruption deletes the store and returns null; the caller then boots the gen-0 baseline.
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
	 * Writes {@code generation} as the newest payload.
	 *
	 * A null byte array keeps the previously persisted file of that kind: deploys are per-kind deltas, and the store is cumulative so a boot always has the newest of everything. Throws on any IO failure, so the caller refuses the deploy loudly rather than leaving a boot path that silently serves older code.
	 *
	 * @return the store's payload files after the write, for callers that apply resources from the persisted copies.
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

	/** A successfully loaded persisted payload; a null {@code dex} means no code deploy was persisted. */
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

	/** The payload files currently in the store (post-persist view). */
	static final class Persisted {

		final File arscFile;
		final File assetsFile;

		Persisted(File arscFile, File assetsFile) {
			this.arscFile = arscFile;
			this.assetsFile = assetsFile;
		}
	}
}
