package com.itsaky.androidide.lsp.kotlin.compiler.vfs

import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.jetbrains.kotlin.com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger(ListingJarFileSystem::class.java)

private const val JAR_SEPARATOR = "!/"

/**
 * A read-only `jar` file system that serves JAR entries from the shared [JarListing][com.itsaky.androidide.zipfs2.listing.JarListing]s
 * of [cache], creating a [VirtualFile] for an entry only when something asks for it.
 *
 * Every lookup checks the JAR on disk through [cache], so a JAR replaced in place gets a fresh
 * handler, and the files of the old one report [VirtualFile.isValid] as false.
 */
class ListingJarFileSystem(
	private val cache: JarListingCache,
) : DeprecatedVirtualFileSystem() {
	private val handlers = ConcurrentHashMap<String, ListingJarHandler>()

	init {
		cache.addListener { jar -> handlers.remove(jar.toString())?.drop() }
	}

	override fun getProtocol(): String = StandardFileSystems.JAR_PROTOCOL

	/**
	 * Returns the file at [path], written `<jar path>!/<entry>`, or null if the JAR cannot be read
	 * or has no such entry.
	 *
	 * @throws IllegalArgumentException if [path] has no `!/` separator
	 */
	override fun findFileByPath(path: String): VirtualFile? {
		val separator = path.indexOf(JAR_SEPARATOR)
		require(separator >= 0) { "Path in JarFileSystem must contain a separator: $path" }
		val jar = Path.of(path.substring(0, separator)).toAbsolutePath().normalize()
		return handlerFor(jar)?.findFile(path.substring(separator + JAR_SEPARATOR.length))
	}

	override fun refresh(asynchronous: Boolean) = Unit

	override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

	private fun handlerFor(jar: Path): ListingJarHandler? = snapshotOf(jar)?.let { handlerFor(jar.toString(), it) }

	/**
	 * Returns the handler for the JAR at [key], replacing the current one only if [snapshot] was
	 * built after it.
	 *
	 * A handler is returned only if it matches the snapshot the cache holds afterwards. Otherwise
	 * another consumer of the cache rebuilt the listing in between, and the listener may already
	 * have run, so the stale handler is removed and the lookup is retried once; if the JAR changes
	 * again, this returns null.
	 */
	internal fun handlerFor(
		key: String,
		snapshot: JarListingCache.Snapshot,
	): ListingJarHandler? {
		val handler = install(key, snapshot)
		if (isCurrent(handler)) return handler
		retire(key, handler)
		val retried = install(key, snapshotOf(Path.of(key)) ?: return null)
		if (isCurrent(retried)) return retried
		retire(key, retried)
		logger.warn("JAR {} changed again while it was being looked up", key)
		return null
	}

	private fun snapshotOf(jar: Path): JarListingCache.Snapshot? =
		try {
			cache.snapshot(jar)
		} catch (e: IOException) {
			if (e is NoSuchFileException) handlers.remove(jar.toString())?.drop()
			logger.warn("Cannot list JAR {}", jar, e)
			null
		}

	private fun install(
		key: String,
		snapshot: JarListingCache.Snapshot,
	): ListingJarHandler =
		handlers.compute(key) { _, old ->
			if (old != null && old.snapshot.generation() >= snapshot.generation()) {
				old
			} else {
				old?.drop()
				ListingJarHandler(this, key, snapshot)
			}
		}!!

	/** Reads only the cache's map: no stat and no path lock, so it is safe after any lookup. */
	private fun isCurrent(handler: ListingJarHandler): Boolean {
		val current = cache.peek(Path.of(handler.jarPath)) ?: return false
		return current.generation() <= handler.snapshot.generation()
	}

	private fun retire(
		key: String,
		handler: ListingJarHandler,
	) {
		if (handlers.remove(key, handler)) handler.drop()
	}
}
