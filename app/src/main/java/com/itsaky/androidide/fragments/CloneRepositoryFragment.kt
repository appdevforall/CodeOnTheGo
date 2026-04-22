package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.databinding.FragmentCloneRepositoryBinding
import com.itsaky.androidide.viewmodel.CloneRepositoryViewModel
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.git.core.models.CloneRepoUiState
import com.itsaky.androidide.R
import com.itsaky.androidide.dnd.handleGitUrlDrop
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.utils.forEachViewRecursively
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class CloneRepositoryFragment : BaseFragment() {

    private var binding: FragmentCloneRepositoryBinding? = null
    private val viewModel: CloneRepositoryViewModel by viewModel()
    private val mainViewModel: MainViewModel by activityViewModel()
    private var lastStatusResId: Int? = null

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
        observePendingCloneUrl()
        observeViewModel()
        handleGitUrlDrop { url ->
            binding?.repoUrl?.setText(url)
            viewModel.onInputChanged(url, binding?.localPath?.text?.toString().orEmpty())
        }
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
                cloneRepo()
            }
            
            cancelButton.setOnClickListener {
                viewModel.cancelClone()
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

    private fun FragmentCloneRepositoryBinding.cloneRepo() {
        val url = repoUrl.text.toString()
        val path = localPath.text.toString()
        val mUsername = if (authCheckbox.isChecked) username.text.toString() else null
        val mPassword = if (authCheckbox.isChecked) password.text.toString() else null

        viewModel.cloneRepository(url, path, mUsername, mPassword)
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

                        cancelButton.visibility =
                            if (state is CloneRepoUiState.Cloning && state.isCancellable) View.VISIBLE else View.GONE

                        when (state) {
                            is CloneRepoUiState.Idle -> {
                                cloneButton.apply {
                                    isEnabled = state.isCloneButtonEnabled
                                    refreshStatus(isForRetry = false)
                                }
                                lastStatusResId = null
                            }

                            is CloneRepoUiState.Cloning -> {
                                cloneButton.apply {
                                    isEnabled = false
                                    refreshStatus(isForRetry = false)
                                }
                                
                                if (state.statusTextResId != lastStatusResId) {
                                    lastStatusResId = state.statusTextResId
                                    val message = state.statusTextResId?.let { getString(it) }
                                        ?: getString(R.string.cloning_repo)
                                    flashInfo(message)
                                }
                            }

                            is CloneRepoUiState.Error -> {
                                cloneButton.apply {
                                    isEnabled = true
                                    refreshStatus(isForRetry = state.canRetry)
                                }
                                val statusMessage =
                                    state.errorResId?.let { getString(it) } ?: state.errorMessage
                                flashError(statusMessage)
                            }

                            is CloneRepoUiState.Success -> {
                                cloneButton.isEnabled = true
                                flashInfo(getString(R.string.clone_successful))
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

    private fun observePendingCloneUrl() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.cloneRepositoryEvent.collect { url ->
                    val trimmedUrl = url.trim()
                    if (trimmedUrl.isNotBlank()) {
                        binding?.repoUrl?.setText(trimmedUrl)
                        viewModel.onInputChanged(trimmedUrl, binding?.localPath?.text?.toString().orEmpty())
                    }
                }
            }
        }
    }

    private fun MaterialButton.refreshStatus(isForRetry: Boolean) {
        setIconResource(if (isForRetry) R.drawable.ic_refresh else 0)

        text = context.getString(
            if (isForRetry) R.string.retry else R.string.clone_project
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
