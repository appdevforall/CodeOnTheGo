package org.appdevforall.cotg.quickbuild.service

import com.google.gson.JsonObject
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic

/**
 * Builds the `statusJson` argument of `IQuickBuildTarget.onBuildStatus`.
 *
 * The three builders below are the schema: each names its `kind` and the fields that go with
 * it, and the runtime's `BuildStatus` is the only reader. Every value must be a STRING on the
 * wire, because the runtime's MiniJson parser reads only strings. It ignores unknown kinds and
 * fields, so the schema can grow without breaking installed proxy apps.
 */
object BuildStatusJson {
	/** `kind` of the [buildFailed] message: a compile error the overlay shows. */
	const val KIND_BUILD_FAILED = "build_failed"

	/** `kind` of the [buildOk] message: clears whatever failure the overlay is showing. */
	const val KIND_BUILD_OK = "build_ok"

	/** `kind` of the [building] message: a build is in flight; the overlay says so. */
	const val KIND_BUILDING = "building"

	/**
	 * Tells the proxy app a build has started while it keeps running [runningGeneration],
	 * so a slow build does not read as silence on screen. Cleared by the [buildFailed] or
	 * [buildOk] the same attempt eventually sends.
	 *
	 * @param runningGeneration the generation the app is still running, not the one being
	 *   built; a caller with nothing truthful to say must not call this at all
	 * @return the `statusJson` argument for `onBuildStatus`
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
	 *
	 * @param diagnostics every diagnostic the compile produced, in the compiler's order;
	 *   errors are preferred over warnings when picking the one to show, and an empty list
	 *   yields a kind-only message
	 * @return the `statusJson` argument for `onBuildStatus`
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

	/**
	 * Reports a successful build, which clears a shown failure and renders nothing itself.
	 *
	 * @return the `statusJson` argument for `onBuildStatus`
	 */
	fun buildOk(): String = JsonObject().apply { addProperty("kind", KIND_BUILD_OK) }.toString()
}
