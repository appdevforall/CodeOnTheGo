package com.itsaky.androidide.actions.sidebar

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.ChatHistoryAdapter
import com.itsaky.androidide.databinding.FragmentChatHistoryBinding
import com.itsaky.androidide.viewmodel.ChatViewModel
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ChatHistoryFragment : Fragment(R.layout.fragment_chat_history) {

    private val chatViewModel: ChatViewModel by activityViewModel()

    private var _binding: FragmentChatHistoryBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChatHistoryBinding.bind(view)

        binding.historyToolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        chatViewModel.sessions.observe(viewLifecycleOwner, Observer { sessions ->
            val adapter = ChatHistoryAdapter(sessions) { session ->
                chatViewModel.setCurrentSession(session.id)
                findNavController().popBackStack()
            }
            binding.historyRecyclerView.adapter = adapter
            binding.historyRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}