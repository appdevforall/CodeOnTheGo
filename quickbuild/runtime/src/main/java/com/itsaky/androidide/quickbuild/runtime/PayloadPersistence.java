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
 * Keeps the newest payload generation on disk so a fresh process boots it rather than the baked gen-0 baseline: providers and a custom Application instantiate before the binder connects and are never re-instantiated, so otherwise they stay pinned to baseline code after any process death.
 *
 * A deploy writes only the payload kinds it carries, under generation-stamped names nothing references yet; one atomic rename of {@code meta.json} then publishes the set, so a torn write leaves unreferenced files rather than a generation mixing dex and resources from different builds. A generation that failed to apply is recorded in {@code quarantine.json} and refused by {@link #load}, so a bad payload cannot crash-loop the app where nothing can report it.
 */
final class PayloadPersistence {

	/** Layout tag {@code meta.json} must carry; any other value is a store this build cannot read. */
	static final String LAYOUT = "2";

	/** Names the generation and its payload files; its atomic rename is the publish. */
	static final String META_FILE = "meta.json";

	/** Names a generation that failed to apply, which {@link #load} then refuses. */
	static final String QUARANTINE_FILE = "quarantine.json";

	/** Payload kind: the dex carrying all user classes; absent when no code deploy landed. */
	static final String KIND_DEX = "dex";

	/** Payload kind: the relinked resource apk, despite the name; absent when no resources changed. */
	static final String KIND_ARSC = "arsc";

	/** Payload kind: the changed-assets zip; absent when no assets changed. */
	static final String KIND_ASSETS = "assets";

	/** Suffix of every generation-stamped payload file. */
	private static final String PAYLOAD_SUFFIX = ".bin";

	/** Suffix of an in-flight {@link #writeAtomic} temp file. */
	private static final String TEMP_SUFFIX = ".tmp";

	/**
	 * Computes the key that ties a persisted payload to the baseline APK it was deployed onto: hex SHA-256 of the baseline dex bytes.
	 *
	 * @param baselineDex
	 *            the whole gen-0 dex as baked into the proxy app APK
	 * @return the lowercase hex digest, which a reinstall or rebaseline changes and so invalidates the store
	 * @throws IllegalStateException
	 *             when SHA-256 is unavailable, which no supported runtime does
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

	/**
	 * The on-disk name for one kind of one generation's payload.
	 *
	 * @param kind
	 *            one of {@link #KIND_DEX}, {@link #KIND_ARSC}, {@link #KIND_ASSETS}
	 * @param generation
	 *            the generation that produced these bytes, which makes the name unique so a write can never touch a file an older generation still needs
	 * @return the file name, relative to the store directory
	 */
	static String payloadFileName(String kind, long generation) {
		return kind + "-" + generation + PAYLOAD_SUFFIX;
	}

	/**
	 * The generation stamped into a payload file name.
	 *
	 * @param name
	 *            a store directory entry name
	 * @return the generation, or -1 when {@code name} is not a generation-stamped payload file
	 */
	private static long generationOf(String name) {
		if (!name.endsWith(PAYLOAD_SUFFIX)) {
			return -1;
		}
		int dash = name.indexOf('-');
		if (dash <= 0) {
			return -1;
		}
		try {
			return Long.parseLong(name.substring(dash + 1, name.length() - PAYLOAD_SUFFIX.length()));
		} catch (NumberFormatException notAPayloadFile) {
			return -1;
		}
	}

	/**
	 * Reads a whole store file.
	 *
	 * @param file
	 *            an existing store file; opened and closed here, never created
	 * @return the whole file in memory, since every store file is payload-sized by construction
	 * @throws IOException
	 *             when the file is unreadable or exceeds the payload cap
	 */
	private static byte[] readBytes(File file) throws IOException {
		InputStream in = new FileInputStream(file);
		try {
			return Streams.readFully(in);
		} finally {
			Streams.closeQuietly(in);
		}
	}

	/**
	 * Reads a whole store file as UTF-8 text.
	 *
	 * @param file
	 *            the file to read, in practice {@link #META_FILE} or {@link #QUARANTINE_FILE}
	 * @return its contents decoded as UTF-8
	 * @throws IOException
	 *             when the file is unreadable
	 */
	private static String readText(File file) throws IOException {
		return new String(readBytes(file), StandardCharsets.UTF_8);
	}

	/**
	 * Writes temp-then-rename with an fsync, so a reader never sees a half-written file.
	 *
	 * @param target
	 *            the final path; its parent must already exist
	 * @param bytes
	 *            the whole contents to write
	 * @throws IOException
	 *             when the write, the sync, or both rename attempts fail
	 */
	private static void writeAtomic(File target, byte[] bytes) throws IOException {
		File temp = new File(target.getParentFile(), target.getName() + TEMP_SUFFIX);
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

	/**
	 * @param dir
	 *            the store directory, app-private and created lazily by {@link #persist}; it need not exist yet
	 */
	PayloadPersistence(File dir) {
		this.dir = dir;
	}

	/**
	 * Deletes the whole store; best-effort, used when the store is untrusted.
	 *
	 * Recursive, because an untrusted store may hold directories a filesystem oddity left where a payload file belonged; a non-recursive delete would leave those behind and the next load would keep tripping over them.
	 */
	synchronized void clear() {
		deleteRecursively(dir);
	}

	/**
	 * The store directory.
	 *
	 * @return the directory this store reads and writes, which may not exist yet
	 */
	File dir() {
		return dir;
	}

	/**
	 * Loads the persisted payload, or discards the store when it cannot be trusted.
	 *
	 * A fingerprint mismatch means a rebaseline or reinstall, so the payload must not outlive the baseline it was compiled against. Any distrust - unreadable layout, mismatch, quarantine, a missing file the meta names - deletes the store, and the caller then boots the gen-0 baseline the installed APK already carries.
	 *
	 * @param expectedFingerprint
	 *            the running baseline's fingerprint from {@link #fingerprint(byte[])}; anything else discards the store
	 * @return the loaded payload, or null when the store is absent, mismatched, quarantined or corrupt - never throws, since an unreadable store is a discard rather than an error the caller handles
	 */
	synchronized Loaded load(String expectedFingerprint) {
		File meta = new File(dir, META_FILE);
		if (!meta.isFile()) {
			return null;
		}
		try {
			Map<String, Object> obj = MiniJson.parseObject(readText(meta));
			if (!LAYOUT.equals(obj.get("layout"))) {
				// An older runtime's flat store, or a layout from a newer one. Treating
				// it as absent is safe; adopting a layout we cannot read is not.
				RuntimeLog.i("persisted payload uses an unreadable layout; discarding");
				clear();
				return null;
			}
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
			if (generation == quarantinedGeneration()) {
				// This generation already failed to apply once. Adopting it again repeats
				// that failure during startup, where no reload is pending and so nothing
				// reports it to CoGo - a silent crash loop.
				RuntimeLog.w("persisted generation " + generation + " is quarantined; discarding");
				clear();
				return null;
			}
			File dexFile = namedFile(obj, KIND_DEX);
			return new Loaded(generation,
					dexFile == null ? null : readBytes(dexFile),
					namedFile(obj, KIND_ARSC),
					namedFile(obj, KIND_ASSETS));
		} catch (Throwable error) {
			RuntimeLog.e("unreadable persisted payload; discarding", error);
			clear();
			return null;
		}
	}

	/**
	 * Writes {@code generation} as the newest payload, published as one atomic set.
	 *
	 * A null byte array keeps the previously persisted file of that kind, since deploys are per-kind deltas and the store is cumulative. The carried-forward file is referenced by name rather than copied, so a full disk cannot turn a delta deploy into a mixed store.
	 *
	 * @param generation
	 *            the generation this store will claim once meta.json lands
	 * @param fingerprint
	 *            the current baseline's fingerprint, which gates a later load
	 * @param dex
	 *            the dex bytes, or null to keep the persisted ones
	 * @param arsc
	 *            the relinked resource apk bytes, or null to keep the persisted ones
	 * @param assetsZip
	 *            the changed-assets zip bytes, or null to keep the persisted ones
	 * @return the store's payload files after the write, for callers that apply resources from the persisted copies; each field is null when that kind was never persisted
	 * @throws IOException
	 *             when the directory cannot be created or any write fails; meta.json lands last, so a failure leaves the store on the previous generation, whole
	 */
	synchronized Persisted persist(long generation, String fingerprint, byte[] dex, byte[] arsc,
			byte[] assetsZip) throws IOException {
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("cannot create " + dir);
		}
		Map<String, Object> previous = readInheritableMeta(generation, fingerprint);
		String dexName = writeOrInherit(KIND_DEX, generation, dex, previous);
		String arscName = writeOrInherit(KIND_ARSC, generation, arsc, previous);
		String assetsName = writeOrInherit(KIND_ASSETS, generation, assetsZip, previous);
		StringBuilder meta = new StringBuilder("{\"layout\":\"").append(LAYOUT)
				.append("\",\"generation\":\"").append(generation)
				.append("\",\"fingerprint\":\"").append(fingerprint).append('"');
		appendName(meta, KIND_DEX, dexName);
		appendName(meta, KIND_ARSC, arscName);
		appendName(meta, KIND_ASSETS, assetsName);
		meta.append('}');
		// The one publishing act: until this rename lands, nothing above is reachable.
		writeAtomic(new File(dir, META_FILE), meta.toString().getBytes(StandardCharsets.UTF_8));
		// A complete published set supersedes any quarantine claim, including one naming
		// this same number from an earlier install's generation sequence.
		deleteQuietly(new File(dir, QUARANTINE_FILE));
		collectOrphans(dexName, arscName, assetsName);
		return new Persisted(fileOrNull(arscName), fileOrNull(assetsName));
	}

	/**
	 * Records that {@code generation} failed to apply, so {@link #load} never adopts it.
	 *
	 * A marker rather than a rollback of the store, because it also survives a crash part-way through the rollback itself, and because the failing generation's files are what a later successful deploy carries forward from. Never throws: the callers are the reload failure path and the uncaught-exception guard, neither of which can handle one.
	 *
	 * @param generation
	 *            the generation whose apply or render failed; a marker for a generation the store does not claim is inert and gets cleared by the next successful persist
	 */
	synchronized void quarantine(long generation) {
		try {
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new IOException("cannot create " + dir);
			}
			writeAtomic(new File(dir, QUARANTINE_FILE),
					("{\"generation\":\"" + generation + "\"}").getBytes(StandardCharsets.UTF_8));
			RuntimeLog.w("quarantined generation " + generation + "; a fresh process will boot the baseline");
		} catch (Throwable error) {
			RuntimeLog.e("cannot quarantine generation " + generation, error);
		}
	}

	/**
	 * Appends one {@code "kind":"file"} member to a meta document under construction.
	 *
	 * @param meta
	 *            the document so far, always already carrying at least one member
	 * @param kind
	 *            the payload kind this name belongs to
	 * @param name
	 *            the file name, or null to omit the member entirely
	 */
	private void appendName(StringBuilder meta, String kind, String name) {
		if (name != null) {
			meta.append(",\"").append(kind).append("\":\"").append(name).append('"');
		}
	}

	/**
	 * Deletes payload files and temp leftovers the just-published generation does not reference.
	 *
	 * Everything unreferenced goes, whatever generation stamps it: {@link #persist} holds the monitor from its first write to here, so no other deploy has a write in flight, and a stamp newer than the published generation can only be a torn write or a leftover from a generation sequence that restarted. Runs after the publish, so a failure here leaks a file rather than removing a live one.
	 *
	 * @param names
	 *            the file names the published generation references; nulls are ignored
	 */
	private void collectOrphans(String... names) {
		File[] entries = dir.listFiles();
		if (entries == null) {
			return;
		}
		for (File entry : entries) {
			String name = entry.getName();
			if (name.endsWith(TEMP_SUFFIX)) {
				deleteQuietly(entry);
				continue;
			}
			if (generationOf(name) >= 0 && !contains(names, name)) {
				deleteQuietly(entry);
			}
		}
	}

	/**
	 * @param names
	 *            the referenced names, possibly holding nulls
	 * @param name
	 *            the candidate name
	 * @return whether {@code name} is one of {@code names}
	 */
	private boolean contains(String[] names, String name) {
		for (String candidate : names) {
			if (name.equals(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Deletes one entry, logging rather than failing when it cannot be removed.
	 *
	 * @param file
	 *            the entry to delete; a missing one is not a failure
	 */
	private void deleteQuietly(File file) {
		if (file.exists() && !file.delete()) {
			RuntimeLog.w("could not delete " + file);
		}
	}

	/**
	 * Removes {@code file} and, when it is a directory, everything under it; best-effort.
	 *
	 * @param file
	 *            the entry to remove; a missing one is not a failure
	 */
	private void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		deleteQuietly(file);
	}

	/**
	 * @param name
	 *            a store file name, or null
	 * @return the file in the store dir, or null when {@code name} was null
	 */
	private File fileOrNull(String name) {
		return name == null ? null : new File(dir, name);
	}

	/**
	 * Resolves the file a meta document names for one kind, asserting it is really there.
	 *
	 * A named file that is missing means a torn or hand-edited store, so it is corruption rather than a plain absence: the meta claims a generation it cannot serve, and serving a subset would be the mixed store this layout exists to prevent.
	 *
	 * @param meta
	 *            the parsed meta document
	 * @param kind
	 *            the payload kind to resolve
	 * @return the file, or null when the meta names none for this kind
	 * @throws IOException
	 *             when the meta names a file that does not exist
	 */
	private File namedFile(Map<String, Object> meta, String kind) throws IOException {
		Object name = meta.get(kind);
		if (name == null) {
			return null;
		}
		if (!(name instanceof String)) {
			throw new IOException("meta.json has a non-string " + kind + " name");
		}
		File file = new File(dir, (String) name);
		if (!file.isFile()) {
			throw new IOException("meta.json names a missing " + kind + " file: " + name);
		}
		return file;
	}

	/**
	 * The generation the quarantine marker names.
	 *
	 * @return the quarantined generation, or -1 when there is no readable marker; an unreadable marker is treated as absent, which only costs the crash-loop guard for one generation
	 */
	private long quarantinedGeneration() {
		File marker = new File(dir, QUARANTINE_FILE);
		if (!marker.isFile()) {
			return -1;
		}
		try {
			Object gen = MiniJson.parseObject(readText(marker)).get("generation");
			return gen instanceof String ? Long.parseLong((String) gen) : -1;
		} catch (Throwable error) {
			RuntimeLog.w("unreadable quarantine marker; ignoring it", error);
			return -1;
		}
	}

	/**
	 * The published meta a new generation may carry files forward from.
	 *
	 * Only a strictly older generation is inheritable. A store already claiming this number or a newer one means the host's generation counter restarted (its project state was wiped while the app stayed installed), and carrying files forward from it would pair this dex with resources from a LATER build - the one mismatch direction the cumulative delta scheme does not make safe.
	 *
	 * @param generation
	 *            the incoming generation
	 * @param fingerprint
	 *            the baseline the incoming payload was built against; a store keyed to another baseline has nothing inheritable in it
	 * @return the parsed meta, or null when the store is absent, unreadable, on another layout, keyed to another baseline, or not strictly older
	 */
	private Map<String, Object> readInheritableMeta(long generation, String fingerprint) {
		File meta = new File(dir, META_FILE);
		if (!meta.isFile()) {
			return null;
		}
		try {
			Map<String, Object> obj = MiniJson.parseObject(readText(meta));
			Object stored = obj.get("fingerprint");
			if (!LAYOUT.equals(obj.get("layout")) || stored == null || !stored.equals(fingerprint)) {
				return null;
			}
			Object gen = obj.get("generation");
			if (!(gen instanceof String)
					|| !Generations.accepts(Long.parseLong((String) gen), generation)) {
				RuntimeLog.w("persisted generation " + gen + " is not older than " + generation
						+ "; persisting a fresh set");
				return null;
			}
			return obj;
		} catch (Throwable error) {
			RuntimeLog.w("previous meta.json unreadable; persisting a fresh set", error);
			return null;
		}
	}

	/**
	 * Writes one kind's bytes under a generation-stamped name, or carries the previous name forward.
	 *
	 * @param kind
	 *            the payload kind being written
	 * @param generation
	 *            the incoming generation, which stamps the new file's name
	 * @param bytes
	 *            the bytes to write, or null when this deploy carried nothing of this kind
	 * @param previous
	 *            the inheritable published meta, or null when there is none
	 * @return the file name the new meta should reference, or null when this kind has never been persisted
	 * @throws IOException
	 *             when the write fails
	 */
	private String writeOrInherit(String kind, long generation, byte[] bytes,
			Map<String, Object> previous) throws IOException {
		if (bytes != null) {
			String name = payloadFileName(kind, generation);
			writeAtomic(new File(dir, name), bytes);
			return name;
		}
		if (previous == null) {
			return null;
		}
		Object inherited = previous.get(kind);
		// Only carry forward a name that still resolves; a meta naming a missing file
		// would be published as corruption.
		if (inherited instanceof String && new File(dir, (String) inherited).isFile()) {
			return (String) inherited;
		}
		return null;
	}

	/**
	 * A successfully loaded persisted payload; a null {@code dex} means no code deploy was persisted.
	 */
	static final class Loaded {

		/** The generation meta.json claimed, always strictly greater than 0 to be worth booting. */
		final long generation;

		/** Payload dex bytes, or null for a resources or assets-only generation. */
		final byte[] dex;

		/** The persisted resource apk, or null when none was ever persisted. */
		final File arscFile;

		/** The persisted assets zip, or null when none was ever persisted. */
		final File assetsFile;

		/**
		 * @param generation
		 *            the generation meta.json claimed; must be greater than 0, since gen 0 is the APK baseline and never worth booting from the store
		 * @param dex
		 *            payload dex bytes, or null to keep the baseline classes
		 * @param arscFile
		 *            the persisted resource apk, or null
		 * @param assetsFile
		 *            the persisted assets zip, or null
		 */
		Loaded(long generation, byte[] dex, File arscFile, File assetsFile) {
			this.generation = generation;
			this.dex = dex;
			this.arscFile = arscFile;
			this.assetsFile = assetsFile;
		}
	}

	/** The payload files currently in the store (post-persist view). */
	static final class Persisted {

		/** The store's resource apk after the write, or null when none was ever persisted. */
		final File arscFile;

		/** The store's assets zip after the write, or null when none was ever persisted. */
		final File assetsFile;

		/**
		 * @param arscFile
		 *            the store's resource apk, or null
		 * @param assetsFile
		 *            the store's assets zip, or null
		 */
		Persisted(File arscFile, File assetsFile) {
			this.arscFile = arscFile;
			this.assetsFile = assetsFile;
		}
	}
}
