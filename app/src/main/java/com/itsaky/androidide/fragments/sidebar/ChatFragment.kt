package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import com.itsaky.androidide.databinding.FragmentChatBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.viewmodel.ChatViewModel

class ChatFragment :
    EmptyStateFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {

    private val chatViewModel by viewModels<ChatViewModel>(
        ownerProducer = { requireActivity() }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyStateViewModel.emptyMessage.value = "No git actions yet"
        emptyStateViewModel.isEmpty.value = false

    }

}