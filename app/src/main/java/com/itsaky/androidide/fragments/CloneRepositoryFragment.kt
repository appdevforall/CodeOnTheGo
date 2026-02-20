package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.databinding.FragmentCloneRepositoryBinding
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.viewmodel.CloneRepositoryViewModel
import kotlinx.coroutines.launch

class CloneRepositoryFragment : BaseFragment() {

    private var binding: FragmentCloneRepositoryBinding? = null
    private val viewModel: CloneRepositoryViewModel by viewModels()

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
                    localPath.setText(file.absolutePath)
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
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding?.apply {
                        repoUrl.isEnabled = !state.isLoading
                        localPath.isEnabled = !state.isLoading
                        
                        val statusMessage = state.statusResId?.let { getString(it) } ?: state.statusMessage
                        statusText.text = statusMessage

                        authCheckbox.isChecked = state.isAuthRequired
                        cloneButton.isEnabled = state.isCloneButtonEnabled
                        progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                        username.isEnabled = !state.isLoading
                        password.isEnabled = !state.isLoading

                        if (state.isSuccess == true) {
                            // TODO: Open project or navigate to saved projects
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
