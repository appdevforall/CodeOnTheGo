package com.itsaky.androidide.utils

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

fun FragmentManager.hasVisibleDialog(): Boolean {
	for (fragment in fragments) {
		if (fragment is DialogFragment && fragment.dialog?.isShowing == true) {
			return true
		}

		if (fragment.isAdded && fragment.childFragmentManager.hasVisibleDialog()) {
			return true
		}
	}
	return false
}

fun FragmentManager.dismissTopDialog(): Boolean {
	for (fragment in fragments.asReversed()) {

		if (fragment is DialogFragment && fragment.dialog?.isShowing == true) {
			fragment.dismissAllowingStateLoss()
			return true
		}

		if (fragment.isAdded && fragment.childFragmentManager.dismissTopDialog()) {
			return true
		}
	}

	return false
}
