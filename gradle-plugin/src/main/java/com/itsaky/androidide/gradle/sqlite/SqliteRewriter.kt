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
import org.objectweb.asm.tree.LabelNode
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
import org.objectweb.asm.tree.analysis.Interpreter
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

	/** [instanceOf]: the types an `instanceof` proved the value to be, on every path to here. */
	private class TaintValue(
		type: Type?,
		val sqliteCursor: Boolean,
		val instanceOf: Set<String> = emptySet(),
	) : BasicValue(type) {
		// Frame.merge keeps the old value when the merged one equals it, so the taint and the proofs must count
		override fun equals(other: Any?): Boolean =
			other is BasicValue && super.equals(other) && sqliteCursor == ((other as? TaintValue)?.sqliteCursor ?: false) &&
				instanceOf == ((other as? TaintValue)?.instanceOf ?: emptySet<String>())

		override fun hashCode(): Int = (super.hashCode() * 31 + if (sqliteCursor) 1 else 0) * 31 + instanceOf.hashCode()

		fun provedInstanceOf(type: String) = TaintValue(this.type, sqliteCursor, instanceOf + type)
	}

	/**
	 * A frame that learns from `ALOAD n; INSTANCEOF X; IFEQ/IFNE`, written without anything between them (no label, so
	 * no other path joins there): on the edge where the test succeeded, local n's value is proved an X. The proof goes
	 * with the value through copies, and a merge keeps it only when every path brings it.
	 */
	private class GuardFrame : Frame<BasicValue> {
		constructor(numLocals: Int, maxStack: Int) : super(numLocals, maxStack)
		constructor(frame: Frame<out BasicValue>) : super(frame)

		private var local = -1
		private var type: String? = null
		private var provedOnJump = false
		private var before: BasicValue? = null

		override fun execute(
			insn: AbstractInsnNode,
			interpreter: Interpreter<BasicValue>,
		) {
			local = -1
			before = null
			val test = insn.previous as? TypeInsnNode
			val load = test?.previous as? VarInsnNode
			if ((insn.opcode == Opcodes.IFEQ || insn.opcode == Opcodes.IFNE) && test?.opcode == Opcodes.INSTANCEOF &&
				load?.opcode == Opcodes.ALOAD
			) {
				local = load.`var`
				type = test.desc
				provedOnJump = insn.opcode == Opcodes.IFNE
			}
			super.execute(insn, interpreter)
		}

		// Called on this frame once per edge of the jump, before it is merged there, in no order this relies on
		override fun initJumpTarget(
			opcode: Int,
			target: LabelNode?,
		) {
			if (local < 0) return
			val value = before ?: getLocal(local).also { before = it }
			val proved = (target != null) == provedOnJump
			setLocal(local, if (proved && value is TaintValue) value.provedInstanceOf(type!!) else value)
		}
	}

	/** A reference whose static type, on some path, is SQLiteCursor or a subclass, which the rename makes requery's. */
	private class StaticCursorValue(
		type: Type?,
		val renamedCursor: Boolean,
	) : BasicValue(type) {
		// as TaintValue: Frame.merge keeps the old value when the merged one equals it
		override fun equals(other: Any?): Boolean =
			other is BasicValue && super.equals(other) && renamedCursor == ((other as? StaticCursorValue)?.renamedCursor ?: false)

		override fun hashCode(): Int = super.hashCode() * 31 + if (renamedCursor) 1 else 0
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
				if (policy.fail) bypassedOverride(m)?.let { violations += it }
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
			for (full in fullFrames(m, originalDesc)) {
				full.node.type = Opcodes.F_NEW
				full.node.local = ArrayList(full.locals)
				full.node.stack = ArrayList(full.stack)
			}
		}

		/** A frame of a method with its locals and stack in full, as a compressed frame leaves them implied. */
		class FullFrame(
			val node: FrameNode,
			val locals: List<Any>,
			val stack: List<Any>,
		)

		/** Every frame of [m] in full, starting from [originalDesc]'s parameters; changes nothing. */
		fun fullFrames(
			m: MethodNode,
			originalDesc: String,
		): List<FullFrame> {
			val result = ArrayList<FullFrame>()
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
				result += FullFrame(frame, ArrayList(locals), stack)
			}
			return result
		}

		/**
		 * A method that overrides one whose calls [decide] retypes, SQLiteQueryBuilder's `query` with a
		 * CancellationSignal: the calls get requery's descriptor and the declaration keeps Android's, so they would
		 * reach requery's inherited method and skip the override. Retyping the declaration too would mean turning
		 * requery's signal back into Android's in a body written for Android's, so the build fails instead.
		 */
		fun bypassedOverride(m: MethodNode): String? {
			if (m.access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) != 0 || m.name !in Rules.DESCRIPTOR_RULE_NAMES) return null
			val ancestors = cn.superName?.let { renamedAncestors(it) }.orEmpty()
			val rule = Rules.DESCRIPTOR_RULES.firstOrNull { it.owner in ancestors && it.name == m.name && it.desc == m.desc } ?: return null
			return "method '${m.name}${m.desc}' overrides ${simple(rule.owner)}.${m.name}, whose calls the rewrite gives requery's " +
				"descriptor; they would reach requery's method and skip the override"
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

		// not getOrPut, which asks again for an owner that cannot be resolved (null)
		private fun superTypes(owner: String): Collection<String>? =
			if (owner in superTypesCache) superTypesCache[owner] else hierarchy.superTypes(owner).also { superTypesCache[owner] = it }

		/** Owners that cannot extend a renamed class: the JDK, Kotlin, and Android outside the renamed set. */
		private fun cannotBeSqlite(owner: String): Boolean =
			owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("kotlin/") || owner.startsWith("kotlinx/") ||
				(owner.startsWith("android/") && owner !in Rules.SQLITE_CLASSES)

		/**
		 * The renamed classes that [owner] is or extends, nearest first, so the rule tables see them all. None for an
		 * owner that cannot be resolved: [unresolvedOwner] reports the calls a rule could match on one.
		 */
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
			val conversions = if (policy.fail) implicitCursorConversions(m) else emptyMap()
			var line = -1
			var insn: AbstractInsnNode? = m.instructions.first
			while (insn != null) {
				val following = insn.next
				if (insn is LineNumberNode) {
					line = insn.line
				}
				conversions[insn]?.let { violate(m, line, it) }
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
			val where = if (line >= 0) "line $line" else "no line number"
			if (Rules.COLLATE_LOCALIZED.containsMatchIn(text)) {
				warn(
					"${cn.name.replace('/', '.')}.${m.name} ($where): COLLATE LOCALIZED works in requery's SQLite but sorts by " +
						"byte value (no ICU), not by the device locale",
				)
			}
			// A warning, not a failure: the string may only be printed. Strings are not rewritten, so a class loaded by
			// this name is Android's, which the rewritten code cannot mix with requery's.
			Rules.RENAMED_CLASS_NAME.find(text)?.let {
				warn(
					"${cn.name.replace('/', '.')}.${m.name} ($where): the string \"${it.value}\" names a class the rewrite moves " +
						"to requery. Strings are not rewritten: Class.forName or reflection on it reaches Android's class, not requery's",
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
				?: unresolvedOwner(owner, name, desc)
		}

		/**
		 * Why `owner.name desc` cannot be checked, or null: [owner] cannot be resolved, so its renamed ancestors are
		 * unknown, and the member has the name and descriptor of one a rule changes or rejects in a renamed class
		 * another class can extend. Like a Context database method on an unresolved owner, this fails under the
		 * full policy rather than pass a call that may break at run time.
		 */
		fun unresolvedOwner(
			owner: String,
			name: String,
			desc: String,
		): String? {
			val declaring = Rules.INHERITABLE_RULE_MEMBERS[name + desc] ?: return null
			if (owner.startsWith("[") || owner in Rules.SQLITE_CLASSES || cannotBeSqlite(owner) || RewritePolicy.isExcluded(owner)) {
				return null
			}
			if (superTypes(owner) != null) return null
			return "${owner.replace('/', '.')} cannot be resolved, so the rewrite cannot tell whether its $name$desc is " +
				"${simple(declaring)}.$name, which it changes or rejects"
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
			if (t.opcode == Opcodes.CHECKCAST && t.desc in Rules.ANDROID_ONLY_CURSOR_TYPES && t in tainted) {
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
				unresolvedOwner(owner, name, desc)?.let { return Action.Fail(it) }
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
						// requery checks androidx.core's signal, so a cancelled query throws androidx.core's
						// OperationCanceledException, which a catch of Android's does not catch (see the docs)
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

		fun mentionsRenamed(desc: String) = desc.contains("android/database/") && Rules.RENAMED_DESCRIPTOR_TOKENS.any { desc.contains(it) }

		fun simple(name: String) = name.substringAfterLast('/')

		/** SQLiteCursor, or a class that extends it, which the rename makes a subclass of requery's SQLiteCursor. */
		fun isRenamedCursorType(type: Type?): Boolean =
			type != null && type.sort == Type.OBJECT && type.descriptor != "Lnull;" &&
				Rules.SQLITE_CURSOR in renamedAncestors(type.internalName)

		/** [type]'s internal name when it is one of the Android-only cursor types, or null. */
		private fun androidOnlyCursor(type: Type): String? = if (type.sort == Type.OBJECT) androidOnlyCursor(type.internalName) else null

		/** A type in a frame or an internal name, when it is one of the Android-only cursor types, or null. */
		private fun androidOnlyCursor(entry: Any?): String? = (entry as? String)?.takeIf { it in Rules.ANDROID_ONLY_CURSOR_TYPES }

		/** Whether [m] names an Android-only cursor type where a value could be converted to it without a cast. */
		private fun mayConvertToAndroidCursor(m: MethodNode): Boolean {
			fun names(desc: String) = Rules.ANDROID_ONLY_CURSOR_TYPES.any { desc.contains("L$it;") }
			if (names(Type.getReturnType(m.desc).descriptor)) return true
			return m.instructions.any { insn ->
				when (insn) {
					is MethodInsnNode -> insn.owner in Rules.ANDROID_ONLY_CURSOR_TYPES || names(insn.desc)
					is InvokeDynamicInsnNode -> names(insn.desc)
					is FieldInsnNode -> names(insn.desc)
					is FrameNode -> (insn.local.orEmpty() + insn.stack.orEmpty()).any { androidOnlyCursor(it) != null }
					else -> false
				}
			}
		}

		/**
		 * The instructions where a value whose static type is SQLiteCursor, or a class that extends it, is used as an
		 * AbstractWindowedCursor, AbstractCursor or CrossProcessCursor without a cast, by the message for each: returned,
		 * passed, called on, stored in a field, or held where a frame declares one of them. javac writes no CHECKCAST
		 * for such an upcast, and requery's SQLiteCursor neither extends those classes nor implements the interface, so
		 * the renamed class would not verify (or, for the interface, the call would fail). Values stored in or loaded
		 * from arrays are not followed; storing one into such an array throws ArrayStoreException.
		 */
		fun implicitCursorConversions(m: MethodNode): Map<AbstractInsnNode, String> {
			if (!mayConvertToAndroidCursor(m)) return emptyMap()
			val interpreter =
				object : BasicInterpreter(Opcodes.ASM9) {
					override fun newValue(type: Type?): BasicValue? {
						val v = super.newValue(type) ?: return null
						return if (v.isReference) StaticCursorValue(v.type, isRenamedCursorType(type)) else v
					}

					override fun merge(
						value1: BasicValue,
						value2: BasicValue,
					): BasicValue {
						val renamed =
							(value1 as? StaticCursorValue)?.renamedCursor == true || (value2 as? StaticCursorValue)?.renamedCursor == true
						if (renamed && value1.type != null && value1.type == value2.type) return StaticCursorValue(value1.type, true)
						return super.merge(value1, value2)
					}
				}
			val frames: Array<Frame<BasicValue>?> =
				try {
					Analyzer(interpreter).analyze(cn.name, m)
				} catch (e: AnalyzerException) {
					return emptyMap()
				}
			val declared = fullFrames(m, m.desc).associateBy { it.node }
			val returnType = Type.getReturnType(m.desc)
			val result = LinkedHashMap<AbstractInsnNode, String>()
			for ((i, insn) in m.instructions.withIndex()) {
				val f = frames[i] ?: continue

				fun renamed(value: BasicValue?) = (value as? StaticCursorValue)?.renamedCursor == true

				fun fromTop(depth: Int) = if (depth < f.stackSize) f.getStack(f.stackSize - 1 - depth) else null
				val target: String? =
					when {
						insn is MethodInsnNode || insn is InvokeDynamicInsnNode -> {
							val args = Type.getArgumentTypes((insn as? MethodInsnNode)?.desc ?: (insn as InvokeDynamicInsnNode).desc)
							args.indices.firstNotNullOfOrNull { k ->
								androidOnlyCursor(args[k])?.takeIf { renamed(fromTop(args.size - 1 - k)) }
							}
								?: (insn as? MethodInsnNode)
									?.takeIf {
										it.opcode != Opcodes.INVOKESTATIC && it.owner in Rules.ANDROID_ONLY_CURSOR_TYPES &&
											renamed(fromTop(args.size))
									}?.owner
						}

						insn is FieldInsnNode && (insn.opcode == Opcodes.PUTFIELD || insn.opcode == Opcodes.PUTSTATIC) -> {
							androidOnlyCursor(Type.getType(insn.desc))?.takeIf { renamed(fromTop(0)) }
						}

						insn.opcode == Opcodes.ARETURN -> {
							androidOnlyCursor(returnType)?.takeIf { renamed(fromTop(0)) }
						}

						insn is FrameNode -> {
							declared[insn]?.let { full ->
								var slot = 0
								var hit: String? = null
								for (t in full.locals) {
									if (hit == null && slot < f.locals && renamed(f.getLocal(slot))) hit = androidOnlyCursor(t)
									slot += if (t == Opcodes.LONG || t == Opcodes.DOUBLE) 2 else 1
								}
								hit ?: full.stack.indices.firstNotNullOfOrNull { k ->
									androidOnlyCursor(full.stack[k])?.takeIf { k < f.stackSize && renamed(f.getStack(k)) }
								}
							}
						}

						else -> {
							null
						}
					}
				if (target != null) {
					result[insn] = "an SQLite cursor used as ${target.replace('/', '.')} without a cast; requery's SQLiteCursor is not " +
						"one, so the rewritten class would not verify or the call would fail"
				}
			}
			return result
		}

		/**
		 * The CHECKCASTs to AbstractWindowedCursor whose operand came from an SQLite API (a call on
		 * one of the renamed classes returning a cursor, or a parameter or instance field declared as
		 * SQLiteCursor), through locals and the stack, unless an `instanceof` of the cast's type proved
		 * the operand on every path to the cast (see [GuardFrame]): `if (c instanceof X) ((X) c)`, `&&`,
		 * `?:` and an early exit when it is false, but not `b || c instanceof X`, which reaches the cast
		 * with `b` alone. Approximate: values that went through other calls, array elements or static
		 * fields are not followed (a miss).
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
							val operand = value as? TaintValue
							return TaintValue(
								r.type,
								operand?.sqliteCursor == true || castTo == Rules.SQLITE_CURSOR,
								operand?.instanceOf.orEmpty(),
							)
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
						val t1 = value1 as? TaintValue
						val t2 = value2 as? TaintValue
						// BasicInterpreter.merge answers UNINITIALIZED for values that differ, taint and proofs included
						if ((t1 != null || t2 != null) && value1.type != null && value1.type == value2.type) {
							val merged =
								TaintValue(
									value1.type,
									t1?.sqliteCursor == true || t2?.sqliteCursor == true,
									t1?.instanceOf.orEmpty() intersect t2?.instanceOf.orEmpty(),
								)
							return if (merged == value1) value1 else merged
						}
						return super.merge(value1, value2)
					}
				}
			val analyzer =
				object : Analyzer<BasicValue>(interpreter) {
					override fun newFrame(
						numLocals: Int,
						numStack: Int,
					): Frame<BasicValue> = GuardFrame(numLocals, numStack)

					override fun newFrame(frame: Frame<out BasicValue>): Frame<BasicValue> = GuardFrame(frame)
				}
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
					val operand = frame?.let { it.getStack(it.stackSize - 1) } as? TaintValue
					operand != null && operand.sqliteCursor && (cast as TypeInsnNode).desc !in operand.instanceOf
				}.toSet()
		}
	}
}
