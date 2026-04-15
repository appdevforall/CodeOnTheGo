package com.itsaky.androidide.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import androidx.core.graphics.drawable.toDrawable

object DropHighlighter {
    /**
     * Applies a highlight foreground to the [view] to indicate an active drop target,
     * saving its original foreground state safely.
     */
    fun highlight(view: View, context: Context) {
        if (view.getTag(R.id.filetree_drop_target_tag) == null) {
            view.setTag(R.id.filetree_drop_target_tag, view.foreground ?: "NULL_FG")
        }

        val baseColor = ContextCompat.getColor(context, R.color.teal_200)
        val highlightColor = (baseColor and 0x00FFFFFF) or (64 shl 24)

        view.foreground = highlightColor.toDrawable()
    }

    /**
     * Restores the original foreground of the [view] and clears the drop target highlight.
     */
    fun clear(view: View) {
        val savedFg = view.getTag(R.id.filetree_drop_target_tag) ?: return

        view.foreground = if (savedFg == "NULL_FG") null else savedFg as? Drawable
        view.setTag(R.id.filetree_drop_target_tag, null)
    }
}
