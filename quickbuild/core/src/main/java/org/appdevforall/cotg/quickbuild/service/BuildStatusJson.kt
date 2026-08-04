package org.appdevforall.cotg.quickbuild.service

import com.google.gson.JsonObject
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic

/**
 * Builds the `statusJson` argument of `IQuickBuildTarget.onBuildStatus` (schema in
 * quickbuild/core/README.md).
 *
 * Every value must be a STRING on the wire: the runtime's MiniJson parser reads only
 * strings. It ignores unknown kinds and fields, so the schema can grow without breaking
 * installed proxy apps.
 */
object BuildStatusJson {
	const val KIND_BUILD_FAILED = "build_failed"
	const val KIND_BUILD_OK = "build_ok"
	const val KIND_BUILDING = "building"

	/**
	 * Tells the proxy app a build has started while it keeps running [runningGeneration],
	 * so a slow build does not read as silence on screen. Cleared by the [buildFailed] or
	 * [buildOk] the same attempt eventually sends.
	 */
	fun building(runningGeneration: Long): String =
		JsonObject()
			.apply {
				addProperty("kind", KIND_BUILDING)
				addProperty("runningGeneration", runningGeneration.toString())
			}.toString()

	/**
	 * Reports a compile failure as the first error's location, the first line of its
	 * message, and a count of the errors not shown. The overlay is a one-glance surface,
	 * not a build log.
	 */
	fun buildFailed(diagnostics: List<BuildDiagnostic>): String {
		val errors = diagnostics.filter { it.severity == BuildDiagnostic.Severity.ERROR }
		val shown = errors.firstOrNull() ?: diagnostics.firstOrNull()
		val more = if (errors.isNotEmpty()) errors.size - 1 else 0
		return JsonObject()
			.apply {
				addProperty("kind", KIND_BUILD_FAILED)
				shown?.file?.let { addProperty("file", it) }
				shown?.line?.let { addProperty("line", it.toString()) }
				shown?.column?.let { addProperty("column", it.toString()) }
				shown
					?.message
					?.lineSequence()
					?.firstOrNull()
					?.let { addProperty("message", it) }
				if (more > 0) {
					addProperty("moreErrors", more.toString())
				}
			}.toString()
	}

	/** Reports a successful build, which clears a shown failure and renders nothing itself. */
	fun buildOk(): String = JsonObject().apply { addProperty("kind", KIND_BUILD_OK) }.toString()
}
