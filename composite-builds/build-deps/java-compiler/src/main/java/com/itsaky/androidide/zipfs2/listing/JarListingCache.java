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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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

	/**
	 * A cached listing together with the size and modification time of the JAR it was built from.
	 *
	 * <p>Each build gets a generation greater than every earlier build in the same cache, so of two
	 * snapshots of one JAR the one with the greater generation is the more recent.
	 */
	public static final class Snapshot {
		private final JarListing listing;
		private final long generation;
		private final long size;
		private final long modifiedNanos;

		Snapshot(JarListing listing, long generation, long size, long modifiedNanos) {
			this.listing = listing;
			this.generation = generation;
			this.size = size;
			this.modifiedNanos = modifiedNanos;
		}

		/** Returns the listing. */
		public JarListing listing() {
			return listing;
		}

		/** Returns the build order of this snapshot within its cache. */
		public long generation() {
			return generation;
		}

		/** Returns the JAR's size in bytes when the listing was built. */
		public long size() {
			return size;
		}

		/** Returns the JAR's modification time, in nanoseconds since the epoch, when the listing was built. */
		public long modifiedNanos() {
			return modifiedNanos;
		}
	}

	private final Reader reader;
	private final AtomicLong generations = new AtomicLong();
	private final ConcurrentHashMap<String, Snapshot> entries = new ConcurrentHashMap<>();
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
	 * <p>Listeners are notified of a dropped listing before its replacement is built. Every
	 * listener is called even if one throws; the first failure is then rethrown with the others
	 * suppressed, and the next call builds the replacement. If the build fails, the exception
	 * propagates and nothing is cached. A JAR that no longer exists has its listing dropped, and
	 * listeners notified, before the {@link NoSuchFileException} propagates.
	 *
	 * @throws IOException if the JAR cannot be read or its central directory is malformed
	 */
	public JarListing get(Path jar) throws IOException {
		return snapshot(jar).listing;
	}

	/**
	 * Returns the listing for the JAR with the state it was built from; see {@link #get}.
	 *
	 * @throws IOException if the JAR cannot be read or its central directory is malformed
	 */
	public Snapshot snapshot(Path jar) throws IOException {
		final Path key = normalize(jar);
		final String id = key.toString();
		final BasicFileAttributes attrs;
		try {
			attrs = Files.readAttributes(key, BasicFileAttributes.class);
		} catch (NoSuchFileException e) {
			// The check keeps lookups of JARs that never existed from adding a lock entry
			if (entries.containsKey(id)) {
				invalidate(key);
			}
			throw e;
		}
		final long size = attrs.size();
		final long mtime = modifiedNanos(attrs);

		final Snapshot cached = entries.get(id);
		if (isCurrent(cached, size, mtime)) {
			return cached;
		}

		final ReentrantLock lock = lockFor(id);
		lock.lock();
		try {
			Snapshot current = entries.get(id);
			if (current != null && !isCurrent(current, size, mtime)) {
				dropAndNotify(id, key);
				current = entries.get(id);
			}
			if (isCurrent(current, size, mtime)) {
				return current;
			}
			final Snapshot built = new Snapshot(reader.read(key), generations.incrementAndGet(), size, mtime);
			entries.put(id, built);
			return built;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Returns the snapshot currently cached for the JAR, or null if none is, without checking the
	 * file on disk or waiting for a build in progress.
	 */
	public Snapshot peek(Path jar) {
		return entries.get(normalize(jar).toString());
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
	 * Drops every cached listing and notifies listeners once per JAR.
	 *
	 * <p>Every JAR is dropped even if a listener throws; the first failure is then rethrown with the
	 * others suppressed. A listing built concurrently with the clear may stay cached.
	 */
	public void clear() {
		RuntimeException failure = null;
		for (String id : entries.keySet()) {
			try {
				invalidate(Paths.get(id));
			} catch (RuntimeException e) {
				if (failure == null) {
					failure = e;
				} else {
					failure.addSuppressed(e);
				}
			}
		}
		if (failure != null) {
			throw failure;
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
		RuntimeException failure = null;
		for (Consumer<Path> listener : listeners) {
			try {
				listener.accept(key);
			} catch (RuntimeException e) {
				if (failure == null) {
					failure = e;
				} else {
					failure.addSuppressed(e);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	private ReentrantLock lockFor(String id) {
		return locks.computeIfAbsent(id, k -> new ReentrantLock());
	}

	private static boolean isCurrent(Snapshot entry, long size, long mtime) {
		return entry != null && entry.size == size && entry.modifiedNanos == mtime;
	}

	private static long modifiedNanos(BasicFileAttributes attrs) {
		final var time = attrs.lastModifiedTime().toInstant();
		return time.getEpochSecond() * 1_000_000_000L + time.getNano();
	}

	private static Path normalize(Path jar) {
		return jar.toAbsolutePath().normalize();
	}
}
