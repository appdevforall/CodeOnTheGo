package org.appdevforall.cotg.quickbuild.domain.annotations

import java.io.File

/**
 * The annotation-processor input the proxy app build ran against - the reference every later
 * edit is compared to.
 *
 * Comparing against the baseline rather than the previous edit is what makes the fast path
 * correct: the generated code in the installed proxy app came from this snapshot, so "unchanged
 * versus the baseline" is exactly when that generated code is still right.
 */
class AnnotationBaseline private constructor(
	/**
	 * Normalized absolute path -> the facts scanned at baseline. A null VALUE means the file was
	 * present but unscannable, which is why membership and value are asked separately.
	 */
	private val facts: Map<String, AnnotationFacts?>,
	/**
	 * Simple type names an annotated file reaches out to: supertypes, `@Database(entities =
	 * [...])` targets, `@Embedded` property types, converter classes. Declaring one of these
	 * forces a rebaseline, because such a file can change generated output without carrying an
	 * annotation itself - Room reads inherited fields and embedded classes.
	 */
	val anchorNames: Set<String>,
) {
	/**
	 * Facts recorded for [file] at baseline.
	 *
	 * @param file any path; matched after normalization, so relative and absolute forms agree.
	 * @return the recorded facts, or null both when the file was absent from the baseline and when
	 *   it was present but unscannable - pair with [known] to tell those apart.
	 */
	fun factsFor(file: File): AnnotationFacts? = facts[key(file)]

	/**
	 * True when [file] existed in the baseline source set (scannable or not).
	 *
	 * @param file any path; matched after normalization, as in [factsFor].
	 * @return true when the baseline scan saw the file, whatever the scan produced.
	 */
	fun known(file: File): Boolean = facts.containsKey(key(file))

	companion object {
		/**
		 * Scans the proxy app build's whole source set into a baseline.
		 *
		 * @param sources every source file the proxy app build compiled, since one missing here is
		 *   later treated as newly added and so costs a rebaseline.
		 * @param profile which annotations count as processor input, and so which files
		 *   contribute their referenced type names as anchors.
		 * @param readText content reader; returning null records the file as unscannable,
		 *   which makes any later change to it rebaseline.
		 * @return the baseline every later edit is compared against.
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

		/**
		 * Reads a source file's text, swallowing any I/O failure.
		 *
		 * @param file the source to read; decoded as UTF-8.
		 * @return the contents, or null when it cannot be read - which callers must treat as
		 *   "deleted or unreadable", not as an empty file.
		 */
		fun readOrNull(file: File): String? = runCatching { file.readText() }.getOrNull()

		private fun key(file: File): String = file.absoluteFile.normalize().path
	}
}
