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

import com.itsaky.androidide.zipfs2.ZipFileSystem
import com.itsaky.androidide.zipfs2.ZipFileSystemProvider
import com.itsaky.androidide.zipfs2.listing.JarListing
import openjdk.tools.javac.file.RelativePath.RelativeDirectory
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path

/**
 * A cached, read-only file system for a JAR that reads its entries through the shared [JarListing].
 *
 * @author Akash Yadav
 */
class CachedJarFileSystem internal constructor(
	provider: ZipFileSystemProvider,
	zfpath: Path,
	internal val listing: JarListing,
	channel: SeekableByteChannel,
) : ZipFileSystem(provider, zfpath, listing, channel) {
	companion object {
		private val log = LoggerFactory.getLogger(CachedJarFileSystem::class.java)
	}

	private val packageIndex: Map<RelativeDirectory, Path> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		ListingPackageMap(this, listing)
	}

	override fun close() {
		// Do nothing
		// This is called manually by the Java LSP
	}

	@Throws(IOException::class)
	fun doClose() {
		try {
			super.close()
		} catch (e: IOException) {
			log.warn("IOException during CachedJarFileSystem close", e)
		} catch (e: java.io.UncheckedIOException) {
			log.warn("UncheckedIOException during CachedJarFileSystem close", e)
		}
	}

	/**
	 * Returns the package directories of this JAR keyed the way javac's archive walk keys them.
	 *
	 * The map is a read-only view of the listing, resolved on demand, and is the same instance on every call.
	 */
	fun packageIndex(): Map<RelativeDirectory, Path> = packageIndex
}
