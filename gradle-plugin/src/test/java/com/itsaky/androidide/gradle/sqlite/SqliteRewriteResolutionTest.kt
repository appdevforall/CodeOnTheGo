@file:Suppress("ktlint:standard:max-line-length")

package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.FileProvider
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class SqliteRewriteResolutionTest {
	private val helperSource =
		FileProvider
			.projectRoot()
			.resolve("subprojects/sqlite-rewrite-runtime/src/main/java/org/appdevforall/sqliterewrite/SqliteRewriteRuntime.java")
			.toFile()

	private class Ref(
		val owner: String,
		val name: String,
		val desc: String,
		val field: Boolean,
		val isStatic: Boolean,
	) {
		override fun toString() = "$owner.$name$desc"
	}

	private fun ClassIndex.resolve(
		ref: Ref,
		caller: String? = null,
		protectedOk: Boolean = false,
	) = resolve(ref.owner, ref.name, ref.desc, ref.field, ref.isStatic, caller, protectedOk)

	private fun refsOf(n: ClassNode): List<Ref> =
		n.methods.flatMap { m ->
			m.instructions.mapNotNull { insn ->
				when (insn) {
					is MethodInsnNode -> {
						Ref(insn.owner, insn.name, insn.desc, false, insn.opcode == Opcodes.INVOKESTATIC)
					}

					is FieldInsnNode -> {
						Ref(
							insn.owner,
							insn.name,
							insn.desc,
							true,
							insn.opcode == Opcodes.GETSTATIC || insn.opcode == Opcodes.PUTSTATIC,
						)
					}

					else -> {
						null
					}
				}
			}
		}

	@Test
	fun `the transformed helper only references what requery, android and androidx core define`() {
		val classes =
			Compiler.compile(
				listOf(JavaSource("org.appdevforall.sqliterewrite.SqliteRewriteRuntime", helperSource.readText())),
				listOf(TestJars.androidJar, TestJars.androidxCoreJar),
			)
		val index = ClassIndex(listOf(TestJars.requeryJar, TestJars.androidJar, TestJars.androidxCoreJar))
		val unresolved = mutableListOf<String>()
		var checked = 0
		for ((name, bytes) in classes) {
			val n = node(SqliteRewriter.rewrite(bytes))
			assertThat(n.name).isEqualTo(name)
			for (ref in refsOf(n)) {
				if (ref.owner in classes.keys) continue
				checked++
				if (index.resolve(ref, caller = n.name) == null) {
					unresolved += ref.toString()
				}
				assertThat(
					ref.owner,
				).doesNotMatch("android/database/sqlite/SQLite(Database|Open|Cursor|Query|Statement|Program|Closable).*")
			}
		}
		assertThat(checked).isGreaterThan(10)
		assertThat(unresolved).isEmpty()
	}

	@Test
	fun `every public or protected member of the 10 renamed android classes, inherited ones included, resolves in requery or fails the build`() {
		val android = TestJars.androidJar
		val index = ClassIndex(listOf(TestJars.requeryJar, TestJars.androidJar, TestJars.androidxCoreJar))
		val helper =
			Compiler.compile(
				listOf(JavaSource("org.appdevforall.sqliterewrite.SqliteRewriteRuntime", helperSource.readText())),
				listOf(android, TestJars.androidxCoreJar),
			)
		helper.values.forEach { index.addClass(SqliteRewriter.rewrite(it)) }

		val unresolved = mutableListOf<String>()
		val failed = mutableListOf<String>()
		var total = 0
		var inheritedTotal = 0
		java.util.zip.ZipFile(android).use { zip ->
			fun load(name: String) =
				zip.getEntry("$name.class")?.let { e ->
					ClassNode().also { ClassReader(zip.getInputStream(e)).accept(it, ClassReader.SKIP_CODE) }
				}

			/** Android classes the class extends or implements and that stay android's: their members are inherited. */
			fun inheritedFrom(cls: ClassNode): List<ClassNode> {
				val result = mutableListOf<ClassNode>()
				val pending = ArrayDeque(listOfNotNull(cls.superName) + cls.interfaces)
				val seen = HashSet<String>()
				while (pending.isNotEmpty()) {
					val n = pending.removeFirst()
					if (!seen.add(n) || n in SqliteRewriteRules.RENAMES) continue
					val c = load(n) ?: continue
					result += c
					c.superName?.let(pending::add)
					pending.addAll(c.interfaces)
				}
				return result
			}
			for (cls in SqliteRewriteRules.SQLITE_CLASSES) {
				val owner = load(cls)!!
				val isInterface = owner.access and Opcodes.ACC_INTERFACE != 0
				val visible = Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED
				// the member through its declaring class and through every renamed subclass; inherited ones through the class
				val callOwners = listOf(cls) + SqliteRewriteRules.RENAMED_SUPERS.filterValues { cls in it }.keys
				val declarers = listOf(owner to callOwners) + inheritedFrom(owner).map { it to listOf(cls) }
				for ((declarer, owners) in declarers) {
					val inherited = declarer !== owner
					// constants are copied into callers by javac, so no field access to them exists
					val fields = declarer.fields.filter { it.access and visible != 0 && it.value == null }
					val methods = declarer.methods.filter { it.access and visible != 0 && !(inherited && it.name == "<init>") }
					val members =
						fields.map { Triple(true, it.name, it.desc) to it.access } +
							methods.map { Triple(false, it.name, it.desc) to it.access }
					for ((member, memberAccess) in members) {
						val (field, name, desc) = member
						for (callOwner in owners) {
							if (callOwner != cls && name == "<init>") continue
							total++
							if (inherited) inheritedTotal++
							val synthetic = synthesize(declarer, callOwner, isInterface, field, name, desc, memberAccess)
							val label = "${cls.substringAfterLast('/')}.$name:$desc"
							val out =
								try {
									node(SqliteRewriter.rewrite(synthetic))
								} catch (e: SqliteRewriteException) {
									failed += label
									continue
								}
							val calls = refsOf(out).filter { it.owner != "p/Probe" }
							// the probe makes exactly one member reference, plus a signal conversion when inserted
							val main = calls.first { it.name == name }
							if (index.resolve(main, protectedOk = memberAccess and Opcodes.ACC_PROTECTED != 0) == null) {
								unresolved += "$label via ${callOwner.substringAfterLast('/')}"
							}
						}
					}
				}
			}
		}
		assertThat(inheritedTotal).isGreaterThan(100)
		println("members checked: $total, fail rule: ${failed.size}")
		assertThat(unresolved).isEmpty()
		// everything the rule table lists as missing must be hit, and nothing else may fail
		val expected =
			SqliteRewriteRules.MISSING_MEMBERS.flatMap { (key, descs) ->
				val prefix = key.substringBeforeLast('.').substringAfterLast('/') + "." + key.substringAfterLast('.') + ":"
				descs.map { prefix + it }
			}
		val unexpected =
			failed.filterNot { f ->
				expected.contains(f) || f.contains("OpenParams") || f.contains("android/database/CursorWindow")
			}
		assertThat(unexpected).isEmpty()
	}

	@Test
	fun `every other platform member that mentions a renamed class is redirected to a target that resolves or fails the build`() {
		val index = ClassIndex(listOf(TestJars.requeryJar, TestJars.androidJar, TestJars.androidxCoreJar))
		val helper =
			Compiler.compile(
				listOf(JavaSource("org.appdevforall.sqliterewrite.SqliteRewriteRuntime", helperSource.readText())),
				listOf(TestJars.androidJar, TestJars.androidxCoreJar),
			)
		helper.values.forEach { index.addClass(SqliteRewriter.rewrite(it)) }
		val renamedTokens = SqliteRewriteRules.RENAMED_DESCRIPTOR_TOKENS
		val unresolved = mutableListOf<String>()
		var probed = 0
		var redirected = 0
		java.util.zip.ZipFile(TestJars.androidJar).use { zip ->
			for (entry in zip.entries()) {
				if (!entry.name.startsWith("android/") || !entry.name.endsWith(".class")) continue
				val owner = ClassNode().also { ClassReader(zip.getInputStream(entry)).accept(it, ClassReader.SKIP_CODE) }
				if (owner.name in SqliteRewriteRules.RENAMES) continue
				val isInterface = owner.access and Opcodes.ACC_INTERFACE != 0
				for (method in owner.methods) {
					if (method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) continue
					if (renamedTokens.none { method.desc.contains(it) }) continue
					probed++
					val probe = synthesize(owner, owner.name, isInterface, false, method.name, method.desc, method.access)
					val out =
						try {
							node(SqliteRewriter.rewrite(probe))
						} catch (e: SqliteRewriteException) {
							continue
						}
					val call = refsOf(out).first { it.name == method.name }
					redirected++
					if (call.owner != "org/appdevforall/sqliterewrite/SqliteRewriteRuntime" || index.resolve(call) == null) {
						unresolved += "${owner.name}.${method.name}${method.desc}"
					}
				}
			}
		}
		println("platform members probed: $probed, redirected: $redirected")
		assertThat(probed).isGreaterThan(10)
		assertThat(redirected).isGreaterThan(8)
		assertThat(unresolved).isEmpty()
	}

	@Test
	fun `the real androidx framework classes transform, verify, and resolve in requery`() {
		val framework = TestJars.androidxSqliteFrameworkJar
		val sources = mutableMapOf<String, ByteArray>()
		java.util.zip.ZipFile(framework).use { zip ->
			for (entry in zip.entries()) {
				if (entry.name.startsWith(SqliteRewriteRules.FRAMEWORK_PACKAGE_PREFIX) && entry.name.endsWith(".class")) {
					sources[entry.name.removeSuffix(".class")] = zip.getInputStream(entry).readBytes()
				}
			}
		}
		// FrameworkSQLiteOpenHelper and its OpenHelper, FrameworkSQLiteDatabase, -Statement, -Program, and the helpers
		assertThat(sources.keys).containsAtLeast(
			"androidx/sqlite/db/framework/FrameworkSQLiteOpenHelper",
			"androidx/sqlite/db/framework/FrameworkSQLiteOpenHelper\$OpenHelper",
			"androidx/sqlite/db/framework/FrameworkSQLiteDatabase",
			"androidx/sqlite/db/framework/FrameworkSQLiteStatement",
			"androidx/sqlite/db/framework/FrameworkSQLiteProgram",
			"androidx/sqlite/db/framework/FrameworkSQLiteOpenHelperFactory",
		)

		val before = ClassIndex(listOf(TestJars.androidJar, TestJars.androidxCoreJar, TestJars.androidxSqliteJar, framework))
		val hierarchy = ClassHierarchy { name -> if (before.knows(name)) before.superTypes(name) else null }
		val transformed = sources.mapValues { SqliteRewriter.rewrite(it.value, hierarchy) }
		assertThat(transformed.keys).isEqualTo(sources.keys)

		val helper =
			Compiler.compile(
				listOf(JavaSource("org.appdevforall.sqliterewrite.SqliteRewriteRuntime", helperSource.readText())),
				listOf(TestJars.androidJar, TestJars.androidxCoreJar),
			)
		// the framework jar also holds androidx.sqlite.util.ProcessLock, which is not rewritten
		val index =
			ClassIndex(listOf(TestJars.requeryJar, TestJars.androidJar, TestJars.androidxCoreJar, TestJars.androidxSqliteJar, framework))
		helper.values.forEach { index.addClass(SqliteRewriter.rewrite(it)) }
		transformed.values.forEach { index.addClass(it) }

		val unresolved = mutableListOf<String>()
		var requeryRefs = 0
		for ((name, bytes) in transformed) {
			for (ref in refsOf(node(bytes))) {
				if (ref.owner.startsWith("kotlin/") || ref.owner.startsWith("[")) continue
				if (ref.owner.startsWith("io/requery/")) requeryRefs++
				if (index.resolve(ref, caller = name) == null) unresolved += "$name -> $ref"
			}
		}
		assertThat(requeryRefs).isGreaterThan(30)
		assertThat(unresolved).isEmpty()
		// requery has no execPerConnectionSQL of its own: the reference resolves to the interface's default, which throws
		assertThat(
			index.resolve(
				"io/requery/android/database/sqlite/SQLiteDatabase",
				"execPerConnectionSQL",
				"(Ljava/lang/String;[Ljava/lang/Object;)V",
				false,
			),
		).isEqualTo("androidx/sqlite/db/SupportSQLiteDatabase")
		// every android.database.sqlite reference is gone, so nothing in the package links to the platform's SQLite
		for (bytes in transformed.values) {
			for (ref in refsOf(node(bytes))) {
				assertThat(
					ref.owner,
				).doesNotMatch("android/database/sqlite/SQLite(Database|Open|Cursor|Query|Statement|Program|Closable).*")
			}
		}
		BytecodeVerifier.verify(transformed)
	}

	/** A class `p/Probe` with one method that performs one member reference with a valid stack. */
	private fun synthesize(
		owner: ClassNode,
		callOwner: String,
		isInterface: Boolean,
		field: Boolean,
		name: String,
		desc: String,
		access: Int,
	): ByteArray {
		val cn = ClassNode()
		cn.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/Probe", null, "java/lang/Object", null)
		val m = MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "probe", "()V", null, null)
		val isStatic = access and Opcodes.ACC_STATIC != 0
		if (field) {
			m.visitFieldInsn(if (isStatic) Opcodes.GETSTATIC else Opcodes.GETFIELD, callOwner, name, desc)
			m.visitInsn(if (Type.getType(desc).size == 2) Opcodes.POP2 else Opcodes.POP)
		} else {
			if (!isStatic) m.visitInsn(Opcodes.ACONST_NULL)
			for (arg in Type.getArgumentTypes(desc)) {
				m.visitInsn(
					when (arg.sort) {
						Type.LONG -> Opcodes.LCONST_0
						Type.FLOAT -> Opcodes.FCONST_0
						Type.DOUBLE -> Opcodes.DCONST_0
						Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL
						else -> Opcodes.ICONST_0
					},
				)
			}
			val opcode =
				when {
					name == "<init>" -> Opcodes.INVOKESPECIAL
					isStatic -> Opcodes.INVOKESTATIC
					isInterface -> Opcodes.INVOKEINTERFACE
					else -> Opcodes.INVOKEVIRTUAL
				}
			m.visitMethodInsn(opcode, callOwner, name, desc, isInterface)
			when (Type.getReturnType(desc).size) {
				2 -> m.visitInsn(Opcodes.POP2)
				1 -> m.visitInsn(Opcodes.POP)
			}
		}
		m.visitInsn(Opcodes.RETURN)
		m.visitMaxs(8, 0)
		cn.methods.add(m)
		val w = ClassWriter(0)
		cn.accept(w)
		return w.toByteArray()
	}
}
