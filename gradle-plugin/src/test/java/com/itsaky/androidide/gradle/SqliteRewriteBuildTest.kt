package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_REWRITE_RUNTIME_AAR
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The class rewrite (ADFA-7138) is registered only for projects that opt in: without requery in
 * the app the rewritten classes cannot load. AGP schedules an ASM transform task per variant
 * exactly when a class visitor is registered, so `--dry-run` tells the two cases apart without
 * compiling the sample app.
 */
class SqliteRewriteBuildTest {
	@Test
	fun `a project with the property gets the class transform and one without does not`(
		@TempDir dir: File,
	) {
		// The same property also adds the debugger, which needs its AAR; --dry-run never reads it.
		val aar = File(dir, "sqlite-debugger-release.aar").apply { writeText("aar") }
		val runtimeAar = File(dir, "sqlite-rewrite-runtime-release.aar").apply { writeText("aar") }
		val enabled =
			buildProject(
				task = ":app:assembleDemoDebug",
				configureArgs = {
					it.add("-P$PROPERTY_SQLITE_DEBUGGER_ENABLED=true")
					it.add("-P$PROPERTY_SQLITE_DEBUGGER_AAR=${aar.absolutePath}")
					it.add("-P$PROPERTY_SQLITE_REWRITE_RUNTIME_AAR=${runtimeAar.absolutePath}")
					it.add("--dry-run")
				},
			)
		val disabled =
			buildProject(
				task = ":app:assembleDemoDebug",
				configureArgs = { it.add("--dry-run") },
			)

		assertThat(enabled.output).contains("transformDemoDebugClassesWithAsm")
		// the classpath check runs before the compile and the rewrite of its variant
		val lines = enabled.output.lines()
		val check = lines.indexOfFirst { it.contains("checkDemoDebugSqliteRewriteClasspath") }
		assertThat(check).isAtLeast(0)
		assertThat(check).isLessThan(lines.indexOfFirst { it.contains("transformDemoDebugClassesWithAsm") })
		assertThat(check).isLessThan(lines.indexOfFirst { it.contains("compileDemoDebugJavaWithJavac") })
		assertThat(disabled.output).doesNotContain("ClassesWithAsm")
		assertThat(disabled.output).doesNotContain("SqliteRewriteClasspath")
	}
}
