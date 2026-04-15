package com.itsaky.androidide.desugaring

import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type

/**
 * Replaces all bytecode references to one or more classes within a method body.
 *
 * Covered visit sites:
 *  - [visitMethodInsn]        — owner and embedded descriptor
 *  - [visitFieldInsn]         — owner and field descriptor
 *  - [visitTypeInsn]          — NEW / CHECKCAST / INSTANCEOF / ANEWARRAY operand
 *  - [visitLdcInsn]           — class-literal Type constants
 *  - [visitLocalVariable]     — local variable descriptor and generic signature
 *  - [visitMultiANewArrayInsn]— array descriptor
 *  - [visitTryCatchBlock]     — caught exception type
 *
 * @param classReplacements Mapping from source internal name (slash-notation)
 *   to target internal name (slash-notation). An empty map is a no-op.
 *
 * @author Akash Yadav
 */
class ClassRefReplacingMethodVisitor(
	api: Int,
	mv: MethodVisitor?,
	private val classReplacements: Map<String, String>,
) : MethodVisitor(api, mv) {

	override fun visitMethodInsn(
		opcode: Int,
		owner: String,
		name: String,
		descriptor: String,
		isInterface: Boolean,
	) {
		super.visitMethodInsn(
			opcode,
			replace(owner),
			name,
			replaceInDescriptor(descriptor),
			isInterface,
		)
	}

	override fun visitFieldInsn(
		opcode: Int,
		owner: String,
		name: String,
		descriptor: String,
	) {
		super.visitFieldInsn(
			opcode,
			replace(owner),
			name,
			replaceInDescriptor(descriptor),
		)
	}

	override fun visitTypeInsn(opcode: Int, type: String) {
		super.visitTypeInsn(opcode, replace(type))
	}

	override fun visitLdcInsn(value: Any?) {
		// Replace class-literal constants: Foo.class → Bar.class
		if (value is Type && value.sort == Type.OBJECT) {
			val replaced = replace(value.internalName)
			if (replaced !== value.internalName) {
				super.visitLdcInsn(Type.getObjectType(replaced))
				return
			}
		}
		super.visitLdcInsn(value)
	}

	override fun visitLocalVariable(
		name: String,
		descriptor: String,
		signature: String?,
		start: Label,
		end: Label,
		index: Int,
	) {
		super.visitLocalVariable(
			name,
			replaceInDescriptor(descriptor),
			replaceInSignature(signature),
			start,
			end,
			index,
		)
	}

	override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
		super.visitMultiANewArrayInsn(replaceInDescriptor(descriptor), numDimensions)
	}

	override fun visitTryCatchBlock(
		start: Label,
		end: Label,
		handler: Label,
		type: String?,
	) {
		super.visitTryCatchBlock(start, end, handler, type?.let { replace(it) })
	}

	/** Replaces a bare internal class name (slash-notation). */
	private fun replace(internalName: String): String =
		classReplacements[internalName] ?: internalName

	/**
	 * Substitutes every `L<from>;` token in a JVM descriptor or generic
	 * signature with `L<to>;`.
	 */
	private fun replaceInDescriptor(descriptor: String): String {
		if (classReplacements.isEmpty()) return descriptor
		var result = descriptor
		for ((from, to) in classReplacements) {
			result = result.replace("L$from;", "L$to;")
		}
		return result
	}

	/** Delegates to [replaceInDescriptor]; returns `null` for `null` input. */
	private fun replaceInSignature(signature: String?): String? =
		signature?.let { replaceInDescriptor(it) }
}