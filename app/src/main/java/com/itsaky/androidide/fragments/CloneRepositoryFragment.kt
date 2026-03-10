package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.databinding.FragmentCloneRepositoryBinding
import com.itsaky.androidide.viewmodel.CloneRepositoryViewModel
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.git.core.models.CloneRepoUiState
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.forEachViewRecursively
import kotlinx.coroutines.launch
import java.io.File

class CloneRepositoryFragment : BaseFragment() {

    private var binding: FragmentCloneRepositoryBinding? = null
    private val viewModel: CloneRepositoryViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentCloneRepositoryBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding?.apply {

            repoUrl.doAfterTextChanged {
                viewModel.onInputChanged(it.toString(), localPath.text.toString())
            }

            localPath.apply {
                doAfterTextChanged {
                    viewModel.onInputChanged(repoUrl.text.toString(), it.toString())
                }
            }

            localPathLayout.setEndIconOnClickListener {
                pickDirectory { file ->
                    val url = repoUrl.text.toString().trim()
                    var projectName = url.substringAfterLast("/", "")
                    if (projectName.endsWith(".git")) {
                        projectName = projectName.dropLast(4)
                    }
                    
                    val destFile = if (projectName.isNotBlank()) {
                        File(file, projectName)
                    } else {
                        file
                    }
                    
                    localPath.setText(destFile.absolutePath)
                }
            }

            authCheckbox.setOnCheckedChangeListener { _, isChecked ->
                authGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            cloneButton.setOnClickListener {
                val url = repoUrl.text.toString()
                val path = localPath.text.toString()
                val username = if (authCheckbox.isChecked) username.text.toString() else null
                val password = if (authCheckbox.isChecked) password.text.toString() else null

                viewModel.cloneRepository(url, path, username, password)
            }
            
            exitButton.setOnClickListener {
                mainViewModel.setScreen(MainViewModel.SCREEN_MAIN)
            }

            root.forEachViewRecursively { child ->
                if (child is EditText || child is TextInputLayout) {
                    return@forEachViewRecursively
                }
                child.setOnLongClickListener { v ->
                    TooltipManager.showIdeCategoryTooltip(
                        context = requireContext(),
                        anchorView = v,
                        tag = TooltipTag.GIT_DOWNLOAD_SCREEN
                    )
                    true
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding?.apply {
                        val isLoading = state is CloneRepoUiState.Cloning
                        repoUrl.isEnabled = !isLoading
                        localPath.isEnabled = !isLoading
                        username.isEnabled = !isLoading
                        password.isEnabled = !isLoading
                        
                        progressBar.apply {
                            visibility = if (isLoading) View.VISIBLE else View.GONE
                            if (state is CloneRepoUiState.Cloning) {
                                progress = state.clonePercentage
                            }
                        }

                        progressText.apply {
                            if (state is CloneRepoUiState.Cloning && state.cloneProgress.isNotEmpty()) {
                                visibility = View.VISIBLE
                                text = state.cloneProgress
                            } else {
                                visibility = View.GONE
                            }
                        }

                        when (state) {
                            is CloneRepoUiState.Idle -> {
                                cloneButton.isEnabled = state.isCloneButtonEnabled
                                statusText.text = ""
                            }
                            is CloneRepoUiState.Cloning -> {
                                cloneButton.isEnabled = false
                                statusText.text = getString(R.string.cloning_repo)
                            }
                            is CloneRepoUiState.Error -> {
                                cloneButton.isEnabled = true
                                val statusMessage = state.errorResId?.let { getString(it) } ?: state.errorMessage
                                statusText.text = statusMessage
                            }
                            is CloneRepoUiState.Success -> {
                                cloneButton.isEnabled = true
                                statusText.text = getString(R.string.clone_successful)
                                
                                val destDir = File(state.localPath)
                                if (destDir.exists()) {
                                    mainViewModel.setScreen(MainViewModel.SCREEN_MAIN)
                                    (requireActivity() as? MainActivity)?.openProject(destDir)

                                    // Reset state after opening project
                                    repoUrl.text?.clear()
                                    localPath.text?.clear()
                                    username.text?.clear()
                                    password.text?.clear()
                                    authCheckbox.isChecked = false
                                    viewModel.resetState()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
