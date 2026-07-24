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
 * - A component class [libraryClassBytes] cannot find on the setup build's library search
 *   path (the variant compile classpath plus the extracted runtime AAR jars) is assumed
 *   project-owned: the pre-generalization default for every component this resolver isn't
 *   built to reason about. This is deliberate, not an oversight: [QuickBuildGenerateSourcesTask]
 *   runs BEFORE compilation in AGP's pipeline (manifest processing gates resource
 *   processing, which gates compilation), so there is no cycle-free way to check a
 *   component's class against the project's OWN compiled output at this point - trying to
 *   wire that dependency in creates a real Gradle task cycle (`generate` -> divert's
 *   payload classes -> compile -> resources -> manifest -> `generate`). A genuinely
 *   unresolvable component (neither project source nor on this search path) still fails
 *   loud at the real proxy compile (`QuickBuildPayloadDexTask`) exactly as before this
 *   resolver existed - unchanged, not regressed.
 * - A class [libraryClassBytes] DOES find is a library class: proxiable only if it is not
 *   `final` (read via [ClassOpener.isFinal] - the class file's access flags, never a
 *   loaded `Class`). This is the generalization that matters: Room's service is genuinely
 *   present on the compile classpath (Room is a real dependency), just `final`.
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
		 * finds `final` really would fail `cannot inherit from final` if proxied.
		 */
		fun forSetupBuild(librarySearchPath: List<File>): ComponentProxiabilityResolver =
			ComponentProxiabilityResolver(libraryClassBytes = { className -> findClassBytes(className, librarySearchPath) })

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
