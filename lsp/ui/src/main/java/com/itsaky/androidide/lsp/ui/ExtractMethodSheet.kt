package com.itsaky.androidide.lsp.ui

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
 * Hosts [ExtractMethodSheetContent], for whichever language server showed it.
 *
 * The candidates are handed in directly rather than through fragment arguments: they are a view of an
 * analysis result whose offsets refer to one snapshot of one document, which is neither `Parcelable`
 * nor meaningful to restore -- after process death the document may be entirely different. So
 * [candidates] is null on a recreated instance and the sheet dismisses itself, which is the same
 * outcome the caller's document-version guard would reach anyway.
 */
class ExtractMethodSheet : BottomSheetDialogFragment() {
	private var candidates: List<MethodCandidateView>? = null
	private var keywords: Set<String> = emptySet()
	private var nameMessages: NameMessages? = null
	private var onSelected: ((ExtractMethodSelection) -> Unit)? = null

	private val viewModel: ExtractMethodViewModel by viewModels {
		ExtractMethodViewModel.factory(
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
					ExtractMethodSheetContent(
						state = state,
						nameMessages = messages,
						onEvent = ::handleEvent,
					)
				}
			}
		}
	}

	private fun handleEvent(event: ExtractMethodUiEvent) {
		when (event) {
			ExtractMethodUiEvent.Confirmed -> {
				viewModel.selection()?.let { selection -> onSelected?.invoke(selection) }
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
		 * Shows the sheet on [activity], calling [onSelected] once if the user confirms.
		 *
		 * [keywords] is the language's reserved-word set and [nameMessages] its name-problem strings, so
		 * a Java user is never shown Kotlin's wording. Returns false when the sheet could not be shown,
		 * so the caller can report a failure rather than silently doing nothing.
		 */
		fun show(
			activity: FragmentActivity,
			candidates: List<MethodCandidateView>,
			keywords: Set<String>,
			nameMessages: NameMessages,
			onSelected: (ExtractMethodSelection) -> Unit,
		): Boolean {
			val manager = activity.supportFragmentManager
			if (manager.isStateSaved || manager.isDestroyed) return false
			ExtractMethodSheet()
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
