package com.itsaky.androidide.fragments.git

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.PopupGitBranchesBinding
import com.itsaky.androidide.fragments.git.adapter.GitBranchAdapter
import com.itsaky.androidide.fragments.git.adapter.GitBranchListItem
import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel.BranchesUiState

/**
 * Popup window that displays the list of local and remote branches with search,
 * branch creation, checkout, and merge capabilities.
 *
 * @param context The host context.
 * @param onBranchSelected Callback invoked when a branch row is tapped to switch/checkout.
 * @param onNewBranchRequested Callback invoked when the "New branch" action is tapped.
 * @param onMergeBranch Optional callback invoked when a branch's merge button is tapped.
 */
class GitBranchPopupWindow(
	private val context: Context,
	private val onBranchSelected: (GitBranch) -> Unit,
	private val onNewBranchRequested: () -> Unit,
	private val onMergeBranch: ((GitBranch) -> Unit)? = null,
) {
	private val binding: PopupGitBranchesBinding =
		PopupGitBranchesBinding.inflate(
			LayoutInflater.from(context),
		)

	private val popupWindow: PopupWindow =
		PopupWindow(
			binding.root,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			true,
		).apply {
			setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
			elevation = 16f
		}

	private val adapter: GitBranchAdapter =
		GitBranchAdapter(
			onBranchSelected = { branch ->
				popupWindow.dismiss()
				onBranchSelected(branch)
			},
			onMergeClicked = { branch ->
				popupWindow.dismiss()
				onMergeBranch?.invoke(branch)
			},
		)

	private var allBranches: List<GitBranch> = emptyList()

	init {
		binding.rvBranches.layoutManager = LinearLayoutManager(context)
		binding.rvBranches.adapter = adapter

		binding.btnNewBranch.setOnClickListener {
			popupWindow.dismiss()
			onNewBranchRequested()
		}

		binding.etSearchBranches.doAfterTextChanged { text ->
			filterBranches(text?.toString())
		}
	}

	/**
	 * Updates the branch list and loading indicator state in the popup.
	 *
	 * @param state The current [BranchesUiState] to render.
	 */
	fun setBranchesState(state: BranchesUiState) {
		when (state) {
			is BranchesUiState.Loading -> {
				binding.branchesProgress.visibility = View.VISIBLE
			}

			is BranchesUiState.Success -> {
				binding.branchesProgress.visibility = View.GONE
				allBranches = state.branches
				filterBranches(binding.etSearchBranches.text?.toString())
			}

			is BranchesUiState.None, is BranchesUiState.Error -> {
				binding.branchesProgress.visibility = View.GONE
				allBranches = emptyList()
				filterBranches(binding.etSearchBranches.text?.toString())
			}
		}
	}

	private fun filterBranches(query: String?) {
		val filtered =
			if (query.isNullOrBlank()) {
				allBranches
			} else {
				allBranches.filter { branch ->
					val displayName = getDisplayName(branch)
					branch.name.contains(query, ignoreCase = true) || displayName.contains(query, ignoreCase = true)
				}
			}

		val localBranches = filtered.filter { !it.isRemote }
		val remoteBranches = filtered.filter { it.isRemote }

		val items = mutableListOf<GitBranchListItem>()

		if (localBranches.isNotEmpty()) {
			items.add(GitBranchListItem.Header(context.getString(R.string.git_local_branches)))
			localBranches.forEach { branch ->
				items.add(GitBranchListItem.BranchItem(branch, getDisplayName(branch)))
			}
		}

		if (remoteBranches.isNotEmpty()) {
			items.add(GitBranchListItem.Header(context.getString(R.string.git_remote_branches)))
			remoteBranches.forEach { branch ->
				items.add(GitBranchListItem.BranchItem(branch, getDisplayName(branch)))
			}
		}

		adapter.submitList(items)
	}

	private fun getDisplayName(branch: GitBranch): String = branch.name

	/**
	 * Displays the popup dropdown positioned relative to [anchor], dynamically sized to span
	 * the parent bottom sheet width with symmetric 16dp margins.
	 *
	 * @param anchor The view below which the popup dropdown should appear.
	 */
	fun show(anchor: View) {
		binding.etSearchBranches.text?.clear()
		filterBranches(null)

		val parentView = (anchor.parent as? View) ?: anchor.rootView
		val marginPx = (16 * context.resources.displayMetrics.density).toInt()
		val targetWidth = parentView.width - (marginPx * 2)

		val xOff =
			if (targetWidth > 0) {
				popupWindow.width = targetWidth
				val anchorLocation = IntArray(2)
				val parentLocation = IntArray(2)
				anchor.getLocationInWindow(anchorLocation)
				parentView.getLocationInWindow(parentLocation)
				val anchorLeftInParent = anchorLocation[0] - parentLocation[0]
				marginPx - anchorLeftInParent
			} else {
				0
			}

		popupWindow.showAsDropDown(anchor, xOff, 8)
	}
}
