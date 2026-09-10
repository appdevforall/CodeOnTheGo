package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import javax.tools.ToolProvider

class FinalStripperTest {
	private fun compileToDir(
		className: String,
		source: String,
	): File {
		val dir = Files.createTempDirectory("final-stripper").toFile()
		val src = dir.resolve("$className.java").apply { writeText(source) }
		val compiler = ToolProvider.getSystemJavaCompiler()
		check(compiler.run(null, null, null, "-d", dir.absolutePath, src.absolutePath) == 0) {
			"test fixture failed to compile"
		}
		return dir
	}

	private fun compile(
		className: String,
		source: String,
	): ByteArray = compileToDir(className, source).resolve("$className.class").readBytes()

	private fun accessFlags(classBytes: ByteArray): Int = ClassReader(classBytes).access

	private fun methodAccessFlags(
		classBytes: ByteArray,
		methodName: String,
	): Int {
		var access = 0
		ClassReader(classBytes).accept(
			object : ClassVisitor(Opcodes.ASM9) {
				override fun visitMethod(
					methodAccess: Int,
					name: String?,
					descriptor: String?,
					signature: String?,
					exceptions: Array<out String>?,
				): MethodVisitor? {
					if (name == methodName) access = methodAccess
					return null
				}
			},
			0,
		)
		return access
	}

	/** Defines exactly the bytes it is handed, so stripped output can be loaded and extended. */
	private class BytesClassLoader(
		private val classes: Map<String, ByteArray>,
	) : ClassLoader(BytesClassLoader::class.java.classLoader) {
		override fun findClass(name: String): Class<*> {
			val bytes = classes[name] ?: return super.findClass(name)
			return defineClass(name, bytes, 0, bytes.size)
		}
	}

	/**
	 * Generates `public class <name> extends <superName>` with a default constructor - the shape
	 * of the proxy app's generated Proxy*Activity classes, which is what the strip exists to make
	 * loadable. Version 52 loads on any JDK these tests run on, and the JVM places no version
	 * relationship between a class and its superclass.
	 *
	 * @param superName internal name of the class to extend, e.g. `SealedFixture`.
	 * @param name internal name to give the generated subclass.
	 * @return a whole class file.
	 */
	private fun subclassBytes(
		superName: String,
		name: String,
	): ByteArray {
		val writer = ClassWriter(0)
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, superName, null)
		val constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
		constructor.visitCode()
		constructor.visitVarInsn(Opcodes.ALOAD, 0)
		constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
		constructor.visitInsn(Opcodes.RETURN)
		constructor.visitMaxs(1, 1)
		constructor.visitEnd()
		writer.visitEnd()
		return writer.toByteArray()
	}

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

	@Test
	fun `stripped bytes load and a generated subclass of them instantiates`() {
		// The contract is not "the flag is clear" but "a proxy can extend it": the JVM resolves
		// the superclass while defining the subclass and rejects a final one, the same check the
		// dex verifier makes on device. Asserting the flag alone would pass on bytes no verifier
		// accepts (a broken constant pool, say).
		val bytes = compile("SealedFixture", "public final class SealedFixture { public int v() { return 5; } }")

		val stripped = FinalStripper.strip(bytes)

		val loader =
			BytesClassLoader(
				mapOf(
					"SealedFixture" to stripped,
					"SubSealed" to subclassBytes("SealedFixture", "SubSealed"),
				),
			)
		val opened = loader.loadClass("SealedFixture")
		assertThat(Modifier.isFinal(opened.modifiers)).isFalse()
		val instance = loader.loadClass("SubSealed").getDeclaredConstructor().newInstance()
		assertThat(opened.isInstance(instance)).isTrue()
		assertThat(opened.getMethod("v").invoke(instance)).isEqualTo(5)
	}

	@Test
	fun `the same subclass over UNSTRIPPED bytes is rejected by the JVM`() {
		// Control for the test above: with the strip removed (or turned into a no-op) the JVM
		// refuses the subclass, so that test cannot pass vacuously. A generator bug would fail
		// both tests, never only this one.
		val bytes = compile("ClosedFixture", "public final class ClosedFixture { public int v() { return 5; } }")
		val loader =
			BytesClassLoader(
				mapOf(
					"ClosedFixture" to bytes,
					"SubClosed" to subclassBytes("ClosedFixture", "SubClosed"),
				),
			)

		// IncompatibleClassChangeError on HotSpot ("cannot inherit from final class"); the
		// assertion names the LinkageError family so it does not pin one JVM's choice, and
		// instantiates so a JVM that defers the check to initialization is covered too.
		assertThrows(LinkageError::class.java) {
			loader.loadClass("SubClosed").getDeclaredConstructor().newInstance()
		}
	}

	@Test
	fun `a stripped nested class loads and can be extended, InnerClasses entry included`() {
		// DexTool strips every .class file it walks, so a nested pair arrives here as two
		// separate strips. HotSpot computes a member class's reflective modifiers from the
		// InnerClasses attribute, so the modifier assertion also exercises the entry rewrite
		// FinalStripperInnerClassTest checks at byte level - though only the subclass step below
		// can fail on the class-level flag alone.
		val dir = compileToDir("Nested", "public class Nested {\n\tpublic static final class Inner {}\n}\n")
		val outer = FinalStripper.strip(dir.resolve("Nested.class").readBytes())
		val inner = FinalStripper.strip(dir.resolve("Nested\$Inner.class").readBytes())

		val loader =
			BytesClassLoader(
				mapOf(
					"Nested" to outer,
					"Nested\$Inner" to inner,
					"SubInner" to subclassBytes("Nested\$Inner", "SubInner"),
				),
			)
		val openedInner = loader.loadClass("Nested\$Inner")
		assertThat(Modifier.isFinal(openedInner.modifiers)).isFalse()
		val instance = loader.loadClass("SubInner").getDeclaredConstructor().newInstance()
		assertThat(openedInner.isInstance(instance)).isTrue()
	}

	@Test
	fun `a final METHOD keeps its flag - the strip opens classes, not members`() {
		// Deliberate scope, matching the gradle-plugin's ClassOpener byte for byte: the payload
		// dex must carry what the gen-0 baseline opened, no more. A final lifecycle method that
		// a generated proxy overrides fails at gen-0, in the proxy's javac pass, not here.
		val bytes =
			compile(
				"FinalMethodFixture",
				"public final class FinalMethodFixture { public final int v() { return 3; } }",
			)
		assertThat(methodAccessFlags(bytes, "v") and Opcodes.ACC_FINAL).isEqualTo(Opcodes.ACC_FINAL)

		val stripped = FinalStripper.strip(bytes)

		assertThat(accessFlags(stripped) and Opcodes.ACC_FINAL).isEqualTo(0)
		assertThat(methodAccessFlags(stripped, "v") and Opcodes.ACC_FINAL).isEqualTo(Opcodes.ACC_FINAL)
	}
}
