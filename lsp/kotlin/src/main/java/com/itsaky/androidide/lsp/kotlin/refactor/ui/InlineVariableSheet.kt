package com.itsaky.androidide.lsp.kotlin.refactor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.itsaky.androidide.common.compose.IdeTheme
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineMode
import com.itsaky.androidide.lsp.kotlin.utils.refactor.InlineVariablePlan

/**
 * Hosts [InlineVariableSheetContent].
 *
 * The plan is handed in directly rather than through fragment arguments: it carries the file's text
 * and offset spans, which is neither `Parcelable` nor meaningful to restore -- after process death the
 * document may be entirely different. So [plan] is null on a recreated instance and the sheet
 * dismisses itself, the same outcome the action's document-version guard would reach anyway.
 */
class InlineVariableSheet : BottomSheetDialogFragment() {
	private var plan: InlineVariablePlan? = null
	private var onMode: ((InlineMode) -> Unit)? = null

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View? {
		val plan = plan ?: run {
			dismissAllowingStateLoss()
			return null
		}

		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				IdeTheme {
					InlineVariableSheetContent(
						plan = plan,
						onEvent = ::handleEvent,
					)
				}
			}
		}
	}

	private fun handleEvent(event: InlineVariableUiEvent) {
		when (event) {
			is InlineVariableUiEvent.ModeChosen -> {
				onMode?.invoke(event.mode)
				dismiss()
			}

			InlineVariableUiEvent.Dismissed -> {
				dismiss()
			}
		}
	}

	companion object {
		private const val TAG = "inline_variable_sheet"

		/**
		 * Shows the sheet on [activity], calling [onMode] once if the user picks a mode. Returns false
		 * when it could not be shown, so the caller can report a failure rather than doing nothing.
		 */
		fun show(
			activity: FragmentActivity,
			plan: InlineVariablePlan,
			onMode: (InlineMode) -> Unit,
		): Boolean {
			val manager = activity.supportFragmentManager
			if (manager.isStateSaved || manager.isDestroyed) return false
			InlineVariableSheet()
				.apply {
					this.plan = plan
					this.onMode = onMode
				}.show(manager, TAG)
			return true
		}
	}
}
