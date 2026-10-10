package org.appdevforall.codeonthego.indexing.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@RunWith(JUnit4::class)
class IndexingServiceManagerTest {
	class TestService(
		override val id: String,
	) : IndexingService {
		override val providedKeys = emptyList<IndexKey<*>>()
		var initialized = false
		var buildCompleted = false
		var closed = false

		override suspend fun initialize(registry: IndexRegistry) {
			initialized = true
		}

		override suspend fun onBuildCompleted() {
			buildCompleted = true
		}

		override fun close() {
			closed = true
		}
	}

	@Test
	fun `register adds service retrievable by id`() {
		val manager = IndexingServiceManager()
		val svc = TestService("svc-a")
		manager.register(svc)
		assertThat(manager.getService("svc-a")).isSameInstanceAs(svc)
		manager.close()
	}

	@Test
	fun `duplicate registration is silently ignored`() {
		val manager = IndexingServiceManager()
		val svc1 = TestService("svc-a")
		val svc2 = TestService("svc-a")
		manager.register(svc1)
		manager.register(svc2)
		assertThat(manager.getService("svc-a")).isSameInstanceAs(svc1)
		manager.close()
	}

	@Test
	fun `allServices returns all registered services`() {
		val manager = IndexingServiceManager()
		manager.register(TestService("a"))
		manager.register(TestService("b"))
		manager.register(TestService("c"))
		assertThat(manager.allServices()).hasSize(3)
		manager.close()
	}

	@Test
	fun `getService returns null for unregistered id`() {
		val manager = IndexingServiceManager()
		assertThat(manager.getService("unknown")).isNull()
		manager.close()
	}

	@Test
	fun `close calls close on each registered service`() {
		val manager = IndexingServiceManager()
		val svc1 = TestService("a")
		val svc2 = TestService("b")
		manager.register(svc1)
		manager.register(svc2)
		manager.close()
		runBlocking { IndexingServiceManager.awaitPendingClose() }
		assertThat(svc1.closed).isTrue()
		assertThat(svc2.closed).isTrue()
	}

	@Test
	fun `close clears services list`() {
		val manager = IndexingServiceManager()
		manager.register(TestService("a"))
		manager.close()
		assertThat(manager.allServices()).isEmpty()
	}

	@Test
	fun `registry is accessible and starts empty`() {
		val manager = IndexingServiceManager()
		assertThat(manager.registry).isNotNull()
		assertThat(manager.registry.registeredKeys()).isEmpty()
		manager.close()
	}

	@Test
	fun `onBuildCompleted before initialization does not throw`() {
		val manager = IndexingServiceManager()
		val svc = TestService("a")
		manager.register(svc)
		// Should be a no-op and not throw
		manager.onBuildCompleted()
		manager.close()
	}

	@Test
	fun `onSourceChanged before initialization is a no-op`() {
		val manager = IndexingServiceManager()
		manager.register(TestService("a"))
		manager.onSourceChanged()
		manager.close()
	}

	@Test
	fun `close after close does not throw`() {
		val manager = IndexingServiceManager()
		manager.close()
		manager.close() // second close should be safe
	}

	@Test
	fun `close returns without waiting for a slow service`() {
		val release = CountDownLatch(1)
		val closed = CountDownLatch(1)
		val manager = IndexingServiceManager()
		manager.register(
			object : IndexingService {
				override val id = "slow"
				override val providedKeys = emptyList<IndexKey<*>>()

				override suspend fun initialize(registry: IndexRegistry) {}

				override fun close() {
					release.await()
					closed.countDown()
				}
			},
		)

		// Before the fix close() blocked here (on the main thread in production).
		assertThat(closesWithin(manager, 2_000)).isTrue()
		assertThat(closed.count).isEqualTo(1)

		release.countDown()
		assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
	}

	@Test
	fun `next manager initializes only after the previous close finishes`() {
		val release = CountDownLatch(1)
		var oldClosed = false
		val old = IndexingServiceManager()
		old.register(
			object : IndexingService {
				override val id = "old"
				override val providedKeys = emptyList<IndexKey<*>>()

				override suspend fun initialize(registry: IndexRegistry) {}

				override fun close() {
					release.await()
					oldClosed = true
				}
			},
		)
		assertThat(closesWithin(old, 2_000)).isTrue()

		val initialized = CountDownLatch(1)
		var sawOldClosed = false
		val next = IndexingServiceManager()
		next.register(
			object : IndexingService {
				override val id = "next"
				override val providedKeys = emptyList<IndexKey<*>>()

				override suspend fun initialize(registry: IndexRegistry) {
					sawOldClosed = oldClosed
					initialized.countDown()
				}

				override fun close() {}
			},
		)
		next.onProjectSynced()

		assertThat(initialized.await(200, TimeUnit.MILLISECONDS)).isFalse()
		release.countDown()
		assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue()
		assertThat(sawOldClosed).isTrue()
		next.close()
		runBlocking { withTimeout(5_000) { IndexingServiceManager.awaitPendingClose() } }
	}

	@Test
	fun `a hung close does not stall the next manager forever`() {
		val release = CountDownLatch(1)
		val savedWait = IndexingServiceManager.pendingCloseWait
		IndexingServiceManager.pendingCloseWait = 300.milliseconds
		try {
			val hung = IndexingServiceManager()
			hung.register(
				object : IndexingService {
					override val id = "hung"
					override val providedKeys = emptyList<IndexKey<*>>()

					override suspend fun initialize(registry: IndexRegistry) {}

					override fun close() {
						release.await() // blocking, so withTimeoutOrNull cannot cut it short
					}
				},
			)
			hung.close()

			val initialized = CountDownLatch(1)
			val next = IndexingServiceManager()
			next.register(
				object : IndexingService {
					override val id = "next"
					override val providedKeys = emptyList<IndexKey<*>>()

					override suspend fun initialize(registry: IndexRegistry) {
						initialized.countDown()
					}

					override fun close() {}
				},
			)
			next.onProjectSynced()

			assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue()
		} finally {
			release.countDown()
			IndexingServiceManager.pendingCloseWait = savedWait
			runBlocking { withTimeout(5_000) { IndexingServiceManager.awaitPendingClose() } }
		}
	}

	/** Calls [IndexingServiceManager.close] on a daemon thread so a blocking close fails the test instead of hanging it. */
	private fun closesWithin(
		manager: IndexingServiceManager,
		millis: Long,
	): Boolean {
		val closer = Thread { manager.close() }.apply { isDaemon = true }
		closer.start()
		closer.join(millis)
		return !closer.isAlive
	}
}
