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

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.ImageUtils
import com.blankj.utilcode.util.ImageUtils.ImageType.TYPE_UNKNOWN
import com.itsaky.androidide.R
import com.termux.shared.reflection.ReflectionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.IllegalArgumentException

/**
 * Utilities for sharing files.
 *
 * @author Akash Yadav
 */
object IntentUtils {

    private val logger = LoggerFactory.getLogger(IntentUtils::class.java)
    private const val ENABLE_DEBUG_FLAG = true

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
        mimeType: String = "*/*",
        intentAction: String = Intent.ACTION_SEND
    ) {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.providers.fileprovider",
                file
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
        debug: Boolean = false,
        logError: Boolean = true
    ): Boolean {
        require(packageName.isNotBlank()) {
            "Package name cannot be empty"
        }

        val onError: suspend () -> Unit = {
            withContext(Dispatchers.Main.immediate) {
                flashError(R.string.msg_app_launch_failed)
            }
        }

        return try {
            doLaunchApp(context, packageName, debug, onError)
        } catch (e: Throwable) {
            if (logError) {
                logger.error("Failed to launch application with package name '{}'", packageName, e)
            }

            onError()
            false
        }
    }

    private suspend fun doLaunchApp(
        context: Context,
        packageName: String,
        debug: Boolean,
        onError: suspend () -> Unit,
    ): Boolean {
        val launchIntent = withContext(Dispatchers.Main.immediate) {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }

        if (launchIntent == null) {
            onError()
            return false
        }

        @Suppress("SimplifyBooleanWithConstants")
        if (/*short-circuit*/ ENABLE_DEBUG_FLAG && debug) {
            // noinspection WrongConstant -- hidden flag
            launchIntent.addFlags(START_FLAG_DEBUG)
        }

        context.startActivity(launchIntent)

        return true
    }

    val START_FLAG_DEBUG by lazy {
        reflectStaticField<ActivityManager, Int>("START_FLAG_DEBUG") { 2 }
    }

    private inline fun <reified T, R> reflectStaticField(
        name: String,
        crossinline default: () -> R
    ): R =
        try {
            @Suppress("UNCHECKED_CAST")
            ReflectionUtils.getDeclaredField(T::class.java, name)?.get(null) as R
        } catch (e: Throwable) {
            val default = default()
            logger.error(
                "Unable to access static field '{}'. Falling back to default value {}",
                name,
                default,
                e
            )
            default
        }
}
