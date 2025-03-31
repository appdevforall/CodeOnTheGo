package com.itsaky.androidide.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blankj.utilcode.util.Utils
import com.itsaky.androidide.common.R

object UrlManager {
    /**
     * Opens a URL with optional package restriction
     * @param url The URL to open
     * @param pkg Optional package name to restrict the intent to
     * @param context Context used to start activities (falls back to app context if null)
     */

    fun openUrl(url: String, pkg: String? = null, context: Context? = null) {
        try {
            val ctx = context ?: Utils.getApp().applicationContext

            Intent().apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                pkg?.let { setPackage(it) }
                ctx.startActivity(this)
            }
        } catch (th: Throwable) {
            when {
                pkg != null -> openUrl(url, context = context)
                th is ActivityNotFoundException -> flashError(R.string.msg_app_unavailable_for_intent)
                else -> flashError(th.message)
            }
        }
    }
}