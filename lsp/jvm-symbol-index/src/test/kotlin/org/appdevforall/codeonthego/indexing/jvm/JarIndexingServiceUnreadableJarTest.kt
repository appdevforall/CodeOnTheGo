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
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A pass over a JAR whose scan cannot open it must report that JAR once through the injected
 * reporter, and must leave no fingerprint recorded for it so a later pass retries it.
 */
@RunWith(RobolectricTestRunner::class)
class JarIndexingServiceUnreadableJarTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val indexKey = IndexKey<JvmSymbolIndex>("jar-indexing-service-unreadable-jar-test")

	@After
	fun tearDown() {
		context.deleteDatabase(DB_NAME)
	}

	@Test
	fun `a pass reports exactly the unreadable jar once and does not fingerprint it`() {
		val good = validJar("good.jar")
		val garbage = garbageJar("garbage.jar")
		val reported = mutableListOf<List<String>>()
		val service = TestService(jars = setOf(good, garbage), reporter = { reported += it })

		try {
			val registry = IndexRegistry()
			runBlocking {
				service.initialize(registry)
				service.refresh().join()
			}
			val index = registry.require(indexKey)

			assertThat(reported).containsExactly(listOf("garbage.jar"))
			runBlocking {
				assertThat(index.sourceFingerprint(good)).isNotNull()
				assertThat(index.sourceFingerprint(garbage)).isNull()
			}
		} finally {
			service.close()
		}
	}

	@Test
	fun `a pass over only readable jars never calls the reporter`() {
		val good = validJar("good.jar")
		val reported = mutableListOf<List<String>>()
		val service = TestService(jars = setOf(good), reporter = { reported += it })

		try {
			runBlocking {
				service.initialize(IndexRegistry())
				service.refresh().join()
			}

			assertThat(reported).isEmpty()
		} finally {
			service.close()
		}
	}

	@Test
	fun `a jar deleted before its scan is not reported and not fingerprinted`() {
		val deleted = File(temp.root, "deleted.jar").absolutePath
		val reported = mutableListOf<List<String>>()
		val service = TestService(jars = setOf(deleted), reporter = { reported += it })

		try {
			val registry = IndexRegistry()
			runBlocking {
				service.initialize(registry)
				service.refresh().join()
			}

			assertThat(reported).isEmpty()
			runBlocking { assertThat(registry.require(indexKey).sourceFingerprint(deleted)).isNull() }
		} finally {
			service.close()
		}
	}

	/** A JAR holding one public class, which scans and fingerprints cleanly. */
	private fun validJar(name: String): String {
		val internalName = "p/${name.substringBefore('.').replaceFirstChar(Char::uppercase)}"
		val bytes =
			ClassWriter(0)
				.apply {
					visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
					visitEnd()
				}.toByteArray()
		val jar = File(temp.root, name)
		ZipOutputStream(jar.outputStream()).use { out ->
			out.putNextEntry(ZipEntry("$internalName.class"))
			out.write(bytes)
			out.closeEntry()
		}
		return jar.absolutePath
	}

	/** Not a valid zip, so opening it as a [java.util.jar.JarFile] fails. */
	private fun garbageJar(name: String): String = File(temp.root, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }.absolutePath

	private inner class TestService(
		private val jars: Set<String>,
		reporter: (List<String>) -> Unit,
	) : JarIndexingService(
			context = context,
			progressTracker = IndexingProgressTracker(),
			workspaceSupplier = { mockk<Workspace>() },
			unreadableJarReporter = reporter,
		) {
		override val id = "jar-indexing-service-unreadable-jar-test"
		override val indexKey = this@JarIndexingServiceUnreadableJarTest.indexKey
		override val dbName = DB_NAME
		override val indexName = INDEX_NAME

		override fun jarsToIndex(workspace: Workspace): Set<String> = jars
	}

	private companion object {
		const val DB_NAME = "jar_indexing_service_unreadable_jar_test.db"
		const val INDEX_NAME = "jar-indexing-service-unreadable-jar-test-index"
	}
}
