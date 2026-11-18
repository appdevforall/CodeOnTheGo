package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.gradle.sqlite.requireEnvironment
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_REWRITE_RUNTIME_AAR
import com.itsaky.androidide.utils.FileProvider
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The release-APK guard (ADFA-7138): an app that opted into the SQLite debugger gets it in its debuggable
 * variants only. Builds the sample app's minified release and its debug variant with the real debugger,
 * rewrite runtime and requery AARs, and runs scripts/check-release-apk-no-sqlite-debugger.sh on both APKs.
 *
 * The debug APK is the control: it must fail the check, which shows the check still sees the debugger the
 * AAR brings. R8 renames and drops classes in a release, so the dex grep of the script can miss them; the
 * manifest provider and libsqlitedbg.so are what the control requires to fail.
 *
 * Run by the sqliteReleaseApkTest task, which builds the AARs and passes their paths. Run without an AAR's path, as
 * from an IDE, it skips with a message, and fails instead when the environment variable CI is set (requireEnvironment).
 */
class SqliteReleaseApkTest {
	private fun aar(name: String): File {
		val path = System.getProperty("cogo.test.sqlite.${name}Aar")
		requireEnvironment(path != null && File(path).isFile, "the $name AAR: run the :gradle-plugin:sqliteReleaseApkTest task")
		return File(path!!)
	}

	private class CheckResult(
		val exitCode: Int,
		val output: String,
	)

	private fun checkApk(apk: File): CheckResult {
		assertWithMessage("APK not built").that(apk.isFile).isTrue()
		val script = FileProvider.projectRoot().resolve("scripts/check-release-apk-no-sqlite-debugger.sh").toFile()
		val process = ProcessBuilder("bash", script.path, apk.path).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		check(process.waitFor(1, TimeUnit.MINUTES)) { "the check script did not finish" }
		println("${apk.name}: exit ${process.exitValue()}\n$output")
		return CheckResult(process.exitValue(), output)
	}

	@Test
	fun `an opted-in app's release APK carries no SQLite debugger, and its debug APK fails the check`(
		@TempDir dir: File,
	) {
		val debuggerAar = aar("debugger")
		val rewriteRuntimeAar = aar("rewriteRuntime")
		val requeryAar = aar("requery")

		// compileSdk: the platform CI's flox environment installs. requery comes from libs/ in a real app.
		val project =
			copySampleProject(
				dir,
				"""
				android { compileSdk = 36 }
				dependencies { implementation(files("${requeryAar.invariantSeparatorsPath}")) }
				""".trimIndent(),
			)
		// R8 does not fit in the fixture's 512m daemon
		project.resolve("gradle.properties").toFile().appendText("\norg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\n")

		val result =
			buildProject(task = ":app:assembleDemoRelease", projectDir = project, configureArgs = {
				it += ":app:assembleDemoDebug"
				it += "-P$PROPERTY_SQLITE_DEBUGGER_ENABLED=true"
				it += "-P$PROPERTY_SQLITE_DEBUGGER_AAR=${debuggerAar.absolutePath}"
				it += "-P$PROPERTY_SQLITE_REWRITE_RUNTIME_AAR=${rewriteRuntimeAar.absolutePath}"
			})

		// The release is a real opted-in build: its classpath check accepts requery's AAR as a file dependency,
		// and it is rewritten, then minified
		assertThat(result.task(":app:checkDemoReleaseSqliteRewriteClasspath")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
		assertThat(result.task(":app:transformDemoReleaseClassesWithAsm")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
		assertThat(result.task(":app:minifyDemoReleaseWithR8")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
		assertThat(result.output).contains("Adding SQLite debugger dependency to variant 'demoDebug' of project ':app'")

		val apks = project.resolve("app/build/outputs/apk/demo").toFile()
		val release = checkApk(File(apks, "release/app-demo-release-unsigned.apk"))
		assertWithMessage(release.output).that(release.exitCode).isEqualTo(0)
		assertThat(release.output).contains("OK: no SQLite debugger found")

		val debug = checkApk(File(apks, "debug/app-demo-debug.apk"))
		assertWithMessage(debug.output).that(debug.exitCode).isEqualTo(1)
		assertThat(debug.output).contains("FAIL: manifest declares provider org.appdevforall.sqlitedebugger.DebuggerStartup")
		assertThat(debug.output).containsMatch("FAIL: native library lib/[^/]+/libsqlitedbg\\.so")
	}
}
