package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.gradle.sqlite.CheckSqliteRewriteClasspathTask
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_REWRITE_RUNTIME_AAR
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildResultException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * The helpers the rewritten classes call (ADFA-7138) are injected by Code on the Go into every variant of a
 * project that opted in, and a per-variant task checks that requery, androidx.sqlite and androidx.core are on
 * the runtime classpath before anything is compiled.
 */
class SqliteRewritePluginTest {
	private val runtimeAarName = "sqlite-rewrite-runtime-release.aar"
	private val allVariants = listOf("demoDebug", "fullDebug", "demoRelease", "fullRelease")
	private val requeryEntry = CheckSqliteRewriteClasspathTask.REQUERY_ENTRY
	private val androidxSqliteEntry = CheckSqliteRewriteClasspathTask.ANDROIDX_SQLITE_ENTRY
	private val androidxCoreEntry = CheckSqliteRewriteClasspathTask.ANDROIDX_CORE_ENTRY

	private fun build(
		dir: File,
		task: String,
		enabled: String? = "true",
		runtimeAar: File? = File(dir, runtimeAarName).apply { writeText("aar") },
		project: Path? = null,
		extraArgs: List<String> = emptyList(),
	): BuildResult {
		val debuggerAar = File(dir, "sqlite-debugger-release.aar").apply { writeText("aar") }
		return buildProject(task = task, projectDir = project, configureArgs = {
			if (task == ":app:printRuntimeDeps") {
				it += "--init-script"
				it += printDepsInitScript(dir).absolutePath
			}
			if (enabled != null) it += "-P$PROPERTY_SQLITE_DEBUGGER_ENABLED=$enabled"
			it += "-P$PROPERTY_SQLITE_DEBUGGER_AAR=${debuggerAar.absolutePath}"
			if (runtimeAar != null) it += "-P$PROPERTY_SQLITE_REWRITE_RUNTIME_AAR=${runtimeAar.absolutePath}"
			it += extraArgs
		})
	}

	private fun BuildResult.declared(variant: String) = output.lines().filter { it.startsWith("RTDEP $variant ") }

	private fun failureOf(block: () -> BuildResult): String {
		val failure = runCatching(block).exceptionOrNull()
		assertThat(failure).isInstanceOf(UnexpectedBuildResultException::class.java)
		return failure!!.message!!
	}

	@Test
	fun `the helper AAR is added to every variant when opted in, not only the debuggable ones`(
		@TempDir dir: File,
	) {
		val result = build(dir, ":app:printRuntimeDeps")

		for (variant in allVariants) {
			assertThat(result.declared(variant)).contains("RTDEP $variant file $runtimeAarName")
			assertThat(result.output).contains("Adding SQLite rewrite runtime dependency to variant '$variant' of project ':app'")
		}
	}

	@Test
	fun `a project that did not opt in gets no helper AAR and no check`(
		@TempDir dir: File,
	) {
		for (enabled in listOf(null, "false")) {
			val result = build(dir, ":app:printRuntimeDeps", enabled)

			for (variant in allVariants) {
				assertThat(result.declared(variant).filter { it.contains(runtimeAarName) }).isEmpty()
			}
			assertThat(result.output).doesNotContain("Adding SQLite rewrite runtime dependency")
		}
	}

	@Test
	fun `opted in without the helper AAR property fails the build and says how to fix it`(
		@TempDir dir: File,
	) {
		val message = failureOf { build(dir, ":app:printRuntimeDeps", runtimeAar = null) }

		assertThat(message).contains("no property '$PROPERTY_SQLITE_REWRITE_RUNTIME_AAR' is set")
		assertThat(message).contains("Remove the line from gradle.properties")
	}

	@Test
	fun `opted in with a missing helper AAR file fails the build and says how to fix it`(
		@TempDir dir: File,
	) {
		val message = failureOf { build(dir, ":app:printRuntimeDeps", runtimeAar = File(dir, "absent.aar")) }

		assertThat(message).contains("SQLite rewrite runtime AAR not found")
		assertThat(message).contains("Remove '$PROPERTY_SQLITE_DEBUGGER_ENABLED' from gradle.properties")
	}

