/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.fragments.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.databinding.FragmentOnboardingGreetingBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.preferences.internal.prefManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.FeatureFlags
import io.sentry.Sentry

/**
 * @author Akash Yadav
 */
class GreetingFragment :
    FragmentWithBinding<FragmentOnboardingGreetingBinding>(FragmentOnboardingGreetingBinding::inflate) {

    companion object {
        private const val KEY_PRIVACY_DISCLOSURE_SHOWN = "privacy.disclosure.shown"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show privacy disclosure dialog if not shown before
        if (!isPrivacyDisclosureShown()) {
            showPrivacyDialog()
        }

        // EXPERIMENTAL MODE - Crash app in order to capture exception on Sentry
        if (FeatureFlags.isExperimentsEnabled()) {
            binding.icon.setOnLongClickListener {
                crashApp()
                true
            }
        }
    }

    private fun showPrivacyDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.privacy_disclosure_title)
            .setMessage(R.string.privacy_disclosure_message)
            .setPositiveButton(R.string.privacy_disclosure_accept) { dialog, _ ->
                markPrivacyDisclosureAsShown()
                dialog.dismiss()
            }
            .setNeutralButton(R.string.privacy_disclosure_learn_more) { _, _ ->
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
            val privacyPolicyUrl = getString(R.string.privacy_policy_url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Sentry.captureException(e)
        }
    }

    private fun crashApp() {
        throw RuntimeException()
    }

}

