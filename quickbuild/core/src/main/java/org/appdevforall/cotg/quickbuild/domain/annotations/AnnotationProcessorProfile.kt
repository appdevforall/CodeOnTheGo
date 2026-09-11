package org.appdevforall.cotg.quickbuild.domain.annotations

/**
 * Which annotations count as processor input for this project, derived from the processors the
 * proxy app build reported (setup.json `annotationProcessors`).
 *
 * Two modes, because being permissive here ships stale generated code: with every processor
 * recognized, only annotations from those processors' own packages are input; with any processor
 * unrecognized, every annotation is input except the language-level ones ([LANGUAGE_INERT]).
 */
class AnnotationProcessorProfile private constructor(
	/** Dependency coordinates as reported by the proxy app build; empty means no processors. */
	val processorCoordinates: List<String>,
	/** Vocabulary of the recognized processors only, deduplicated by [ProcessorSpec.id]. */
	private val specs: List<ProcessorSpec>,
	/**
	 * True when at least one coordinate matched no known processor, which switches the profile
	 * into the conservative mode where every non-inert annotation counts as input.
	 */
	private val hasUnrecognized: Boolean,
) {
	/** False when the project configures no annotation processor at all. */
	val hasProcessors: Boolean get() = processorCoordinates.isNotEmpty()

	private val packages: Set<String> = specs.flatMapTo(mutableSetOf()) { it.annotationPackages }
	private val simpleNames: Set<String> = specs.flatMapTo(mutableSetOf()) { it.annotationSimpleNames }

	/**
	 * True when [use], as written in [facts], can feed a configured processor.
	 *
	 * @param use one annotation occurrence, name exactly as the source wrote it.
	 * @param facts the same file's facts, needed for the imports that resolve a simple name.
	 * @return true when the annotation could be processor input, deliberately over-inclusive on an
	 *   unresolvable name because a wrong `false` here would ship stale generated code.
	 */
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
		// Unresolvable (star import, same-package annotation, missing import): the simple name
		// is all we have. Known processor vocabulary wins; otherwise only unrecognized mode
		// treats it as input, minus the stdlib names that are in scope without an import.
		if (use.simpleName in LANGUAGE_INERT_NAMES) return false
		return use.simpleName in simpleNames || hasUnrecognized
	}

	/**
	 * FQN of [use] if imports (or the use site itself) pin it down.
	 *
	 * @param use one annotation occurrence; already fully qualified at the use site when its
	 *   name carries a dot.
	 * @param facts the same file's facts, read only for its import list.
	 * @return the resolved FQN, or null when nothing pins the simple name down (star import,
	 *   same-package annotation, missing import) and the caller must fall back to that name.
	 */
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
		/** Stable key for the processor; two coordinates mapping to it contribute one spec. */
		val id: String,
		/** Packages whose annotations this processor consumes; matched as an FQN prefix. */
		val annotationPackages: Set<String>,
		/**
		 * Names this processor consumes, used when an import cannot resolve the use site and so
		 * not exhaustive by design - it is a fallback on top of the package match.
		 */
		val annotationSimpleNames: Set<String>,
	)

	companion object {
		/** No processors configured: nothing is processor input, nothing ever escalates. */
		val NONE = AnnotationProcessorProfile(emptyList(), emptyList(), hasUnrecognized = false)

		/**
		 * Builds the profile for a project's configured processors.
		 *
		 * @param coordinates processor dependency coordinates as the proxy app build reports
		 *   them, `group:artifact:version`. Only the group is matched, and exactly: a substring
		 *   match read `com.example:roomy-processor` as Room, and would read a local processor
		 *   module in a project called `ClassroomApp` the same way, silencing the escalation
		 *   that keeps its generated code fresh.
		 * @return [NONE] for an empty or blank-only list; otherwise a profile that turns
		 *   conservative as soon as a single coordinate goes unrecognized.
		 */
		fun of(coordinates: List<String>): AnnotationProcessorProfile {
			val cleaned = coordinates.map { it.trim() }.filter { it.isNotEmpty() }
			if (cleaned.isEmpty()) return NONE
			val specs = mutableListOf<ProcessorSpec>()
			var unrecognized = false
			for (coordinate in cleaned) {
				val spec = KNOWN[coordinate.substringBefore(':').lowercase()]
				if (spec == null) unrecognized = true else specs += spec
			}
			return AnnotationProcessorProfile(cleaned, specs.distinctBy { it.id }, unrecognized)
		}

		private val ROOM =
			ProcessorSpec(
				id = "room",
				annotationPackages = setOf("androidx.room"),
				annotationSimpleNames =
					setOf(
						"Database",
						"Entity",
						"Dao",
						"Query",
						"Insert",
						"Update",
						"Delete",
						"Upsert",
						"PrimaryKey",
						"ColumnInfo",
						"Embedded",
						"Relation",
						"Ignore",
						"Index",
						"ForeignKey",
						"TypeConverter",
						"TypeConverters",
						"Transaction",
						"RawQuery",
						"RewriteQueriesToDropUnusedColumns",
						"DatabaseView",
						"Fts3",
						"Fts4",
						"AutoMigration",
						"DeleteColumn",
						"DeleteTable",
						"RenameColumn",
						"RenameTable",
						"MapInfo",
						"SkipQueryVerification",
						"Junction",
					),
			)

		private val DAGGER_HILT =
			ProcessorSpec(
				id = "dagger-hilt",
				annotationPackages =
					setOf("dagger", "javax.inject", "jakarta.inject", "androidx.hilt", "dagger.hilt"),
				annotationSimpleNames =
					setOf(
						"Inject",
						"Module",
						"Provides",
						"Binds",
						"Component",
						"Subcomponent",
						"AndroidEntryPoint",
						"HiltAndroidApp",
						"HiltViewModel",
						"HiltWorker",
						"InstallIn",
						"EntryPoint",
						"Qualifier",
						"Scope",
						"Singleton",
						"Named",
						"IntoSet",
						"IntoMap",
						"BindsInstance",
						"Assisted",
						"AssistedInject",
						"AssistedFactory",
						"MapKey",
						"Reusable",
						"DefineComponent",
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
		 * Maven group (lower case) -> vocabulary. A coordinate whose group is not here flips the
		 * profile into the conservative unrecognized mode, which costs a full build rather than
		 * risking stale generated code - so a group this list misses fails in the safe direction.
		 */
		private val KNOWN: Map<String, ProcessorSpec> =
			mapOf(
				"androidx.room" to ROOM,
				"com.google.dagger" to DAGGER_HILT,
				"androidx.hilt" to DAGGER_HILT,
				"com.squareup.moshi" to MOSHI,
				"com.github.bumptech.glide" to GLIDE,
				"com.google.auto.value" to AUTO_VALUE,
				"com.google.auto.service" to AUTO_VALUE,
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
				"Deprecated",
				"Suppress",
				"SuppressWarnings",
				"Override",
				"SafeVarargs",
				"FunctionalInterface",
				"Throws",
				"OptIn",
				"RequiresOptIn",
				"PublishedApi",
				"JvmStatic",
				"JvmField",
				"JvmName",
				"JvmOverloads",
				"JvmSynthetic",
				"JvmInline",
				"Synchronized",
				"Volatile",
				"Transient",
				"Strictfp",
				"DslMarker",
				"Target",
				"Retention",
				"MustBeDocumented",
				"Repeatable",
			)
	}
}
