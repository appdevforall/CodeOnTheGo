package com.itsaky.androidide.plugins.ai.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigFixture.files
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigFixture.parser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Unit tests for [PromptConfigStore], the in-memory cache every chat turn reads from. */
class PromptConfigStoreTest {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val store = PromptConfigStore(parser)

	@After
	fun tearDown() {
		scope.cancel()
	}

	/** A source over the fixture that counts loads (reads of the entry file) and can throw. */
	private class CountingSource(
		private val fail: Boolean = false,
	) : PromptConfigSource {
		val scans = AtomicInteger()

		override fun read(path: String): String {
			if (path == PromptConfigLoader.FILE) scans.incrementAndGet()
			if (fail) error("assets missing")
			return files.getValue(path)
		}
	}

	@Test
	fun givenNoPreload_whenReading_thenItFailsRatherThanWaitingForever() {
		assertThrows(IllegalStateException::class.java) { runBlocking { store.config() } }
	}

	@Test
	fun givenAPreload_whenManyTurnsReadConcurrently_thenTheSourceIsReadOnce() {
		val source = CountingSource()
		store.preload(scope, source)

		val results =
			runBlocking {
				(1..50).map { async(Dispatchers.Default) { store.config() } }.awaitAll()
			}

		assertEquals(1, source.scans.get())
		results.forEach { assertSame(results.first(), it) }
	}

	@Test
	fun givenConcurrentPreloads_whenReading_thenOnlyOneLoadRuns() {
		val source = CountingSource()

		runBlocking {
			(1..20).map { async(Dispatchers.Default) { store.preload(scope, source) } }.awaitAll()
			store.config()
		}

		assertEquals(1, source.scans.get())
	}

	@Test
	fun givenAFailedLoad_whenPreloadingAgain_thenTheLoadIsRetried() {
		store.preload(scope, CountingSource(fail = true)).settle()
		val retry = CountingSource()

		val config =
			runBlocking {
				store.preload(scope, retry)
				store.config()
			}

		assertEquals("agent.yml: identity", config.identity.label)
		assertEquals(1, retry.scans.get())
	}

	@Test
	fun givenAClearedStore_whenPreloadingAgain_thenTheSourceIsReadAfresh() {
		val source = CountingSource()
		runBlocking { store.preload(scope, source).await() }

		store.clear()
		runBlocking { store.preload(scope, source).await() }

		assertEquals(2, source.scans.get())
	}

	@Test
	fun givenATurnBeforeTheLoadFinishes_whenReading_thenItWaitsForTheLoad() {
		val gate = CompletableDeferred<Unit>()
		store.preload(scope, gatedSource(gate))

		val config =
			runBlocking {
				val turn = async(Dispatchers.Default) { store.config() }
				gate.complete(Unit)
				turn.await()
			}

		assertEquals("agent.yml: identity", config.identity.label)
	}

	@Test
	fun givenACompletedLoad_whenReadingWithoutWaiting_thenTheConfigIsReturned() {
		val loaded = runBlocking { store.preload(scope, CountingSource()).await() }

		assertSame(loaded, store.configIfLoaded())
	}

	@Test
	fun givenNoCompletedLoad_whenReadingWithoutWaiting_thenNothingIsReturned() {
		assertNull(store.configIfLoaded())

		val gate = CompletableDeferred<Unit>()
		store.preload(scope, gatedSource(gate))
		assertNull(store.configIfLoaded())
		gate.complete(Unit)
		store.clear()

		store.preload(scope, CountingSource(fail = true)).settle()
		assertNull(store.configIfLoaded())
	}

	private fun Deferred<*>.settle() = runBlocking { runCatching { await() } }
}
