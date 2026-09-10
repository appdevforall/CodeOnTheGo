package org.appdevforall.cotg.quickbuild.service.deploy

import com.google.gson.JsonObject
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic

/**
 * Builds the `statusJson` argument of `IQuickBuildTarget.onBuildStatus`.
 *
 * The builders below are the schema: each names its `kind` and the fields that go with
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
	 * `kind` of the [reinstallPending] message: a rebuild finished but its reinstall is
	 * waiting on an install confirmation only CoGo can show.
	 */
	const val KIND_REINSTALL_PENDING = "reinstall_pending"

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
	 * Reports a compile failure as the first line of the first error's message plus a count of
	 * the errors not shown - the overlay is a one-glance "your build failed and this app is
	 * stale" surface, not a build log.
	 *
	 * Deliberately position-free: jumping to an error is CoGo-side functionality, so
	 * file/line/column stay in Build Output rather than going to a runtime with no use for them.
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

	/**
	 * Tells the proxy app its pending update needs an install confirmation that can only be
	 * shown from CoGo, so the user staring at the stale app knows to switch back.
	 *
	 * Android defers the install-confirm while CoGo is backgrounded, and every other recovery
	 * signal lives in CoGo - the one app the user is not looking at. Kind-only on purpose: the
	 * copy is static and lives runtime-side with the other overlay text.
	 *
	 * @return the `statusJson` argument for `onBuildStatus`
	 */
	fun reinstallPending(): String = JsonObject().apply { addProperty("kind", KIND_REINSTALL_PENDING) }.toString()
}
