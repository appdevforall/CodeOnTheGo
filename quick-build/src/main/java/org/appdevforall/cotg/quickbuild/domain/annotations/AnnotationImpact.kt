package org.appdevforall.cotg.quickbuild.domain.annotations

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Decides whether a code change can have moved annotation-processor output, and so
 * whether the quick path must give way to a full Gradle rebaseline.
 *
 * Without this, a project with any processor configured has to rebaseline on EVERY edit
 * (the ~8 s path), because a stale generated class is indistinguishable from a fresh one
 * at run time. With it, only edits that actually touch processor input pay that cost -
 * in a Room app that is the entities, DAOs and database class, not the UI.
 */
interface AnnotationImpact {
	/** True when the project configures at least one annotation processor. */
	val active: Boolean

	/**
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
 * Indirection so a re-baseline can move the reference point without rebuilding the
 * orchestrator: the Gradle build that just ran IS the new baseline, and comparing later
 * edits against the pre-rebaseline snapshot would keep charging for changes already
 * absorbed. Mirrors how the session swaps its executor on the same event.
 */
class SwitchableAnnotationImpact(
	var delegate: AnnotationImpact,
) : AnnotationImpact {
	override val active: Boolean get() = delegate.active

	override fun escalation(changedCodeFiles: List<File>): String? = delegate.escalation(changedCodeFiles)
}

/**
 * The real [AnnotationImpact], comparing each changed file against the setup build's
 * [AnnotationBaseline].
 *
 * **Cases deliberately treated as SAFE (fast path), and why:**
 * 1. *A file with no processor-relevant annotation, before or after.* No configured
 *    processor has a declaration in it to read. (Its edits can still reach a processor
 *    indirectly - case 3 covers that.)
 * 2. *An edit inside a function/initializer body of an annotated file* - including a
 *    comment, whitespace or formatting change anywhere in it. Every processor the profile
 *    recognizes (Room, Dagger/Hilt, Moshi, Glide, AutoValue) generates from declarations
 *    and annotation arguments; none reads statement bodies. The declaration fingerprint
 *    covers the whole file except those bodies, and annotation ARGUMENTS keep their string
 *    literals verbatim, so an `@Query("SELECT ...")` SQL edit is NOT body-only.
 * 3. *A brand-new file with no processor-relevant annotation.* Adding a declaration cannot
 *    change what an existing annotated declaration generates, unless it collides with an
 *    anchor name - which is checked.
 * 4. *A changed-set entry whose declaration surface is identical to the baseline's* - a
 *    touch, an editor re-save, a reformat. Nothing a processor reads moved.
 *
 * **Cases deliberately treated as UNSAFE (rebaseline), and why:**
 * - Any change to a file carrying a processor-relevant annotation whose declaration
 *   surface or annotation list moved: adding an `@Entity` field, editing `@Query` SQL,
 *   changing an `@Inject` constructor's parameters, adding or removing the annotation.
 * - A new or deleted file that carries a processor-relevant annotation.
 * - A file declaring a type whose name is an [AnnotationBaseline.anchorNames] entry: an
 *   `@Entity`'s non-annotated base class, an `@Embedded` value type, a `@TypeConverters`
 *   converter, a `@Database(entities = [...])` target. Those feed generated code without
 *   an annotation of their own.
 * - Anything the scanner could not read confidently (unbalanced braces mid-edit, an
 *   unreadable file, a file the baseline never saw and cannot be scanned now).
 * - [ChangedFiles.Unknown]-shaped situations, handled one level up by the classifier: if
 *   we cannot enumerate what changed, we cannot prove it missed processor input.
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

	private fun AnnotationFacts.hasProcessorInput(): Boolean =
		annotations.any { profile.isProcessorInput(it, this) }

	private fun AnnotationFacts.processorAnnotations(): List<AnnotationUse> =
		annotations.filter { profile.isProcessorInput(it, this) }
}
