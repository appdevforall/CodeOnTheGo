package com.itsaky.androidide.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager
import com.blankj.utilcode.util.Utils
import com.itsaky.androidide.common.R

object UrlManager {
    private const val PREF_EXTERNAL_URL_WHITELIST = "external_url_whitelist"

    fun openUrl(url: String, pkg: String? = null, context: Context? = null) {
        val ctx = context ?: Utils.getApp().applicationContext

        if (needsConsent(url, ctx)) {
            if (ctx is Activity) {
                showConsentDialog(url, pkg, ctx)
            } else {
                openUrlInternal(url, pkg, ctx)
            }
        } else {
            openUrlInternal(url, pkg, ctx)
        }
    }

    private fun needsConsent(url: String, context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val whitelist = prefs.getStringSet(PREF_EXTERNAL_URL_WHITELIST, emptySet()) ?: emptySet()
        return !whitelist.contains(url)
    }

    private fun showConsentDialog(url: String, pkg: String?, context: Activity) {
        DialogUtils.newMaterialDialogBuilder(context)
            .setTitle(R.string.url_consent_title)
            .setMessage(context.getString(R.string.url_consent_message, url))
            .setPositiveButton(R.string.url_consent_proceed) { dialog, _ ->
                dialog.dismiss()
                openUrlInternal(url, pkg, context)
            }
            .setNegativeButton(R.string.url_consent_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton(R.string.url_consent_dont_ask) { dialog, _ ->
                dialog.dismiss()
                addToWhitelist(url, context)
                openUrlInternal(url, pkg, context)
            }
            .setCancelable(true)
            .show()
    }

    private fun addToWhitelist(url: String, context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val whitelist = prefs.getStringSet(PREF_EXTERNAL_URL_WHITELIST, emptySet())?.toMutableSet() ?: mutableSetOf()
        whitelist.add(url)
        prefs.edit().putStringSet(PREF_EXTERNAL_URL_WHITELIST, whitelist).apply()
    }

    private fun openUrlInternal(url: String, pkg: String?, context: Context) {
        try {
            Intent().apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                pkg?.let { setPackage(it) }
                context.startActivity(this)
            }
        } catch (th: Throwable) {
            when {
                pkg != null -> openUrlInternal(url, null, context)
                th is ActivityNotFoundException -> flashError(R.string.msg_app_unavailable_for_intent)
                else -> flashError(th.message)
            }
        }
    }
}