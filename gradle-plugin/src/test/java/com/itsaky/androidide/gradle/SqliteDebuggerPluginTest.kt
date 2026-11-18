package com.itsaky.androidide.gradle

import com.android.build.api.variant.ApplicationVariant
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_REWRITE_RUNTIME_AAR
import org.adfa.constants.KOTLIN_VERSION
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.UnexpectedBuildResultException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy

/**
 * The SQLite debugger (ADFA-7131) reaches the debuggable variants' runtime classpath, and only
 * when the project opts in. The fixture project has the debuggable `demoDebug`/`fullDebug` and the
 * non-debuggable `demoRelease`/`fullRelease` variants.
 */
class SqliteDebuggerPluginTest {
	private val aarName = "sqlite-debugger-release.aar"
	private val stdlib = "org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION"
	private val debugVariants = listOf("demoDebug", "fullDebug")
	private val releaseVariants = listOf("demoRelease", "fullRelease")

	private fun build(
		dir: File,
		enabled: String?,
		aar: File?,
		quickBuildAar: File? = null,
		runtimeAar: File? = File(dir, "sqlite-rewrite-runtime-release.aar").apply { writeText("aar") },
	): BuildResult =
		buildProject(task = ":app:printRuntimeDeps", configureArgs = {
			it += "--init-script"
			it += printDepsInitScript(dir).absolutePath
			if (enabled != null) it += "-P$PROPERTY_SQLITE_DEBUGGER_ENABLED=$enabled"
			if (aar != null) it += "-P$PROPERTY_SQLITE_DEBUGGER_AAR=${aar.absolutePath}"
			if (runtimeAar != null) it += "-P$PROPERTY_SQLITE_REWRITE_RUNTIME_AAR=${runtimeAar.absolutePath}"
			if (quickBuildAar != null) {
				it += "-P$PROPERTY_QUICK_BUILD_ENABLED=true"
				it += "-P$PROPERTY_QUICK_BUILD_RUNTIME_AAR=${quickBuildAar.absolutePath}"
			}
		})

	private fun BuildResult.declared(variant: String) = output.lines().filter { it.startsWith("RTDEP $variant ") }

	@Test
	fun `enabled adds the AAR and kotlin-stdlib to debuggable variants only`(
		@TempDir dir: File,
	) {
		val aar = File(dir, aarName).apply { writeText("aar") }
		val result = build(dir, "true", aar)

		assertThat(result.output).contains("Applying SqliteDebuggerPlugin to project ':app'")
		for (variant in debugVariants) {
			assertThat(result.declared(variant)).contains("RTDEP $variant file $aarName")
			assertThat(result.declared(variant)).contains("RTDEP $variant $stdlib")
			assertThat(result.output)
				.contains("Adding SQLite debugger dependency to variant '$variant' of project ':app'")
		}
		for (variant in releaseVariants) {
			// The fixture's own libraries declare other kotlin-stdlib versions in every variant
			assertThat(result.declared(variant).filter { it.contains(aarName) || it.endsWith(stdlib) })
				.isEmpty()
			assertThat(result.output)
				.doesNotContain("Adding SQLite debugger dependency to variant '$variant' of project ':app'")
		}
	}

	@Test
	fun `debuggable is read from the variant itself, not from a builder`() {
		fun variant(debuggable: Boolean): ApplicationVariant =
			Proxy.newProxyInstance(
				ApplicationVariant::class.java.classLoader,
				arrayOf(ApplicationVariant::class.java),
			) { _, method, _ ->
				when (method.name) {
					"getDebuggable" -> debuggable
					else -> throw UnsupportedOperationException(method.name)
				}
			} as ApplicationVariant

		assertThat(SqliteDebuggerPlugin.readDebuggable(variant(true))).isTrue()
		assertThat(SqliteDebuggerPlugin.readDebuggable(variant(false))).isFalse()
	}

	@Test
	fun `quick build is not applied to a project that opted into the debugger`(
		@TempDir dir: File,
	) {
		val aar = File(dir, aarName).apply { writeText("aar") }
		val runtime = File(dir, "quickbuild-runtime.aar").apply { writeText("aar") }

		val withDebugger = build(dir, "true", aar, runtime)
		assertThat(withDebugger.output).contains(AndroidIDEGradlePlugin.QUICK_BUILD_SQLITE_DEBUGGER_MESSAGE)
		assertThat(withDebugger.output).doesNotContain("Configuring Quick Build for variant")
		assertThat(withDebugger.output).contains("Adding SQLite debugger dependency to variant 'demoDebug'")

		// the control: without the opt-in the same invocation configures Quick Build
		val without = build(dir, null, aar, runtime)
		assertThat(without.output).doesNotContain(AndroidIDEGradlePlugin.QUICK_BUILD_SQLITE_DEBUGGER_MESSAGE)
		assertThat(without.output).contains("Configuring Quick Build for variant")
	}

	@Test
	fun `a property that is not true adds nothing`(
		@TempDir dir: File,
	) {
		val aar = File(dir, aarName).apply { writeText("aar") }
		for (enabled in listOf(null, "false")) {
			val result = build(dir, enabled, aar)

			assertThat(result.output).doesNotContain("Applying SqliteDebuggerPlugin")
			for (variant in debugVariants + releaseVariants) {
				assertThat(result.declared(variant).filter { it.contains(aarName) }).isEmpty()
			}
		}
	}

	@Test
	fun `enabled without the AAR property fails the build and says how to fix it`(
		@TempDir dir: File,
	) {
		val failure = runCatching { build(dir, "true", null) }.exceptionOrNull()

		assertThat(failure).isInstanceOf(UnexpectedBuildResultException::class.java)
		assertThat(failure!!.message).contains("no property '$PROPERTY_SQLITE_DEBUGGER_AAR' is set")
	}

	@Test
	fun `enabled with a missing AAR file fails the build and says how to fix it`(
		@TempDir dir: File,
	) {
		val failure = runCatching { build(dir, "true", File(dir, "absent.aar")) }.exceptionOrNull()

		assertThat(failure).isInstanceOf(UnexpectedBuildResultException::class.java)
		assertThat(failure!!.message).contains("SQLite debugger AAR not found")
	}
}
