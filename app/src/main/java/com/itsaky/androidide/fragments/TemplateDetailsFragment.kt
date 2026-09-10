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

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.ui.TemplateScrollGateKeeper
import com.itsaky.androidide.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * A fragment which shows a wizard-like interface for creating templates.
 *
 * @author Akash Yadav
 */
class TemplateDetailsFragment :
	FragmentWithBinding<FragmentTemplateDetailsBinding>(
		R.layout.fragment_template_details,
		FragmentTemplateDetailsBinding::bind,
	) {
	private val viewModel by activityViewModel<MainViewModel>()
	private var widgetsBindJob: Job? = null

	private var scrollGateKeeper: TemplateScrollGateKeeper? = null
	private val projectCreationManager by lazy { ProjectCreationManager(requireContext()) }
	private var blinkAnimator: ObjectAnimator? = null

	/** Whether this fragment's view is started, one of the three [shouldBlinkScrollIndicator] inputs. */
	private var isViewStarted = false

	/** Keeps [isViewStarted] current and re-evaluates the blink whenever the view starts or stops. */
	private val blinkWhileOnScreen =
		object : DefaultLifecycleObserver {
			override fun onStart(owner: LifecycleOwner) {
				isViewStarted = true
				updateBlinkState()
			}

			override fun onStop(owner: LifecycleOwner) {
				isViewStarted = false
				updateBlinkState()
			}
		}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		setupRecyclerView()
		setupTooltips()
		setupObservers()
		setupClickListeners()
		viewLifecycleOwner.lifecycle.addObserver(blinkWhileOnScreen)
	}

	override fun onDestroyView() {
		/*
		 * [blinkWhileOnScreen] is what guarantees the animator is gone: performDestroyView drives the
		 * view lifecycle to DESTROYED before calling here, and that backward pass dispatches ON_STOP.
		 * This call is a no-op belt-and-braces for a teardown that skipped the observer; it still has
		 * to precede super, since FragmentWithBinding.onDestroyView nulls _binding before calling up.
		 */
		stopBlinkingIndicator()

		scrollGateKeeper?.detach()
		scrollGateKeeper = null

		super.onDestroyView()
	}

	private fun setupRecyclerView() {
		binding.widgets.layoutManager = LinearLayoutManager(requireContext())

		scrollGateKeeper =
			TemplateScrollGateKeeper(binding.widgets) {
				updateFinishEnabledState()
			}
		scrollGateKeeper?.attach()
	}

	private fun setupObservers() {
		viewModel.currentScreen.observe(viewLifecycleOwner) { updateBlinkState() }

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
		val template =
			viewModel.template.value ?: run {
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
						hasTemplateIssues = result.hasErrorsWarnings,
					)
				}
			},
			onError = { errorMsg ->
				viewModel.creatingProject.value = false
				flashError(errorMsg)
			},
		)
	}

	private fun bindWithTemplate(template: Template<*>?) {
		template ?: return

		binding.title.text = template.templateNameStr

		// Some parameters do disk work in their beforeCreateView hook (e.g. computing a
		// non-colliding default project name). Run those hooks on Dispatchers.IO before
		// attaching the adapter so that onBindViewHolder skips them (they are one-shot).
		widgetsBindJob?.cancel()
		widgetsBindJob =
			viewLifecycleOwner.lifecycleScope.launch {
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
		val canFinish = !isCreating && hasScrolledToBottom
		val stateDesc = if (canFinish) null else getString(string.msg_scroll_to_create_project)

		binding.finish.isEnabled = !isCreating && hasScrolledToBottom
		binding.scrollIndicator.isVisible = !hasScrolledToBottom
		ViewCompat.setStateDescription(binding.finish, stateDesc)

		updateBlinkState()
	}

	/**
	 * Starts or stops the blink to match [shouldBlinkScrollIndicator].
	 */
	private fun updateBlinkState() {
		val shouldBlink =
			shouldBlinkScrollIndicator(
				isViewStarted = isViewStarted,
				currentScreen = viewModel.currentScreen.value,
				isIndicatorVisible = _binding?.scrollIndicator?.isVisible == true,
			)

		if (shouldBlink) {
			startBlinkingIndicator()
		} else {
			stopBlinkingIndicator()
		}
	}

	private fun startBlinkingIndicator() {
		if (blinkAnimator != null) {
			return
		}

		blinkAnimator =
			ObjectAnimator.ofFloat(binding.scrollIndicator, View.ALPHA, 1f, 0.2f, 1f).apply {
				duration = 1200
				interpolator = LinearInterpolator()
				repeatCount = ObjectAnimator.INFINITE
				start()
			}
	}

	private fun stopBlinkingIndicator() {
		blinkAnimator?.cancel()
		blinkAnimator = null

		// cancelling mid-repeat leaves the indicator at whatever alpha it had reached
		_binding?.scrollIndicator?.alpha = 1f
	}
}

/**
 * Whether the scroll indicator's blink should be running.
 *
 * The blink repeats forever, and an endlessly repeating animator keeps the main thread's
 * Choreographer loop alive process-wide: it re-posts a vsync callback every frame whether or not
 * anything is drawn, and it does not pause when its activity merely stops. So it may run only
 * while the indicator can actually be seen, which takes all three inputs here.
 *
 * A started view lifecycle is not on its own a proxy for that. `activity_main.xml` declares every
 * one of MainActivity's screens as a sibling container in one `FrameLayout`, and switching screens
 * only flips their `View` visibility, so this fragment's view reaches `STARTED` at cold start and
 * stays there for as long as MainActivity is started -- including the whole time the user sits on
 * the project list having never opened the new-project flow. The indicator also hides itself once
 * the form has been scrolled to the bottom, which is a third way for it to be off screen while
 * this screen is the current one.
 */
internal fun shouldBlinkScrollIndicator(
	isViewStarted: Boolean,
	currentScreen: Int?,
	isIndicatorVisible: Boolean,
): Boolean = isViewStarted && currentScreen == MainViewModel.SCREEN_TEMPLATE_DETAILS && isIndicatorVisible
