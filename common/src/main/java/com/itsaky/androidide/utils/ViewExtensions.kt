

package com.itsaky.androidide.utils

import android.view.View
import android.view.ViewGroup

fun View.applyLongPressRecursively(listener: (View) -> Boolean) {
    setOnLongClickListener { listener(it) }
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).applyLongPressRecursively(listener)
        }
    }
}