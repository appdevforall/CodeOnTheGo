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

package com.itsaky.androidide.projects.classpath

import com.google.common.collect.ImmutableSet
import com.itsaky.androidide.javac.services.fs.CachedJarFileSystem
import com.itsaky.androidide.javac.services.fs.CachingJarFileSystemProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.FileVisitResult.SKIP_SUBTREE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipException
import kotlin.io.path.pathString

/** @author Akash Yadav */
class JarFsClasspathReader : IClasspathReader {

  companion object {
    private val log = LoggerFactory.getLogger(JarFsClasspathReader::class.java)
  }

  private val _unreadableJars = mutableListOf<File>()

  /**
   * JARs skipped during the most recent [listClasses] call because they were corrupt or could not
   * be read (e.g. a truncated download / incomplete offline provisioning). Exposed so a caller can
   * surface them to the user and offer a recovery path, instead of silently dropping that library's
   * symbols. Reset at the start of every [listClasses] call.
   */
  val unreadableJars: List<File>
    get() = _unreadableJars.toList()

  /**
   * Lists the classes contained in the given JAR files. Any JAR that is corrupt or cannot be read is
   * skipped (rather than aborting the whole scan) and recorded in [unreadableJars] for reporting.
   * [unreadableJars] is reset at the start of each call.
   */
  override fun listClasses(files: Collection<File>): ImmutableSet<ClassInfo> {
    _unreadableJars.clear()
    val builder = ImmutableSet.builder<ClassInfo>()
    for (path in files.map(File::toPath)) {
      if (!Files.exists(path)) {
        continue
      }

      try {
        val fs = CachingJarFileSystemProvider.newFileSystem(path) as CachedJarFileSystem
        for (rootDirectory in fs.rootDirectories) {
          Files.walkFileTree(
            rootDirectory,
            emptySet(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {

              override fun preVisitDirectory(
                dir: Path?,
                attrs: BasicFileAttributes?
              ): FileVisitResult {
                return if (fs.storeJARPackageDir(dir)) {
                  CONTINUE
                } else {
                  SKIP_SUBTREE
                }
              }

              override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                var name = file.pathString
                if (name.endsWith("/package-info.class") || !name.endsWith(".class")) {
                  return CONTINUE
                }

                name = name.substringBeforeLast(".class")

                if (name.isBlank()) {
                  return CONTINUE
                }

                if (name.startsWith('/')) {
                  name = name.substring(1)
                }

                if (name.contains('/')) {
                  name = name.replace('/', '.')
                }

                ClassInfo.create(name)?.also {
                  builder.add(it)
                }

                return super.visitFile(file, attrs)
              }
            }
          )
        }
      } catch (e: ZipException) {
        // A corrupt or truncated JAR must not abort indexing of the remaining classpath entries.
        log.warn("Skipping corrupt/unreadable JAR while indexing classpath: {}", path, e)
        _unreadableJars.add(path.toFile())
      } catch (e: IOException) {
        log.warn("Skipping JAR that could not be read while indexing classpath: {}", path, e)
        _unreadableJars.add(path.toFile())
      }
    }
    return builder.build()
  }
}
