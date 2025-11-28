package com.itsaky.androidide.fragments.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.appintro.SlideSelectionListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.onboarding.PermissionInfoAdapter
import com.itsaky.androidide.databinding.FragmentPermissionsInfoBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.models.PermissionInfoItem
import com.itsaky.androidide.preferences.internal.prefManager
import io.sentry.Sentry
import androidx.core.net.toUri

class PermissionsInfoFragment :
    FragmentWithBinding<FragmentPermissionsInfoBinding>(FragmentPermissionsInfoBinding::inflate),
    SlideSelectionListener {

    companion object {
        private const val KEY_PRIVACY_DISCLOSURE_SHOWN = "privacy.disclosure.shown"

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val items = listOf(
            PermissionInfoItem(R.string.permissions_info_notifications),
            PermissionInfoItem(R.string.permissions_info_storage),
            PermissionInfoItem(R.string.permissions_info_install),
            PermissionInfoItem(R.string.permissions_info_overlay_accessibility)
        )

        binding.permissionsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = PermissionInfoAdapter(items)
        }
    }

    private fun showPrivacyDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(com.itsaky.androidide.resources.R.string.privacy_disclosure_title)
            .setMessage(com.itsaky.androidide.resources.R.string.privacy_disclosure_message)
            .setPositiveButton(com.itsaky.androidide.resources.R.string.privacy_disclosure_accept) { dialog, _ ->
                markPrivacyDisclosureAsShown()
                dialog.dismiss()
            }
            .setNeutralButton(com.itsaky.androidide.resources.R.string.privacy_disclosure_learn_more) { _, _ ->
                openPrivacyPolicy()
                markPrivacyDisclosureAsShown()
            }
            .setCancelable(false)
            .show()
    }

    private fun isPrivacyDisclosureShown(): Boolean {
        return prefManager.getBoolean(KEY_PRIVACY_DISCLOSURE_SHOWN, false)
    }

    private fun markPrivacyDisclosureAsShown() {
        prefManager.putBoolean(KEY_PRIVACY_DISCLOSURE_SHOWN, true)
    }

    private fun openPrivacyPolicy() {
        try {
            val privacyPolicyUrl = getString(com.itsaky.androidide.resources.R.string.privacy_policy_url)
            val intent = Intent(Intent.ACTION_VIEW, privacyPolicyUrl.toUri())
            startActivity(intent)
        } catch (e: Exception) {
            Sentry.captureException(e)
        }
    }

    override fun onSlideSelected() {
        // Show privacy disclosure dialog if not shown before
        if (!isPrivacyDisclosureShown()) {
            showPrivacyDialog()
        }
    }

    override fun onSlideDeselected() {
    }
}