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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.javac.services.fs.CachingJarFileSystemProvider
import com.itsaky.androidide.utils.FileProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for ADFA-3364: a corrupt/truncated/zero-byte JAR among the classpath entries
 * must not abort indexing of the remaining (valid) entries.
 *
 * @author Verification Agent (ADFA-3364)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.DEFAULT_VALUE_STRING)
class JarFsClasspathReaderCorruptJarTest {

  private lateinit var tmpDir: File
  private lateinit var validJar: File
  private lateinit var corruptJar: File
  private lateinit var zeroByteJar: File

  @Before
  fun setUp() {
    // The provider caches by normalized path; start clean so prior runs cannot mask behavior.
    CachingJarFileSystemProvider.clearCache()

    tmpDir = Files.createTempDirectory("adfa3364").toFile()

    // A genuinely valid JAR known to contain android.content.Context.
    val sourceAndroidJar =
      FileProvider.testProjectRoot()
        .resolve("app/src/main/resources/android.jar")
        .toFile()
    assertThat(sourceAndroidJar.exists()).isTrue()

    // Copy it to a unique temp path so the FS-provider cache key is distinct per run.
    validJar = File(tmpDir, "valid.jar")
    sourceAndroidJar.copyTo(validJar, overwrite = true)

    // A truncated JAR: take the first 64 bytes of a real JAR. It has the local-file-header
    // signature but no valid end-of-central-directory record -> ZipException on open/walk.
    corruptJar = File(tmpDir, "corrupt.jar")
    val head = sourceAndroidJar.inputStream().use { it.readNBytes(64) }
    corruptJar.writeBytes(head)

    // A zero-byte file with a .jar extension -> also unreadable as a zip.
    zeroByteJar = File(tmpDir, "empty.jar")
    zeroByteJar.writeBytes(ByteArray(0))
  }

  @After
  fun tearDown() {
    CachingJarFileSystemProvider.clearCache()
    tmpDir.deleteRecursively()
  }

  /**
   * On the FIX branch: the corrupt JAR is skipped (ZipException caught) and the valid JAR is still
   * indexed. On the pre-fix baseline: the ZipException from the corrupt JAR propagates out of
   * [JarFsClasspathReader.listClasses] and this test fails with that exception.
   *
   * Corrupt JAR is listed FIRST so that, if the exception aborts the loop, the valid JAR after it
   * never gets indexed either (stronger assertion that indexing did not abort).
   */
  @Test
  fun corruptJarDoesNotAbortIndexingOfRemainingEntries() {
    val classes =
      JarFsClasspathReader()
        .listClasses(listOf(corruptJar, zeroByteJar, validJar))

    // The valid JAR after the corrupt ones must have been fully indexed.
    val context = classes.firstOrNull { it.name == "android.content.Context" }
    assertThat(context).isNotNull()
    assertThat(context!!.packageName).isEqualTo("android.content")

    // Sanity: a non-trivial number of classes were indexed from the valid jar.
    assertThat(classes.size).isGreaterThan(100)
  }
}
