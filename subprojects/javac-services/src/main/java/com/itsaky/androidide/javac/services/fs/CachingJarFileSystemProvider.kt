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

package com.itsaky.androidide.javac.services.fs

import com.itsaky.androidide.zipfs2.JarFileSystemProvider
import com.itsaky.androidide.zipfs2.ZipFileSystem
import com.itsaky.androidide.zipfs2.listing.JarListing
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.pathString

/**
 * An implementation of [JarFileSystemProvider] that caches the created [CachedJarFileSystem] so
 * that it can be (re)used in multiple compilations.
 *
 * Every open checks the JAR against [JarListingCache.SHARED]; when the JAR changed on disk, a new
 * file system is opened on the new listing. The superseded one is retired, not closed: javac's
 * archive containers keep the file system they opened until their file manager is closed, and a
 * closed one throws an unchecked `ClosedFileSystemException` from `Files.exists`. Retired file
 * systems are closed by [closeRetired] and [clearCache], once the file managers are gone.
 *
 * @author Akash Yadav
 */
object CachingJarFileSystemProvider : JarFileSystemProvider() {
	private val cachedFs = ConcurrentHashMap<String, CachedJarFileSystem>()
	private val retired = ConcurrentLinkedQueue<CachedJarFileSystem>()

	private val log = LoggerFactory.getLogger(CachingJarFileSystemProvider::class.java)

	init {
		/*
		 * Runs while the listing cache holds that JAR's lock. createFs never calls the cache from
		 * inside a map operation, and this listener only touches the map, so the two locks are
		 * always taken in the order cache lock -> map bin.
		 */
		JarListingCache.SHARED.addListener { path -> cachedFs.remove(keyOf(path))?.let(retired::add) }
	}

	/*
	 * A cached file system is stale exactly when the listing cache no longer returns its listing.
	 * The map is read before that lookup, so a mismatch proves the cached one was superseded, never
	 * that a racing caller published a newer one. A caller that loses the publish race closes its
	 * own unseen file system and re-checks the winner on the next round.
	 */
	override fun createFs(
		path: Path,
		env: MutableMap<String, *>?,
	): ZipFileSystem {
		val key = keyOf(path)
		repeat(3) {
			val cached = cachedFs[key]
			val listing = currentListing(path)
			if (cached != null) {
				if (cached.listing === listing) {
					return cached
				}
				if (cachedFs.remove(key, cached)) {
					retired.add(cached)
				}
			}
			val opened = openBound(path, listing)
			if (cachedFs.putIfAbsent(key, opened) == null) {
				return opened
			}
			closeFs(opened)
		}
		throw IOException("JAR kept changing on disk while it was being opened: $path")
	}

	/*
	 * The channel must read the file the listing describes. Opening it between two lookups that
	 * return the same listing shows the path still matched the listing after the channel opened. A
	 * JAR replaced in between yields a new listing, and the open is retried once against it.
	 */
	private fun openBound(
		path: Path,
		first: JarListing,
	): CachedJarFileSystem {
		var listing = first
		repeat(2) {
			val channel = Files.newByteChannel(path, StandardOpenOption.READ)
			val current =
				try {
					currentListing(path)
				} catch (e: Throwable) {
					channel.close()
					throw e
				}
			if (current === listing) {
				return CachedJarFileSystem(this, path, listing, channel)
			}
			channel.close()
			listing = current
		}
		throw IOException("JAR changed on disk while it was being opened: $path")
	}

	private fun currentListing(path: Path): JarListing =
		try {
			JarListingCache.SHARED.get(path)
		} catch (e: NoSuchFileException) {
			throw FileSystemNotFoundException(path.toString()).apply { initCause(e) }
		}

	private fun keyOf(path: Path): String = path.toAbsolutePath().normalize().pathString

	fun newFileSystem(path: Path): FileSystem? = newFileSystem(path, mutableMapOf<String, Any>())

	/** Closes every cached and retired file system. */
	fun clearCache() {
		cachedFs.values.forEach(this::closeFs)
		cachedFs.clear()
		closeRetired()
	}

	/** Closes the file systems superseded by a newer listing of their JAR; call only once no file manager holds them. */
	fun closeRetired() {
		while (true) {
			closeFs(retired.poll() ?: return)
		}
	}

	fun clearCaches(predicate: (Path) -> Boolean) = clearCachesForPaths { predicate(Paths.get(it)) }

	fun clearCachesForPaths(predicate: (String) -> Boolean) {
		val toRemove =
			this.cachedFs.keys.mapNotNull {
				return@mapNotNull if (predicate(it)) {
					it
				} else {
					null
				}
			}

		if (toRemove.isNotEmpty()) {
			toRemove.forEach(this::clearCache)
		}
	}

	fun clearCache(path: Path) {
		clearCache(keyOf(path))
	}

	fun clearCache(path: String) {
		val fs = cachedFs.remove(path)
		if (fs != null) {
			log.debug("Clearing cached JAR file system for path: {}", path)
			closeFs(fs)
		}
	}

	private fun closeFs(fs: CachedJarFileSystem) {
		try {
			fs.doClose()
		} catch (err: Throwable) {
			log.error("Failed to close cached zip file system: {}", fs, err)
		}
	}
}
