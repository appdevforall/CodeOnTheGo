package com.itsaky.androidide.lsp.java.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.javac.services.fs.JarPackageProviderImpl
import jdkx.tools.JavaFileObject
import jdkx.tools.StandardLocation
import openjdk.tools.javac.file.JavacFileManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(JUnit4::class)
class SourceFileManagerSharedPackagesTest {
	@get:Rule
	val temp = TemporaryFolder()

	private fun jarWithClasses(): File {
		val jar = temp.newFile("lib.jar")
		ZipOutputStream(jar.outputStream()).use { zip ->
			for (entry in listOf("a/", "a/b/", "a/b/C.class", "a/D.class")) {
				zip.putNextEntry(ZipEntry(entry))
				zip.closeEntry()
			}
		}
		return jar
	}

	private fun managerWithClasspath(jar: File): JavacFileManager {
		val manager = SourceFileManager.createDelegateFileManager()
		manager.setLocation(StandardLocation.CLASS_PATH, listOf(jar))
		manager.list(StandardLocation.CLASS_PATH, "a.b", setOf(JavaFileObject.Kind.CLASS), false).toList()
		return manager
	}

	private fun containerPackages(manager: JavacFileManager): List<Any?> {
		val containers = manager.javaClass.getDeclaredField("containers").apply { isAccessible = true }
		return (containers.get(manager) as Map<*, *>).values.map { container ->
			container!!
				.javaClass
				.getDeclaredField("packages")
				.apply { isAccessible = true }
				.get(container)
		}
	}

	@Test
	fun `archive containers of separate managers share the jar package map`() {
		val jar = jarWithClasses()
		val shared = JarPackageProviderImpl.getPackages(jar.toPath())

		val first = containerPackages(managerWithClasspath(jar))
		val second = containerPackages(managerWithClasspath(jar))

		assertThat(first).isNotEmpty()
		assertThat(first.all { it === shared }).isTrue()
		assertThat(second.all { it === shared }).isTrue()
	}

	@Test
	fun `classes are still listed through the shared package map`() {
		val manager = managerWithClasspath(jarWithClasses())

		val listed = manager.list(StandardLocation.CLASS_PATH, "a.b", setOf(JavaFileObject.Kind.CLASS), false)

		assertThat(listed.map { it.isNameCompatible("C", JavaFileObject.Kind.CLASS) }).containsExactly(true)
	}
}
