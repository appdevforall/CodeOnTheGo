package com.itsaky.androidide.lsp.kotlin.refactor.ui

import android.content.Context
import android.content.ContextWrapper
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
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionPlan

/**
 * Hosts [ExtractVariableSheetContent].
 *
 * The plan is handed in directly rather than through fragment arguments: it carries the file's text and
 * offset spans, which is neither `Parcelable` nor meaningful to restore -- after process death the
 * document may be entirely different. So [plan] is null on a recreated instance and the sheet dismisses
 * itself, which is the same outcome the action's document-version guard would reach anyway.
 */
class ExtractVariableSheet : BottomSheetDialogFragment() {
	private var plan: ExtractionPlan? = null
	private var onChoice: ((ExtractionChoice) -> Unit)? = null

	private val viewModel: ExtractVariableViewModel by viewModels {
		ExtractVariableViewModel.factory(requireNotNull(plan) { "sheet shown without a plan" })
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
			// The sheet's window is torn down with the fragment's view, so dispose with it.
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				// IdeTheme, not a bare MaterialTheme: the latter falls back to Material's purple
				// baseline and ignores the user's theme, so the sheet would not match the editor.
				IdeTheme {
					val state by viewModel.uiState.collectAsStateWithLifecycle()
					ExtractVariableSheetContent(
						state = state,
						onEvent = ::handleEvent,
					)
				}
			}
		}
	}

	private fun handleEvent(event: ExtractVariableUiEvent) {
		when (event) {
			ExtractVariableUiEvent.Confirmed -> {
				viewModel.choice()?.let { choice -> onChoice?.invoke(choice) }
				dismiss()
			}

			ExtractVariableUiEvent.Dismissed -> {
				dismiss()
			}

			else -> {
				viewModel.onEvent(event)
			}
		}
	}

	companion object {
		private const val TAG = "extract_variable_sheet"

		/**
		 * Shows the sheet on [activity], calling [onChoice] once if the user confirms.
		 *
		 * Returns false when the sheet could not be shown, so the caller can report a failure rather
		 * than silently doing nothing.
		 */
		fun show(
			activity: FragmentActivity,
			plan: ExtractionPlan,
			onChoice: (ExtractionChoice) -> Unit,
		): Boolean {
			val manager = activity.supportFragmentManager
			if (manager.isStateSaved || manager.isDestroyed) return false
			ExtractVariableSheet()
				.apply {
					this.plan = plan
					this.onChoice = onChoice
				}.show(manager, TAG)
			return true
		}
	}
}

/**
 * Finds the [FragmentActivity] hosting this context by unwrapping the [ContextWrapper] chain.
 *
 * A view inflated into an activity reports that activity as its context, but a theme overlay wraps it,
 * so a direct cast is not reliable. `ActionData` carries only the editor's `Context`, and adding a
 * `FragmentActivity` key would only move the same unwrapping one module upstream, into `editor`.
 */
fun Context.findFragmentActivity(): FragmentActivity? {
	var context: Context? = this
	while (context != null) {
		if (context is FragmentActivity) return context
		context = (context as? ContextWrapper)?.baseContext
	}
	return null
}
