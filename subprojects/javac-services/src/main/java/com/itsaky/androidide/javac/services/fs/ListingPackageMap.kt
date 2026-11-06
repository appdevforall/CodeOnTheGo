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

import com.itsaky.androidide.zipfs2.listing.JarListing
import jdkx.lang.model.SourceVersion
import openjdk.tools.javac.file.RelativePath.RelativeDirectory
import java.nio.file.Path

private typealias PackageEntry = Map.Entry<RelativeDirectory, Path>

/**
 * A read-only view of the package directories of a JAR, resolved on demand from its [JarListing].
 *
 * Keys and values match what javac's archive walk produces: the root plus every directory whose
 * segments are all Java identifiers. Nothing is materialized per package.
 */
internal class ListingPackageMap(
	private val fs: CachedJarFileSystem,
	private val listing: JarListing,
) : AbstractMap<RelativeDirectory, Path>() {
	override val entries: Set<PackageEntry> =
		object : AbstractSet<PackageEntry>() {
			override val size: Int
				get() = PackageIterator().asSequence().count()

			override fun iterator(): Iterator<PackageEntry> = PackageIterator().asSequence().map { entryOf(it) }.iterator()

			override fun contains(element: PackageEntry): Boolean = get(element.key) == element.value
		}

	override fun containsKey(key: RelativeDirectory): Boolean = indexOf(key) >= 0

	override fun get(key: RelativeDirectory): Path? {
		val index = indexOf(key)
		return if (index < 0) null else fs.getPath(listing.name(index))
	}

	private fun entryOf(index: Int): PackageEntry {
		val name = listing.name(index)
		return java.util.AbstractMap.SimpleImmutableEntry(
			RelativeDirectory(name.substring(1)),
			fs.getPath(name),
		)
	}

	// Returns the listing index of the package directory for the key, or -1 if it is not one.
	private fun indexOf(key: RelativeDirectory): Int {
		val relative = key.path.removeSuffix("/")
		if (relative.isNotEmpty() && !relative.split('/').all(SourceVersion::isIdentifier)) {
			return -1
		}
		val index = listing.find("/$relative")
		return if (index >= 0 && listing.isDirectory(index)) index else -1
	}

	// Depth-first walk that does not descend into files or non-identifier directories.
	private inner class PackageIterator : Iterator<Int> {
		private val pending = ArrayDeque<Int>().apply { addLast(0) }
		private var upcoming = advance()

		override fun hasNext(): Boolean = upcoming >= 0

		override fun next(): Int {
			val current = upcoming
			if (current < 0) throw NoSuchElementException()
			upcoming = advance()
			return current
		}

		private fun advance(): Int {
			while (pending.isNotEmpty()) {
				val index = pending.removeLast()
				val sibling = listing.nextSibling(index)
				if (sibling >= 0) pending.addLast(sibling)
				if (!isPackage(index)) continue
				val child = listing.firstChild(index)
				if (child >= 0) pending.addLast(child)
				return index
			}
			return -1
		}

		private fun isPackage(index: Int): Boolean =
			listing.isDirectory(index) &&
				(index == 0 || SourceVersion.isIdentifier(listing.shortName(index)))
	}
}
