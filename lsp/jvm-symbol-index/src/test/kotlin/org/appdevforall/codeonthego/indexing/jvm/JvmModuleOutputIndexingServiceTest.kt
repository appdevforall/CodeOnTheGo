package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
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

	@After
	fun tearDown() {
		runCatching { ProjectManagerImpl.getInstance().workspace = null }
		context.deleteDatabase("jvm_module_output_symbol_index.db")
	}

	@Test
	fun `the jar set is the union of the modules' classpath deltas`() {
		val app = androidModule(":app", moduleDeps = listOf(":lib"), externalJars = listOf(builtFile("ext/guava.jar")))
		val lib = androidModule(":lib")
		val unbuilt = androidModule(":unbuilt", built = false)
		val jlib = javaModule(":jlib")
		val workspace = installWorkspace(app, lib, unbuilt, jlib)

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
		val lib = androidModule(":lib")
		installWorkspace(lib)
		val jar = lib.getGeneratedJar()
		writeClassJar(jar, "p/Aa")
		val service = JvmModuleOutputIndexingService(context)
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
		val lib = androidModule(":lib")
		installWorkspace(lib)
		val jar = lib.getGeneratedJar()
		writeClassJar(jar, "p/Aa")
		val service = JvmModuleOutputIndexingService(context)
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

	private fun builtFile(relativePath: String): String =
		File(temp.root, relativePath)
			.apply {
				parentFile.mkdirs()
				writeBytes(ByteArray(1))
			}.absolutePath

	private fun moduleDir(path: String) = File(temp.root, path.replace(':', '/'))

	/**
	 * Builds an [AndroidModule] whose compile graph lists [moduleDeps] as project libraries and
	 * [externalJars] as external Java libraries, with an `R.jar` on its selected variant. Its
	 * generated JAR and `R.jar` exist on disk when [built].
	 */
	private fun androidModule(
		path: String,
		moduleDeps: List<String> = emptyList(),
		externalJars: List<String> = emptyList(),
		built: Boolean = true,
	): AndroidModule {
		val dir = moduleDir(path)
		val generatedJar = File(dir, "build/classes.jar")
		val rJar = File(dir, "build/R.jar")
		if (built) {
			builtFile(generatedJar.relativeTo(temp.root).path)
			builtFile(rJar.relativeTo(temp.root).path)
		}

		val graph = AndroidModels.DependencyGraph.newBuilder()
		val variantDeps = AndroidModels.VariantDependencies.newBuilder().setName(VARIANT)

		fun addRoot(library: AndroidModels.Library) {
			val keyId = graph.keyCount
			graph.addKey(library.key)
			graph.addRoot(graph.nodeCount)
			graph.addNode(
				AndroidModels.GraphNode
					.newBuilder()
					.setKeyId(keyId)
					.build(),
			)
			variantDeps.putLibraries(library.key, library)
		}

		for (depPath in moduleDeps) {
			addRoot(
				AndroidModels.Library
					.newBuilder()
					.setKey("project$depPath")
					.setType(AndroidModels.LibraryType.Project)
					.setProjectInfo(
						AndroidModels.ProjectInfo
							.newBuilder()
							.setBuildId(":")
							.setProjectPath(depPath)
							.build(),
					).build(),
			)
		}

		for (jar in externalJars) {
			addRoot(
				AndroidModels.Library
					.newBuilder()
					.setKey("external$jar")
					.setType(AndroidModels.LibraryType.ExternalJavaLibrary)
					.setArtifactPath(jar)
					.build(),
			)
		}

		variantDeps.setMainArtifact(
			AndroidModels.ArtifactDependencies
				.newBuilder()
				.setCompileGraph(graph.build())
				.build(),
		)

		val module =
			AndroidModule(
				gradleProject(path)
					.setAndroidProject(
						AndroidModels.AndroidProject
							.newBuilder()
							.setProjectType(AndroidModels.ProjectType.LibraryProject)
							.setVariantDependencies(variantDeps.build())
							.setClassesJarPath(generatedJar.absolutePath)
							.build(),
					).build(),
			)

		// The selected variant comes from the project manager's sync state, which a test cannot set.
		val variant =
			AndroidModels.AndroidVariant
				.newBuilder()
				.setName(VARIANT)
				.setMainArtifact(
					AndroidModels.AndroidArtifact
						.newBuilder()
						.setName(VARIANT)
						.addClassJarPaths(rJar.absolutePath)
						.build(),
				).build()
		return spyk(module).also { every { it.getSelectedVariant() } returns variant }
	}

	/** Builds a [JavaModule] whose `build/libs/<name>.jar` exists on disk. */
	private fun javaModule(path: String): JavaModule {
		builtFile("${path.replace(':', '/')}/build/libs/${path.trimStart(':')}.jar")
		return JavaModule(
			gradleProject(path)
				.setJavaProject(JavaModels.JavaProject.getDefaultInstance())
				.build(),
		)
	}

	private fun gradleProject(path: String): GradleModels.GradleProject.Builder {
		val dir = moduleDir(path)
		return GradleModels.GradleProject
			.newBuilder()
			.setName(path.trimStart(':'))
			.setPath(path)
			.setProjectDirPath(dir.path)
			.setBuildDirPath(File(dir, "build").path)
			.setBuildScriptPath(File(dir, "build.gradle").path)
	}

	private fun installWorkspace(vararg modules: ModuleProject): Workspace {
		val root = GradleProject(gradleProject(":").setName("root").build())
		val workspace = Workspace(rootProject = root, subProjects = modules.toList(), syncIssues = emptyList())
		ProjectManagerImpl.getInstance().workspace = workspace
		return workspace
	}

	private companion object {
		const val VARIANT = "debug"
		const val MODIFIED_AT = 1_700_000_000_000L
	}
}
