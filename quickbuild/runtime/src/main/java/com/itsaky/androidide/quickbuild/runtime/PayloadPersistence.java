package com.itsaky.androidide.quickbuild.runtime;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

	/**
	 * A copy of {@link #META_FILE} for the newest generation that got an activity on screen, which {@link #load} falls back to when the published one is quarantined.
	 *
	 * Without it a quarantine drops the app all the way to the installed baseline, discarding every save since - and CoGo, seeing the app reconnect far behind the session, re-sends its retained payload onto that baseline, which fails the same way and gets quarantined too. Measured on an A56: one bad generation cost a crash, a silent revert to install-time code, a second crash, and the system's "app keeps stopping" dialog, with the good generations swept up along with the bad one.
	 */
	static final String GOOD_FILE = "good.json";

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
			return Streams.readFully(in, Streams.MAX_PAYLOAD_BYTES, file.length());
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
		writeAtomic(target, new ByteArrayInputStream(bytes));
	}

	/**
	 * Writes temp-then-rename with an fsync, streaming so the contents are never held whole in memory.
	 *
	 * @param target
	 *            the final path; its parent must already exist
	 * @param in
	 *            the contents, read to exhaustion; not closed, since the caller owns it
	 * @throws IOException
	 *             when the read, the write, the sync, or both rename attempts fail, or when the stream exceeds {@link Streams#MAX_PAYLOAD_BYTES}
	 */
	private static void writeAtomic(File target, InputStream in) throws IOException {
		File temp = new File(target.getParentFile(), target.getName() + TEMP_SUFFIX);
		boolean placed = false;
		try {
			FileOutputStream out = new FileOutputStream(temp);
			try {
				Streams.copy(in, out, Streams.MAX_PAYLOAD_BYTES);
				out.getFD().sync();
			} finally {
				Streams.closeQuietly(out);
			}
			if (!temp.renameTo(target)) {
				// rename over an existing file is atomic on POSIX; a failure here is a
				// filesystem oddity - fall back to delete+rename before giving up. The
				// delete's own result is not the test: it returns false on a first write,
				// where there was nothing to delete, which short-circuited the retry that
				// would have worked and left the temp file behind for good.
				target.delete();
				if (!temp.renameTo(target)) {
					throw new IOException("cannot rename " + temp + " to " + target);
				}
			}
			placed = true;
		} finally {
			// Every failure, not just a failed rename: an oversize payload or a full disk
			// threw out of the copy above and left the partial temp in the store dir, where
			// nothing sweeps it and the next write of the same generation finds it there.
			if (!placed) {
				temp.delete();
			}
		}
	}

	private final File dir;

	/**
	 * Highest generation this process has published, or 0 before the first publish.
	 *
	 * Deliberately per-process rather than read from the store, and that is what separates the two cases a generation number alone conflates. An overtaken deploy is always two binder threads inside one live process, so it is caught here. A restarted host counter always arrives in a process that has published nothing yet - the store it finds was written by an earlier session or install - so this is 0 and the low generation is adopted, which is what the app needs for the deploy to work at all.
	 */
	private long highestPersistedGeneration;

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
	 * A fingerprint mismatch means a rebaseline or reinstall, so the payload must not outlive the baseline it was compiled against. Distrust - an unreadable layout, a mismatch, a missing file the meta names - deletes the store, and the caller then boots the gen-0 baseline the installed APK already carries.
	 *
	 * A quarantined generation is the one distrust that does NOT discard the store: it falls back to {@link #GOOD_FILE}, the newest generation that got an activity on screen, and republishes that as the current set. Every other distrust means the store itself cannot be read, and {@link #GOOD_FILE} lives in the same store.
	 *
	 * @param expectedFingerprint
	 *            the running baseline's fingerprint from {@link #fingerprint(byte[])}; anything else discards the store
	 * @return the loaded payload, or null when the store is absent, mismatched, corrupt, or quarantined with nothing good behind it - never throws, since an unreadable store is a discard rather than an error the caller handles
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
				RuntimeLog.w("persisted generation " + generation
						+ " is quarantined; falling back to the last generation that ran");
				return loadLastGood(expectedFingerprint);
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
	 * Records that {@code generation} is one a fresh process may boot when a newer one is quarantined.
	 *
	 * Call when the generation has demonstrably run - an activity of its was resumed - which is exactly the bar a fallback has to clear, since the failure this guards against is a payload that throws on the way to the screen. Never throws: the caller is a lifecycle callback.
	 *
	 * @param generation
	 *            the generation now on screen; ignored unless the store currently publishes it, since a caller confirming a superseded generation has nothing here to record
	 * @return true when {@link #GOOD_FILE} names {@code generation} after this call, which is also the moment {@link #quarantine} starts refusing to name it; false when the store no longer publishes it, when it is already quarantined, or when the write failed, and the caller must go on treating it as unproven. {@link #markGoodCanSucceed} separates the retryable false from the other two.
	 */
	synchronized boolean markGood(long generation) {
		try {
			File meta = new File(dir, META_FILE);
			if (!meta.isFile() || generationIn(meta) != generation) {
				return false;
			}
			if (generationIn(new File(dir, QUARANTINE_FILE)) == generation) {
				// The crash guard got here first. Recording it good anyway leaves both
				// markers naming the same generation, and the next boot then finds the
				// published set quarantined AND the fallback set quarantined, which load()
				// reads as corruption and answers by clearing the whole store - the app
				// drops to install-time code with every save since discarded. quarantine()
				// holds this same monitor and carries the mirror-image guard, so whichever
				// of the two runs first, the second refuses.
				RuntimeLog.w("not recording generation " + generation + " as good; it is quarantined");
				return false;
			}
			if (generationIn(new File(dir, GOOD_FILE)) == generation) {
				return true;
			}
			writeAtomic(new File(dir, GOOD_FILE), readBytes(meta));
			RuntimeLog.i("generation " + generation + " reached the screen; keeping it as the fallback");
			return true;
		} catch (Throwable error) {
			// Costs the fallback one generation of freshness, nothing else.
			RuntimeLog.w("could not record generation " + generation + " as good", error);
			return false;
		}
	}

	/**
	 * Whether a {@link #markGood} that returned false is worth calling again.
	 *
	 * markGood returns the same false for three different situations and only one of them is transient. The store having moved on to a newer generation, or having quarantined this one, are both permanent for this generation - a caller that retried on those would spawn a write per resume for the rest of the process. A failed write is the one the caller should come back to.
	 *
	 * @param generation
	 *            the generation whose markGood returned false
	 * @return true when the store still publishes {@code generation} and has not quarantined it, so the false came from the write rather than from the store's state
	 */
	synchronized boolean markGoodCanSucceed(long generation) {
		File meta = new File(dir, META_FILE);
		return meta.isFile() && generationIn(meta) == generation
				&& generationIn(new File(dir, QUARANTINE_FILE)) != generation;
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
	 *            the relinked resource apk, streamed straight to its store file, or null to keep the persisted one
	 * @param assetsZip
	 *            the changed-assets zip, streamed straight to its store file, or null to keep the persisted one
	 * @return the store's payload files after the write, for callers that apply resources from the persisted copies; each field is null when that kind was never persisted
	 * @throws StalePayloadException
	 *             when a generation this process already published is newer than this one, which means this deploy was overtaken while it read its payload
	 * @throws IOException
	 *             when the directory cannot be created or any write fails; meta.json lands last, so a failure leaves the store on the previous generation, whole
	 */
	synchronized Persisted persist(long generation, String fingerprint, byte[] dex, InputStream arsc,
			InputStream assetsZip) throws IOException {
		if (generation < highestPersistedGeneration) {
			// onPayload is oneway, so two deploys genuinely arrive on two binder threads.
			// The older one can be overtaken while it reads its payload and then publish
			// itself over the newer one, leaving disk a generation behind the running
			// process - invisible until the next cold boot adopts it.
			throw new StalePayloadException("refusing to persist generation " + generation
					+ " over " + highestPersistedGeneration + " already published by this process");
		}
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
		if (generationIn(new File(dir, GOOD_FILE)) >= generation) {
			// The host's generation counter restarted (its project state was wiped while the
			// app stayed installed), so the last-good set belongs to a sequence that no
			// longer exists and falling back to it would boot a LATER-numbered older build.
			deleteQuietly(new File(dir, GOOD_FILE));
		}
		// Only after the publishing rename: a persist that threw part-way published
		// nothing, and must not raise the bar against the retry that follows it.
		highestPersistedGeneration = generation;
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
		if (generation == generationIn(new File(dir, GOOD_FILE))) {
			// This generation already got an activity on screen, so a fresh process booting
			// it does not repeat whatever just failed - the startup crash loop the marker
			// exists to break cannot happen here. Writing one anyway is what swept the
			// user's last working saves away along with the broken generation: the app then
			// dropped to install-time code, CoGo re-sent its retained payload onto it, and
			// that failed too.
			RuntimeLog.w("not quarantining generation " + generation
					+ "; it already ran, so it is the fallback rather than the fault");
			return;
		}
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
	 * Deletes payload files and temp leftovers no live meta references.
	 *
	 * "Live" is the just-published generation plus the last-good set, whose files a quarantine boots from and which the published meta therefore does not name. Everything else goes whatever generation stamps it: {@link #persist} holds the monitor from its first write to here, so no other deploy has a write in flight, and a stamp newer than the published generation can only be a torn write or a leftover from a generation sequence that restarted. Runs after the publish, so a failure here leaks a file rather than removing a live one.
	 *
	 * @param names
	 *            the file names the published generation references; nulls are ignored
	 */
	private void collectOrphans(String... names) {
		File[] entries = dir.listFiles();
		if (entries == null) {
			return;
		}
		Set<String> referenced = payloadNamesIn(new File(dir, GOOD_FILE));
		for (String name : names) {
			if (name != null) {
				referenced.add(name);
			}
		}
		for (File entry : entries) {
			String name = entry.getName();
			if (name.endsWith(TEMP_SUFFIX)) {
				deleteQuietly(entry);
				continue;
			}
			if (generationOf(name) >= 0 && !referenced.contains(name)) {
				deleteQuietly(entry);
			}
		}
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
	 * The generation a meta-shaped document names.
	 *
	 * @param file
	 *            {@link #META_FILE}, {@link #GOOD_FILE} or {@link #QUARANTINE_FILE}
	 * @return the generation, or -1 when the file is absent or unreadable; treating an unreadable side file as absent only costs the guard it feeds, where failing closed would strand the app on the baseline forever
	 */
	private long generationIn(File file) {
		if (!file.isFile()) {
			return -1;
		}
		try {
			Object gen = MiniJson.parseObject(readText(file)).get("generation");
			return gen instanceof String ? Long.parseLong((String) gen) : -1;
		} catch (Throwable error) {
			RuntimeLog.w("unreadable " + file.getName() + "; ignoring it", error);
			return -1;
		}
	}

	/**
	 * The previous generation's file name for {@code kind}, when it is still there to carry forward.
	 *
	 * @param kind
	 *            the payload kind this deploy did not carry
	 * @param previous
	 *            the inheritable published meta, or null when there is none
	 * @return the name to reference, or null when there is nothing inheritable
	 */
	private String inherit(String kind, Map<String, Object> previous) {
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
	 * Boots the last generation that reached the screen, and republishes it as the current set.
	 *
	 * Republishing matters as much as loading: the store has to agree with what this process is running, or the next deploy inherits payload files from the quarantined generation and every later boot walks the same fallback again.
	 *
	 * @param expectedFingerprint
	 *            the running baseline's fingerprint; a last-good set keyed to another baseline is as unusable as a published one
	 * @return the payload to boot, or null after discarding the store when there is no usable last-good set - which returns the caller to the installed baseline, the behaviour a quarantine had before
	 */
	private Loaded loadLastGood(String expectedFingerprint) {
		File good = new File(dir, GOOD_FILE);
		if (!good.isFile()) {
			clear();
			return null;
		}
		try {
			Map<String, Object> obj = MiniJson.parseObject(readText(good));
			Object fp = obj.get("fingerprint");
			Object gen = obj.get("generation");
			if (!LAYOUT.equals(obj.get("layout")) || !(fp instanceof String) || !(gen instanceof String)
					|| !fp.equals(expectedFingerprint)) {
				clear();
				return null;
			}
			long generation = Long.parseLong((String) gen);
			if (generation == quarantinedGeneration()) {
				// Belt and braces: quarantine() refuses to name the good generation, so this
				// can only be a hand-edited or torn store.
				clear();
				return null;
			}
			File dexFile = namedFile(obj, KIND_DEX);
			Loaded loaded = new Loaded(generation,
					dexFile == null ? null : readBytes(dexFile),
					namedFile(obj, KIND_ARSC),
					namedFile(obj, KIND_ASSETS));
			// Only once every file it names has resolved, so a torn last-good set cannot
			// replace a readable meta with an unservable one.
			writeAtomic(new File(dir, META_FILE), readBytes(good));
			RuntimeLog.i("booting generation " + generation + ", the last one that ran");
			return loaded;
		} catch (Throwable error) {
			RuntimeLog.e("unusable last-good payload; discarding the store", error);
			clear();
			return null;
		}
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
	 * The payload file names a meta-shaped document references.
	 *
	 * @param metaFile
	 *            {@link #META_FILE} or {@link #GOOD_FILE}; a missing or unreadable one yields an empty set, which only costs the caller the files it names
	 * @return the referenced names, never null
	 */
	private Set<String> payloadNamesIn(File metaFile) {
		Set<String> names = new HashSet<String>();
		if (!metaFile.isFile()) {
			return names;
		}
		try {
			Map<String, Object> obj = MiniJson.parseObject(readText(metaFile));
			String[] kinds = {KIND_DEX, KIND_ARSC, KIND_ASSETS};
			for (String kind : kinds) {
				Object name = obj.get(kind);
				if (name instanceof String) {
					names.add((String) name);
				}
			}
		} catch (Throwable error) {
			RuntimeLog.w("unreadable " + metaFile.getName() + "; the files it names may be collected", error);
		}
		return names;
	}

	/**
	 * The generation the quarantine marker names.
	 *
	 * @return the quarantined generation, or -1 when there is no readable marker; an unreadable marker is treated as absent, which only costs the crash-loop guard for one generation
	 */
	private long quarantinedGeneration() {
		return generationIn(new File(dir, QUARANTINE_FILE));
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
		return writeOrInherit(kind, generation,
				bytes == null ? null : new ByteArrayInputStream(bytes), previous);
	}

	/**
	 * Streams one kind's contents to a generation-stamped name, or carries the previous name forward.
	 *
	 * @param kind
	 *            the payload kind being written
	 * @param generation
	 *            the incoming generation, which stamps the new file's name
	 * @param in
	 *            the contents, or null when this deploy carried nothing of this kind; read to exhaustion, not closed
	 * @param previous
	 *            the inheritable published meta, or null when there is none
	 * @return the file name the new meta should reference, or null when this kind has never been persisted
	 * @throws IOException
	 *             when the write fails
	 */
	private String writeOrInherit(String kind, long generation, InputStream in,
			Map<String, Object> previous) throws IOException {
		if (in == null) {
			return inherit(kind, previous);
		}
		String name = payloadFileName(kind, generation);
		writeAtomic(new File(dir, name), in);
		return name;
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

	/**
	 * Refusal of a payload an already-persisted one has overtaken.
	 *
	 * Separate from a plain {@link IOException} so the deploy path can tell "this payload lost a race" from "the store is broken": the first is ordinary and must stay silent, and reporting it would put a crash banner on a screen that is running exactly what it should be.
	 */
	static final class StalePayloadException extends IOException {

		private static final long serialVersionUID = 1L;

		/**
		 * @param message
		 *            names both generations, since which one won is the whole content of the event
		 */
		StalePayloadException(String message) {
			super(message);
		}
	}
}
