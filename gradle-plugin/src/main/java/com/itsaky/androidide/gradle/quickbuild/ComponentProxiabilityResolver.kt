package com.itsaky.androidide.gradle.quickbuild

import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Decides, for one manifest component's class, whether the setup build can safely emit
 * `Proxy<N><Type> extends <userClass>` for it. Generalizes the by-name
 * [QuickBuildManifestTransformer.UNPROXIABLE_LIBRARY_COMPONENTS] exclusion (ADFA-4128 Bug
 * 7 - Compose's `PreviewActivity`) to any library class, by reading the ACTUAL class file
 * instead of only recognizing a few hardcoded names: Room's manifest-merged
 * `MultiInstanceInvalidationService` is `final` too, and nothing named it until it broke a
 * real project's setup build with `error: cannot inherit from final`.
 *
 * - A class [libraryClassBytes] DOES find is a library class: proxiable only if it is not
 *   `final` (read via [ClassOpener.isFinal] - the class file's access flags, never a
 *   loaded `Class`). This is the generalization that matters: Room's service is genuinely
 *   present on the compile classpath (Room is a real dependency), just `final`.
 * - A component class [libraryClassBytes] cannot find at all is assumed project-owned and
 *   is always [Resolution.Proxiable].
 *
 * [resolveWithProjectOverride] is the caller-facing entry point that ALSO takes a
 * project-owned class-name set and checks it FIRST, unconditionally winning over whatever
 * [libraryClassBytes] would say: a mixed Kotlin/Java module's compile classpath can expose
 * a RAW (pre-[ClassOpener]) copy of the project's own class alongside the divert task's
 * opened copy, and that raw copy reports `final` for every ordinary Kotlin class (Kotlin
 * classes are final by default) - `resolve()` alone would then wrongly flag the user's own
 * `MainActivity` as unproxiable (regressed a real corpus app: ADFA-4128). Project
 * membership must always win, because [ClassOpener] strips `final` from the divert task's
 * own output before the real compile, regardless of what a second, unrelated copy on the
 * classpath looks like.
 *
 * Pure logic - no Gradle types - so it unit-tests against fixture class bytes and fake
 * lookups, without a real classpath or Gradle test fixture.
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

	fun resolve(userClass: String): Resolution {
		val bytes = libraryClassBytes(userClass) ?: return Resolution.Proxiable
		return if (ClassOpener.isFinal(bytes)) {
			Resolution.Skip("final class - cannot be extended")
		} else {
			Resolution.Proxiable
		}
	}

	companion object {
		/**
		 * Every component is proxiable - the pre-generalization behavior, where the by-name
		 * [QuickBuildManifestTransformer.UNPROXIABLE_LIBRARY_COMPONENTS] set was the only
		 * exclusion. The [QuickBuildManifestTransformer] default, so tests and callers that
		 * don't care about classpath-derived skipping see unchanged behavior.
		 */
		fun alwaysProxiable(): ComponentProxiabilityResolver = ComponentProxiabilityResolver(libraryClassBytes = { null })

		/**
		 * The real resolver for a setup build. [librarySearchPath] is searched, in order,
		 * for each component's class - directories are probed by relative path, jars by zip
		 * entry name. Mirrors the exact classpath the real proxy compile uses (variant
		 * compile classpath plus the extracted runtime AAR jars), so a class this resolver
		 * finds `final` really would fail `cannot inherit from final` if proxied. Always
		 * pair this with [resolveWithProjectOverride] rather than calling [resolve]
		 * directly - see the class KDoc for why.
		 */
		fun forSetupBuild(librarySearchPath: List<File>): ComponentProxiabilityResolver =
			ComponentProxiabilityResolver(libraryClassBytes = { className -> findClassBytes(className, librarySearchPath) })

		/**
		 * [userClass] is [Resolution.Proxiable] unconditionally when it's in
		 * [projectClasses] (e.g. the key set of [SupertypeResolver.supertypeIndex] over the
		 * divert task's diverted classes) - checked BEFORE ever consulting [resolver], so a
		 * raw pre-open copy the compile classpath might also expose (the mixed-language
		 * shape described in the class KDoc) can never override project ownership.
		 * Otherwise defers to `resolver.resolve(userClass)`.
		 */
		fun resolveWithProjectOverride(
			userClass: String,
			projectClasses: Set<String>,
			resolver: ComponentProxiabilityResolver,
		): Resolution = if (userClass in projectClasses) Resolution.Proxiable else resolver.resolve(userClass)

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
