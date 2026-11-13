package com.itsaky.androidide.projects.classpath

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.javac.services.fs.CachingJarFileSystemProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JarFsClasspathReaderListClassesTest {
	private lateinit var tmpDir: File
	private lateinit var jar: File

	@Before
	fun setUp() {
		CachingJarFileSystemProvider.clearCache()
		tmpDir = Files.createTempDirectory("list-classes").toFile()
		jar = File(tmpDir, "fixture.jar")
		ZipOutputStream(jar.outputStream()).use { zip ->
			listOf(
				"Root.class",
				"META-INF/MANIFEST.MF",
				"META-INF/versions/9/a/B.class",
				"com/example/A.class",
				"com/example/A\$Inner.class",
				"com/example/package-info.class",
				"com/example/notes.txt",
				"com/example/sub/C.class",
				"2fast/E.class",
			).forEach {
				zip.putNextEntry(ZipEntry(it))
				zip.write(ByteArray(4))
				zip.closeEntry()
			}
		}
	}

	@After
	fun tearDown() {
		CachingJarFileSystemProvider.clearCache()
		tmpDir.deleteRecursively()
	}

	@Test
	fun `lists classes of identifier directories only`() {
		val names = JarFsClasspathReader().listClasses(listOf(jar)).map { it.name }

		assertThat(names).containsExactly("Root", "com.example.A", "com.example.A\$Inner", "com.example.sub.C")
	}
}
