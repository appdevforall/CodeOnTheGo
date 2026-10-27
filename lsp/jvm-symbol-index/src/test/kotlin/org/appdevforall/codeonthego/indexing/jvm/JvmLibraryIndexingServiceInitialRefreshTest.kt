package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * The language servers can refresh the library index before the manager has initialized it, and
 * that refresh is dropped, so initialization must run a pass of its own.
 */
@RunWith(RobolectricTestRunner::class)
class JvmLibraryIndexingServiceInitialRefreshTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context = ApplicationProvider.getApplicationContext<Context>()

	@After
	fun tearDown() {
		context.deleteDatabase(JvmSymbolIndex.DB_NAME_DEFAULT)
	}

	@Test
	fun `a refresh before initialization still leaves the index populated once initialized`() {
		val jar = classJar("lib.jar", "p/Lib")
		val workspace = workspaceOf(jar)
		val service = JvmLibraryIndexingService(context, IndexingProgressTracker(), workspaceSupplier = { workspace })
		val registry = IndexRegistry()

		val indexed =
			service.use {
				runBlocking {
					it.refresh().join()
					it.initialize(registry)
					val index = registry.require(JVM_LIBRARY_SYMBOL_INDEX)
					withTimeoutOrNull(10_000) {
						while (index.findTopLevelClassesNamed("Lib", listOf(jar)).none()) {
							delay(20)
						}
						true
					} ?: false
				}
			}

		assertThat(indexed).isTrue()
	}

	private fun workspaceOf(jar: String): Workspace {
		val module =
			mockk<ModuleProject> {
				every { path } returns ":app"
				every { getCompileClasspaths(excludeSourceGeneratedClassPath = true) } returns setOf(File(jar))
			}
		val root = mockk<GradleProject> { every { path } returns ":" }
		return mockk<Workspace> {
			every { rootProject } returns root
			every { subProjects } returns listOf(module)
		}
	}

	private fun classJar(
		name: String,
		internalName: String,
	): String {
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
}
