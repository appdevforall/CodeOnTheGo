package com.itsaky.androidide.quickbuild

import com.itsaky.androidide.utils.Environment
import org.appdevforall.cotg.quickbuild.data.QuickBuildPaths
import java.io.File

/**
 * [QuickBuildPaths] backed by CoGo's [Environment]. All quick-build artifacts stage
 * under `<ANDROIDIDE_HOME>/quickbuild/` (see [QuickBuildArtifactStager]); toolchain
 * binaries reuse the same discovery the tooling server uses.
 */
class EnvironmentQuickBuildPaths : QuickBuildPaths {
	/** Deliberately a getter: Environment.init runs after app start. */
	private val quickBuildHome: File
		get() = File(Environment.ANDROIDIDE_HOME, "quickbuild")

	val daemonDir: File
		get() = File(quickBuildHome, "daemon")

	override val javaBinary: File
		get() = Environment.JAVA

	override val daemonJar: File
		get() = File(daemonDir, "quickbuild-daemon.jar")

	override val runtimeAar: File
		get() = File(quickBuildHome, "quickbuild-runtime.aar")

	override val aapt2: File
		get() = Environment.AAPT2

	override val d8Jar: File
		get() =
			// Standard build-tools layout ships d8 as lib/d8.jar next to the aapt2 we
			// already use; fall back to a jar staged with the daemon if absent.
			File(Environment.BUILD_TOOLS_DIR, "lib/d8.jar").takeIf { it.isFile }
				?: File(daemonDir, "d8.jar")

	override val composeCompilerPlugin: File
		get() = File(daemonDir, "compose-compiler-plugin.jar")

	override val androidJar: File
		get() = Environment.ANDROID_JAR

	override fun daemonEnvironment(): Map<String, String> {
		val env = HashMap<String, String>()
		// Same base env the Gradle builds get (JAVA_HOME, ANDROID_HOME, HOME, ...);
		// built from scratch, never inherited from the app process.
		Environment.putEnvironment(env, false)
		return env
	}
}
