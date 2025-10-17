package com.itsaky.androidide.agent.fragments

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DisclaimerDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Disclaimer")
            .setMessage("This AI agent is experimental and may give incorrect or harmful answers. Always back up your work before you try it, and always double-check its suggestions before using them. We are not responsible for any errors, bugs, or damage caused by its output. Use at your own risk.")
            .setPositiveButton("OK", null)
            .create()
    }
}
