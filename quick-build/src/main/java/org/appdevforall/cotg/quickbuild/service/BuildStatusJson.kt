package org.appdevforall.cotg.quickbuild.service

import com.google.gson.JsonObject
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic

/**
 * Encodes the `statusJson` argument of `IQuickBuildTarget.onBuildStatus` (plan A1;
 * schema in quick-build/README.md). Every value is a STRING on the wire - the runtime's
 * deliberately tiny MiniJson parser reads only strings - and the runtime ignores unknown
 * kinds/fields, so the schema can grow without breaking installed test apps.
 */
object BuildStatusJson {
	const val KIND_BUILD_FAILED = "build_failed"
	const val KIND_BUILD_OK = "build_ok"

	/**
	 * A compile failure: carries the FIRST error's location plus the first line of its
	 * message (the overlay is a one-glance surface, not a build log), and how many more
	 * errors the build reported.
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

	/** A successful build: clears a previously shown failure, renders nothing itself. */
	fun buildOk(): String = JsonObject().apply { addProperty("kind", KIND_BUILD_OK) }.toString()
}
