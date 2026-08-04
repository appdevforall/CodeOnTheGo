package org.appdevforall.cotg.quickbuild.domain.annotations

import java.io.File

/**
 * The annotation-processor input the proxy app build ran against - the reference every later
 * edit is compared to.
 *
 * Comparing against the baseline rather than the previous edit is what makes the fast path
 * correct: the generated code in the installed proxy app came from this snapshot, so
 * "unchanged versus the baseline" is exactly when that generated code is still right. It also
 * lets an edit that adds an annotation and a later one that removes it end up back on the live
 * reload path.
 */
class AnnotationBaseline private constructor(
	private val facts: Map<String, AnnotationFacts?>,
	/**
	 * Simple type names an annotated file reaches out to: supertypes, `@Database(entities =
	 * [...])` targets, `@Embedded` property types, converter classes. Declaring one of these
	 * forces a rebaseline, because such a file can change generated output without carrying an
	 * annotation itself - Room reads inherited fields and embedded classes.
	 */
	val anchorNames: Set<String>,
) {
	/** Facts recorded for [file] at baseline; null both when absent and when unscannable. */
	fun factsFor(file: File): AnnotationFacts? = facts[key(file)]

	/** True when [file] existed in the baseline source set (scannable or not). */
	fun known(file: File): Boolean = facts.containsKey(key(file))

	companion object {
		/**
		 * Scans the proxy app build's whole source set into a baseline.
		 *
		 * @param sources every source file the proxy app build compiled.
		 * @param readText content reader; returning null records the file as unscannable,
		 *   which makes any later change to it rebaseline.
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

		/** File contents, or null when it cannot be read. */
		fun readOrNull(file: File): String? = runCatching { file.readText() }.getOrNull()

		private fun key(file: File): String = file.absoluteFile.normalize().path
	}
}
