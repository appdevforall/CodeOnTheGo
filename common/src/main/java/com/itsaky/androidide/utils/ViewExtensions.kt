package com.itsaky.androidide.utils

import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.appcompat.app.AlertDialog

fun View.applyLongPressRecursively(listener: (View) -> Boolean) {

    if (this is ListView) return

    setOnLongClickListener { listener(it) }
    isLongClickable = true
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).applyLongPressRecursively(listener)
        }
    }
}

/**
 * Sets up a long-press listener on an AlertDialog's decor view to show a tooltip.
 *
 * This extension function allows an AlertDialog to display a tooltip when its content area
 * is long-pressed. It works by recursively attaching a long-press listener to the
 * dialog's decor view and all its children.
 *
 *  * @param listener A lambda function that will be invoked when a long-press event occurs.
 *  *                 The lambda receives the [View] that was long-pressed as its argument
 *  *                 and should return `true` if the listener has consumed the event, `false` otherwise.
 */
fun AlertDialog.onLongPress(listener: (View) -> Boolean) {
    this.setOnShowListener {
        this.window?.decorView?.applyLongPressRecursively(listener)
    }
}