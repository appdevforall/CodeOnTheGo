package com.itsaky.androidide.api

import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import java.lang.ref.WeakReference

/**
 * Provides a weak reference to the current EditorHandlerActivity
 * to allow decoupled services to trigger UI actions.
 */
object ActionContextProvider {
    private var activityRef: WeakReference<EditorHandlerActivity>? = null

    fun setActivity(activity: EditorHandlerActivity) {
        this.activityRef = WeakReference(activity)
    }

    fun clearActivity() {
        this.activityRef?.clear()
        this.activityRef = null
    }

    fun getActivity(): EditorHandlerActivity? {
        return activityRef?.get()
    }
}