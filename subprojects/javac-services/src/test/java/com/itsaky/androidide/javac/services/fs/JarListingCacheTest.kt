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
import com.itsaky.androidide.zipfs2.listing.JarListing
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [JarListingCache] must share one listing per JAR and rebuild it when the JAR changes. */
class JarListingCacheTest {
	private lateinit var tmpDir: File
	private val builds = AtomicInteger()
	private val cache =
		JarListingCache { jar ->
			builds.incrementAndGet()
			JarListing.read(jar)
		}

	@Before
	fun setUp() {
		tmpDir =
			File.createTempFile("jar-listing-cache-test", "").apply {
				delete()
				mkdirs()
			}
	}

	@After
	fun tearDown() {
		tmpDir.deleteRecursively()
	}

	private fun jar(
		vararg names: String,
		name: String = "fixture.jar",
	): Path {
		val file = File(tmpDir, name)
		ZipOutputStream(file.outputStream()).use { zip ->
			for (entry in names) {
				zip.putNextEntry(ZipEntry(entry))
				zip.write(entry.toByteArray())
				zip.closeEntry()
			}
		}
		return file.toPath()
	}

	@Test
	fun `unchanged jar returns the same listing`() {
		val jar = jar("a/B.class")

		val first = cache.get(jar)
		val second = cache.get(jar)

		assertThat(second).isSameInstanceAs(first)
		assertThat(builds.get()).isEqualTo(1)
	}

	@Test
	fun `rewritten jar with new size or mtime returns a new listing and notifies once`() {
		val jar = jar("a/B.class")
		val dropped = ConcurrentLinkedQueue<Path>()
		cache.addListener { dropped.add(it) }
		val first = cache.get(jar)

		jar("a/B.class", "a/C.class")
		Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 5_000))
		val second = cache.get(jar)

		assertThat(second).isNotSameInstanceAs(first)
		assertThat(second.find("/a/C.class")).isAtLeast(0)
		assertThat(dropped.toList()).containsExactly(jar.toAbsolutePath().normalize())
	}

	@Test
	fun `same size jar with a different mtime is rebuilt`() {
		val jar = jar("a/B.class")
		val first = cache.get(jar)

		Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 5_000))

		assertThat(cache.get(jar)).isNotSameInstanceAs(first)
	}

	@Test
	fun `sixteen threads on a cold jar build it once`() {
		val jar = jar("a/B.class")
		val pool = Executors.newFixedThreadPool(16)
		val start = CountDownLatch(1)
		val results =
			(1..16).map {
				pool.submit<JarListing> {
					start.await()
					cache.get(jar)
				}
			}

		start.countDown()
		val listings = results.map { it.get(30, TimeUnit.SECONDS) }
		pool.shutdown()

		assertThat(builds.get()).isEqualTo(1)
		assertThat(listings.toSet()).hasSize(1)
	}

	@Test
	fun `invalidate drops the listing and notifies`() {
		val jar = jar("a/B.class")
		val dropped = ConcurrentLinkedQueue<Path>()
		cache.addListener { dropped.add(it) }
		val first = cache.get(jar)

		cache.invalidate(jar)
		val second = cache.get(jar)

		assertThat(dropped).hasSize(1)
		assertThat(second).isNotSameInstanceAs(first)
		assertThat(builds.get()).isEqualTo(2)
	}

	@Test
	fun `invalidate of an uncached jar does not notify`() {
		val dropped = ConcurrentLinkedQueue<Path>()
		cache.addListener { dropped.add(it) }

		cache.invalidate(jar("a/B.class"))

		assertThat(dropped).isEmpty()
	}

	@Test
	fun `a failed build caches nothing and is retried`() {
		val file = File(tmpDir, "bad.jar").apply { writeText("not a zip") }

		repeat(2) { runCatching { cache.get(file.toPath()) } }

		assertThat(builds.get()).isEqualTo(2)
		assertThat(runCatching { cache.get(file.toPath()) }.isFailure).isTrue()
	}

	@Test
	fun `a listener may invalidate and get the same jar without deadlock`() {
		val jar = jar("a/B.class")
		cache.addListener {
			cache.invalidate(it)
			cache.get(it)
		}
		cache.get(jar)
		val pool = Executors.newSingleThreadExecutor()

		val result =
			pool.submit<JarListing> {
				cache.invalidate(jar)
				cache.get(jar)
			}

		assertThat(result.get(10, TimeUnit.SECONDS)).isNotNull()
		pool.shutdown()
	}

	@Test
	fun `a throwing listener does not stop later listeners and the failure surfaces`() {
		val jar = jar("a/B.class")
		val called = AtomicInteger()
		cache.addListener { throw IllegalStateException("first") }
		cache.addListener { called.incrementAndGet() }
		cache.get(jar)

		val failure = runCatching { cache.invalidate(jar) }.exceptionOrNull()

		assertThat(failure).hasMessageThat().isEqualTo("first")
		assertThat(called.get()).isEqualTo(1)
	}
}
