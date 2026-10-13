package com.itsaky.androidide.projects

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.service.IndexingServiceManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The indexing service manager is read from worker threads, so racing first reads share one instance. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProjectManagerImplIndexingServiceManagerTest {
	@Test
	fun `racing first reads all get the same indexing service manager`() {
		val seen = Collections.newSetFromMap(IdentityHashMap<IndexingServiceManager, Boolean>())
		repeat(ROUNDS) {
			val manager = ProjectManagerImpl()
			val barrier = CyclicBarrier(THREADS)
			val pool = Executors.newFixedThreadPool(THREADS)
			val reads =
				(1..THREADS).map {
					pool.submit<IndexingServiceManager> {
						barrier.await()
						manager.indexingServiceManager
					}
				}
			val managers = reads.map { it.get(10, TimeUnit.SECONDS) }
			pool.shutdown()

			seen.clear()
			seen.addAll(managers)
			assertThat(seen).hasSize(1)
			manager.destroy()
		}
	}

	@Test
	fun `a read after destroy gets a new indexing service manager`() {
		val manager = ProjectManagerImpl()
		val first = manager.indexingServiceManager

		manager.destroy()

		assertThat(manager.indexingServiceManager).isNotSameInstanceAs(first)
		manager.destroy()
	}

	private companion object {
		const val ROUNDS = 50
		const val THREADS = 16
	}
}
