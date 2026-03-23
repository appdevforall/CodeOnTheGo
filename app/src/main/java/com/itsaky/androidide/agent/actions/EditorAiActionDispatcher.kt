package com.itsaky.androidide.agent.actions

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.agent.viewmodel.ChatViewModel
import com.itsaky.androidide.viewmodel.BottomSheetViewModel

object EditorAiActionDispatcher {

    fun dispatch(
        data: ActionData,
        fullPrompt: String,
        originalUserText: String
    ): Boolean {
        val context = data.get(Context::class.java) ?: return false
        val activity = context as? BaseEditorActivity ?: return false

        activity.bottomSheetViewModel.setSheetState(
            sheetState = BottomSheetBehavior.STATE_EXPANDED,
            currentTab = BottomSheetViewModel.TAB_AGENT
        )

        val chatViewModel = ViewModelProvider(activity)[ChatViewModel::class.java]
        chatViewModel.sendMessage(fullPrompt, originalUserText, activity.applicationContext)

        return true
    }
}
