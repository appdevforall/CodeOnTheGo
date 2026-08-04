package org.appdevforall.cotg.quickbuild.domain.annotations

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Decides whether a code change can have moved annotation-processor output, and so whether
 * the quick path must give way to a full Gradle rebaseline.
 *
 * Without it, a project with any processor configured would have to rebaseline on every edit,
 * because a stale generated class is indistinguishable from a fresh one at run time. With it,
 * only edits that touch processor input pay that cost - in a Room app, the entities, DAOs and
 * database class, not the UI.
 */
interface AnnotationImpact {
	/** True when the project configures at least one annotation processor. */
	val active: Boolean

	/**
	 * Checks a build's changed code files against the processor input.
	 *
	 * @return a human-readable reason to rebaseline, or null when every changed file is
	 *   provably outside processor input.
	 */
	fun escalation(changedCodeFiles: List<File>): String?

	/** No processors configured: nothing to protect, nothing ever escalates. */
	object Inactive : AnnotationImpact {
		override val active: Boolean = false

		override fun escalation(changedCodeFiles: List<File>): String? = null
	}
}

/**
 * An [AnnotationImpact] whose delegate can be swapped, so a rebaseline can move the reference
 * point without rebuilding the orchestrator.
 *
 * The Gradle build that just ran is the new baseline; comparing later edits against the
 * pre-rebaseline snapshot would keep charging for changes it already absorbed.
 */
class SwitchableAnnotationImpact(
	var delegate: AnnotationImpact,
) : AnnotationImpact {
	override val active: Boolean get() = delegate.active

	override fun escalation(changedCodeFiles: List<File>): String? = delegate.escalation(changedCodeFiles)
}

/**
 * The real [AnnotationImpact]: compares each changed file against the proxy app build's
 * [AnnotationBaseline].
 *
 * Rebaselines when a file carrying a processor-relevant annotation changes its declarations or
 * its annotations, when such a file is added or deleted, when a file declares one of the
 * baseline's [AnnotationBaseline.anchorNames], or when the scanner could not read a file
 * confidently.
 *
 * Stays on the live reload path for a file no processor reads, for a change that leaves the
 * declaration surface identical (a touch, a re-save, a reformat), and for edits confined to
 * function or initializer bodies - every processor the profile knows (Room, Dagger/Hilt,
 * Moshi, Glide, AutoValue) generates from declarations and annotation arguments, not statement
 * bodies. Annotation arguments keep their string literals, so an `@Query("SELECT ...")` edit
 * counts as a declaration change rather than a body edit.
 */
class AnnotationImpactAnalyzer(
	private val profile: AnnotationProcessorProfile,
	private val baseline: AnnotationBaseline,
	private val readText: (File) -> String? = AnnotationBaseline::readOrNull,
) : AnnotationImpact {
	private val log = LoggerFactory.getLogger(AnnotationImpactAnalyzer::class.java)

	override val active: Boolean get() = profile.hasProcessors

	override fun escalation(changedCodeFiles: List<File>): String? {
		if (!active) return null
		for (file in changedCodeFiles) {
			val reason = escalationFor(file)
			if (reason != null) {
				log.info("Quick build: annotation-processor input changed in {} ({})", file.name, reason)
				return "${file.name}: $reason"
			}
		}
		return null
	}

	/** Why [file] forces a rebaseline, or null when it provably misses processor input. */
	private fun escalationFor(file: File): String? {
		val old = baseline.factsFor(file)
		val existedAtBaseline = baseline.known(file)
		val current = readText(file)
		val new = current?.let(SourceAnnotationScanner::scan)

		if (existedAtBaseline && old == null) {
			return "baseline copy could not be scanned"
		}
		if (current == null) {
			// Deleted (or unreadable). Only matters if it fed a processor directly or as an
			// anchor; a deleted plain file cannot change generated output.
			if (old == null) return null
			if (old.hasProcessorInput()) return "annotated file was deleted"
			val anchors = old.declaredTypeNames.intersect(baseline.anchorNames)
			return if (anchors.isEmpty()) {
				null
			} else {
				"deleted ${anchors.sorted().joinToString()}, read by an annotated declaration"
			}
		}
		if (new == null) {
			return "file could not be scanned"
		}

		val oldIsInput = old?.hasProcessorInput() == true
		val newIsInput = new.hasProcessorInput()
		if (oldIsInput || newIsInput) {
			if (old == null) return "new file declares processor-relevant annotations"
			if (!oldIsInput || !newIsInput) return "processor-relevant annotations added or removed"
			if (old.processorAnnotations() != new.processorAnnotations()) {
				return "processor-relevant annotations changed"
			}
			if (old.declarationFingerprint != new.declarationFingerprint) {
				return "declarations of an annotated file changed"
			}
			return null
		}

		// A plain file only matters when it actually moved AND declares a type an annotated
		// declaration reads (an entity base class, an `@Embedded` value type, a converter).
		// A watcher event on an untouched file must not cost a rebaseline.
		if (old != null && old.declarationFingerprint == new.declarationFingerprint) return null
		val declaredAnchors =
			(new.declaredTypeNames + old?.declaredTypeNames.orEmpty()).intersect(baseline.anchorNames)
		if (declaredAnchors.isNotEmpty()) {
			return "declares ${declaredAnchors.sorted().joinToString()}, read by an annotated declaration"
		}
		return null
	}

	private fun AnnotationFacts.hasProcessorInput(): Boolean = annotations.any { profile.isProcessorInput(it, this) }

	private fun AnnotationFacts.processorAnnotations(): List<AnnotationUse> = annotations.filter { profile.isProcessorInput(it, this) }
}
