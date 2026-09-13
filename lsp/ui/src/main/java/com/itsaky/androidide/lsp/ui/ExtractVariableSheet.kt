package com.itsaky.androidide.lsp.ui

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

/**
 * Hosts [ExtractVariableSheetContent], for whichever language server showed it.
 *
 * The candidates are handed in directly rather than through fragment arguments: they are a view of an
 * analysis result whose offsets refer to one snapshot of one document, which is neither `Parcelable`
 * nor meaningful to restore -- after process death the document may be entirely different. So
 * [candidates] is null on a recreated instance and the sheet dismisses itself, which is the same
 * outcome the caller's document-version guard would reach anyway.
 */
class ExtractVariableSheet : BottomSheetDialogFragment() {
	private var candidates: List<CandidateView>? = null
	private var keywords: Set<String> = emptySet()
	private var nameMessages: NameMessages? = null
	private var onSelected: ((ExtractVariableSelection) -> Unit)? = null

	private val viewModel: ExtractVariableViewModel by viewModels {
		ExtractVariableViewModel.factory(
			requireNotNull(candidates) { "sheet shown without candidates" },
			keywords,
		)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View? {
		val messages = nameMessages
		if (candidates == null || messages == null) {
			dismissAllowingStateLoss()
			return null
		}

		return ComposeView(requireContext()).apply {
			// The sheet's window is torn down with the fragment's view, so dispose with it.
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				IdeTheme {
					val state by viewModel.uiState.collectAsStateWithLifecycle()
					ExtractVariableSheetContent(
						state = state,
						nameMessages = messages,
						onEvent = ::handleEvent,
					)
				}
			}
		}
	}

	private fun handleEvent(event: ExtractVariableUiEvent) {
		when (event) {
			ExtractVariableUiEvent.Confirmed -> {
				viewModel.selection()?.let { selection -> onSelected?.invoke(selection) }
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
		 * Shows the sheet on [activity], calling [onSelected] once if the user confirms.
		 *
		 * [keywords] is the language's reserved-word set and [nameMessages] its name-problem strings, so
		 * a Java user is never shown Kotlin's wording. Returns false when the sheet could not be shown,
		 * so the caller can report a failure rather than silently doing nothing.
		 */
		fun show(
			activity: FragmentActivity,
			candidates: List<CandidateView>,
			keywords: Set<String>,
			nameMessages: NameMessages,
			onSelected: (ExtractVariableSelection) -> Unit,
		): Boolean {
			val manager = activity.supportFragmentManager
			if (manager.isStateSaved || manager.isDestroyed) return false
			ExtractVariableSheet()
				.apply {
					this.candidates = candidates
					this.keywords = keywords
					this.nameMessages = nameMessages
					this.onSelected = onSelected
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
