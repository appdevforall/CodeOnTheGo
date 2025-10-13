package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitCommitDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GitCommitDetailFragment : Fragment(R.layout.fragment_git_commit_detail) {

    private var _binding: FragmentGitCommitDetailBinding? = null
    private val binding get() = _binding!!
    private val args: GitCommitDetailFragmentArgs by navArgs()
    private var originalRemoteName: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitCommitDetailBinding.bind(view)

        originalRemoteName = args.commitHash

        if (originalRemoteName != null) {
            loadRemoteDetails(originalRemoteName!!)
        } else {
//            binding.btnDeleteRemote.isVisible = false
        }

//        binding.btnSaveRemote.setOnClickListener { saveRemote() }
//        binding.btnDeleteRemote.setOnClickListener { /* Add confirmation dialog and delete logic */ }
    }

    private fun loadRemoteDetails(remoteName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // ... (Fetch remote details and populate UI)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}