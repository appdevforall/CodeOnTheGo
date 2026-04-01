package com.itsaky.androidide.desugaring.dsl

import com.itsaky.androidide.desugaring.internal.parsing.InsnLexer
import com.itsaky.androidide.desugaring.internal.parsing.InsnParser
import com.itsaky.androidide.desugaring.utils.ReflectionUtils
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import java.io.File
import java.lang.reflect.Method
import java.util.TreeSet
import javax.inject.Inject

/**
 * Defines replacements for desugaring.
 *
 * Two replacement strategies are supported and can be combined freely:
 *
 * - **Method-level** ([replaceMethod]): replaces a specific method call with
 *   another, with full control over opcodes and descriptors.
 * - **Class-level** ([replaceClass]): rewrites every bytecode reference to a
 *   given class (owners, descriptors, type instructions, LDC constants, etc.)
 *   with a replacement class. This is a broader, structural operation.
 *
 * When both apply to the same instruction, method-level replacement wins
 * because it runs first in the visitor chain.
 *
 * @author Akash Yadav
 */
abstract class DesugarReplacementsContainer @Inject constructor(
	private val objects: ObjectFactory,
) {

	internal val includePackages = TreeSet<String>()

	internal val instructions =
		mutableMapOf<ReplaceMethodInsnKey, ReplaceMethodInsn>()

	/** Class-level replacements: dot-notation source → dot-notation target. */
	internal val classReplacements = mutableMapOf<String, String>()

	companion object {
		private val PACKAGE_NAME_REGEX =
			Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*${'$'}""")
	}

	fun includePackage(vararg packages: String) {
		for (pck in packages) {
			if (!PACKAGE_NAME_REGEX.matches(pck)) {
				throw IllegalArgumentException("Invalid package name: $pck")
			}
			includePackages.add(pck)
		}
	}

	fun removePackage(vararg packages: String) {
		includePackages.removeAll(packages.toSet())
	}

	fun replaceMethod(configure: Action<ReplaceMethodInsn>) {
		val instruction = objects.newInstance(ReplaceMethodInsn::class.java)
		configure.execute(instruction)
		addReplaceInsns(instruction)
	}

	@JvmOverloads
	fun replaceMethod(
		sourceMethod: Method,
		targetMethod: Method,
		configure: Action<ReplaceMethodInsn> = Action {},
	) {
		val instruction = ReplaceMethodInsn.forMethods(sourceMethod, targetMethod).build()
		configure.execute(instruction)
		if (instruction.requireOpcode == MethodOpcode.INVOKEVIRTUAL
			&& instruction.toOpcode == MethodOpcode.INVOKESTATIC
		) {
			ReflectionUtils.validateVirtualToStaticReplacement(sourceMethod, targetMethod)
		}
		addReplaceInsns(instruction)
	}

	/**
	 * Replaces every bytecode reference to [fromClass] with [toClass].
	 *
	 * This rewrites:
	 * - Instruction owners (`INVOKEVIRTUAL`, `GETFIELD`, `NEW`, `CHECKCAST`, …)
	 * - Type descriptors and generic signatures in method bodies
	 * - Class-literal LDC constants (`Foo.class`)
	 * - Field and method *declaration* descriptors in the instrumented class
	 *
	 * Class names can be provided in dot-notation (`com.example.Foo`) or
	 * slash-notation (`com/example/Foo`).
	 *
	 * Note: unlike [replaceMethod], class-level replacement is applied to
	 * **all** instrumented classes regardless of [includePackage] filters,
	 * because any class may contain a reference to the replaced one.
	 */
	fun replaceClass(fromClass: String, toClass: String) {
		require(fromClass.isNotBlank()) { "fromClass must not be blank." }
		require(toClass.isNotBlank()) { "toClass must not be blank." }
		val from = fromClass.replace('/', '.')
		val to = toClass.replace('/', '.')
		classReplacements[from] = to
	}

	/**
	 * Replaces every bytecode reference to [fromClass] with [toClass].
	 *
	 * @throws UnsupportedOperationException for array or primitive types.
	 */
	fun replaceClass(fromClass: Class<*>, toClass: Class<*>) {
		require(!fromClass.isArray && !fromClass.isPrimitive) {
			"Array and primitive types are not supported for class replacement."
		}
		require(!toClass.isArray && !toClass.isPrimitive) {
			"Array and primitive types are not supported for class replacement."
		}
		replaceClass(fromClass.name, toClass.name)
	}

	fun loadFromFile(file: File) {
		val lexer = InsnLexer(file.readText())
		val parser = InsnParser(lexer)
		val insns = parser.parse()
		addReplaceInsns(insns)
	}

	private fun addReplaceInsns(vararg insns: ReplaceMethodInsn) =
		addReplaceInsns(insns.asIterable())

	private fun addReplaceInsns(insns: Iterable<ReplaceMethodInsn>) {
		for (insn in insns) {
			val className = insn.fromClass.replace('/', '.')
			insn.requireOpcode = insn.requireOpcode ?: MethodOpcode.ANY
			val key = ReplaceMethodInsnKey(className, insn.methodName, insn.methodDescriptor)
			instructions[key] = insn
		}
	}
}