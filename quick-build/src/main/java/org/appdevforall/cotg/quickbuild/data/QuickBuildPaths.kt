package org.appdevforall.cotg.quickbuild.data

import java.io.File

/**
 * Filesystem locations the quick-build pipeline needs on device. Kept behind an
 * interface so the module stays free of CoGo's `:common` Environment singleton and
 * unit tests can point everything at temp directories.
 *
 * Staged layout (tonight's contract, produced by the app-side artifact stager from
 * APK assets on every provision — content-keyed staging is deliberately avoided by
 * re-staging each time, so a fixed bundle can never be served stale):
 *
 * `<ANDROIDIDE_HOME>/quickbuild/`
 * - `quickbuild-runtime.aar` - injected into the setup build via
 *   `-Pcotg.quickbuild.runtimeAar` (LogSender AAR pattern)
 * - `daemon/quickbuild-daemon.jar` - runnable daemon jar; its manifest Class-Path
 *   names sibling jars, so the WHOLE runtime classpath is staged in `daemon/`
 */
interface QuickBuildPaths {
	/** The bundled JDK's `java` binary (same discovery the tooling server uses). */
	val javaBinary: File

	/** The staged daemon jar; the daemon process runs with this jar's dir as cwd. */
	val daemonJar: File

	/** The staged runtime AAR handed to the setup build. */
	val runtimeAar: File

	/** On-device aapt2 (CoGo's Android-built binary, not the Maven one). */
	val aapt2: File

	/** d8/r8 jar for the daemon's in-process dexing. */
	val d8Jar: File

	/**
	 * The Compose compiler plugin jar staged next to the daemon jar, version-matched
	 * to the daemon's bundled Kotlin compiler (NOT the user project's Compose compiler,
	 * whose version tracks the project's own Kotlin). Passed as -Xplugin when the setup
	 * build reports the project uses Compose.
	 */
	val composeCompilerPlugin: File

	/** `android.jar` of the bundled compile SDK. */
	val androidJar: File

	/**
	 * Full environment for the daemon child process. The host app env must NOT be
	 * inherited: Android runtime classpath vars crash a standalone OpenJDK on some
	 * OEM images (same reason ToolingServerRunner clears its env).
	 */
	fun daemonEnvironment(): Map<String, String>
}
