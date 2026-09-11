package com.itsaky.androidide.lsp.kotlin.refactor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.itsaky.androidide.common.compose.IdeTheme
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan

/**
 * Hosts [ExtractMethodSheetContent].
 *
 * The plan is handed in directly rather than through fragment arguments: it carries the file's text
 * and offset spans, which is neither `Parcelable` nor meaningful to restore -- after process death
 * the document may be entirely different. So [plan] is null on a recreated instance and the sheet
 * dismisses itself, the same outcome the action's document-version guard would reach anyway.
 */
class ExtractMethodSheet : BottomSheetDialogFragment() {
	private var plan: ExtractMethodPlan? = null
	private var onChoice: ((ExtractMethodChoice) -> Unit)? = null

	private val viewModel: ExtractMethodViewModel by viewModels {
		ExtractMethodViewModel.factory(requireNotNull(plan) { "sheet shown without a plan" })
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View? {
		if (plan == null) {
			dismissAllowingStateLoss()
			return null
		}

		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				IdeTheme {
					val state by viewModel.uiState.collectAsStateWithLifecycle()
					ExtractMethodSheetContent(
						state = state,
						onEvent = ::handleEvent,
					)
				}
			}
		}
	}

	private fun handleEvent(event: ExtractMethodUiEvent) {
		when (event) {
			ExtractMethodUiEvent.Confirmed -> {
				viewModel.choice()?.let { choice -> onChoice?.invoke(choice) }
				dismiss()
			}

			ExtractMethodUiEvent.Dismissed -> {
				dismiss()
			}

			else -> {
				viewModel.onEvent(event)
			}
		}
	}

	companion object {
		private const val TAG = "extract_method_sheet"

		/**
		 * Shows the sheet on [activity], calling [onChoice] once if the user confirms. Returns false
		 * when it could not be shown, so the caller can report a failure rather than doing nothing.
		 */
		fun show(
			activity: FragmentActivity,
			plan: ExtractMethodPlan,
			onChoice: (ExtractMethodChoice) -> Unit,
		): Boolean {
			val manager = activity.supportFragmentManager
			if (manager.isStateSaved || manager.isDestroyed) return false
			ExtractMethodSheet()
				.apply {
					this.plan = plan
					this.onChoice = onChoice
				}.show(manager, TAG)
			return true
		}
	}
}
