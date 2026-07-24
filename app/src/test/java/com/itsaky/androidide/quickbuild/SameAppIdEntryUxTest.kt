package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.service.SameAppIdModeController.EntryRequest
import org.junit.Test

class SameAppIdEntryUxTest {
	private fun showWarning(existingInstallReplaced: Boolean) =
		EntryRequest.ShowWarning(
			realApplicationId = "com.example.app",
			existingInstallReplaced = existingInstallReplaced,
			updateInstall = existingInstallReplaced,
			pinnedVersionCode = 42,
		)

	@Test
	fun `update entry shows all four consequences in contract order`() {
		val model = SameAppIdEntryUx.warningModel(showWarning(existingInstallReplaced = true))

		assertThat(model.consequenceResIds)
			.containsExactly(
				R.string.quick_build_same_app_id_consequence_replaced,
				R.string.quick_build_same_app_id_consequence_os_routing,
				R.string.quick_build_same_app_id_consequence_shared_data,
				R.string.quick_build_same_app_id_consequence_reinstall,
			).inOrder()
	}

	@Test
	fun `fresh install drops only the existing-app-replaced line`() {
		val model = SameAppIdEntryUx.warningModel(showWarning(existingInstallReplaced = false))

		assertThat(model.consequenceResIds)
			.containsExactly(
				R.string.quick_build_same_app_id_consequence_os_routing,
				R.string.quick_build_same_app_id_consequence_shared_data,
				R.string.quick_build_same_app_id_consequence_reinstall,
			).inOrder()
	}

	@Test
	fun `model carries the appId and the dialog's title and confirm strings`() {
		val model = SameAppIdEntryUx.warningModel(showWarning(existingInstallReplaced = true))

		assertThat(model.realApplicationId).isEqualTo("com.example.app")
		assertThat(model.titleRes).isEqualTo(R.string.quick_build_same_app_id_warning_title)
		assertThat(model.confirmRes).isEqualTo(R.string.quick_build_same_app_id_confirm)
	}

	@Test
	fun `formatMessage resolves every consequence with the appId and joins as a bulleted list`() {
		val model = SameAppIdEntryUx.warningModel(showWarning(existingInstallReplaced = false))

		val resolvedWith = mutableListOf<Pair<Int, String>>()
		val message =
			SameAppIdEntryUx.formatMessage(model) { resId, appId ->
				resolvedWith += resId to appId
				"line-$resId"
			}

		assertThat(resolvedWith.map { it.first }).isEqualTo(model.consequenceResIds)
		assertThat(resolvedWith.map { it.second }).containsExactly(
			"com.example.app",
			"com.example.app",
			"com.example.app",
		)
		assertThat(message).isEqualTo(
			model.consequenceResIds.joinToString("\n\n") { "- line-$it" },
		)
	}
}
