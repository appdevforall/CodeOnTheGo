package com.itsaky.androidide.gradle.quickbuild

import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Decides whether the build can emit `Proxy<N><Type> extends <userClass>` for one manifest
 * component. The single authority for that call: the manifest transform skips a component this
 * rejects, and [QuickBuildPayloadDexTask.checkProxiability] fails the build if one slips through.
 *
 * Two rules, in order:
 *
 * 1. [UNPROXIABLE_BY_NAME] - the cases no class file can reveal, keyed by name.
 * 2. The class file's own `ACC_FINAL` flag, for any class [libraryClassBytes] finds. This rule
 *    generalizes to future final library components with no CoGo release.
 *
 * A class [libraryClassBytes] cannot find is assumed project-owned and [Resolution.Proxiable]:
 * at manifest-transform time the project's own classes are not compiled yet, so absence from
 * the dependency artifacts is the only signal available.
 */
class ComponentProxiabilityResolver(
	private val libraryClassBytes: (String) -> ByteArray?,
) {
	/** Outcome for one component's userClass. */
	sealed interface Resolution {
		/** Safe to generate a `Proxy<N><Type> extends userClass` for this component. */
		data object Proxiable : Resolution

		/** Not safe; [reason] is a short, human-readable explanation for a build log line. */
		data class Skip(
			val reason: String,
		) : Resolution
	}

	/** Applies both rules to [userClass]: the name list first, then the class file's final flag. */
	fun resolve(userClass: String): Resolution {
		UNPROXIABLE_BY_NAME[userClass]?.let { return Resolution.Skip(it) }
		val bytes = libraryClassBytes(userClass) ?: return Resolution.Proxiable
		return if (ClassOpener.isFinal(bytes)) {
			Resolution.Skip("final class - cannot be extended")
		} else {
			Resolution.Proxiable
		}
	}

	/**
	 * [resolve], except that a class the project itself compiled is always proxiable.
	 *
	 * A mixed Kotlin/Java module's compile classpath can carry a raw, pre-[ClassOpener] copy of
	 * a project class, and that copy reports final for every ordinary Kotlin class - [resolve]
	 * alone would then reject the user's own `MainActivity`. [UNPROXIABLE_BY_NAME] still wins.
	 *
	 * @param projectClasses project-compiled class names, e.g. the key set of
	 *   [SupertypeResolver.supertypeIndex] over the divert task's output
	 */
	fun resolveWithProjectOverride(
		userClass: String,
		projectClasses: Set<String>,
	): Resolution {
		UNPROXIABLE_BY_NAME[userClass]?.let { return Resolution.Skip(it) }
		return if (userClass in projectClasses) Resolution.Proxiable else resolve(userClass)
	}

	companion object {
		/**
		 * Library components whose class file cannot reveal why they are unproxiable, mapped to
		 * the reason. Everything else is detected from the bytes by [resolve], so this list is
		 * meant to stay small.
		 *
		 * - `androidx.startup.InitializationProvider` looks itself up by this exact component
		 *   name at runtime, to read the `<meta-data>` initializer list every androidx library
		 *   registers into. A renamed proxy turns that into `NameNotFoundException` ->
		 *   `StartupException` during `handleBindApplication`. It is not final, so its bytes
		 *   give nothing away.
		 * - `androidx.profileinstaller.ProfileInstallReceiver` is in the merged manifest but not
		 *   always on the proxy compile classpath, so `extends` fails to compile. Absence is
		 *   indistinguishable from "project-owned, not compiled yet", so it is named here rather
		 *   than inferred. A classpath exclusion, not a correctness one.
		 *
		 * Excluding either is safe: the daemon never recompiles them, so they gain nothing from
		 * a proxy, and they instantiate through the framework's default `AppComponentFactory`
		 * path like any unmodified app.
		 */
		internal val UNPROXIABLE_BY_NAME =
			mapOf(
				"androidx.startup.InitializationProvider" to
					"resolves its own component by name at runtime; a renamed proxy breaks androidx App Startup",
				"androidx.profileinstaller.ProfileInstallReceiver" to
					"not on every proxy compile classpath, so the generated subclass would not compile",
			)

		/**
		 * Builds a resolver that applies [UNPROXIABLE_BY_NAME] only - with no classpath, the
		 * final-flag rule never fires. The [QuickBuildManifestTransformer] default, for callers
		 * that have no classpath to offer.
		 */
		fun byNameOnly(): ComponentProxiabilityResolver = ComponentProxiabilityResolver(libraryClassBytes = { null })

		/**
		 * Builds a resolver that looks each component's class up in [classpath], in order -
		 * directories by relative path, jars by zip entry name.
		 *
		 * Pass a classpath matching the decision: the manifest transform passes the variant's
		 * dependency artifacts, which resolve without compiling anything and so avoid a
		 * task-graph cycle; the payload dex task passes the real proxy compile classpath.
		 */
		fun searchingClasspath(classpath: List<File>): ComponentProxiabilityResolver =
			ComponentProxiabilityResolver(libraryClassBytes = { className -> findClassBytes(className, classpath) })

		private fun findClassBytes(
			binaryClassName: String,
			searchPath: List<File>,
		): ByteArray? {
			val relativePath = binaryClassName.replace('.', '/') + ".class"
			for (root in searchPath) {
				if (root.isDirectory) {
					val candidate = File(root, relativePath)
					if (candidate.isFile) return candidate.readBytes()
				} else if (root.isFile) {
					findClassBytesInJar(root, relativePath)?.let { return it }
				}
			}
			return null
		}

		private fun findClassBytesInJar(
			jarFile: File,
			relativePath: String,
		): ByteArray? =
			try {
				JarFile(jarFile).use { jar ->
					jar.getEntry(relativePath)?.let { entry -> jar.getInputStream(entry).use { it.readBytes() } }
				}
			} catch (_: IOException) {
				// Corrupt or non-jar entry on the search path: treat as "doesn't have it",
				// the same tolerant handling SupertypeResolver gives a corrupt payload jar.
				null
			}
	}
}
