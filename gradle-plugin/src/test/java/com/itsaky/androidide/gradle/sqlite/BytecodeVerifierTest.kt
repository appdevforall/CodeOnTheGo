package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

/** The verifier helper itself must catch a bad type and a stale frame, or its "OK" means nothing. */
class BytecodeVerifierTest {
	private fun clazz(build: (ClassWriter) -> Unit): Map<String, ByteArray> {
		val w = ClassWriter(0)
		w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/Bad", null, "java/lang/Object", null)
		build(w)
		w.visitEnd()
		return mapOf("p/Bad" to w.toByteArray())
	}

	@Test
	fun `rejects an operand of the wrong type`() {
		val classes =
			clazz { w ->
				w.visitMethod(Opcodes.ACC_STATIC, "m", "(Ljava/lang/Object;)I", null, null).apply {
					visitCode()
					visitVarInsn(Opcodes.ALOAD, 0)
					visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
					visitInsn(Opcodes.IRETURN)
					visitMaxs(1, 1)
					visitEnd()
				}
			}
		val e = assertThrows(IllegalStateException::class.java) { BytecodeVerifier.verify(classes) }
		assertThat(e.message).contains("p/Bad")
	}

	@Test
	fun `rejects a stack map frame that disagrees with the code`() {
		val classes =
			clazz { w ->
				w.visitMethod(Opcodes.ACC_STATIC, "m", "(Ljava/lang/Object;)I", null, null).apply {
					visitCode()
					val l = Label()
					visitVarInsn(Opcodes.ALOAD, 0)
					visitJumpInsn(Opcodes.IFNULL, l)
					visitLabel(l)
					// the frame claims a String where the parameter is an Object
					visitFrame(Opcodes.F_NEW, 1, arrayOf<Any>("java/lang/String"), 0, arrayOf<Any>())
					visitInsn(Opcodes.ICONST_0)
					visitInsn(Opcodes.IRETURN)
					visitMaxs(1, 1)
					visitEnd()
				}
			}
		val e = assertThrows(IllegalStateException::class.java) { BytecodeVerifier.verify(classes) }
		assertThat(e.message).contains("p/Bad")
	}
}
