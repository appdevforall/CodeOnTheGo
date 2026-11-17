@file:Suppress("ktlint:standard:max-line-length")

package com.itsaky.androidide.gradle.sqlite

import com.android.build.api.instrumentation.FramesComputationMode
import org.gradle.api.logging.Logging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.Frame
import com.itsaky.androidide.gradle.sqlite.SqliteRewriteRules as Rules

/** Thrown with every unsupported reference of a class, so the build names them all at once. */
class SqliteRewriteException(
	message: String,
) : RuntimeException(message)

/** All supertypes (classes and interfaces, transitive) of a class in internal names, or null. */
fun interface ClassHierarchy {
	fun superTypes(internalName: String): Collection<String>?

	companion object {
		val NONE = ClassHierarchy { null }
	}
}

/** Which rules run on a class. The rename always runs. */
enum class RewritePolicy(
	val redirect: Boolean,
	val fail: Boolean,
) {
	FULL(true, true),

	/** androidx's framework classes (still reachable through AndroidSQLiteDriver; see the docs) and androidx.core's database helpers. */
	NO_FAIL(true, false),

	/** The runtime helper: its redirect targets must not be redirected themselves. */
	RENAME_ONLY(false, false),
	;

	companion object {
		fun forClass(internalName: String): RewritePolicy =
			when {
				internalName.startsWith(Rules.HELPER_PACKAGE_PREFIX) -> RENAME_ONLY
				internalName.startsWith(Rules.FRAMEWORK_PACKAGE_PREFIX) -> NO_FAIL
				internalName.startsWith(Rules.CORE_DATABASE_PACKAGE_PREFIX) -> NO_FAIL
				else -> FULL
			}

		/** Classes the rewrite never touches: requery's own. */
		fun isExcluded(internalName: String): Boolean = internalName.startsWith(Rules.REQUERY_PACKAGE_PREFIX)
	}
}

object SqliteRewriter {
	/** The transform cannot tell an app class from a library class, so the message covers both. */
	private const val WAY_OUT =
		"If this class is the app's, change the code above. If it is in a library, the library cannot run on " +
			"requery: comment out cogo.sqliteDebugger=true in gradle.properties to build without the SQLite debugger."

	private val remapper =
		object : Remapper() {
			override fun map(internalName: String): String = Rules.RENAMES[internalName] ?: internalName
		}

	/**
	 * What the plugin registers with AGP, and what [rewrite] writes. Frames are copied, so the one change
	 * that invalidates them, a retyped `SQLiteCursorDriver.query` parameter, is handled in [ClassRewrite].
	 */
	val FRAMES_MODE = FramesComputationMode.COPY_FRAMES

	private val logger by lazy { Logging.getLogger(SqliteRewriter::class.java) }

	/** For tests: the transformed bytes of one class. */
	fun rewrite(
		bytes: ByteArray,
		hierarchy: ClassHierarchy = ClassHierarchy.NONE,
		warn: (String) -> Unit = {},
	): ByteArray {
		val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
		ClassReader(bytes).accept(createVisitor(writer, hierarchy, warn), 0)
		return writer.toByteArray()
	}

	/**
	 * Buffers the class, applies the call rules on the original (Android) names, then renames on
	 * the way to [next]. Throws from visitEnd, which fails the AGP transform with the message.
	 */
	fun createVisitor(
		next: ClassVisitor,
		hierarchy: ClassHierarchy,
		warn: (String) -> Unit = { logger.warn(it) },
	): ClassVisitor =
		object : ClassNode(Opcodes.ASM9) {
			override fun visitEnd() {
				super.visitEnd()
				if (RewritePolicy.isExcluded(name)) {
					accept(next)
					return
				}
				ClassRewrite(this, RewritePolicy.forClass(name), hierarchy, warn).run()
				accept(ClassRemapper(next, remapper))
			}
		}

