package com.itsaky.androidide.agent.fragments

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.resources.R

class DisclaimerDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.ai_disclaimer))
            .setMessage(getString(R.string.ai_disclaimer_message))
            .setPositiveButton("OK", null)
            .create()
    }
}
