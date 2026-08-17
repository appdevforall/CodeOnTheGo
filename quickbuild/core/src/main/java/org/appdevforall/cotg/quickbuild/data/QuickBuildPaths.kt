package org.appdevforall.cotg.quickbuild.data

import java.io.File

/**
 * Filesystem locations the quick-build pipeline needs on device.
 *
 * An interface so the module stays free of CoGo's `:common` Environment singleton and unit
 * tests can point everything at temp directories. The app-side stager re-extracts the
 * `<ANDROIDIDE_HOME>/quickbuild/` layout from APK assets on every provision, so a stale bundle
 * can never be served.
 */
interface QuickBuildPaths {
	/** The bundled JDK's `java` binary (same discovery the tooling server uses). */
	val javaBinary: File

	/**
	 * The staged daemon jar; the process runs with this jar's dir as cwd, and the jar's manifest
	 * Class-Path names sibling jars, so the whole runtime classpath is staged beside it.
	 */
	val daemonJar: File

	/** The staged runtime AAR handed to the proxy app build. */
	val runtimeAar: File

	/** On-device aapt2 (CoGo's Android-built binary, not the Maven one). */
	val aapt2: File

	/** d8/r8 jar for the daemon's in-process dexing. */
	val d8Jar: File

	/**
	 * The Compose compiler plugin jar staged next to the daemon jar, version-matched to the
	 * daemon's bundled Kotlin compiler - not the user project's Compose compiler, whose
	 * version tracks the project's own Kotlin. Passed as -Xplugin when the proxy app build
	 * reports the project uses Compose.
	 */
	val composeCompilerPlugin: File

	/** `android.jar` of the bundled compile SDK. */
	val androidJar: File

	/**
	 * Root for per-project scratch trees ([QuickBuildScratch]) on app-private, ext4-backed
	 * storage - not under the project on `/storage/emulated`, whose FUSE layer costs ~50x
	 * per file on this intermediate-heavy path (ADFA-4930). The app wires a
	 * `Context.noBackupFilesDir` subtree.
	 */
	val projectScratchRoot: File

	/**
	 * Builds the full environment for the daemon child process. The host app env must not be
	 * inherited: Android runtime classpath vars crash a standalone OpenJDK on some OEM images
	 * (the same reason ToolingServerRunner clears its env).
	 *
	 * @return the complete environment for the child - callers replace rather than merge, so
	 *   anything the daemon needs (`HOME`, `PATH`, `TMPDIR`, ...) has to be in here.
	 */
	fun daemonEnvironment(): Map<String, String>
}
