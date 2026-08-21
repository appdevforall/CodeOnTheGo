package com.itsaky.androidide.gradle.quickbuild

import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Decides whether the build can emit `Proxy<N><Type> extends <userClass>` for one manifest
 * component, by [UNPROXIABLE_BY_NAME] first and then the class file's own `ACC_FINAL` flag. The
 * manifest transform skips what this rejects; [QuickBuildPayloadDexTask.checkProxiability] fails
 * the build if one slips through. A class [libraryClassBytes] cannot find is assumed project-owned
 * and [Resolution.Proxiable] - at transform time the project's own classes are not compiled yet.
 *
 * @property libraryClassBytes looks a binary class name up on whatever classpath the caller chose,
 *   returning the raw `.class` bytes or null when it holds no such class - see [byNameOnly] and
 *   [searchingClasspath] for the two shipped implementations.
 */
class ComponentProxiabilityResolver(
	private val libraryClassBytes: (String) -> ByteArray?,
) {
	/** Outcome for one component's userClass. */
	sealed interface Resolution {
		/** Safe to generate a `Proxy<N><Type> extends userClass` for this component. */
		data object Proxiable : Resolution

		/**
		 * Not safe; [reason] is a short, human-readable explanation for a build log line.
		 *
		 * @property reason why the component was rejected, as a lowercase phrase that reads after a
		 *   component name; log text only, nothing branches on it.
		 */
		data class Skip(
			val reason: String,
		) : Resolution
	}

	/**
	 * Applies both rules to [userClass]: the name list first, then the class file's final flag.
	 *
	 * @param userClass the component's implementation class, as a dotted binary name resolved
	 *   from the manifest (so `.MainActivity` has already been expanded against the package).
	 * @return [Resolution.Skip] with a reason if either rule rejects it, else
	 *   [Resolution.Proxiable] - including when the class is not on the classpath at all.
	 */
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
	 * @param userClass the component's implementation class, as a dotted binary name.
	 * @param projectClasses project-compiled class names, e.g. the key set of
	 *   [SupertypeResolver.supertypeIndex] over the divert task's output
	 * @return [Resolution.Proxiable] for anything in [projectClasses] that
	 *   [UNPROXIABLE_BY_NAME] does not name; otherwise whatever [resolve] decides.
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
		 * Library components whose class file cannot reveal why they are unproxiable, mapped to the
		 * reason; everything else is detected from the bytes by [resolve], so this list stays small.
		 * Detection cannot reach any of them: `InitializationProvider` is not final,
		 * `ProfileInstallReceiver` is absent from the classpath (indistinguishable from a
		 * not-yet-compiled project class), the keep-alive's finality depends on the classpath, and
		 * Firebase's `ComponentDiscoveryService` is an ordinary non-final class that happens to look
		 * itself up by name. Excluding them costs nothing - the daemon never recompiles them.
		 */
		internal val UNPROXIABLE_BY_NAME =
			mapOf(
				"androidx.startup.InitializationProvider" to
					"resolves its own component by name at runtime; a renamed proxy breaks androidx App Startup",
				"androidx.profileinstaller.ProfileInstallReceiver" to
					"not on every proxy compile classpath, so the generated subclass would not compile",
				"com.itsaky.androidide.quickbuild.runtime.QuickBuildKeepAliveService" to
					"CoGo binds this keep-alive by component name; a renamed proxy would leave the app freezer-eligible",
				"com.google.firebase.components.ComponentDiscoveryService" to
					"Firebase reads this service's own metadata by component name; a renamed proxy makes it discover zero ComponentRegistrars",
			)

		/**
		 * Builds a resolver that applies [UNPROXIABLE_BY_NAME] only - with no classpath, the
		 * final-flag rule never fires. The [QuickBuildManifestTransformer] default, for callers
		 * that have no classpath to offer.
		 *
		 * @return a resolver whose [resolve] answers [Resolution.Proxiable] for every class not
		 *   in [UNPROXIABLE_BY_NAME].
		 */
		fun byNameOnly(): ComponentProxiabilityResolver = ComponentProxiabilityResolver(libraryClassBytes = { null })

		/**
		 * Builds a resolver that looks each component's class up in [classpath], in order -
		 * directories by relative path, jars by zip entry name.
		 *
		 * Pass a classpath matching the decision: the manifest transform passes the variant's
		 * dependency artifacts, which resolve without compiling anything and so avoid a
		 * task-graph cycle; the payload dex task passes the real proxy compile classpath.
		 *
		 * @param classpath directories and jars to search, in precedence order; entries that are
		 *   neither, or that cannot be opened, are skipped rather than failing the lookup.
		 * @return a resolver that applies [UNPROXIABLE_BY_NAME] and then the final-flag rule.
		 */
		fun searchingClasspath(classpath: List<File>): ComponentProxiabilityResolver =
			ComponentProxiabilityResolver(libraryClassBytes = { className -> findClassBytes(className, classpath) })

		/**
		 * Finds one class's bytes on a mixed directory/jar search path.
		 *
		 * @param binaryClassName dotted class name, translated here to its `.class` entry path.
		 * @param searchPath roots to try in order; the first hit wins.
		 * @return the class bytes, or null if no root holds that class.
		 */
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

		/**
		 * Reads one zip entry out of a jar on the search path.
		 *
		 * @param jarFile the jar to open; need not actually be a zip.
		 * @param relativePath the entry name, e.g. `androidx/startup/InitializationProvider.class`.
		 * @return the entry's bytes, or null if the jar lacks the entry or cannot be read.
		 */
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
