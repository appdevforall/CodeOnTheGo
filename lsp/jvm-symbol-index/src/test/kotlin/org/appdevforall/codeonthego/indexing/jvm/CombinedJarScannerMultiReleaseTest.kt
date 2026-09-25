package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.com.intellij.mock.MockApplication
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A multi-release JAR must contribute its base classes only.
 *
 * Its `META-INF/versions/<n>/` copy of a class shares the base class's key within the same source,
 * so indexing both let the versioned copy overwrite the base row.
 */
@RunWith(JUnit4::class)
class CombinedJarScannerMultiReleaseTest {
	@get:Rule
	val temp = TemporaryFolder()

	private fun classWithMethod(
		internalName: String,
		methodName: String,
	): ByteArray =
		ClassWriter(0)
			.apply {
				visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
				visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null).visitEnd()
				visitEnd()
			}.toByteArray()

	private fun multiReleaseJar(): File {
		val manifest =
			Manifest().apply {
				mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
				mainAttributes[Attributes.Name("Multi-Release")] = "true"
			}
		val jar = temp.newFile("multi-release.jar")
		JarOutputStream(jar.outputStream(), manifest).use { out ->
			fun put(
				path: String,
				bytes: ByteArray,
			) {
				out.putNextEntry(JarEntry(path))
				out.write(bytes)
				out.closeEntry()
			}
			put("com/example/Widget.class", classWithMethod("com/example/Widget", "base"))
			put("META-INF/versions/11/com/example/Widget.class", classWithMethod("com/example/Widget", "versioned"))
			put("META-INF/versions/11/com/example/Extra.class", classWithMethod("com/example/Extra", "extra"))
		}
		return jar
	}

	private fun assertOnlyBaseClasses(symbols: List<JvmSymbol>) {
		assertThat(symbols.filter { it.kind.isJvmClass }.map { it.key }).containsExactly("com/example/Widget")
		assertThat(symbols.filter { it.kind == JvmSymbolKind.FUNCTION }.map { it.shortName }).containsExactly("base")
	}

	@Test
	fun `a jar path scan skips versioned entries`() {
		val jar = multiReleaseJar()

		assertOnlyBaseClasses(CombinedJarScanner.scan(jar.toPath(), "mr").toList())
	}

	@Test
	fun `a virtual file scan skips versioned entries`() {
		val jar = multiReleaseJar()
		val disposable = Disposer.newDisposable()
		val jarFileSystem = CoreJarFileSystem()
		try {
			// Reading a jar entry's contents consults the application's file size limits.
			ApplicationManager.setApplication(MockApplication(disposable), disposable)
			val root = jarFileSystem.findFileByPath("${jar.absolutePath}!/")!!

			assertOnlyBaseClasses(CombinedJarScanner.scan(root, "mr").toList())
		} finally {
			// The handler this test opened for the jar keeps its file handle open; releasing it here
			// is what lets TemporaryFolder delete the jar afterwards.
			jarFileSystem.clearHandlersCache()
			Disposer.dispose(disposable)
		}
	}
}
