package com.itsaky.androidide.fragments.git

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.databinding.PopupGitBranchesBinding
import com.itsaky.androidide.fragments.git.adapter.GitBranchAdapter
import com.itsaky.androidide.git.core.models.GitBranch
import androidx.core.graphics.drawable.toDrawable

class GitBranchPopupWindow(
    private val context: Context,
    private val onBranchSelected: (GitBranch) -> Unit,
    private val onNewBranchRequested: () -> Unit
) {

    private val binding: PopupGitBranchesBinding = PopupGitBranchesBinding.inflate(
        LayoutInflater.from(context)
    )

    private val popupWindow: PopupWindow = PopupWindow(
        binding.root,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        elevation = 16f
    }

    private val adapter: GitBranchAdapter = GitBranchAdapter { branch ->
        popupWindow.dismiss()
        onBranchSelected(branch)
    }

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
        val filtered = if (query.isNullOrBlank()) {
            allBranches
        } else {
            allBranches.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
    }

    fun show(anchor: View) {
        binding.etSearchBranches.text?.clear()
        adapter.submitList(allBranches)
        popupWindow.showAsDropDown(anchor, 0, 8)
    }
}
