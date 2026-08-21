package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.compile.JavaCompileStep
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File

/**
 * The InnerClasses attribute carries its own copy of each nested class's access flags;
 * the dex verifier reads finality from there too, so stripping only the class-level
 * ACC_FINAL would leave a final nested class the proxies cannot extend.
 */
class FinalStripperInnerClassTest {
	@TempDir
	lateinit var tempDir: File

	private fun innerAccessOf(classBytes: ByteArray): Int? {
		var access: Int? = null
		ClassReader(classBytes).accept(
			object : ClassVisitor(Opcodes.ASM9) {
				override fun visitInnerClass(
					name: String?,
					outerName: String?,
					innerName: String?,
					innerAccess: Int,
				) {
					if (innerName == "Inner") access = innerAccess
				}
			},
			0,
		)
		return access
	}

	@Test
	fun `clears ACC_FINAL from the InnerClasses attribute entries`() {
		val source =
			File(tempDir, "Outer.java").apply {
				writeText("package demo;\n\npublic class Outer {\n\tpublic final class Inner {}\n}\n")
			}
		val classesDir = File(tempDir, "classes").apply { mkdirs() }
		val compiled = JavaCompileStep.compile(listOf(source), emptyList(), classesDir)
		check(compiled.success) { "fixture compile failed: ${compiled.diagnostics}" }
		val outerBytes = File(classesDir, "demo/Outer.class").readBytes()
		// Guard against a vacuous fixture: the entry must start out final.
		assertThat(innerAccessOf(outerBytes)!! and Opcodes.ACC_FINAL).isEqualTo(Opcodes.ACC_FINAL)

		val stripped = FinalStripper.strip(outerBytes)

		val strippedAccess = innerAccessOf(stripped)!!
		assertThat(strippedAccess and Opcodes.ACC_FINAL).isEqualTo(0)
		// Everything else about the entry survives (still a public member class).
		assertThat(strippedAccess and Opcodes.ACC_PUBLIC).isEqualTo(Opcodes.ACC_PUBLIC)
	}
}
