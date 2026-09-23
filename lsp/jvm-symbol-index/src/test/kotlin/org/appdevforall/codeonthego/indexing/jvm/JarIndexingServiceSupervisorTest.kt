package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [refresh] whose [JarIndexingService.jarsToIndex] throws must not take down the service's
 * coroutine scope: a later [refresh] still has to run its pass.
 */
@RunWith(RobolectricTestRunner::class)
class JarIndexingServiceSupervisorTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `a refresh whose jarsToIndex throws does not stop a later refresh from running`() {
		val callCount = AtomicInteger(0)
		val firstCallStarted = CountDownLatch(1)
		val secondCallRan = CountDownLatch(1)

		val service =
			object : JarIndexingService(
				context = context,
				workspaceSupplier = { mockk<Workspace>() },
			) {
				override val id = "jar-indexing-service-supervisor-test"
				override val indexKey = IndexKey<JvmSymbolIndex>("jar-indexing-service-supervisor-test")
				override val dbName = DB_NAME
				override val indexName = INDEX_NAME

				override fun jarsToIndex(workspace: Workspace): Set<String> {
					if (callCount.getAndIncrement() == 0) {
						firstCallStarted.countDown()
						throw IllegalStateException("jarsToIndex failed")
					}
					secondCallRan.countDown()
					return emptySet()
				}
			}

		try {
			runBlocking { service.initialize(IndexRegistry()) }

			service.refresh()
			assertThat(firstCallStarted.await(5, TimeUnit.SECONDS)).isTrue()

			service.refresh()
			assertThat(secondCallRan.await(5, TimeUnit.SECONDS)).isTrue()

			assertThat(callCount.get()).isAtLeast(2)
		} finally {
			service.close()
		}
	}

	private companion object {
		const val DB_NAME = "jar_indexing_service_supervisor_test.db"
		const val INDEX_NAME = "jar-indexing-service-supervisor-test-index"
	}
}
