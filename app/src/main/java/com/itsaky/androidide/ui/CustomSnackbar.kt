package com.itsaky.androidide.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.R

class CustomSnackbar(private val context: Context, private val rootView: View) {

    private var snackbar: Snackbar? = null

    @SuppressLint("RestrictedApi")
    fun show(
        message: String,
        textFirstAction: String?,
        textSecondaryAction: String?,
        actionFirst: (() -> Unit)? = null,
        actionSecondary: (() -> Unit)? = null
    ) {
        snackbar = Snackbar.make(rootView, "", Snackbar.LENGTH_LONG)

        val snackbarView = snackbar?.view
        val inflater = LayoutInflater.from(context)

        val customView = inflater.inflate(R.layout.snackbar_custom, null)

        val textView = customView.findViewById<TextView>(R.id.snackbar_message)
        textView.text = message

        val btnFirstAction = customView.findViewById<Button>(R.id.snackbar_action_yes)
        val btnSecondAction = customView.findViewById<Button>(R.id.snackbar_action_no)

        textFirstAction?.let {
            btnFirstAction.text = it
        }
        textSecondaryAction?.let {
            btnSecondAction.text = it
        }

        btnFirstAction.setOnClickListener {
            actionFirst?.invoke()
            snackbar?.dismiss()
        }

        btnSecondAction.setOnClickListener {
            actionSecondary?.invoke()
            snackbar?.dismiss()
        }

        snackbarView?.setPadding(0, 0, 0, 0)
        (snackbarView as? Snackbar.SnackbarLayout)?.apply {
            removeAllViews()
            addView(customView)
        }
        snackbar?.show()
    }

    fun dismiss() {
        snackbar?.dismiss()
    }
}