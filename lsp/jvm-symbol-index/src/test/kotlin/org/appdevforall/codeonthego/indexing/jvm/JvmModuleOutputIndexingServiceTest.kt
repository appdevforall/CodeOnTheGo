package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import kotlinx.coroutines.runBlocking
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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class JvmModuleOutputIndexingServiceTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context = ApplicationProvider.getApplicationContext<Context>()

	private val fixtures = ModuleFixtures(temp)

	@After
	fun tearDown() {
		runCatching { ProjectManagerImpl.getInstance().workspace = null }
		context.deleteDatabase("jvm_module_output_symbol_index.db")
	}

	@Test
	fun `the jar set is the union of the modules' classpath deltas`() {
		val app = fixtures.androidModule(":app", moduleDeps = listOf(":lib"), externalJars = listOf(fixtures.builtFile("ext/guava.jar")))
		val lib = fixtures.androidModule(":lib")
		val unbuilt = fixtures.androidModule(":unbuilt", built = false)
		val jlib = fixtures.javaModule(":jlib")
		val workspace = fixtures.installWorkspace(app, lib, unbuilt, jlib)

		val deltas =
			workspace.subProjects
				.filterIsInstance<ModuleProject>()
				.flatMap { it.getCompileClasspaths(false) - it.getCompileClasspaths(true) }
				.filter { it.exists() }
				.map { it.absolutePath }
				.toSet()

		assertThat(moduleOutputJars(workspace)).isEqualTo(deltas)
		assertThat(deltas.map { File(it).name }).doesNotContain("R.jar")
	}

	@Test
	fun `a jar whose fingerprint is unchanged is not re-indexed after a build`() {
		val lib = fixtures.androidModule(":lib")
		fixtures.installWorkspace(lib)
		val jar = lib.getGeneratedJar()
		writeClassJar(jar, "p/Aa")
		val service = JvmModuleOutputIndexingService(context, IndexingProgressTracker())
		try {
			val index = initializeAndAwait(service)

			writeClassJar(jar, "p/Bb")
			// Passes run one at a time, so joining a later pass waits out the build's pass too.
			runBlocking {
				service.onBuildCompleted()
				service.refresh().join()
			}

			assertThat(index.topLevelClassNames(jar)).containsExactly("Aa")
		} finally {
			service.close()
		}
	}

	@Test
	fun `a jar whose fingerprint changed is re-indexed after a build`() {
		val lib = fixtures.androidModule(":lib")
		fixtures.installWorkspace(lib)
		val jar = lib.getGeneratedJar()
		writeClassJar(jar, "p/Aa")
		val service = JvmModuleOutputIndexingService(context, IndexingProgressTracker())
		try {
			val index = initializeAndAwait(service)

			writeClassJar(jar, "p/Bb", modifiedAt = MODIFIED_AT + 60_000)
			runBlocking {
				service.onBuildCompleted()
				service.refresh().join()
			}

			assertThat(index.topLevelClassNames(jar)).containsExactly("Bb")
		} finally {
			service.close()
		}
	}

	private fun initializeAndAwait(service: JvmModuleOutputIndexingService): JvmSymbolIndex {
		val registry = IndexRegistry()
		runBlocking {
			service.initialize(registry)
			service.refresh().join()
		}
		return registry.require(JVM_MODULE_OUTPUT_SYMBOL_INDEX)
	}

	private fun JvmSymbolIndex.topLevelClassNames(jar: File): List<String> =
		listOf("Aa", "Bb").filter { name ->
			findTopLevelClassesNamed(name, listOf(jar.absolutePath)).any()
		}

	/**
	 * Writes a JAR holding one public class, with stored entries and fixed timestamps, so two JARs
	 * whose class names have the same length have the same size and, with [modifiedAt], the same
	 * fingerprint.
	 */
	private fun writeClassJar(
		jar: File,
		internalName: String,
		modifiedAt: Long = MODIFIED_AT,
	) {
		val bytes =
			ClassWriter(0)
				.apply {
					visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
					visitEnd()
				}.toByteArray()
		val entry =
			ZipEntry("$internalName.class").apply {
				method = ZipEntry.STORED
				size = bytes.size.toLong()
				compressedSize = bytes.size.toLong()
				crc = CRC32().apply { update(bytes) }.value
				time = MODIFIED_AT
			}

		jar.parentFile.mkdirs()
		ZipOutputStream(jar.outputStream()).use { out ->
			out.putNextEntry(entry)
			out.write(bytes)
			out.closeEntry()
		}
		jar.setLastModified(modifiedAt)
	}

	private companion object {
		const val MODIFIED_AT = 1_700_000_000_000L
	}
}
