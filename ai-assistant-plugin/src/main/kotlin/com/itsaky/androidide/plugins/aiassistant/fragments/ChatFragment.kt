package com.itsaky.androidide.plugins.aiassistant.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.TextView

/**
 * ChatFragment for Agent chat UI.
 * Full implementation will be completed in Task 8.
 * This is a placeholder to allow plugin tab registration.
 */
class ChatFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = TextView(requireContext()).apply {
            text = "Agent Chat - Initializing..."
        }
        return view
    }
}
