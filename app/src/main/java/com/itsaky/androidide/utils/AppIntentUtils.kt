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

package com.itsaky.androidide.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.blankj.utilcode.util.ImageUtils
import com.blankj.utilcode.util.ImageUtils.ImageType.TYPE_UNKNOWN
import com.itsaky.androidide.R
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.io.File

/**
 * App-specific intent utilities for file sharing, image handling, and app launching.
 *
 * This extends FileShareUtils from the common module with app-specific functionality
 * for image handling and application launching.
 *
 * @author Akash Yadav
 */
object AppIntentUtils {
    private val logger = LoggerFactory.getLogger(AppIntentUtils::class.java)

	private const val RESULT_LAUNCH_APP_INTENT_SENDER = 223

	@JvmStatic
	fun openImage(
		context: Context,
		file: File,
	) {
		imageIntent(context = context, file = file, intentAction = Intent.ACTION_VIEW)
	}

	@JvmStatic
	@JvmOverloads
	fun imageIntent(
		context: Context,
		file: File,
		intentAction: String = Intent.ACTION_SEND,
	) {
		val type = ImageUtils.getImageType(file)
		var typeString = type.value
		if (type == TYPE_UNKNOWN) {
			typeString = "*"
		}
		startIntent(
			context = context,
			file = file,
			mimeType = "image/$typeString",
			intentAction = intentAction,
		)
	}

	@JvmStatic
	fun shareFile(
		context: Context,
		file: File,
		mimeType: String,
	) {
        // Delegate to FileShareUtils in common module to avoid code duplication
        FileShareUtils.shareFile(context, file, mimeType)
	}

	@JvmStatic
	@JvmOverloads
	fun startIntent(
		context: Context,
		file: File,
        mimeType: String = "*/*",
		intentAction: String = Intent.ACTION_SEND,
	) {
        // Delegate to FileShareUtils in common module to avoid code duplication
        FileShareUtils.startIntent(context, file, mimeType, intentAction)
	}

	/**
	 * Launch the application with the given [package name][packageName].
	 *
	 * @param context The context that will be used to fetch the launch intent.
	 * @param packageName The package name of the application.
	 */
	@JvmOverloads
	suspend fun launchApp(
		context: Context,
		packageName: String,
		logError: Boolean = true,
		debug: Boolean = false,
	): Boolean {
		if (debug || Build.VERSION.SDK_INT < 33) {
			return doLaunchApp(context, packageName, debug)
		}
		return launchAppApi33(context, packageName, logError)
	}

	private suspend fun doLaunchApp(
		context: Context,
		packageName: String,
		debug: Boolean = false,
	): Boolean {
		try {
			val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
			if (launchIntent == null) {
				flashError(R.string.msg_app_launch_failed)
				return false
			}

			if (!debug) {
				// launch-only, simply start the activity
				context.startActivity(launchIntent)
				return true
			}

			if (!Shizuku.pingBinder()) {
				logger.debug("Shizuku service is not running. Cannot debug app.")
				return false
			}

			val component = launchIntent.component!!
			return PrivilegedActions.launchApp(
				component = component,
				action = launchIntent.action ?: Intent.ACTION_MAIN,
				categories = launchIntent.categories ?: setOf(Intent.CATEGORY_LAUNCHER),
				forceStop = true,
				debugMode = true,
			)
		} catch (e: Throwable) {
			flashError(R.string.msg_app_launch_failed)
			return false
		}
	}

	@RequiresApi(33)
	private fun launchAppApi33(
		context: Context,
		packageName: String,
		logError: Boolean = true,
	): Boolean =
		try {
			val sender = context.packageManager.getLaunchIntentSenderForPackage(packageName)
			sender.sendIntent(
				context,
				RESULT_LAUNCH_APP_INTENT_SENDER,
				null,
				null,
				null,
			)
			true
		} catch (e: Throwable) {
			flashError(R.string.msg_app_launch_failed)
			if (logError) {
				logger.error("Failed to launch app", e)
			}
			false
		}
}
