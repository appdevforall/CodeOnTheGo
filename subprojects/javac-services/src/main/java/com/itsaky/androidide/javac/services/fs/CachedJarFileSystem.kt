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
import jdkx.lang.model.SourceVersion
import openjdk.tools.javac.file.RelativePath.RelativeDirectory
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections

/**
 * A cached file system for JAR files.
 *
 * @author Akash Yadav
 */
class CachedJarFileSystem(
	provider: ZipFileSystemProvider?,
	zfpath: Path?,
	env: MutableMap<String, *>?,
) : ZipFileSystem(provider, zfpath, env) {
	companion object {
		private val log = LoggerFactory.getLogger(CachedJarFileSystem::class.java)
	}

	private val packageIndex: Map<RelativeDirectory, Path> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		Collections.unmodifiableMap(walkPackages())
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
	 * The map is built on first use, is read-only, and is the same instance on every call.
	 */
	fun packageIndex(): Map<RelativeDirectory, Path> = packageIndex

	private fun walkPackages(): Map<RelativeDirectory, Path> {
		val packages = HashMap<RelativeDirectory, Path>()
		for (root in rootDirectories) {
			Files.walkFileTree(
				root,
				emptySet(),
				Int.MAX_VALUE,
				object : SimpleFileVisitor<Path>() {
					override fun preVisitDirectory(
						dir: Path,
						attrs: BasicFileAttributes,
					): FileVisitResult {
						if (!isValid(dir.fileName)) {
							return FileVisitResult.SKIP_SUBTREE
						}
						packages[RelativeDirectory(root.relativize(dir).toString())] = dir
						return FileVisitResult.CONTINUE
					}
				},
			)
		}
		return packages
	}

	private fun isValid(fileName: Path?): Boolean =
		if (fileName == null) {
			true
		} else {
			var name = fileName.toString()
			if (name.endsWith("/")) {
				name = name.substring(0, name.length - 1)
			}
			SourceVersion.isIdentifier(name)
		}
}
