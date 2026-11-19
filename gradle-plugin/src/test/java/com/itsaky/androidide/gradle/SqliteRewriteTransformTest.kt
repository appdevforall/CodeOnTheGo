@file:Suppress("ktlint:standard:max-line-length")

package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.gradle.sqlite.Compiler
import com.itsaky.androidide.gradle.sqlite.JavaSource
import com.itsaky.androidide.gradle.sqlite.TestJars
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_REWRITE_RUNTIME_AAR
import com.itsaky.androidide.utils.FileProvider
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A real build, not a dry run: AGP runs the ASM transform of a variant over a class in a local AAR, and the
 * transformed class refers to requery. The check of the classpath passes because stand-in jars hold the class
 * entries it looks for; nothing loads them.
 */
class SqliteRewriteTransformTest {
	private fun classesJar(classes: Map<String, ByteArray>): ByteArray {
		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { zip ->
			zip.putNextEntry(ZipEntry("META-INF/stand-in"))
			zip.closeEntry()
			for ((name, content) in classes) {
				zip.putNextEntry(ZipEntry("$name.class"))
				zip.write(content)
				zip.closeEntry()
			}
		}
		return bytes.toByteArray()
	}

	private fun aar(
		file: File,
		namespace: String,
		classes: Map<String, ByteArray>,
	): File =
		standIn(
			file,
			"AndroidManifest.xml" to
				"<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$namespace\"/>".toByteArray(),
			"R.txt" to ByteArray(0),
			"classes.jar" to classesJar(classes),
		)

	/**
	 * AGP instruments the classes of dependencies with artifact transforms, which the dex builder triggers, not
	 * in the variant's transform task, so the output is in Gradle's transform cache, which every run of this test
	 * shares: the bytes of [read] in each jar that holds [marker]. [marker] names a class no earlier run used, so a
	 * hit is this run's.
	 */
	private fun transformedJarsWith(
		marker: String,
		read: String = marker,
	): List<ByteArray> {
		val cache = FileProvider.projectRoot().resolve("gradle-plugin/build/tmp/test/work/.gradle-test-kit/caches").toFile()
		return cache
			.walkTopDown()
			.filter { it.extension == "jar" && "/transforms/" in it.path }
			.mapNotNull { jar ->
				runCatching {
					ZipFile(jar).use { zip ->
						if (zip.getEntry(marker) == null) null else zip.getEntry(read)?.let { zip.getInputStream(it).readBytes() }
					}
				}.getOrNull()
			}.toList()
	}

