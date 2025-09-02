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
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.ImageUtils
import com.blankj.utilcode.util.ImageUtils.ImageType.TYPE_UNKNOWN
import com.itsaky.androidide.R
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.math.log

/**
 * Utilities for sharing files.
 *
 * @author Akash Yadav
 */
object IntentUtils {

	private val logger = LoggerFactory.getLogger(IntentUtils::class.java)

	private const val RESULT_LAUNCH_APP_INTENT_SENDER = 223

	// using '*/*' results in weird syntax highlighting on github
	// use this as a workaround
	private const val MIME_ANY = "*" + "/" + "*"

	@JvmStatic
	fun openImage(context: Context, file: File) {
		imageIntent(context = context, file = file, intentAction = Intent.ACTION_VIEW)
	}

	@JvmStatic
	@JvmOverloads
	fun imageIntent(
		context: Context,
		file: File,
		intentAction: String = Intent.ACTION_SEND
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
			intentAction = intentAction
		)
	}

	@JvmStatic
	fun shareFile(context: Context, file: File, mimeType: String) {
		startIntent(context = context, file = file, mimeType = mimeType)
	}

	@JvmStatic
	@JvmOverloads
	fun startIntent(
		context: Context,
		file: File,
		mimeType: String = MIME_ANY,
		intentAction: String = Intent.ACTION_SEND
	) {
		val uri =
			FileProvider.getUriForFile(
				context,
				"${context.packageName}.providers.fileprovider",
				file,
			)
		val intent =
			ShareCompat.IntentBuilder(context)
				.setType(mimeType)
				.setStream(uri)
				.intent
				.setAction(intentAction)
				.setDataAndType(uri, mimeType)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

		context.startActivity(Intent.createChooser(intent, null))
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
			return doLaunchApp(context, packageName, logError, debug)
		}
		return launchAppApi33(context, packageName, logError)
	}

	private suspend fun doLaunchApp(
		context: Context,
		packageName: String,
		logError: Boolean = true,
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
			val action = launchIntent.action ?: Intent.ACTION_MAIN
			val category = launchIntent.categories?.firstOrNull() ?: Intent.CATEGORY_LAUNCHER

			// @formatter:off
			val launchCmd = arrayOf(
				"/system/bin/am",
				"start",
				"-n", component.flattenToString(), // component name (e.g. com.itsaky.example/com.itsaky.example.MainActivity)
				"-a", action, // action (e.g. android.intent.action.MAIN)
				"-c", category, // category (e.g. android.intent.category.LAUNCHER)
				"-S", // force stop before launch
				"-D", // launch in debug mode,

				// Instead of using ADB to connect to the already-running JDWP server (like Android Studio),
				// we instruct the system to attach the JDWP agent to process before it's started.
				// We also provide options to the JDWP agent so that it connects to us on the right
				// port.
				//
				// Why not use absolute path to our build of oj-libjdwp?
				// - Because the system will only look for `libjdwp.so` in the library search paths
				// already known to it. If we provide an absolute path, it'll still have to find
				// the dependent libraries (like `libdt_socket.so`), which might fail. This can be
				// fixed by recompiling libjdwp.so to include DT_RUNPATH/DT_RPATH entries in the
				// elf file, but that's too much work given that we can already use libjdwp.so from
				// the system. In case we need to add certain features to the debugger which are
				// not already available in system's libjdwp.so, we'll have to update this to load
				// our version of the agent.
				"--attach-agent", "libjdwp.so=" + JdwpOptions.JDWP_OPTIONS,
			)
			// @formatter:on

			logger.debug("Launching app with command: {}", launchCmd.joinToString(" "))

			// TODO: Maybe use a UserService to handle this? Or maybe add custom APIs to Shizuku?
			@Suppress("DEPRECATION")
			val process =
				Shizuku.newProcess(
					// cmd =
					launchCmd,
					// env =
					null,
					// dir =
					null,
				)

			val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
			logger.debug("Launch process exited with exit code {}", exitCode)
			return exitCode == 0
		} catch (e: Throwable) {
			flashError(R.string.msg_app_launch_failed)
			if (logError) {
				logger.error(
					"Failed to launch application with package name '{}'",
					packageName,
					e,
				)
			}
			return false
		}
	}

	@RequiresApi(33)
	private fun launchAppApi33(
		context: Context,
		packageName: String,
		logError: Boolean = true
	): Boolean {
		return try {
			val sender = context.packageManager.getLaunchIntentSenderForPackage(packageName)
			sender.sendIntent(
				context,
				RESULT_LAUNCH_APP_INTENT_SENDER,
				null,
				null,
				null
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
}
