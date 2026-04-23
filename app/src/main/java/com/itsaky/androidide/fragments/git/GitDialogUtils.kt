package com.itsaky.androidide.fragments.git

import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.DialogGitCredentialsBinding
import com.itsaky.androidide.git.core.GitCredentialsManager

fun Fragment.showGitCredentialsDialog(
    credentialsManager: GitCredentialsManager,
    positiveButtonTextResId: Int,
    onPositiveClick: (username: String, token: String) -> Unit
) {
    val dialogBinding = DialogGitCredentialsBinding.inflate(layoutInflater)

    dialogBinding.username.setText(credentialsManager.getUsername())
    dialogBinding.token.setText(credentialsManager.getToken())

    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.git_credentials_title)
        .setView(dialogBinding.root)
        .setPositiveButton(positiveButtonTextResId) { _, _ ->
            val username = dialogBinding.username.text?.toString()?.trim()
            val token = dialogBinding.token.text?.toString()?.trim()
            if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                onPositiveClick(username, token)
            }
        }
        .setNeutralButton(R.string.git_credentials_clear) { _, _ ->
            credentialsManager.clearCredentials()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    dialog.setOnShowListener {
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        
        val validate = {
            val user = dialogBinding.username.text?.toString()?.trim()
            val token = dialogBinding.token.text?.toString()?.trim()
            positiveButton.isEnabled = !user.isNullOrBlank() && !token.isNullOrBlank()
        }
        
        dialogBinding.username.doAfterTextChanged { validate() }
        dialogBinding.token.doAfterTextChanged { validate() }
        
        validate()
    }

    dialog.show()
}
