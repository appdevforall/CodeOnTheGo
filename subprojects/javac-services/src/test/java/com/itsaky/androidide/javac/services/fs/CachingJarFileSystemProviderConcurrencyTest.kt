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

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Concurrent [CachingJarFileSystemProvider.createFs] calls for the same JAR path must open that
 * JAR's file system exactly once.
 */
class CachingJarFileSystemProviderConcurrencyTest {
	private lateinit var tmpDir: File
	private lateinit var jarPath: Path

	@Before
	fun setUp() {
		// The provider caches by normalized path; start clean so prior runs cannot mask the race.
		CachingJarFileSystemProvider.clearCache()

		tmpDir = Files.createTempDirectory("createfs-race").toFile()
		jarPath = File(tmpDir, "test.jar").toPath()
		writeMinimalJar(jarPath)
	}

	@After
	fun tearDown() {
		CachingJarFileSystemProvider.clearCache()
		tmpDir.deleteRecursively()
	}

	@Test
	fun concurrentCreateFsOpensTheSameJarOnce() {
		val threadCount = 32
		val ready = CountDownLatch(threadCount)
		val start = CountDownLatch(1)
		val results = Collections.synchronizedList(mutableListOf<FileSystem>())
		val executor = Executors.newFixedThreadPool(threadCount)

		repeat(threadCount) {
			executor.submit {
				ready.countDown()
				start.await()
				results.add(checkNotNull(CachingJarFileSystemProvider.newFileSystem(jarPath)))
			}
		}

		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
		start.countDown()
		executor.shutdown()
		assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

		assertThat(results).hasSize(threadCount)
		/*
		 * Referential identity is the point: computeIfAbsent must hand every racing caller the
		 * same CachedJarFileSystem instance instead of each caller building and briefly holding
		 * its own, only for all but one to be overwritten and never closed.
		 */
		assertThat(results.toSet()).hasSize(1)
	}
}

private fun writeMinimalJar(path: Path) {
	ZipOutputStream(Files.newOutputStream(path)).use { zip ->
		zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
		zip.write("Manifest-Version: 1.0\n".toByteArray())
		zip.closeEntry()

		zip.putNextEntry(ZipEntry("com/example/Placeholder.class"))
		zip.write(ByteArray(16))
		zip.closeEntry()
	}
}
