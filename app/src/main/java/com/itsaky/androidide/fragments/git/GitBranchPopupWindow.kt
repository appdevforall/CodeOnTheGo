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

	fun setBranches(branches: List<GitBranch>) {
		allBranches = branches
		filterBranches(binding.etSearchBranches.text?.toString())
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

	private fun getDisplayName(branch: GitBranch): String {
		if (!branch.isRemote) return branch.name
		val remoteName = branch.remoteName
		return when {
			!remoteName.isNullOrEmpty() && branch.name.startsWith("$remoteName/") -> {
				branch.name.removePrefix("$remoteName/")
			}

			branch.name.startsWith("origin/") -> {
				branch.name.removePrefix("origin/")
			}

			branch.name.startsWith("refs/remotes/") -> {
				branch.name.substringAfter("refs/remotes/").substringAfter('/')
			}

			else -> {
				branch.name
			}
		}
	}

	fun show(anchor: View) {
		binding.etSearchBranches.text?.clear()
		filterBranches(null)
		popupWindow.showAsDropDown(anchor, 0, 8)
	}
}
