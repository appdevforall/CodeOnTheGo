package com.itsaky.androidide.fragments.git

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitBottomSheetBinding

class GitBottomSheetFragment : Fragment(R.layout.fragment_git_bottom_sheet) {

    private var _binding: FragmentGitBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitBottomSheetBinding.bind(view)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
