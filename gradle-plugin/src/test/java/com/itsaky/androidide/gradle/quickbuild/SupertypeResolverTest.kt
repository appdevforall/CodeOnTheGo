package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class SupertypeResolverTest {
	/** @param superName null writes a class with no superclass, the way `java/lang/Object` is encoded. */
	private fun classBytes(
		name: String,
		superName: String?,
		interfaces: Array<String>? = null,
	): ByteArray {
		val writer = ClassWriter(0)
		writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, superName, interfaces)
		writer.visitEnd()
		return writer.toByteArray()
	}

	private fun writeClassFile(
		root: File,
		name: String,
		superName: String?,
		interfaces: Array<String>? = null,
	) {
		File(root, "$name.class")
			.apply { parentFile.mkdirs() }
			.writeBytes(classBytes(name, superName, interfaces))
	}

	/** Payload layout the divert task produces: dirs/N trees + jars/N.jar. */
	private fun payloadRoot(tempDir: File): File {
		val root = File(tempDir, "payload-classes")
		val dir = File(root, "dirs/0")
		writeClassFile(dir, "com/example/app/SyncService", "com/example/app/BaseService")
		writeClassFile(dir, "com/example/app/MainActivity", "androidx/appcompat/app/AppCompatActivity")

		val jarsDir = File(root, "jars").apply { mkdirs() }
		JarOutputStream(File(jarsDir, "0.jar").outputStream()).use { out ->
			out.putNextEntry(JarEntry("com/example/app/BaseService.class"))
			out.write(classBytes("com/example/app/BaseService", "android/app/Service"))
			out.closeEntry()
		}
		return root
	}

	@Test
	fun `indexes class headers from both dir trees and jars`(
		@TempDir tempDir: File,
	) {
		val index = SupertypeResolver.supertypeIndex(payloadRoot(tempDir))

		assertThat(index).containsEntry("com.example.app.SyncService", listOf("com.example.app.BaseService"))
		assertThat(index).containsEntry("com.example.app.BaseService", listOf("android.app.Service"))
		assertThat(index)
			.containsEntry("com.example.app.MainActivity", listOf("androidx.appcompat.app.AppCompatActivity"))
	}

	@Test
	fun `chain follows project-compiled supers and stops at the first library class`(
		@TempDir tempDir: File,
	) {
		val index = SupertypeResolver.supertypeIndex(payloadRoot(tempDir))

		// BaseService is project-compiled (in the payload); android.app.Service is not.
		assertThat(SupertypeResolver.chainFor("com.example.app.SyncService", index))
			.containsExactly("com.example.app.BaseService")
			.inOrder()
		// MainActivity's direct super is a library class: empty chain.
		assertThat(SupertypeResolver.chainFor("com.example.app.MainActivity", index)).isEmpty()
	}

	@Test
	fun `chain includes project-compiled interfaces, not just the superclass chain`(
		@TempDir tempDir: File,
	) {
		val root = File(tempDir, "payload-classes")
		val dir = File(root, "dirs/0")
		// SyncService extends BaseService implements Ticker; Ticker extends TickerBase.
		writeClassFile(
			dir,
			"com/example/app/SyncService",
			"com/example/app/BaseService",
			arrayOf("com/example/app/Ticker", "android/os/Parcelable"),
		)
		writeClassFile(dir, "com/example/app/BaseService", "android/app/Service")
		writeClassFile(
			dir,
			"com/example/app/Ticker",
			"java/lang/Object",
			arrayOf("com/example/app/TickerBase"),
		)
		writeClassFile(dir, "com/example/app/TickerBase", "java/lang/Object")

		val index = SupertypeResolver.supertypeIndex(root)

		// Superclass (BaseService), the implemented project interface (Ticker) and its
		// project super-interface (TickerBase) are all in the closure; the framework
		// interface android.os.Parcelable is not project-compiled and is dropped.
		assertThat(SupertypeResolver.chainFor("com.example.app.SyncService", index))
			.containsExactly(
				"com.example.app.BaseService",
				"com.example.app.Ticker",
				"com.example.app.TickerBase",
			)
	}

	@Test
	fun `chain of an unknown class is empty`(
		@TempDir tempDir: File,
	) {
		val index = SupertypeResolver.supertypeIndex(payloadRoot(tempDir))

		assertThat(SupertypeResolver.chainFor("com.example.app.NotCompiledHere", index)).isEmpty()
	}

	@Test
	fun `chain terminates on a supertype cycle instead of looping`() {
		// Impossible from javac output, but the resolver reads whatever bytes are on disk.
		val index = mapOf("a.A" to listOf("a.B"), "a.B" to listOf("a.A"))

		assertThat(SupertypeResolver.chainFor("a.A", index)).containsExactly("a.B").inOrder()
	}

	@Test
	fun `unreadable class files are skipped, not fatal`(
		@TempDir tempDir: File,
	) {
		val root = File(tempDir, "payload-classes")
		writeClassFile(File(root, "dirs/0"), "com/example/app/Good", "java/lang/Object")
		File(root, "dirs/0/com/example/app/Broken.class").writeBytes(byteArrayOf(1, 2, 3))

		val index = SupertypeResolver.supertypeIndex(root)

		assertThat(index).containsEntry("com.example.app.Good", listOf("java.lang.Object"))
		assertThat(index).doesNotContainKey("com.example.app.Broken")
	}

	@Test
	fun `non-class files in the dir trees are ignored`(
		@TempDir tempDir: File,
	) {
		val root = File(tempDir, "payload-classes")
		val dir = File(root, "dirs/0")
		writeClassFile(dir, "com/example/app/Good", "java/lang/Object")
		// Real class bytes under a non-class name (kotlin_module, .txt resources and friends
		// sit in the same trees): only the extension filter keeps this out of the index.
		File(dir, "com/example/app/Hidden.txt")
			.writeBytes(classBytes("com/example/app/Hidden", "java/lang/Object"))

		val index = SupertypeResolver.supertypeIndex(root)

		assertThat(index).containsExactly("com.example.app.Good", listOf("java.lang.Object"))
	}

	@Test
	fun `a class with no supertypes is left out of the index`(
		@TempDir tempDir: File,
	) {
		val root = File(tempDir, "payload-classes")
		val dir = File(root, "dirs/0")
		writeClassFile(dir, "com/example/app/Good", "java/lang/Object")
		// java.lang.Object and module-info declare neither a superclass nor an interface;
		// an entry for them would be a supertype edge to nowhere.
		writeClassFile(dir, "java/lang/Object", superName = null)

		val index = SupertypeResolver.supertypeIndex(root)

		assertThat(index).containsExactly("com.example.app.Good", listOf("java.lang.Object"))
	}

	@Test
	fun `jar directory entries and non-class entries are ignored`(
		@TempDir tempDir: File,
	) {
		val root = File(tempDir, "payload-classes")
		val jarsDir = File(root, "jars").apply { mkdirs() }
		JarOutputStream(File(jarsDir, "0.jar").outputStream()).use { out ->
			out.putNextEntry(JarEntry("com/example/app/"))
			out.closeEntry()
			out.putNextEntry(JarEntry("com/example/app/Hidden.txt"))
			out.write(classBytes("com/example/app/Hidden", "java/lang/Object"))
			out.closeEntry()
			out.putNextEntry(JarEntry("com/example/app/BaseService.class"))
			out.write(classBytes("com/example/app/BaseService", "android/app/Service"))
			out.closeEntry()
		}

		val index = SupertypeResolver.supertypeIndex(root)

		assertThat(index).containsExactly("com.example.app.BaseService", listOf("android.app.Service"))
	}

	@Test
	fun `unreadable jar entries are skipped, not fatal`(
		@TempDir tempDir: File,
	) {
		val root = File(tempDir, "payload-classes")
		val jarsDir = File(root, "jars").apply { mkdirs() }
		JarOutputStream(File(jarsDir, "0.jar").outputStream()).use { out ->
			out.putNextEntry(JarEntry("com/example/app/Broken.class"))
			out.write(byteArrayOf(1, 2, 3))
			out.closeEntry()
			out.putNextEntry(JarEntry("com/example/app/BaseService.class"))
			out.write(classBytes("com/example/app/BaseService", "android/app/Service"))
			out.closeEntry()
		}

		val index = SupertypeResolver.supertypeIndex(root)

		assertThat(index).containsExactly("com.example.app.BaseService", listOf("android.app.Service"))
	}

	@Test
	fun `a corrupt jar is skipped and the remaining payload still indexes`(
		@TempDir tempDir: File,
	) {
		val root = payloadRoot(tempDir)
		File(root, "jars/corrupt.jar").writeBytes("not a zip archive".toByteArray())

		val index = SupertypeResolver.supertypeIndex(root)

		// The good jar's class is still there, so the corrupt one neither aborted the sweep
		// nor took the rest of its own jar list with it.
		assertThat(index).containsEntry("com.example.app.BaseService", listOf("android.app.Service"))
		assertThat(index).containsEntry("com.example.app.SyncService", listOf("com.example.app.BaseService"))
	}
}
