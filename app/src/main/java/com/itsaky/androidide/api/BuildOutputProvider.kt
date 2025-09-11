package com.itsaky.androidide.api

import com.itsaky.androidide.ui.EditorBottomSheet
import java.lang.ref.WeakReference

/**
 * Provides access to the EditorBottomSheet instance from a decoupled context.
 * This acts as a service locator to avoid memory leaks.
 */
object BuildOutputProvider {
    private var bottomSheetRef: WeakReference<EditorBottomSheet>? = null

    fun setBottomSheet(sheet: EditorBottomSheet) {
        this.bottomSheetRef = WeakReference(sheet)
    }

    fun clearBottomSheet() {
        this.bottomSheetRef?.clear()
        this.bottomSheetRef = null
    }

    fun getBuildOutputContent(): String? {
        val bottomSheet = bottomSheetRef?.get() ?: return null
        return bottomSheet.pagerAdapter.buildOutputFragment?.getContent()
    }
}