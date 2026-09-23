package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingProgressTracker
import org.appdevforall.codeonthego.indexing.service.IndexingServiceManager
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A JAR that stays unreadable is retried every pass but reported to the user at most once per
 * project session: [JarIndexingService.refresh] runs every recorded JAR through the shared
 * `unreadableJarFilter`, typically [IndexingServiceManager.filterNewlyUnreadableJars].
 */
@RunWith(RobolectricTestRunner::class)
class JarIndexingServiceUnreadableJarDedupTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context = ApplicationProvider.getApplicationContext<Context>()

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `two passes finding the same unreadable jar report it only once`() {
		val garbage = garbageJar("garbage.jar")
		val manager = IndexingServiceManager()
		val reported = mutableListOf<List<String>>()
		val service = testService(jars = setOf(garbage), reporter = { reported += it }, filter = manager::filterNewlyUnreadableJars)

		try {
			runBlocking {
				service.initialize(IndexRegistry())
				service.refresh().join()
				service.refresh().join()
			}

			assertThat(reported).containsExactly(listOf("garbage.jar"))
		} finally {
			service.close()
			manager.close()
		}
	}

	@Test
	fun `a new session reports the same unreadable jar again`() {
		val garbage = garbageJar("garbage.jar")
		val manager = IndexingServiceManager()
		val reported = mutableListOf<List<String>>()
		val service = testService(jars = setOf(garbage), reporter = { reported += it }, filter = manager::filterNewlyUnreadableJars)

		try {
			runBlocking {
				service.initialize(IndexRegistry())
				service.refresh().join()
			}
			manager.close()
			runBlocking { service.refresh().join() }

			assertThat(reported).containsExactly(listOf("garbage.jar"), listOf("garbage.jar"))
		} finally {
			service.close()
		}
	}

	private fun garbageJar(name: String): String = File(temp.root, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }.absolutePath

	private fun testService(
		jars: Set<String>,
		reporter: (List<String>) -> Unit,
		filter: (Collection<String>) -> List<String>,
	): JarIndexingService =
		object : JarIndexingService(
			context = context,
			progressTracker = IndexingProgressTracker(),
			workspaceSupplier = { mockk<Workspace>() },
			unreadableJarReporter = reporter,
			unreadableJarFilter = filter,
		) {
			override val id = "jar-indexing-service-unreadable-jar-dedup-test"
			override val indexKey = IndexKey<JvmSymbolIndex>("jar-indexing-service-unreadable-jar-dedup-test")
			override val dbName = DB_NAME
			override val indexName = INDEX_NAME

			override fun jarsToIndex(workspace: Workspace): Set<String> = jars
		}

	private companion object {
		const val DB_NAME = "jar_indexing_service_unreadable_jar_dedup_test.db"
		const val INDEX_NAME = "jar-indexing-service-unreadable-jar-dedup-test-index"
	}
}
