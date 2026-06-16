package com.itsaky.androidide.agent.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.core.content.edit
import com.itsaky.androidide.databinding.FragmentAgentContainerBinding
import com.itsaky.androidide.fragments.EmptyStateFragment

internal const val PREFS_NAME = "LlamaPrefs"
private const val DISCLAIMER_SHOWN_KEY = "disclaimer_shown"

class AgentFragmentContainer : EmptyStateFragment<FragmentAgentContainerBinding>(FragmentAgentContainerBinding::inflate) {
	override fun onFragmentLongPressed() {
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		emptyStateViewModel.setEmptyMessage("No git actions yet")
		emptyStateViewModel.setEmpty(false)
	}

	// Deferred to onResume so the disclaimer only fires when the Agent tab is
	// actually visible. ViewPager2 + FragmentStateAdapter pre-creates adjacent
	// pages through onViewCreated; only the visible page reaches onResume.
	override fun onResume() {
		super.onResume()
		showDisclaimerDialogIfNeeded()
	}

	private fun showDisclaimerDialogIfNeeded() {
		val prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val disclaimerShown = prefs.getBoolean(DISCLAIMER_SHOWN_KEY, false)

		if (!disclaimerShown) {
			DisclaimerDialogFragment().show(childFragmentManager, "DisclaimerDialogFragment")

			prefs.edit {
				putBoolean(DISCLAIMER_SHOWN_KEY, true)
			}
		}
	}
}