	private class TaintValue(
		type: Type?,
		val sqliteCursor: Boolean,
	) : BasicValue(type) {
		// Frame.merge keeps the old value when the merged one equals it, so the taint must count
		override fun equals(other: Any?): Boolean =
			other is BasicValue && super.equals(other) && sqliteCursor == ((other as? TaintValue)?.sqliteCursor ?: false)

		override fun hashCode(): Int = super.hashCode() * 31 + if (sqliteCursor) 1 else 0
	}

	private class ClassRewrite(
		val cn: ClassNode,
		val policy: RewritePolicy,
		val hierarchy: ClassHierarchy,
		val warn: (String) -> Unit,
	) {
		val violations = linkedSetOf<String>()

		fun run() {
			if (policy.fail) {
				checkDeclarations()
			}
			for (m in cn.methods) {
				if (policy.fail && isContextDatabaseMethod(m.name, m.desc) &&
					(cn.superName in Rules.CONTEXT_FAMILY || isContext(cn.name))
				) {
					violations += "method '${m.name}${m.desc}' overrides Context.${m.name}; calls to it are redirected to requery's " +
						"runtime and would skip the override"
				}
				if (policy.redirect && m.name == Rules.DRIVER_QUERY_NAME && m.desc == Rules.DRIVER_QUERY_DESC) {
					retypeParameters(m, Rules.DRIVER_QUERY_NEW_DESC)
				}
				rewriteMethod(m)
			}
			if (violations.isNotEmpty()) {
				throw SqliteRewriteException(
					"Code on the Go cannot move '${cn.name.replace('/', '.')}' from android.database.sqlite to " +
						"requery (cogo.sqliteDebugger=true):\n" + violations.joinToString("\n") { "  - $it" } +
						"\n" + WAY_OUT,
				)
			}
		}

		/**
		 * Gives [m] the descriptor [newDesc], which differs from its own in parameters that become wider
		 * (`String[]` to `Object[]`), and keeps the body valid. The body and its copied frames still treat those
		 * parameters as the old type, so each is cast back on entry. Frames before the first full one are relative
		 * to the method's implicit first frame, which now has the wide type, so every frame is written in full.
		 */
		fun retypeParameters(
			m: MethodNode,
			newDesc: String,
		) {
			val oldDesc = m.desc
			m.desc = newDesc
			if (m.instructions.size() == 0) return
			expandFrames(m, oldDesc)
			val old = Type.getArgumentTypes(oldDesc)
			val new = Type.getArgumentTypes(newDesc)
			var slot = if (m.access and Opcodes.ACC_STATIC != 0) 0 else 1
			val prologue = InsnList()
			for (i in old.indices) {
				if (old[i] != new[i]) {
					prologue.add(VarInsnNode(Opcodes.ALOAD, slot))
					prologue.add(TypeInsnNode(Opcodes.CHECKCAST, old[i].internalName))
					prologue.add(VarInsnNode(Opcodes.ASTORE, slot))
				}
				slot += old[i].size
			}
			m.instructions.insert(prologue)
		}

		/** Rewrites every compressed frame of [m] as a full one, starting from [originalDesc]'s parameters. */
		fun expandFrames(
			m: MethodNode,
			originalDesc: String,
		) {
			var locals = ArrayList<Any>()
			if (m.access and Opcodes.ACC_STATIC == 0) locals += cn.name
			for (t in Type.getArgumentTypes(originalDesc)) {
				locals +=
					when (t.sort) {
						Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER
						Type.FLOAT -> Opcodes.FLOAT
						Type.LONG -> Opcodes.LONG
						Type.DOUBLE -> Opcodes.DOUBLE
						else -> t.internalName
					}
			}
			for (frame in m.instructions) {
				if (frame !is FrameNode) continue
				var stack: List<Any> = emptyList()
				when (frame.type) {
					Opcodes.F_NEW, Opcodes.F_FULL -> {
						locals = ArrayList(frame.local)
						stack = ArrayList(frame.stack)
					}

					Opcodes.F_APPEND -> {
						locals.addAll(frame.local)
					}

					Opcodes.F_CHOP -> {
						repeat(frame.local.size) { locals.removeAt(locals.lastIndex) }
					}

					Opcodes.F_SAME1 -> {
						stack = ArrayList(frame.stack)
					}
				}
				frame.type = Opcodes.F_NEW
				frame.local = ArrayList(locals)
				frame.stack = ArrayList(stack)
			}
		}

		fun checkDeclarations() {
			for (f in cn.fields) {
				if (f.desc.contains(Rules.OPEN_PARAMS)) violations += "field '${f.name}' has type OpenParams, which requery lacks"
			}
			for (m in cn.methods) {
				if (m.desc.contains(Rules.OPEN_PARAMS)) {
					violations += "method '${m.name}${m.desc}' uses OpenParams, which requery lacks"
				}
			}
		}

		private val ancestorsCache = HashMap<String, List<String>>()
		private val superTypesCache = HashMap<String, Collection<String>?>()

		private fun superTypes(owner: String): Collection<String>? = superTypesCache.getOrPut(owner) { hierarchy.superTypes(owner) }

		/** Owners that cannot extend a renamed class: the JDK, Kotlin, and Android outside the renamed set. */
		private fun cannotBeSqlite(owner: String): Boolean =
			owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("kotlin/") || owner.startsWith("kotlinx/") ||
				(owner.startsWith("android/") && owner !in Rules.SQLITE_CLASSES)

		/** The renamed classes that [owner] is or extends, nearest first, so the rule tables see them all. */
		fun renamedAncestors(owner: String): List<String> {
			if (owner.startsWith("[")) return emptyList()
			return ancestorsCache.getOrPut(owner) {
				val direct =
					when {
						owner in Rules.SQLITE_CLASSES -> listOf(owner)
						cannotBeSqlite(owner) -> emptyList()
						else -> superTypes(owner)?.filter { it in Rules.SQLITE_CLASSES }.orEmpty()
					}
				direct.flatMap { listOf(it) + Rules.RENAMED_SUPERS[it].orEmpty() }.distinct()
			}
		}

		fun isContext(owner: String): Boolean = owner in Rules.CONTEXT_FAMILY || superTypes(owner)?.contains(Rules.CONTEXT) == true

		fun rewriteMethod(m: MethodNode) {
			val tainted = if (policy.fail) cursorCastTaint(m) else emptySet()
			var line = -1
			var insn: AbstractInsnNode? = m.instructions.first
			while (insn != null) {
				val following = insn.next
				if (insn is LineNumberNode) {
					line = insn.line
				}
				when (insn) {
					is MethodInsnNode -> {
						methodInsn(m, insn, line)
					}

					is FieldInsnNode -> {
						fieldInsn(m, insn, line)
					}

					is TypeInsnNode -> {
						typeInsn(m, insn, line, tainted)
					}

					is LdcInsnNode -> {
						(insn.cst as? Type)?.let {
							if (it.descriptor.contains(Rules.OPEN_PARAMS)) violate(m, line, "OpenParams, which requery lacks")
						}
						(insn.cst as? String)?.let { sqlConstant(m, line, it) }
					}

					is InvokeDynamicInsnNode -> {
						invokeDynamic(m, insn, line)
					}
				}
				insn = following
			}
		}

		/** requery's native SQLite has no ICU: there is no UNICODE collator, and LOCALIZED compares bytes. */
		fun sqlConstant(
			m: MethodNode,
			line: Int,
			text: String,
		) {
			if (!policy.fail) return
			if (Rules.COLLATE_UNICODE.containsMatchIn(text)) {
				violate(
					m,
					line,
					"SQL text uses COLLATE UNICODE, which does not exist in requery's SQLite (no ICU); the statement would fail",
				)
			}
			if (Rules.COLLATE_LOCALIZED.containsMatchIn(text)) {
				val where = if (line >= 0) "line $line" else "no line number"
				warn(
					"${cn.name.replace('/', '.')}.${m.name} ($where): COLLATE LOCALIZED works in requery's SQLite but sorts by " +
						"byte value (no ICU), not by the device locale",
				)
			}
		}

		fun invokeDynamic(
			m: MethodNode,
			insn: InvokeDynamicInsnNode,
			line: Int,
		) {
			if (insn.desc.contains(Rules.OPEN_PARAMS)) violate(m, line, "OpenParams, which requery lacks")
			for (arg in insn.bsmArgs) {
				when (arg) {
					is String -> sqlConstant(m, line, arg)
					is Handle -> handle(m, line, arg)
					is Type -> if (arg.descriptor.contains(Rules.OPEN_PARAMS)) violate(m, line, "OpenParams, which requery lacks")
				}
			}
		}

		fun violate(
			m: MethodNode,
			line: Int,
			what: String,
		) {
			if (!policy.fail) return
			val where = if (line >= 0) "line $line" else "no line number"
			violations += "${m.name}${m.desc} ($where): $what"
		}

		fun fieldInsn(
			m: MethodNode,
			f: FieldInsnNode,
			line: Int,
		) {
			fieldProblem(f.owner, f.name, f.desc)?.let { violate(m, line, it) }
		}

		fun fieldProblem(
			owner: String,
			name: String,
			desc: String,
		): String? {
			if (desc.contains(Rules.OPEN_PARAMS) || owner == Rules.OPEN_PARAMS) {
				return "OpenParams, which requery lacks"
			}
			// only SQLiteCursor.mWindow is listed today; skip the hierarchy lookup for every other field
			if (name !in Rules.MISSING_FIELD_NAMES) return null
			return renamedAncestors(owner)
				.firstOrNull { Rules.MISSING_MEMBERS["$it.$name"]?.contains(desc) == true }
				?.let { "${simple(it)}.$name does not exist in requery" }
		}

		fun typeInsn(
			m: MethodNode,
			t: TypeInsnNode,
			line: Int,
			tainted: Set<AbstractInsnNode>,
		) {
			if (t.desc.startsWith(Rules.OPEN_PARAMS)) {
				violate(m, line, "OpenParams, which requery lacks")
			}
			if (t.opcode == Opcodes.CHECKCAST && t.desc in Rules.ANDROID_ONLY_CURSOR_TYPES && t in tainted &&
				!isGuardedByInstanceof(m, t)
			) {
				violate(
					m,
					line,
					"cast of an SQLite cursor to ${t.desc.replace('/', '.')}; requery's cursors are not instances of it, " +
						"so the cast would throw",
				)
			}
		}

		/** What the rules do with one call or method handle. */
		sealed class Action {
			object None : Action()

			class Fail(
				val what: String,
			) : Action()

			/** Replace the call by an INVOKESTATIC of the runtime helper. */
			class Redirect(
				val name: String,
				val desc: String,
			) : Action()

			/** Same call, new descriptor; [signal] inserts the CancellationSignal conversion. */
			class Retype(
				val desc: String,
				val signal: Boolean,
			) : Action()
		}

		/** The platform methods on Context that are redirected: name, then the descriptors they match. */
		private fun isContextDatabaseMethod(
			name: String,
			desc: String,
		) = (name == "openOrCreateDatabase" && (desc == Rules.CONTEXT_OPEN_DESC || desc == Rules.CONTEXT_OPEN_HANDLER_DESC)) ||
			(name == "deleteDatabase" && desc == Rules.CONTEXT_DELETE_DESC)

		/**
		 * Decides, without changing anything, what happens to `owner.name desc` invoked with [opcode]
		 * (the opcode a method handle's kind stands for). Redirects and retyping apply only when the
		 * policy allows; a [Action.Fail] is reported only when it fails.
		 */
		fun decide(
			opcode: Int,
			owner: String,
			name: String,
			desc: String,
		): Action {
			if (owner == Rules.OPEN_PARAMS || desc.contains(Rules.OPEN_PARAMS)) {
				return Action.Fail("OpenParams, which requery lacks")
			}
			if (policy.redirect) {
				redirectTarget(opcode, owner, name, desc)?.let { return it }
				if (name == Rules.DRIVER_QUERY_NAME && desc == Rules.DRIVER_QUERY_DESC) {
					return Action.Retype(Rules.DRIVER_QUERY_NEW_DESC, false)
				}
			}
			if (isContextDatabaseMethod(name, desc) && !owner.startsWith("[")) {
				// the redirect above did not take it: super.* calls and owners that are not known to be a Context
				if (opcode == Opcodes.INVOKESPECIAL && isContext(owner)) {
					return Action.Fail(
						"super.$name on a Context cannot be redirected to requery; call it on this or on a Context instead",
					)
				}
				if (superTypes(owner) == null && owner !in Rules.CONTEXT_FAMILY) {
					return Action.Fail(
						"$owner.$name is a Context database method if $owner is a Context, " +
							"but $owner cannot be resolved, so it cannot be redirected to requery",
					)
				}
			}

			// the lookup is skipped for owners no rule below can match, which is nearly every call
			val ancestors = if (needsAncestors(owner, name, desc)) renamedAncestors(owner) else emptyList()
			if (ancestors.isEmpty()) {
				if (owner.startsWith("android/") && mentionsRenamed(desc)) {
					return Action.Fail(
						"${simple(owner)}.$name$desc takes or returns an SQLite class that requery replaces; " +
							"the platform method would not link",
					)
				}
				return Action.None
			}
			for (c in ancestors) {
				if (Rules.MISSING_MEMBERS["$c.$name"]?.contains(desc) == true) {
					return Action.Fail("${simple(c)}.$name$desc does not exist in requery")
				}
			}
			if (desc.contains(Rules.CURSOR_WINDOW)) {
				return Action.Fail("${simple(ancestors.first())}.$name uses android.database.CursorWindow; requery has its own")
			}
			if (!policy.redirect) return Action.None
			val rule =
				Rules.DESCRIPTOR_RULES.firstOrNull { it.owner in ancestors && it.name == name && it.desc == desc } ?: return Action.None
			val args = Type.getArgumentTypes(desc).map { it.descriptor }.toMutableList()
			rule.objectArrayParams.forEach { args[it] = "[Ljava/lang/Object;" }
			val signal = args.last() == "L${Rules.ANDROID_SIGNAL};"
			if (signal) {
				args[args.lastIndex] = "L${Rules.CORE_SIGNAL};"
			}
			return Action.Retype("(" + args.joinToString("") + ")" + Type.getReturnType(desc).descriptor, signal)
		}

		fun methodInsn(
			m: MethodNode,
			mi: MethodInsnNode,
			line: Int,
		) {
			when (val action = decide(mi.opcode, mi.owner, mi.name, mi.desc)) {
				Action.None -> {}

				is Action.Fail -> {
					violate(m, line, action.what)
				}

				is Action.Redirect -> {
					mi.setOpcode(Opcodes.INVOKESTATIC)
					mi.owner = Rules.HELPER
					mi.name = action.name
					mi.desc = action.desc
					mi.itf = false
				}

				is Action.Retype -> {
					if (action.signal) {
						m.instructions.insertBefore(
							mi,
							MethodInsnNode(Opcodes.INVOKESTATIC, Rules.HELPER, "toCancellationSignal", Rules.HELPER_SIGNAL_DESC, false),
						)
					}
					mi.desc = action.desc
				}
			}
		}

		/**
		 * A method handle among an invokedynamic's bootstrap arguments (`db::rawQuery`) points at a
		 * member the call rules would change or reject, but the handle itself cannot be patched in
		 * place the way an instruction is. Such handles fail the build with a hint to write a lambda,
		 * whose body is an ordinary call.
		 */
		fun handle(
			m: MethodNode,
			line: Int,
			h: Handle,
		) {
			val what =
				when (h.tag) {
					Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> {
						fieldProblem(h.owner, h.name, h.desc)
					}

					else -> {
						val opcode =
							when (h.tag) {
								Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC
								Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE
								Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL
								else -> Opcodes.INVOKESPECIAL
							}
						when (val action = decide(opcode, h.owner, h.name, h.desc)) {
							Action.None -> null
							is Action.Fail -> action.what
							is Action.Redirect -> "the runtime takes this call over"
							is Action.Retype -> "its descriptor changes in requery"
						}
					}
				}
			if (what != null) {
				violate(m, line, "method reference ${simple(h.owner)}::${h.name} cannot be rewritten ($what); use a lambda instead")
			}
		}

		/** Returns the replacement for a call the runtime helper takes over, or null. */
		fun redirectTarget(
			opcode: Int,
			owner: String,
			name: String,
			desc: String,
		): Action.Redirect? =
			when {
				opcode == Opcodes.INVOKESTATIC && owner == Rules.DATABASE && name == "openDatabase" &&
					(desc == Rules.HELPER_OPEN_DESC || desc == Rules.HELPER_OPEN_HANDLER_DESC) -> {
					Action.Redirect(name, desc)
				}

				opcode == Opcodes.INVOKEVIRTUAL && name == "openOrCreateDatabase" &&
					(desc == Rules.CONTEXT_OPEN_DESC || desc == Rules.CONTEXT_OPEN_HANDLER_DESC) && isContext(owner) -> {
					Action.Redirect(name, "(L${Rules.CONTEXT};" + desc.substring(1))
				}

				opcode == Opcodes.INVOKEVIRTUAL && name == "deleteDatabase" && desc == Rules.CONTEXT_DELETE_DESC && isContext(owner) -> {
					Action.Redirect(name, "(L${Rules.CONTEXT};Ljava/lang/String;)Z")
				}

				opcode == Opcodes.INVOKESTATIC && owner == Rules.DATABASE_UTILS && (name + desc) in Rules.REDIRECTED_UTILS -> {
					Action.Redirect(name, desc)
				}

				else -> {
					null
				}
			}

		fun needsAncestors(
			owner: String,
			name: String,
			desc: String,
		) = owner in Rules.SQLITE_CLASSES || name in Rules.MISSING_MEMBER_NAMES || name in Rules.DESCRIPTOR_RULE_NAMES ||
			desc.contains(Rules.CURSOR_WINDOW)

		private fun previousReal(node: AbstractInsnNode): AbstractInsnNode? {
			var n = node.previous
			while (n != null && n.opcode < 0) n = n.previous
			return n
		}

		private fun nextReal(node: AbstractInsnNode): AbstractInsnNode? {
			var n = node.next
			while (n != null && n.opcode < 0) n = n.next
			return n
		}

		/** True when the instruction before [label] never falls through to it. */
		private fun endsWithExit(label: AbstractInsnNode): Boolean {
			val op = previousReal(label)?.opcode ?: return false
			return op == Opcodes.GOTO || op == Opcodes.ATHROW || op in Opcodes.IRETURN..Opcodes.RETURN
		}

		/**
		 * The idiom `if (c instanceof X) ... (X) c`: the operand of [cast] is a local read right before it,
		 * and an INSTANCEOF of the same type on that local sits before it in a branch that contains the
		 * cast, with no store to the local in between. A guard that jumps over the cast when false (IFEQ)
		 * covers the instructions up to its target; one that jumps to the cast when true (IFNE) must be
		 * followed by code that cannot fall through to its target. The cast then cannot throw, so it is
		 * not reported.
		 */
		fun isGuardedByInstanceof(
			m: MethodNode,
			cast: TypeInsnNode,
		): Boolean {
			val local = (previousReal(cast) as? VarInsnNode)?.takeIf { it.opcode == Opcodes.ALOAD }?.`var` ?: return false
			val castIndex = m.instructions.indexOf(cast)
			var node: AbstractInsnNode? = cast.previous
			while (node != null) {
				if (node is VarInsnNode && node.`var` == local && node.opcode in Opcodes.ISTORE..Opcodes.ASTORE) return false
				if (node is TypeInsnNode && node.opcode == Opcodes.INSTANCEOF && node.desc == cast.desc &&
					(previousReal(node) as? VarInsnNode)?.let { it.opcode == Opcodes.ALOAD && it.`var` == local } == true
				) {
					val jump = nextReal(node) as? JumpInsnNode
					if (jump != null) {
						val target = m.instructions.indexOf(jump.label)
						when (jump.opcode) {
							Opcodes.IFEQ -> {
								if (target > castIndex) return true
							}

							Opcodes.IFNE -> {
								if (target <= castIndex && target > m.instructions.indexOf(jump) && endsWithExit(jump.label)) return true
							}
						}
					}
				}
				node = node.previous
			}
			return false
		}

		fun mentionsRenamed(desc: String) = desc.contains("android/database/") && Rules.RENAMED_DESCRIPTOR_TOKENS.any { desc.contains(it) }

		fun simple(name: String) = name.substringAfterLast('/')

		/**
		 * The CHECKCASTs to AbstractWindowedCursor whose operand came from an SQLite API (a call on
		 * one of the renamed classes returning a cursor, or a parameter or instance field declared as
		 * SQLiteCursor), through locals and the stack. Approximate: values that went through other
		 * calls, array elements or static fields are not followed (a miss).
		 */
		fun cursorCastTaint(m: MethodNode): Set<AbstractInsnNode> {
			val casts =
				m.instructions.filter {
					it is TypeInsnNode && it.opcode == Opcodes.CHECKCAST && it.desc in Rules.ANDROID_ONLY_CURSOR_TYPES
				}
			if (casts.isEmpty()) return emptySet()
			val interpreter =
				object : BasicInterpreter(Opcodes.ASM9) {
					override fun newValue(type: Type?): BasicValue? =
						super.newValue(type)?.let {
							if (it.type ==
								null
							) {
								it
							} else {
								TaintValue(it.type, false)
							}
						}

					override fun newParameterValue(
						isInstanceMethod: Boolean,
						local: Int,
						type: Type?,
					): BasicValue? {
						val r = super.newParameterValue(isInstanceMethod, local, type)
						return if (r != null && type?.descriptor == "L${Rules.SQLITE_CURSOR};") TaintValue(r.type, true) else r
					}

					override fun unaryOperation(
						insn: AbstractInsnNode,
						value: BasicValue,
					): BasicValue? {
						val r = super.unaryOperation(insn, value)
						if (insn is FieldInsnNode && r != null && insn.desc == "L${Rules.SQLITE_CURSOR};") {
							return TaintValue(r.type, true)
						}
						if (insn.opcode == Opcodes.CHECKCAST && r != null) {
							val castTo = (insn as TypeInsnNode).desc
							return TaintValue(r.type, (value as? TaintValue)?.sqliteCursor == true || castTo == Rules.SQLITE_CURSOR)
						}
						return r
					}

					override fun naryOperation(
						insn: AbstractInsnNode,
						values: MutableList<out BasicValue>,
					): BasicValue? {
						val r = super.naryOperation(insn, values)
						if (insn is MethodInsnNode && r != null && r.type != null) {
							val ret = Type.getReturnType(insn.desc)
							val cursorLike =
								ret.sort == Type.OBJECT &&
									(ret.internalName == "android/database/Cursor" || ret.internalName == Rules.SQLITE_CURSOR)
							if (cursorLike && (renamedAncestors(insn.owner).isNotEmpty() || insn.owner == Rules.CURSOR_FACTORY)) {
								return TaintValue(r.type, true)
							}
						}
						return r
					}

					override fun merge(
						value1: BasicValue,
						value2: BasicValue,
					): BasicValue {
						val tainted = (value1 as? TaintValue)?.sqliteCursor == true || (value2 as? TaintValue)?.sqliteCursor == true
						// BasicInterpreter.merge answers UNINITIALIZED for values that differ, taint included
						if (tainted && value1.type != null && value1.type == value2.type) return TaintValue(value1.type, true)
						return super.merge(value1, value2)
					}
				}
			val analyzer = Analyzer(interpreter)
			val frames: Array<Frame<BasicValue>?> =
				try {
					analyzer.analyze(cn.name, m)
				} catch (e: AnalyzerException) {
					return emptySet()
				}
			val insns = m.instructions
			return casts
				.filter { cast ->
					val frame = frames[insns.indexOf(cast)]
					frame != null && (frame.getStack(frame.stackSize - 1) as? TaintValue)?.sqliteCursor == true
				}.toSet()
		}
	}
}
