package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.junit.Test
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

@OptIn(KaImplementationDetail::class)
class KtLibraryModuleScopeTest : KtLspTest() {
	@Test
	fun `scope contains every entry of its jar and nothing of another jar`() {
		val a = libraryModule(writeJar("a.jar", "p/A.class", "p/q/B.class"))
		val b = libraryModule(writeJar("b.jar", "p/A.class", "r/C.class"))

		val scope = a.baseContentScope

		assertThat(entriesOf(a).filterNot { scope.contains(it) }).isEmpty()
		assertThat(entriesOf(b).filter { scope.contains(it) }).isEmpty()
	}

	@Test
	fun `scope matches the old url-set scope on a jar and a class directory`() {
		val jar = libraryModule(writeJar("a.jar", "p/A.class", "p/q/B.class"))
		val classDir = libraryModule(writeClassDirectory("classes", "p/D.class", "p/s/E.class"))
		val foreign = libraryModule(writeJar("b.jar", "p/A.class", "r/C.class"))
		val module = libraryModule(jar.contentRoots + classDir.contentRoots)
		val oldUrls = module.computeFiles(extended = true).map { it.url }.toSet()

		val scope = module.baseContentScope

		val everyFile = listOf(jar, classDir, foreign).flatMap { entriesOf(it) }
		assertThat(everyFile.associate { it.url to scope.contains(it) })
			.isEqualTo(everyFile.associate { it.url to (it.url in oldUrls) })
	}

	@Test
	fun `class file added to a class directory after the scope is built is contained`() {
		val dir = writeClassDirectory("classes", "p/D.class")
		val module = libraryModule(dir)
		val scope = module.baseContentScope

		val added = dir.resolve("p/Added.class").also { it.writeBytes(byteArrayOf(1)) }
		val addedFile = findAfterRefresh(module, added)

		assertThat(scope.contains(addedFile)).isTrue()
	}

	@Test
	fun `library module base scope holds only its roots`() {
		val module = libraryModule(writeJar("a.jar", "p/A.class", "p/q/B.class"))

		val scope = module.baseContentScope as LibraryRootsScope

		assertThat(scope.roots).isEqualTo(module.computeFiles(extended = false).toSet())
	}

	private fun libraryModule(vararg roots: Path): KtLibraryModule = libraryModule(roots.toSet())

	private fun libraryModule(roots: Set<Path>): KtLibraryModule =
		buildKtLibraryModule(env.project, env.applicationEnv) {
			id = roots.joinToString { it.name }
			roots.forEach { addContentRoot(it) }
		}

	private fun entriesOf(module: KtLibraryModule): List<VirtualFile> =
		module.computeFiles(extended = false).flatMap { LibraryUtils.getAllVirtualFilesFromRoot(it, includeRoot = true) }.toList()

	private fun findAfterRefresh(
		module: KtLibraryModule,
		path: Path,
	): VirtualFile {
		val root = module.computeFiles(extended = false).single()
		root.refresh(false, true)
		return LibraryUtils
			.getAllVirtualFilesFromRoot(root, includeRoot = true)
			.single { it.name == path.name }
	}

	private fun writeJar(
		name: String,
		vararg entries: String,
	): Path {
		val jar =
			lspTestRule.tempDir.root
				.toPath()
				.resolve(name)
		ZipOutputStream(jar.outputStream()).use { zip ->
			entries.forEach {
				zip.putNextEntry(ZipEntry(it))
				zip.write(1)
				zip.closeEntry()
			}
		}
		return jar
	}

	private fun writeClassDirectory(
		name: String,
		vararg entries: String,
	): Path {
		val dir =
			lspTestRule.tempDir.root
				.toPath()
				.resolve(name)
		entries.forEach {
			val file = dir.resolve(it)
			file.parent.createDirectories()
			file.writeBytes(byteArrayOf(1))
		}
		return dir
	}
}
