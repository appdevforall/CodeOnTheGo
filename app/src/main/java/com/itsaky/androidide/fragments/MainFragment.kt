package com.itsaky.androidide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.adapters.MainActionsListAdapter
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.databinding.FragmentMainBinding
import com.itsaky.androidide.dnd.handleGitUrlDrop
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.MAIN_GET_STARTED
import com.itsaky.androidide.viewmodel.MainViewModel
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class MainFragment : BaseFragment() {
	private val viewModel by activityViewModel<MainViewModel>()
	private var binding: FragmentMainBinding? = null

	companion object {
		const val KEY_TOOLTIP_URL = "tooltip_url"
	}

	private val registry by lazy { ActionsRegistry.getInstance() }

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		binding = FragmentMainBinding.inflate(inflater, container, false)
		return binding!!.root
	}

	private val onClick: (ActionItem, View) -> Unit = { action, _ ->
		ifAttached {
			val actionData = createActionData()
			if (action.enabled && registry is DefaultActionsRegistry) {
				(registry as DefaultActionsRegistry).executeAction(action, actionData)
			}
		}
	}

	private val onLongClick: (ActionItem, View) -> Boolean = { action, view ->
		ifAttached {
			val tag = action.retrieveTooltipTag(false)
			if (tag.isNotEmpty()) {
				TooltipManager.showIdeCategoryTooltip(requireContext(), view, tag)
			}
		}
		true
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		binding!!.headerContainer?.setOnClickListener { ifAttached { openQuickstartPageAction() } }
		binding!!.headerContainer?.setOnLongClickListener {
			ifAttached { TooltipManager.showIdeCategoryTooltip(requireContext(), it, MAIN_GET_STARTED) }
			true
		}
		// Landscape layout uses "greeting" instead of "headerContainer"
		binding!!.greeting?.setOnClickListener { ifAttached { openQuickstartPageAction() } }
		binding!!.greeting?.setOnLongClickListener {
			ifAttached { TooltipManager.showIdeCategoryTooltip(requireContext(), it, MAIN_GET_STARTED) }
			true
		}

		binding!!.greetingText.setOnLongClickListener {
			ifAttached { TooltipManager.showIdeCategoryTooltip(requireContext(), it, MAIN_GET_STARTED) }
			true
		}
		binding!!.greetingText.setOnClickListener { ifAttached { openQuickstartPageAction() } }

		handleGitUrlDrop(
			shouldAcceptDrop = {
				isVisible &&
				viewModel.currentScreen.value == MainViewModel.SCREEN_MAIN &&
				ProjectManager.instance.openedProject == null
			},
			onDropped = viewModel::requestCloneRepository
		)
	}

	private fun openQuickstartPageAction() {
		val intent =
			Intent(requireContext(), HelpActivity::class.java).apply {
				putExtra(CONTENT_KEY, getString(R.string.quickstart_url))
				putExtra(CONTENT_TITLE_KEY, R.string.back_to_cogo)
			}
		startActivity(intent)
	}

	override fun onResume() {
		super.onResume()
		reloadActions()
	}

	private fun reloadActions() {
		val actionData = createActionData()
		val actions = registry.getActions(ActionItem.Location.MAIN_SCREEN)
			.values
			.onEach { it.prepare(actionData) }
			.filter { it.visible }
			.sortedWith(compareBy({ it.order }, { it.id }))

		// Portrait: single list. Landscape: first 3 (Create, Open, Delete) in middle, last 3 (Terminal, Preferences, Docs) on right.
		val leftActions = if (binding!!.actionsRight != null) actions.take(3) else actions
		binding!!.actions.adapter = MainActionsListAdapter(leftActions, onClick, onLongClick)
		binding!!.actionsRight?.adapter = MainActionsListAdapter(actions.drop(3), onClick, onLongClick)
	}

	private fun createActionData(): ActionData {
		return ActionData.create(requireActivity()).apply {
			put(MainViewModel::class.java, viewModel)
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		binding = null
	}
}
