package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import com.itsaky.androidide.databinding.FragmentAgentContainerBinding
import com.itsaky.androidide.databinding.FragmentGitContainerBinding
import com.itsaky.androidide.fragments.EmptyStateFragment

class AgentFragmentContainer :
    EmptyStateFragment<FragmentAgentContainerBinding>(FragmentAgentContainerBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyStateViewModel.emptyMessage.value = "No git actions yet"
        emptyStateViewModel.isEmpty.value = false

    }

}