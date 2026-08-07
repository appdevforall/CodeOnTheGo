package com.itsaky.androidide.utils

import android.content.Context
import com.itsaky.androidide.resources.R
import java.io.IOException

/**
 * Throws if this result is anything other than [TerminalInstaller.InstallResult.Success].
 * Shared by SplitAssetsInstaller and BundledAssetsInstaller so a non-Success result can't
 * be logged-and-ignored by one of them without the other -- that mismatch is exactly how
 * ADFA-5037's "install() reports Success when it actually failed" bug happened once already.
 */
fun TerminalInstaller.InstallResult.throwIfNotSuccess(context: Context) {
	when (this) {
		is TerminalInstaller.InstallResult.Success -> {}

		is TerminalInstaller.InstallResult.Error.Interactive -> {
			throw IOException("$title: $message")
		}

		is TerminalInstaller.InstallResult.Error.IsSecondaryUser -> {
			throw IOException(
				context.getString(R.string.terminal_installation_failed_secondary_user),
			)
		}

		is TerminalInstaller.InstallResult.NotInstalled -> {
			throw IllegalStateException("Terminal installation failed: NotInstalled state")
		}
	}
}
