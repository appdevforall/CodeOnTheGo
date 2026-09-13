package com.itsaky.androidide.fragments.git

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.graphics.drawable.toDrawable
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
			setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
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
				binding.tvEmptyBranches.visibility = View.GONE
				binding.rvBranches.visibility = View.VISIBLE
			}

			is BranchesUiState.Success -> {
				binding.branchesProgress.visibility = View.GONE
				allBranches = state.branches
				filterBranches(binding.etSearchBranches.text?.toString())
			}

			is BranchesUiState.None -> {
				binding.branchesProgress.visibility = View.GONE
				binding.tvEmptyBranches.visibility = View.GONE
				binding.rvBranches.visibility = View.VISIBLE
				allBranches = emptyList()
				adapter.submitList(emptyList())
			}

			is BranchesUiState.Error -> {
				binding.branchesProgress.visibility = View.GONE
				allBranches = emptyList()
				binding.tvEmptyBranches.text = context.getString(R.string.git_branches_load_failed)
				binding.tvEmptyBranches.visibility = View.VISIBLE
				binding.rvBranches.visibility = View.GONE
				adapter.submitList(emptyList())
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

		if (items.isEmpty()) {
			binding.tvEmptyBranches.text = context.getString(R.string.git_no_branches_found)
			binding.tvEmptyBranches.visibility = View.VISIBLE
			binding.rvBranches.visibility = View.GONE
		} else {
			binding.tvEmptyBranches.visibility = View.GONE
			binding.rvBranches.visibility = View.VISIBLE
		}

		adapter.submitList(items)
	}

	private fun getDisplayName(branch: GitBranch): String = branch.name

	/**
	 * Displays the popup window centered horizontally on the screen below [anchor].
	 *
	 * Clears any existing search input, resets the filtered branch list,
	 * dynamically sizes the popup, and positions the dropdown centered on screen.
	 *
	 * @param anchor The view below which the popup dropdown should be displayed.
	 */
	fun show(anchor: View) {
		binding.etSearchBranches.text?.clear()
		filterBranches(null)
		val displayWidth = context.resources.displayMetrics.widthPixels
		val density = context.resources.displayMetrics.density
		val maxWidth = (360 * density).toInt()
		val margin = (32 * density).toInt()
		val targetWidth = maxWidth.coerceAtMost(displayWidth - margin)
		popupWindow.width = targetWidth

		val location = IntArray(2)
		anchor.getLocationOnScreen(location)
		val anchorX = location[0]
		val desiredX = (displayWidth - targetWidth) / 2
		val xOffset = desiredX - anchorX

		popupWindow.showAsDropDown(anchor, xOffset, (8 * density).toInt())
	}

	/**
	 * Dismisses the popup window if it is currently showing.
	 */
	fun dismiss() {
		if (popupWindow.isShowing) {
			popupWindow.dismiss()
		}
	}
}
