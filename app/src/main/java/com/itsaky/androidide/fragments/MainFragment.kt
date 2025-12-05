package com.itsaky.androidide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.adapters.MainActionsListAdapter
import com.itsaky.androidide.databinding.FragmentMainBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.MAIN_GET_STARTED
import com.itsaky.androidide.idetooltips.TooltipTag.MAIN_HELP
import com.itsaky.androidide.idetooltips.TooltipTag.MAIN_PROJECT_DELETE
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_NEW
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_OPEN
import com.itsaky.androidide.models.MainScreenAction
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_CREATE_PROJECT
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_DELETE_PROJECT
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_DOCS
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_OPEN_PROJECT
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_OPEN_TERMINAL
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_PREFERENCES
import com.itsaky.androidide.viewmodel.MainViewModel
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY

class MainFragment : BaseFragment() {
	private val viewModel by activityViewModels<MainViewModel>()
	private var binding: FragmentMainBinding? = null

	companion object {
		const val KEY_TOOLTIP_URL = "tooltip_url"
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		binding = FragmentMainBinding.inflate(inflater, container, false)
		return binding!!.root
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		val actions =
			MainScreenAction.mainScreen().also { actions ->
				val onClick = { action: MainScreenAction, _: View ->
					ifAttached {
						when (action.id) {
						ACTION_CREATE_PROJECT -> showCreateProject()
						ACTION_OPEN_PROJECT -> showViewSavedProjects()
						ACTION_DELETE_PROJECT -> pickDirectoryForDeletion()
						ACTION_OPEN_TERMINAL ->
							startActivity(
								Intent(requireActivity(), TerminalActivity::class.java),
							)

						ACTION_PREFERENCES -> gotoPreferences()

						ACTION_DOCS -> {
							val intent =
								Intent(requireContext(), HelpActivity::class.java).apply {
									putExtra(CONTENT_KEY, getString(R.string.docs_url))
									putExtra(
										CONTENT_TITLE_KEY,
										getString(R.string.back_to_cogo),
									)
								}
							startActivity(intent)
						}
						}
					}
				}
				val onLongClick = { action: MainScreenAction, _: View ->
					ifAttached { performOptionsMenuClick(action) }
					true
				}

				actions.forEach { action ->
					action.onClick = onClick
					action.onLongClick = onLongClick

					if (action.id == MainScreenAction.ACTION_OPEN_TERMINAL) {
						action.onLongClick = { _: MainScreenAction, _: View ->
							ifAttached {
								val intent =
									Intent(requireActivity(), TerminalActivity::class.java).apply {
										putExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, true)
									}
								startActivity(intent)
							}
							true
						}
					}
				}
			}

		binding!!.actions.adapter = MainActionsListAdapter(actions)

		binding!!.headerContainer?.setOnClickListener { ifAttached { openQuickstartPageAction() } }
		binding!!.headerContainer?.setOnLongClickListener {
			ifAttached { TooltipManager.showIdeCategoryTooltip(requireContext(), it, MAIN_GET_STARTED) }
			true
		}

		binding!!.greetingText.setOnLongClickListener {
			ifAttached { TooltipManager.showIdeCategoryTooltip(requireContext(), it, MAIN_GET_STARTED) }
			true
		}
		binding!!.greetingText.setOnClickListener { ifAttached { openQuickstartPageAction() } }
	}

	private fun performOptionsMenuClick(action: MainScreenAction) {
		val view = action.view
		val tag = getToolTipTagForAction(action.id)
		if (tag.isNotEmpty()) {
			view.let {
				TooltipManager.showIdeCategoryTooltip(requireContext(), it!!, tag)
			}
		}
	}

	private fun getToolTipTagForAction(id: Int): String =
		when (id) {
			ACTION_CREATE_PROJECT -> PROJECT_NEW
			ACTION_OPEN_PROJECT -> PROJECT_OPEN
			ACTION_DELETE_PROJECT -> MAIN_PROJECT_DELETE
			ACTION_DOCS -> MAIN_HELP
			else -> ""
		}

	private fun openQuickstartPageAction() {
		val intent =
			Intent(requireContext(), HelpActivity::class.java).apply {
				putExtra(CONTENT_KEY, getString(R.string.quickstart_url))
				putExtra(CONTENT_TITLE_KEY, R.string.back_to_cogo)
			}
		startActivity(intent)
	}

	override fun onDestroyView() {
		super.onDestroyView()
		binding = null
	}

	private fun pickDirectoryForDeletion() {
		viewModel.setScreen(MainViewModel.SCREEN_DELETE_PROJECTS)
	}

	private fun showCreateProject() {
		viewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
	}

	private fun showViewSavedProjects() {
		viewModel.setScreen(MainViewModel.SCREEN_SAVED_PROJECTS)
	}

	private fun gotoPreferences() {
		startActivity(Intent(requireActivity(), PreferencesActivity::class.java))
	}
}
