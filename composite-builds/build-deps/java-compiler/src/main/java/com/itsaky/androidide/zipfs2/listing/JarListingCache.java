/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.zipfs2.listing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Shares one {@link JarListing} per JAR and rebuilds it when the JAR's size or modification time
 * changes on disk.
 *
 * <p>Each JAR has its own lock, so a build or a listener on one JAR never blocks {@link #get} on
 * another. The lock is reentrant: a listener may call {@link #get} or {@link #invalidate} for the
 * JAR it was notified about.
 */
public final class JarListingCache {

	/** The cache shared by every file system and virtual file system in the process. */
	public static final JarListingCache SHARED = new JarListingCache(JarListing::read);

	/** Builds a listing for a JAR. */
	@FunctionalInterface
	public interface Reader {
		JarListing read(Path jar) throws IOException;
	}

	private static final class Entry {
		final JarListing listing;
		final long size;
		final long mtimeNanos;

		Entry(JarListing listing, long size, long mtimeNanos) {
			this.listing = listing;
			this.size = size;
			this.mtimeNanos = mtimeNanos;
		}
	}

	private final Reader reader;
	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
	private final List<Consumer<Path>> listeners = new CopyOnWriteArrayList<>();

	/** Creates a cache that builds listings with the given reader. */
	public JarListingCache(Reader reader) {
		this.reader = reader;
	}

	/**
	 * Returns the listing for the JAR, building it on first use and rebuilding it when the JAR's
	 * size or modification time differs from when it was built.
	 *
	 * <p>Listeners are notified of a dropped listing before its replacement is built. If the build
	 * fails, the exception propagates and nothing is cached.
	 *
	 * @throws IOException if the JAR cannot be read or its central directory is malformed
	 */
	public JarListing get(Path jar) throws IOException {
		final Path key = normalize(jar);
		final String id = key.toString();
		final long size = Files.size(key);
		final long mtime = modifiedNanos(key);

		final Entry cached = entries.get(id);
		if (isCurrent(cached, size, mtime)) {
			return cached.listing;
		}

		final ReentrantLock lock = lockFor(id);
		lock.lock();
		try {
			Entry current = entries.get(id);
			if (current != null && !isCurrent(current, size, mtime)) {
				dropAndNotify(id, key);
				current = entries.get(id);
			}
			if (isCurrent(current, size, mtime)) {
				return current.listing;
			}
			final Entry built = new Entry(reader.read(key), size, mtime);
			entries.put(id, built);
			return built.listing;
		} finally {
			lock.unlock();
		}
	}

	/** Drops the JAR's listing, if cached, and notifies listeners. */
	public void invalidate(Path jar) {
		final Path key = normalize(jar);
		final String id = key.toString();
		final ReentrantLock lock = lockFor(id);
		lock.lock();
		try {
			dropAndNotify(id, key);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Registers a listener called with the JAR's path once for every listing that is dropped.
	 *
	 * <p>The listener runs on the thread that dropped the listing, after the old listing is no
	 * longer returned and before its replacement is built.
	 */
	public void addListener(Consumer<Path> onInvalidated) {
		listeners.add(onInvalidated);
	}

	private void dropAndNotify(String id, Path key) {
		if (entries.remove(id) == null) {
			return;
		}
		for (Consumer<Path> listener : listeners) {
			listener.accept(key);
		}
	}

	private ReentrantLock lockFor(String id) {
		return locks.computeIfAbsent(id, k -> new ReentrantLock());
	}

	private static boolean isCurrent(Entry entry, long size, long mtime) {
		return entry != null && entry.size == size && entry.mtimeNanos == mtime;
	}

	private static long modifiedNanos(Path jar) throws IOException {
		final var time = Files.getLastModifiedTime(jar).toInstant();
		return time.getEpochSecond() * 1_000_000_000L + time.getNano();
	}

	private static Path normalize(Path jar) {
		return jar.toAbsolutePath().normalize();
	}
}
