package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

@RunWith(JUnit4::class)
class CombinedJarScannerTest {
	private fun openFds() = File("/dev/fd").list()!!.size

	private fun tempJar(): Path =
		Files.createTempFile("scan", ".jar").also { jar ->
			JarOutputStream(Files.newOutputStream(jar)).use { out ->
				out.putNextEntry(JarEntry("java/lang/Runnable.class"))
				Runnable::class.java.getResourceAsStream("Runnable.class")!!.use { it.copyTo(out) }
				out.closeEntry()
			}
		}

	@Test
	fun `scan closes the JAR when collection is cancelled after the first symbol`() =
		runTest {
			val warmUp = tempJar()
			val jar = tempJar()
			try {
				// Warm up class loading on a different file: JarFiles on one file share a descriptor.
				CombinedJarScanner.scan(warmUp).toList()

				val before = openFds()
				CombinedJarScanner.scan(jar).first()

				assertThat(openFds()).isAtMost(before)
			} finally {
				Files.delete(warmUp)
				Files.delete(jar)
			}
		}
}
