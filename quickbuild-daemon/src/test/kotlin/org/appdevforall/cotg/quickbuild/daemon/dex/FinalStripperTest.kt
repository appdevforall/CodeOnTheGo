package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import javax.tools.ToolProvider

class FinalStripperTest {
	private fun compile(
		className: String,
		source: String,
	): ByteArray {
		val dir = Files.createTempDirectory("final-stripper").toFile()
		val src = dir.resolve("$className.java").apply { writeText(source) }
		val compiler = ToolProvider.getSystemJavaCompiler()
		check(compiler.run(null, null, null, "-d", dir.absolutePath, src.absolutePath) == 0) {
			"test fixture failed to compile"
		}
		return dir.resolve("$className.class").readBytes()
	}

	private fun accessFlags(classBytes: ByteArray): Int = ClassReader(classBytes).access

	@Test
	fun `clears ACC_FINAL from a final class`() {
		val bytes = compile("FinalFixture", "public final class FinalFixture {}")
		assertThat(accessFlags(bytes) and Opcodes.ACC_FINAL).isNotEqualTo(0)

		val stripped = FinalStripper.strip(bytes)

		assertThat(accessFlags(stripped) and Opcodes.ACC_FINAL).isEqualTo(0)
		// The class is otherwise intact: same name, still loadable by ASM, still public.
		assertThat(ClassReader(stripped).className).isEqualTo("FinalFixture")
		assertThat(accessFlags(stripped) and Opcodes.ACC_PUBLIC).isNotEqualTo(0)
	}

	@Test
	fun `leaves a non-final class byte-identical in behavior`() {
		val bytes = compile("OpenFixture", "public class OpenFixture { public int f() { return 7; } }")

		val stripped = FinalStripper.strip(bytes)

		assertThat(accessFlags(stripped)).isEqualTo(accessFlags(bytes))
		assertThat(ClassReader(stripped).className).isEqualTo("OpenFixture")
	}
}
