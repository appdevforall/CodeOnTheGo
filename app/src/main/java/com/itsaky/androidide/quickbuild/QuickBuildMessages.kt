package com.itsaky.androidide.quickbuild

import android.content.Context
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMessage

/**
 * Turns a [QuickBuildMessage] into the text the user reads.
 *
 * This is the whole reason `:quickbuild:core` names its failures instead of writing them: the
 * module has no `R`, and CoGo ships a dozen locales, so a sentence written down there would be
 * English forever. Every case maps to a string resource here; add a case and the compiler
 * demands its copy.
 */
fun QuickBuildMessage.resolve(context: Context): String =
	when (this) {
		is QuickBuildMessage.Literal -> {
			text
		}

		QuickBuildMessage.ReinstallReturnToCoGo -> {
			context.getString(R.string.quick_build_reinstall_return_to_cogo)
		}

		QuickBuildMessage.ReinstallDeclined -> {
			context.getString(R.string.quick_build_reinstall_declined)
		}

		is QuickBuildMessage.ReinstallTimedOut -> {
			context.getString(R.string.quick_build_reinstall_timed_out, seconds)
		}

		QuickBuildMessage.ReinstallWaitingForGradle -> {
			context.getString(R.string.quick_build_reinstall_waiting_for_gradle)
		}

		QuickBuildMessage.InstallCouldNotStart -> {
			context.getString(R.string.quick_build_install_could_not_start)
		}

		QuickBuildMessage.InstallFailed -> {
			context.getString(R.string.quick_build_install_failed)
		}

		is QuickBuildMessage.InstalledButUnresolvable -> {
			context.getString(R.string.quick_build_installed_but_unresolvable, packageName)
		}

		is QuickBuildMessage.ForeignAppInstalled -> {
			context.getString(R.string.quick_build_foreign_app_installed, applicationId)
		}

		QuickBuildMessage.RebuildFailed -> {
			context.getString(R.string.quick_build_rebuild_failed)
		}

		is QuickBuildMessage.DaemonRestartFailed -> {
			context.getString(R.string.quick_build_daemon_restart_failed, detail)
		}

		is QuickBuildMessage.NotEnoughStorage -> {
			context.getString(R.string.quick_build_not_enough_storage, requiredMb, availableMb)
		}

		is QuickBuildMessage.ScratchDirUnavailable -> {
			context.getString(R.string.quick_build_scratch_dir_unavailable, path)
		}

		QuickBuildMessage.DaemonRejectedConfiguration -> {
			context.getString(R.string.quick_build_daemon_rejected_config)
		}
	}
