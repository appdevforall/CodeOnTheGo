package com.itsaky.androidide.fragments

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.color.MaterialColors
import com.itsaky.androidide.R
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.utils.DialogUtils

class PackageNameChangeDialogFragment : DialogFragment() {
	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		isCancelable = false

		// Tint the info drawable
		val icon =
			AppCompatResources.getDrawable(requireContext(), R.drawable.ic_info)?.mutate()?.apply {
				val tint =
					MaterialColors.getColor(
						requireContext(),
						com.google.android.material.R.attr.colorSecondary,
						0,
					)
				DrawableCompat.setTint(this, tint)
			}

		return DialogUtils
			.newMaterialDialogBuilder(requireContext())
			.setIcon(icon)
			.setTitle(R.string.package_name_change_title)
			.setMessage(R.string.package_name_change_message)
			.setPositiveButton(R.string.learn_more) { _, _ ->
				// TODO - Navigate to "Learn More" screen; do not dismiss
				setFragmentResult(REQUEST_KEY, Bundle.EMPTY)
			}.setNegativeButton(R.string.msg_got_it) { _, _ ->
				GeneralPreferences.packageNameChangeNoticeDismissedAt = System.currentTimeMillis()
				setFragmentResult(REQUEST_KEY, Bundle.EMPTY)
			}.create()
	}

	companion object {
		const val TAG = "PackageNameChangeDialog"
		const val REQUEST_KEY = "package_name_change_dialog_dismissed"
	}
}
