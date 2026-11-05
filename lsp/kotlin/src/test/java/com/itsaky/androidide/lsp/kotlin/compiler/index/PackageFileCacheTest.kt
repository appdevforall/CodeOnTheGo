package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataWrite
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class PackageFileCacheTest {
	private val backing = mutableMapOf<String, List<String>>()
	private val queries = AtomicInteger()
	private val pathConversions = AtomicInteger()

	private val entered = AtomicReference<CountDownLatch?>()
	private val release = AtomicReference<CountDownLatch?>()
	private var held: CountDownLatch? = null

	private fun cache(max: Long = 100) =
		PackageFileCache<String>(
			maxCachedFiles = max,
			pathOf = {
				pathConversions.incrementAndGet()
				it
			},
			query = { pkg ->
				queries.incrementAndGet()
				val snapshot = backing[pkg].orEmpty()
				entered.getAndSet(null)?.countDown()
				release.getAndSet(null)?.await(5, TimeUnit.SECONDS)
				snapshot
			},
			executor = Runnable::run,
		)

	private fun upserted(
		path: String,
		pkg: String,
	) = KtFileMetadataWrite.Upserted(path, pkg)

	@Test
	fun secondLookupDoesNotQueryAgain() {
		backing["p"] = listOf("/a.kt")
		val cache = cache()
		cache.filesFor("p")
		assertThat(cache.filesFor("p")).containsExactly("/a.kt")
		assertThat(queries.get()).isEqualTo(1)
	}

	@Test
	fun upsertDropsItsPackage() {
		backing["p"] = listOf("/a.kt")
		val cache = cache()
		cache.filesFor("p")
		backing["p"] = listOf("/a.kt", "/b.kt")
		cache.onWrite(upserted("/b.kt", "p"))
		assertThat(cache.filesFor("p")).containsExactly("/a.kt", "/b.kt")
	}

	@Test
	fun upsertDropsThePackageThatListedThePath() {
		backing["old"] = listOf("/a.kt")
		val cache = cache()
		cache.filesFor("old")
		backing["old"] = emptyList()
		backing["new"] = listOf("/a.kt")
		cache.onWrite(upserted("/a.kt", "new"))
		assertThat(cache.filesFor("old")).isEmpty()
	}

	@Test
	fun upsertOfAFileAlreadyListedKeepsItsPackage() {
		backing["p"] = listOf("/a.kt", "/b.kt")
		val cache = cache()
		cache.filesFor("p")
		cache.onWrite(upserted("/a.kt", "p"))
		assertThat(cache.filesFor("p")).containsExactly("/a.kt", "/b.kt")
		assertThat(queries.get()).isEqualTo(1)
	}

	@Test
	fun writesDoNotConvertCachedPaths() {
		backing["p"] = listOf("/a.kt", "/b.kt", "/c.kt")
		val cache = cache()
		cache.filesFor("p")
		pathConversions.set(0)
		cache.onWrite(KtFileMetadataWrite.Removed(listOf("/other.kt")))
		cache.onWrite(upserted("/other.kt", "q"))
		assertThat(pathConversions.get()).isEqualTo(0)
	}

	@Test
	fun removedPathDropsItsPackage() {
		backing["p"] = listOf("/a.kt", "/b.kt")
		val cache = cache()
		cache.filesFor("p")
		backing["p"] = listOf("/b.kt")
		cache.onWrite(KtFileMetadataWrite.Removed(listOf("/a.kt")))
		assertThat(cache.filesFor("p")).containsExactly("/b.kt")
	}

	@Test
	fun clearDropsEveryPackage() {
		backing["p"] = listOf("/a.kt")
		backing["q"] = listOf("/b.kt")
		val cache = cache()
		cache.filesFor("p")
		cache.filesFor("q")
		backing.clear()
		cache.onWrite(KtFileMetadataWrite.Cleared)
		assertThat(cache.filesFor("p")).isEmpty()
		assertThat(cache.filesFor("q")).isEmpty()
	}

	@Test
	fun rootPackageIsCachedAndInvalidated() {
		backing[""] = listOf("/a.kt")
		val cache = cache()
		cache.filesFor("")
		cache.filesFor("")
		assertThat(queries.get()).isEqualTo(1)
		backing[""] = listOf("/a.kt", "/b.kt")
		cache.onWrite(upserted("/b.kt", ""))
		assertThat(cache.filesFor("")).containsExactly("/a.kt", "/b.kt")
	}

	@Test
	fun lookupRacingAWriteDoesNotCacheTheOldList() {
		backing["p"] = listOf("/a.kt")
		val cache = cache()
		val racing = startLookupBlockedInQuery(cache, "p")

		backing["p"] = listOf("/a.kt", "/b.kt")
		cache.onWrite(upserted("/b.kt", "p"))
		finish(racing)

		assertThat(cache.filesFor("p")).containsExactly("/a.kt", "/b.kt")
		assertThat(queries.get()).isEqualTo(2)
	}

	@Test
	fun evictedPackagesAreRebuilt() {
		val cache = cache(max = 3)
		val names = listOf("a", "b", "c", "d")
		names.forEach {
			backing[it] = listOf("/$it.kt")
			cache.filesFor(it)
		}
		val evicted =
			names.first { name ->
				val before = queries.get()
				cache.filesFor(name)
				queries.get() != before
			}
		backing[evicted] = listOf("/changed.kt")
		cache.onWrite(upserted("/changed.kt", evicted))
		assertThat(cache.filesFor(evicted)).containsExactly("/changed.kt")
	}

	@Test
	fun evictionRacingInvalidationLeavesNoStaleList() {
		backing["a"] = listOf("/a.kt")
		backing["b"] = listOf("/b.kt")
		val cache = cache(max = 2)
		val racing = startLookupBlockedInQuery(cache, "a")

		cache.filesFor("b")
		backing["a"] = listOf("/a2.kt")
		cache.onWrite(upserted("/a2.kt", "a"))
		finish(racing)

		assertThat(cache.filesFor("a")).containsExactly("/a2.kt")
	}

	private fun startLookupBlockedInQuery(
		cache: PackageFileCache<String>,
		pkg: String,
	): Thread {
		val enteredLatch = CountDownLatch(1)
		entered.set(enteredLatch)
		held = CountDownLatch(1).also { release.set(it) }
		val thread = thread { cache.filesFor(pkg) }
		assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue()
		return thread
	}

	private fun finish(racing: Thread) {
		held?.countDown()
		racing.join(5000)
		assertThat(racing.isAlive).isFalse()
	}
}