	@Test
	fun `AGP's transform rewrites a class of a local AAR to call requery with Object arrays`(
		@TempDir dir: File,
	) {
		val daoName = "Dao${System.nanoTime()}"
		val library =
			Compiler
				.compile(
					listOf(
						JavaSource(
							"x.lib.$daoName",
							"package x.lib; public class $daoName { public Object q(android.database.sqlite.SQLiteDatabase db, String[] a) { return db.rawQuery(\"select 1\", a); } " +
								"public boolean probe(com.unknown.Row r) { return r.isString(0); } }",
						),
						JavaSource(
							"com.unknown.Row",
							"package com.unknown; public class Row { public boolean isString(int column) { return false; } }",
						),
					),
				)
				// left out of the AAR, as an optional class a library references and the app does not ship
				.filterKeys { it != "com/unknown/Row" }
		val helperSource =
			FileProvider
				.projectRoot()
				.resolve("subprojects/sqlite-rewrite-runtime/src/main/java/org/appdevforall/sqliterewrite/SqliteRewriteRuntime.java")
				.toFile()
		// The helper's name is fixed, so its AAR also carries a class of this run's, to tell its transformed jar apart
		val markerName = "Marker${System.nanoTime()}"
		val helper =
			Compiler.compile(
				listOf(
					JavaSource("org.appdevforall.sqliterewrite.SqliteRewriteRuntime", helperSource.readText()),
					JavaSource(
						"org.appdevforall.sqliterewrite.$markerName",
						"package org.appdevforall.sqliterewrite; class $markerName {}",
					),
				),
				listOf(TestJars.androidJar, TestJars.androidxCoreJar),
			)
		val libAar = aar(File(dir, "libs/x.aar"), "x.lib", library)
		val runtimeAar = aar(File(dir, "sqlite-rewrite-runtime-release.aar"), "org.appdevforall.sqliterewrite", helper)
		// real, empty classes: the dex builder reads them
		val requery =
			standIn(
				File(dir, "libs/requery.jar"),
				*Compiler
					.compile(
						listOf(
							JavaSource(
								"io.requery.android.database.sqlite.SQLiteDatabase",
								"package io.requery.android.database.sqlite; public class SQLiteDatabase {}",
							),
						),
					).map { (name, bytes) -> "$name.class" to bytes }
					.toTypedArray(),
			)
		// the debugger goes into the debug variants with the rewrite, and AGP reads its manifest
		val debuggerAar = aar(File(dir, "sqlite-debugger-release.aar"), "org.appdevforall.sqlitedebugger", emptyMap())
		val project =
			copySampleProject(
				dir,
				"""
				// the sample compiles against 33, which AGP's AAR metadata check rejects for its own view binding
				android { compileSdk = 36 }
				dependencies {
					implementation(files("${libAar.invariantSeparatorsPath}"))
					implementation(files("${requery.invariantSeparatorsPath}"))
				}
				""".trimIndent(),
			)

		val result =
			buildProject(
				task = ":app:dexBuilderDemoDebug",
				projectDir = project,
				configureArgs = {
					it += "-P$PROPERTY_SQLITE_DEBUGGER_ENABLED=true"
					it += "-P$PROPERTY_SQLITE_DEBUGGER_AAR=${debuggerAar.absolutePath}"
					it += "-P$PROPERTY_SQLITE_REWRITE_RUNTIME_AAR=${runtimeAar.absolutePath}"
				},
			)

		assertThat(result.task(":app:checkDemoDebugSqliteRewriteClasspath")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
		assertThat(result.task(":app:transformDemoDebugClassesWithAsm")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
		// isString(I)Z names no SQLite type, so the owner that cannot be resolved is only reported through Gradle's logger
		val warning =
			"x.lib.$daoName: com.unknown.Row cannot be resolved, so the rewrite cannot tell whether its isString(I)Z is " +
				"SQLiteCursor.isString, which it changes or rejects; the call is left as it is, and fails at run time if the owner " +
				"extends that class"
		assertThat(result.output).contains(warning)
		println("WARNING LINE: " + result.output.lines().first { warning in it })

		// the unmodified copy from extracting the AAR is in the cache too; the rewritten one is the other
		val daos = transformedJarsWith("x/lib/$daoName.class").map { bytes -> ClassNode().also { ClassReader(bytes).accept(it, 0) } }
		assertThat(daos).isNotEmpty()
		val rewritten =
			daos.filter { dao ->
				dao.methods
					.first { it.name == "q" }
					.instructions
					.filterIsInstance<MethodInsnNode>()
					.any { it.owner.startsWith("io/requery/") }
			}
		assertThat(rewritten).isNotEmpty()
		val q = rewritten.first().methods.first { it.name == "q" }
		val call = q.instructions.filterIsInstance<MethodInsnNode>().first { it.name == "rawQuery" }
		assertThat(call.owner).isEqualTo("io/requery/android/database/sqlite/SQLiteDatabase")
		assertThat(call.desc).isEqualTo("(Ljava/lang/String;[Ljava/lang/Object;)Landroid/database/Cursor;")
		assertThat(q.desc).isEqualTo("(Lio/requery/android/database/sqlite/SQLiteDatabase;[Ljava/lang/String;)Ljava/lang/Object;")

		// the injected helper AAR goes through the same transform: its class is rewritten to requery too
		val helpers =
			transformedJarsWith(
				"org/appdevforall/sqliterewrite/$markerName.class",
				read = "org/appdevforall/sqliterewrite/SqliteRewriteRuntime.class",
			).map { bytes -> ClassNode().also { ClassReader(bytes).accept(it, 0) } }
		assertThat(helpers).isNotEmpty()
		assertThat(
			helpers.any { helper ->
				helper.methods.any { method ->
					"io/requery/" in method.desc ||
						method.instructions.filterIsInstance<MethodInsnNode>().any { it.owner.startsWith("io/requery/") }
				}
			},
		).isTrue()
	}
}