	private fun jar(
		dir: File,
		name: String,
		vararg entries: String,
	) = standIn(File(dir, "libs/$name"), *entries.map { it to ByteArray(0) }.toTypedArray())

	private fun project(
		dir: File,
		script: String,
	) = copySampleProject(dir, script)

	@Test
	fun `the check fails without requery on the runtime classpath and says how to add it`(
		@TempDir dir: File,
	) {
		val project = project(dir, "")
		val message = failureOf { build(dir, ":app:checkDemoDebugSqliteRewriteClasspath", project = project) }

		assertThat(message).contains("variant 'demoDebug'")
		assertThat(message).contains(requeryEntry)
		// the sample project's Room and AppCompat bring the real AARs, which the check must find
		assertThat(message).doesNotContain(androidxSqliteEntry)
		assertThat(message).doesNotContain(androidxCoreEntry)
		assertThat(message).contains("file dependency")
		assertThat(message).contains("brings none of its own dependencies")
	}

	@Test
	fun `the check fails without androidx sqlite, which requery's file dependency does not bring`(
		@TempDir dir: File,
	) {
		val requery = jar(dir, "requery.jar", requeryEntry)
		val project =
			project(
				dir,
				"""
				configurations.all { exclude(group = "androidx.sqlite") }
				dependencies { implementation(files("${requery.invariantSeparatorsPath}")) }
				""".trimIndent(),
			)
		val message = failureOf { build(dir, ":app:checkDemoDebugSqliteRewriteClasspath", project = project) }

		assertThat(message).contains(androidxSqliteEntry)
		assertThat(message).doesNotContain("requery's SQLiteDatabase")
		assertThat(message).doesNotContain(androidxCoreEntry)
	}

	@Test
	fun `the check fails without androidx core, whose CancellationSignal requery's API takes`(
		@TempDir dir: File,
	) {
		val requery = jar(dir, "requery.jar", requeryEntry)
		val project =
			project(
				dir,
				"""
				configurations.all { exclude(group = "androidx.core") }
				dependencies { implementation(files("${requery.invariantSeparatorsPath}")) }
				""".trimIndent(),
			)
		val message = failureOf { build(dir, ":app:checkDemoDebugSqliteRewriteClasspath", project = project) }

		assertThat(message).contains(androidxCoreEntry)
		assertThat(message).contains("androidx.core:core")
		assertThat(message).doesNotContain("requery's SQLiteDatabase")
		assertThat(message).doesNotContain(androidxSqliteEntry)
	}

	@Test
	fun `the check passes with stand-in jars that contain the three class entries, and is reused from the configuration cache`(
		@TempDir dir: File,
	) {
		val requery = jar(dir, "requery.jar", requeryEntry)
		val sqlite = jar(dir, "sqlite.jar", androidxSqliteEntry)
		val core = jar(dir, "core.jar", androidxCoreEntry)
		val project =
			project(
				dir,
				"""
				configurations.all {
					exclude(group = "androidx.sqlite")
					exclude(group = "androidx.core")
				}
				dependencies {
					implementation(files("${requery.invariantSeparatorsPath}"))
					implementation(files("${sqlite.invariantSeparatorsPath}"))
					implementation(files("${core.invariantSeparatorsPath}"))
				}
				""".trimIndent(),
			)
		val first =
			build(dir, ":app:checkDemoDebugSqliteRewriteClasspath", project = project, extraArgs = listOf("--configuration-cache"))
		assertThat(first.task(":app:checkDemoDebugSqliteRewriteClasspath")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)

		val second =
			build(dir, ":app:checkDemoDebugSqliteRewriteClasspath", project = project, extraArgs = listOf("--configuration-cache"))
		assertThat(second.output).contains("Reusing configuration cache")
		assertThat(second.task(":app:checkDemoDebugSqliteRewriteClasspath")!!.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
	}
}
