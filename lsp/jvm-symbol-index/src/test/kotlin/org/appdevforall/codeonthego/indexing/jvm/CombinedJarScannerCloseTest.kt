package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Closing a JAR scan part-way closes the JAR, which an abandoned `sequence {}` never does itself. */
@RunWith(JUnit4::class)
class CombinedJarScannerCloseTest {
	@get:Rule
	val temp = TemporaryFolder()

	private fun emptyClass(internalName: String): ByteArray =
		ClassWriter(0)
			.apply {
				visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
				visitEnd()
			}.toByteArray()

	@Test
	fun `closing a scan part-way closes its jar`() {
		val jar = temp.newFile("two.jar")
		JarOutputStream(jar.outputStream()).use { out ->
			for (name in listOf("com/example/A", "com/example/B")) {
				out.putNextEntry(JarEntry("$name.class"))
				out.write(emptyClass(name))
				out.closeEntry()
			}
		}
		val scan = CombinedJarScanner.scan(jar.toPath(), "two")
		val symbols = scan.iterator()
		symbols.next()

		scan.close()

		val afterClose = runCatching { while (symbols.hasNext()) symbols.next() }.exceptionOrNull()
		assertThat(afterClose).isInstanceOf(IllegalStateException::class.java)
		assertThat(afterClose).hasMessageThat().contains("closed")
	}
}
