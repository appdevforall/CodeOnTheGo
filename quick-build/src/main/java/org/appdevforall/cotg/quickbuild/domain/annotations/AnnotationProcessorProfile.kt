package org.appdevforall.cotg.quickbuild.domain.annotations

/**
 * Which annotations count as processor input for THIS project, derived from the
 * processors the setup build reported (`setup.json` `annotationProcessors`, the
 * `ksp` / `kapt` / `annotationProcessor` configurations).
 *
 * Two modes, because getting this wrong in the permissive direction ships stale
 * generated code:
 * - **All processors recognized** - only annotations from those processors' own packages
 *   are input. A Room-only project can edit a Composable, a ViewModel or an Activity on
 *   the fast path.
 * - **Any processor unrecognized** - ANY annotation is treated as input, except the
 *   language-level ones no processor can meaningfully consume ([LANGUAGE_INERT]). Files
 *   with no annotations at all still take the fast path, which is most of a real app.
 *
 * An annotation whose name cannot be resolved to a package (no matching import, no
 * qualified use) resolves as input whenever its simple name is one a configured
 * processor is known to consume, and always resolves as input in unrecognized mode.
 */
class AnnotationProcessorProfile private constructor(
	/** Dependency coordinates as reported by the setup build; empty means no processors. */
	val processorCoordinates: List<String>,
	private val specs: List<ProcessorSpec>,
	private val hasUnrecognized: Boolean,
) {
	/** False when the project configures no annotation processor at all. */
	val hasProcessors: Boolean get() = processorCoordinates.isNotEmpty()

	private val packages: Set<String> = specs.flatMapTo(mutableSetOf()) { it.annotationPackages }
	private val simpleNames: Set<String> = specs.flatMapTo(mutableSetOf()) { it.annotationSimpleNames }

	/** True when [use], as written in [facts], can feed a configured processor. */
	fun isProcessorInput(
		use: AnnotationUse,
		facts: AnnotationFacts,
	): Boolean {
		if (!hasProcessors) return false
		val resolved = resolve(use, facts)
		if (resolved != null) {
			if (isLanguageInert(resolved)) return false
			if (hasUnrecognized) return true
			return packages.any { resolved.startsWith("$it.") }
		}
		// Unresolvable (star import, same-package annotation, missing import): the simple
		// name is all we have. Known processor vocabulary wins; otherwise only the
		// unrecognized-processor mode treats it as input - minus the stdlib names that are
		// always in scope without an import and can never be processor input.
		if (use.simpleName in LANGUAGE_INERT_NAMES) return false
		return use.simpleName in simpleNames || hasUnrecognized
	}

	/** FQN of [use] if imports (or the use site itself) pin it down, else null. */
	private fun resolve(
		use: AnnotationUse,
		facts: AnnotationFacts,
	): String? {
		if (use.name.contains('.')) return use.name
		val simple = use.simpleName
		facts.imports.firstOrNull { it.substringAfterLast('.') == simple }?.let { return it }
		return null
	}

	private fun isLanguageInert(fqn: String): Boolean = LANGUAGE_INERT.any { fqn.startsWith("$it.") }

	/** One processor's annotation vocabulary. */
	data class ProcessorSpec(
		val id: String,
		val annotationPackages: Set<String>,
		/**
		 * Names this processor consumes, used when an import cannot resolve the use site.
		 * Not exhaustive by design - it is a fallback on top of the package match.
		 */
		val annotationSimpleNames: Set<String>,
	)

	companion object {
		/** No processors configured: nothing is processor input, nothing ever escalates. */
		val NONE = AnnotationProcessorProfile(emptyList(), emptyList(), hasUnrecognized = false)

		/**
		 * @param coordinates processor dependency coordinates (`group:artifact:version`,
		 *   or whatever the setup build could report - matching is substring-based so a
		 *   version-catalog alias like `libs.room.compiler` still identifies Room).
		 */
		fun of(coordinates: List<String>): AnnotationProcessorProfile {
			val cleaned = coordinates.map { it.trim() }.filter { it.isNotEmpty() }
			if (cleaned.isEmpty()) return NONE
			val specs = mutableListOf<ProcessorSpec>()
			var unrecognized = false
			for (coordinate in cleaned) {
				val spec = KNOWN.firstOrNull { (marker, _) -> coordinate.contains(marker, ignoreCase = true) }
				if (spec == null) unrecognized = true else specs += spec.second
			}
			return AnnotationProcessorProfile(cleaned, specs.distinctBy { it.id }, unrecognized)
		}

		private val ROOM =
			ProcessorSpec(
				id = "room",
				annotationPackages = setOf("androidx.room"),
				annotationSimpleNames =
					setOf(
						"Database", "Entity", "Dao", "Query", "Insert", "Update", "Delete", "Upsert",
						"PrimaryKey", "ColumnInfo", "Embedded", "Relation", "Ignore", "Index",
						"ForeignKey", "TypeConverter", "TypeConverters", "Transaction", "RawQuery",
						"RewriteQueriesToDropUnusedColumns", "DatabaseView", "Fts3", "Fts4",
						"AutoMigration", "DeleteColumn", "DeleteTable", "RenameColumn", "RenameTable",
						"MapInfo", "SkipQueryVerification", "Junction",
					),
			)

		private val DAGGER_HILT =
			ProcessorSpec(
				id = "dagger-hilt",
				annotationPackages =
					setOf("dagger", "javax.inject", "jakarta.inject", "androidx.hilt", "dagger.hilt"),
				annotationSimpleNames =
					setOf(
						"Inject", "Module", "Provides", "Binds", "Component", "Subcomponent",
						"AndroidEntryPoint", "HiltAndroidApp", "HiltViewModel", "HiltWorker",
						"InstallIn", "EntryPoint", "Qualifier", "Scope", "Singleton", "Named",
						"IntoSet", "IntoMap", "BindsInstance", "Assisted", "AssistedInject",
						"AssistedFactory", "MapKey", "Reusable", "DefineComponent",
					),
			)

		private val MOSHI =
			ProcessorSpec(
				id = "moshi",
				annotationPackages = setOf("com.squareup.moshi"),
				annotationSimpleNames = setOf("JsonClass", "Json", "JsonQualifier"),
			)

		private val GLIDE =
			ProcessorSpec(
				id = "glide",
				annotationPackages = setOf("com.bumptech.glide.annotation"),
				annotationSimpleNames = setOf("GlideModule", "GlideExtension", "GlideOption", "GlideType"),
			)

		private val AUTO_VALUE =
			ProcessorSpec(
				id = "auto-value",
				annotationPackages = setOf("com.google.auto.value", "com.google.auto.service"),
				annotationSimpleNames = setOf("AutoValue", "AutoService", "Memoized", "CopyAnnotations"),
			)

		/**
		 * Coordinate marker -> vocabulary. Substring match against whatever the setup
		 * build reported, so both `androidx.room:room-compiler:2.6.1` and a catalog alias
		 * (`libs.room.compiler`) identify Room. A coordinate matching nothing here flips
		 * the profile into the conservative unrecognized mode.
		 */
		private val KNOWN: List<Pair<String, ProcessorSpec>> =
			listOf(
				"room" to ROOM,
				"hilt" to DAGGER_HILT,
				"dagger" to DAGGER_HILT,
				"moshi" to MOSHI,
				"glide" to GLIDE,
				"auto-value" to AUTO_VALUE,
				"auto.value" to AUTO_VALUE,
				"auto-service" to AUTO_VALUE,
				"auto.service" to AUTO_VALUE,
			)

		/**
		 * Packages whose annotations are language/compiler-level and cannot be a
		 * processor's input, so they never force a rebaseline even in unrecognized mode.
		 * Kept deliberately narrow: `androidx.annotation` and `androidx.compose` are NOT
		 * here, because third-party processors (Showkase, Compose Destinations and kin)
		 * really do read Compose annotations.
		 */
		private val LANGUAGE_INERT =
			setOf("kotlin", "java.lang", "org.jetbrains.annotations")

		/**
		 * The [LANGUAGE_INERT] annotations that are in scope with no import, so a use site
		 * cannot be resolved to a package. Treating a same-package user annotation with one
		 * of these names as inert is the one accepted (and vanishingly rare) blind spot.
		 */
		private val LANGUAGE_INERT_NAMES =
			setOf(
				"Deprecated", "Suppress", "SuppressWarnings", "Override", "SafeVarargs",
				"FunctionalInterface", "Throws", "OptIn", "RequiresOptIn", "PublishedApi",
				"JvmStatic", "JvmField", "JvmName", "JvmOverloads", "JvmSynthetic", "JvmInline",
				"Synchronized", "Volatile", "Transient", "Strictfp", "DslMarker",
				"Target", "Retention", "MustBeDocumented", "Repeatable",
			)
	}
}
