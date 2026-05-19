/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionManager
import com.itsaky.androidide.R
import com.itsaky.androidide.R.string
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.adapters.TemplateWidgetsListAdapter
import com.itsaky.androidide.databinding.FragmentTemplateDetailsBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.SETUP_CREATE_PROJECT
import com.itsaky.androidide.idetooltips.TooltipTag.SETUP_OVERVIEW
import com.itsaky.androidide.idetooltips.TooltipTag.SETUP_PREVIOUS
import com.itsaky.androidide.templates.ParameterWidget
import com.itsaky.androidide.templates.Template
import com.itsaky.androidide.utils.ProjectCreationManager
import com.itsaky.androidide.utils.ui.TemplateScrollGateKeeper
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import androidx.core.view.isVisible

/**
 * A fragment which shows a wizard-like interface for creating templates.
 *
 * @author Akash Yadav
 */
class TemplateDetailsFragment :
    FragmentWithBinding<FragmentTemplateDetailsBinding>(
        R.layout.fragment_template_details, FragmentTemplateDetailsBinding::bind
    ) {

    private val viewModel by activityViewModel<MainViewModel>()
    private var widgetsBindJob: Job? = null

    private var scrollGateKeeper: TemplateScrollGateKeeper? = null
    private val projectCreationManager by lazy { ProjectCreationManager(requireContext()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupTooltips()
        setupObservers()
        setupClickListeners()
        startBlinkingIndicator()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scrollGateKeeper?.detach()
        scrollGateKeeper = null
    }

    private fun setupRecyclerView() {
        binding.widgets.layoutManager = LinearLayoutManager(requireContext())

        scrollGateKeeper = TemplateScrollGateKeeper(binding.widgets) {
            updateFinishEnabledState()
        }
        scrollGateKeeper?.attach()
    }

    private fun setupObservers() {
        viewModel.template.observe(viewLifecycleOwner) {
            binding.widgets.adapter = null
            scrollGateKeeper?.reset()
            updateFinishEnabledState()
            viewModel.postTransition(viewLifecycleOwner) { bindWithTemplate(it) }
        }

        viewModel.creatingProject.observe(viewLifecycleOwner) { isCreating ->
            TransitionManager.beginDelayedTransition(binding.root)
            updateFinishEnabledState()
            binding.previous.isEnabled = !isCreating
        }
    }

    private fun setupClickListeners() {
        binding.previous.setOnClickListener {
            viewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
        }

        binding.finish.setOnClickListener {
            handleProjectCreation()
        }
    }

    private fun setupTooltips() {
        binding.previous.setOnLongClickListener {
            TooltipManager.showIdeCategoryTooltip(requireContext(), it, SETUP_PREVIOUS)
            true
        }

        binding.finish.setOnLongClickListener {
            TooltipManager.showIdeCategoryTooltip(requireContext(), it, SETUP_CREATE_PROJECT)
            true
        }

        binding.title.setOnLongClickListener {
            TooltipManager.showIdeCategoryTooltip(requireContext(), binding.root, SETUP_OVERVIEW)
            true
        }
    }

    private fun handleProjectCreation() {
        val template = viewModel.template.value ?: run {
            viewModel.setScreen(MainViewModel.SCREEN_MAIN)
            return
        }

        projectCreationManager.execute(
            template = template,
            onStart = { viewModel.creatingProject.value = true },
            onSuccess = { result, project ->
                viewModel.creatingProject.value = false
                viewModel.setScreen(MainViewModel.SCREEN_MAIN)
                flashSuccess(string.project_created_successfully)

                viewModel.postTransition(viewLifecycleOwner) {
                    // open the project
                    (requireActivity() as MainActivity).openProject(
                        result.data.projectDir,
                        project = project,
                        hasTemplateIssues = result.hasErrorsWarnings
                    )
                }
            },
            onError = { errorMsg ->
                viewModel.creatingProject.value = false
                flashError(errorMsg)
            }
        )
    }

    private fun bindWithTemplate(template: Template<*>?) {
        template ?: return

        binding.title.text = template.templateNameStr

        // Some parameters do disk work in their beforeCreateView hook (e.g. computing a
        // non-colliding default project name). Run those hooks on Dispatchers.IO before
        // attaching the adapter so that onBindViewHolder skips them (they are one-shot).
        widgetsBindJob?.cancel()
        widgetsBindJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                template.widgets.forEach { widget ->
                    if (widget is ParameterWidget<*>) {
                        widget.parameter.beforeCreateView()
                    }
                }
            }
            _binding ?: return@launch
            binding.widgets.adapter = TemplateWidgetsListAdapter(template.widgets)
            binding.widgets.post {
                scrollGateKeeper?.checkIfReachedEnd()
            }
        }
    }

    private fun updateFinishEnabledState() {
        val isCreating = viewModel.creatingProject.value ?: false
        val hasScrolledToBottom = scrollGateKeeper?.hasReachedEnd ?: false

        binding.finish.isEnabled = !isCreating && hasScrolledToBottom
        binding.scrollIndicator.isVisible = !hasScrolledToBottom
    }

    private fun startBlinkingIndicator() {
        val animator = ObjectAnimator.ofFloat(binding.scrollIndicator, View.ALPHA, 1f, 0.2f, 1f)
        animator.duration = 1200
        animator.interpolator = LinearInterpolator()
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.start()
    }
}
