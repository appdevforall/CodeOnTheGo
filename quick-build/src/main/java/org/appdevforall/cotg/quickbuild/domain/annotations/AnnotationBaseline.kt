package org.appdevforall.cotg.quickbuild.domain.annotations

import java.io.File

/**
 * The annotation-processor input the setup build actually ran against - the reference
 * every later edit is compared to.
 *
 * Comparing against the BASELINE (not against the previous edit) is what makes the fast
 * path correct: the generated code sitting in the installed test app was produced from
 * this snapshot, so "unchanged versus the baseline" is exactly the condition under which
 * that generated code is still right. It also means an edit that adds an annotation and
 * a later edit that removes it again lands back on the fast path, which comparing
 * successive revisions would not.
 */
class AnnotationBaseline private constructor(
	private val facts: Map<String, AnnotationFacts?>,
	/**
	 * Simple type names an annotated file reaches out to: supertypes, `@Database(entities
	 * = [...])` targets, `@Embedded` property types, converter classes. A file that
	 * declares one of these can change generated output WITHOUT carrying an annotation
	 * itself (Room reads inherited fields and embedded classes), so declaring an anchor
	 * name forces a rebaseline.
	 */
	val anchorNames: Set<String>,
) {
	/** Facts recorded for [file] at baseline; null both when absent and when unscannable. */
	fun factsFor(file: File): AnnotationFacts? = facts[key(file)]

	/** True when [file] existed in the baseline source set (scannable or not). */
	fun known(file: File): Boolean = facts.containsKey(key(file))

	companion object {
		/**
		 * @param sources every source file the setup build compiled.
		 * @param readText content reader; returning null (unreadable) records the file as
		 *   unscannable, which makes any later change to it rebaseline.
		 */
		fun capture(
			sources: List<File>,
			profile: AnnotationProcessorProfile,
			readText: (File) -> String? = ::readOrNull,
		): AnnotationBaseline {
			val facts = LinkedHashMap<String, AnnotationFacts?>(sources.size)
			val anchors = mutableSetOf<String>()
			for (source in sources) {
				val scanned = readText(source)?.let(SourceAnnotationScanner::scan)
				facts[key(source)] = scanned
				if (scanned != null && scanned.annotations.any { profile.isProcessorInput(it, scanned) }) {
					anchors += scanned.referencedTypeNames
				}
			}
			return AnnotationBaseline(facts, anchors)
		}

		fun readOrNull(file: File): String? = runCatching { file.readText() }.getOrNull()

		private fun key(file: File): String = file.absoluteFile.normalize().path
	}
}
