package com.itsaky.androidide.quickbuild

import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.service.SameAppIdModeController.EntryRequest

/**
 * Pure decision logic for the same-app-id clobber-warning dialog (ADFA-4128 Path B,
 * `quick-build/docs/same-app-id-design.md` section 3): which consequence lines the
 * dialog shows, in contract order, and how they render into one message. JVM-tested;
 * the rendering (MaterialAlertDialog) lives in ProjectHandlerActivity.
 */
object SameAppIdEntryUx {
	/** The clobber-warning dialog's content, resolved to string-resource ids. */
	data class WarningModel(
		val realApplicationId: String,
		@StringRes val titleRes: Int,
		/** Contract section 3's consequence list, in order. Each may take the appId as arg 1. */
		val consequenceResIds: List<Int>,
		@StringRes val confirmRes: Int,
	)

	/**
	 * Maps the controller's [EntryRequest.ShowWarning] to dialog content. The
	 * "existing app replaced" line is dropped when nothing is installed under the real
	 * id (contract section 2, fresh-install case); the other three consequences always
	 * apply.
	 */
	fun warningModel(request: EntryRequest.ShowWarning): WarningModel =
		WarningModel(
			realApplicationId = request.realApplicationId,
			titleRes = R.string.quick_build_same_app_id_warning_title,
			consequenceResIds =
				buildList {
					if (request.existingInstallReplaced) {
						add(R.string.quick_build_same_app_id_consequence_replaced)
					}
					add(R.string.quick_build_same_app_id_consequence_os_routing)
					add(R.string.quick_build_same_app_id_consequence_shared_data)
					add(R.string.quick_build_same_app_id_consequence_reinstall)
				},
			confirmRes = R.string.quick_build_same_app_id_confirm,
		)

	/**
	 * Renders the consequence list as one bulleted dialog message. [resolve] is
	 * `getString(resId, appId)` in production; injected so the join logic is JVM-testable.
	 */
	fun formatMessage(
		model: WarningModel,
		resolve: (Int, String) -> String,
	): String =
		model.consequenceResIds.joinToString(separator = "\n\n") { resId ->
			"- " + resolve(resId, model.realApplicationId)
		}
}
