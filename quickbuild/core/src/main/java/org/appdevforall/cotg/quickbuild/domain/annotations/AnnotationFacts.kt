package org.appdevforall.cotg.quickbuild.domain.annotations

/**
 * What a single source file tells us about annotation-processor input, extracted by
 * [SourceAnnotationScanner]. Everything here is derived from text - the quick path has
 * no compiler front-end at classification time - so the scanner is deliberately
 * over-inclusive and the analyzer treats "not sure" as "rebaseline".
 *
 * @property packageName declared package, empty for the default package.
 * @property imports import FQNs as written; a star import keeps its trailing `.*`.
 * @property annotations every `@Name(args)` occurrence in source order.
 * @property declaredTypeNames simple names of types this file declares (class /
 *   interface / object / enum / record), including nested ones.
 * @property declarationFingerprint the file's declaration surface: every code line
 *   outside a function/initializer body, comment- and whitespace-normalized. Two
 *   revisions with the same fingerprint differ only inside executable bodies.
 * @property referencedTypeNames capitalized identifiers appearing in the declaration
 *   surface (types a processor could follow out of this file, e.g. an `@Embedded`
 *   property's class or a `@Database(entities = [...])` argument).
 */
data class AnnotationFacts(
	val packageName: String,
	val imports: List<String>,
	val annotations: List<AnnotationUse>,
	val declaredTypeNames: Set<String>,
	val declarationFingerprint: List<String>,
	val referencedTypeNames: Set<String>,
)

/**
 * One annotation occurrence, exactly as written.
 *
 * @property name the name at the use site - simple (`Entity`) or qualified
 *   (`androidx.room.Entity`), without any use-site target.
 * @property arguments the parenthesized argument text with whitespace collapsed, or
 *   empty when the annotation has no argument list. Part of processor input: Room reads
 *   `@Query("...")`'s SQL, `@ColumnInfo(name = ...)`'s column name, and so on.
 * @property useSiteTarget the Kotlin use-site target (`field` in `@field:Json`), empty
 *   when absent. Kept separate from [name] so imports still resolve the name, but part
 *   of equality because `@get:Json` and `@field:Json` are different processor input.
 */
data class AnnotationUse(
	val name: String,
	val arguments: String,
	val useSiteTarget: String = "",
) {
	/** Last dot-segment of [name] - what an import has to match to resolve it. */
	val simpleName: String get() = name.substringAfterLast('.')
}
