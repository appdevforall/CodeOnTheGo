package com.itsaky.androidide.gradle.quickbuild

import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * THE authority on whether the proxy app build can emit `Proxy<N><Type> extends <userClass>`
 * for one manifest component (ADFA-4128). Both call sites ask this one object: the manifest
 * transform, which SKIPS a component this rejects (leaving it under its real manifest name),
 * and [QuickBuildPayloadDexTask.checkProxiability], which fails loud if one slips through.
 *
 * Two rules, in order:
 *
 * 1. [UNPROXIABLE_BY_NAME] - the cases no class file can reveal, keyed by name.
 * 2. The class file's own `ACC_FINAL` flag ([ClassOpener.isFinal] - the header's access
 *    flags, never a loaded `Class`), for any class [libraryClassBytes] finds. This is the
 *    rule that generalizes: Room's `MultiInstanceInvalidationService` and Compose's
 *    `PreviewActivity` are both simply `final`, and each broke a real project before being
 *    recognized. Any FUTURE final library component is now skipped automatically, without a
 *    CoGo release.
 *
 * A class [libraryClassBytes] cannot find at all is assumed project-owned and [Proxiable].
 * At manifest-transform time the project's own classes genuinely aren't compiled yet, so
 * "absent from the dependency artifacts" is the only signal available - which is exactly why
 * `ProfileInstallReceiver`, whose real problem is absence from the proxy compile classpath,
 * still has to be named in [UNPROXIABLE_BY_NAME] rather than inferred.
 *
 * [resolveWithProjectOverride] adds a project-owned class-name set that wins over rule 2 (but
 * not rule 1): a mixed Kotlin/Java module's compile classpath can expose a RAW
 * (pre-[ClassOpener]) copy of the project's own class alongside the divert task's opened
 * copy, and that raw copy reports `final` for every ordinary Kotlin class (Kotlin classes are
 * final by default) - [resolve] alone would then wrongly flag the user's own `MainActivity`
 * as unproxiable (regressed a real corpus app: ADFA-4128). Project membership must win,
 * because [ClassOpener] strips `final` from the divert task's own output before the real
 * compile, regardless of what a second, unrelated copy on the classpath looks like.
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
		UNPROXIABLE_BY_NAME[userClass]?.let { return Resolution.Skip(it) }
		val bytes = libraryClassBytes(userClass) ?: return Resolution.Proxiable
		return if (ClassOpener.isFinal(bytes)) {
			Resolution.Skip("final class - cannot be extended")
		} else {
			Resolution.Proxiable
		}
	}

	/**
	 * [resolve], except a [userClass] in [projectClasses] (e.g. the key set of
	 * [SupertypeResolver.supertypeIndex] over the divert task's diverted classes) is
	 * [Resolution.Proxiable] whatever the classpath says - see the class KDoc for the
	 * mixed-language raw-copy shape this defeats. [UNPROXIABLE_BY_NAME] still wins over both.
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
		 * Library components that cannot be proxied for a reason their class file does not
		 * carry, mapped to the reason. Everything else unproxiable - today's `final` classes
		 * and tomorrow's - is detected from the bytes by [resolve], so this list is meant to
		 * stay at two entries.
		 *
		 * - `androidx.startup.InitializationProvider` does a hardcoded self-lookup by this
		 *   exact component name at runtime (`AppInitializer` calls
		 *   `PackageManager.getProviderInfo(ComponentName(pkg, InitializationProvider))`) to
		 *   read its own `<meta-data>` initializer list - how every androidx library
		 *   (lifecycle-process, profileinstaller, emoji2, WorkManager, ...) registers its
		 *   startup hook, merged into ONE provider element. A renamed proxy breaks that
		 *   lookup with `NameNotFoundException` -> `StartupException`, crashing the proxy app
		 *   during `handleBindApplication` before anything binds. Not `final`, so nothing in
		 *   its bytes gives this away.
		 * - `androidx.profileinstaller.ProfileInstallReceiver` is in the merged manifest but
		 *   not on every proxy app build's proxy-compile classpath (`variant.compileClasspath`
		 *   doesn't always carry an AGP/transitively-injected runtime-only dependency), so
		 *   `extends` fails "cannot find symbol". Absence can't be distinguished from
		 *   "project-owned, not compiled yet" at manifest-transform time - see the class KDoc
		 *   - so it is named here. It does no self-lookup (checked its decompiled bytecode):
		 *   this is a classpath exclusion, not a correctness one.
		 *
		 * Excluding either is always safe: neither is ever recompiled by the daemon, so the
		 * swap-via-restart machinery a proxy exists for buys nothing, and left as-is they
		 * instantiate through the framework's default `AppComponentFactory` path exactly like
		 * an unmodified Android app (the runtime's `LoaderRouter` already falls back to the
		 * default loader for a class absent from the payload dex, which neither ever is).
		 */
		internal val UNPROXIABLE_BY_NAME =
			mapOf(
				"androidx.startup.InitializationProvider" to
					"resolves its own component by name at runtime; a renamed proxy breaks androidx App Startup",
				"androidx.profileinstaller.ProfileInstallReceiver" to
					"not on every proxy compile classpath, so the generated subclass would not compile",
			)

		/**
		 * Applies [UNPROXIABLE_BY_NAME] only - no classpath, so no class is ever found and
		 * rule 2 never fires. The [QuickBuildManifestTransformer] default, for tests and
		 * callers that have no classpath to offer.
		 */
		fun byNameOnly(): ComponentProxiabilityResolver = ComponentProxiabilityResolver(libraryClassBytes = { null })

		/**
		 * The real resolver. [classpath] is searched, in order, for each component's class -
		 * directories are probed by relative path, jars by zip entry name.
		 *
		 * Give it a classpath whose shape matches the decision being made: the manifest
		 * transform passes the variant's DEPENDENCY artifacts (available without compiling
		 * anything, so no task-graph cycle), the payload dex task passes the actual proxy
		 * compile classpath.
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
