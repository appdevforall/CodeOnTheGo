package com.itsaky.androidide.utils

import android.view.View
import android.view.ViewGroup
import android.widget.ListView

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