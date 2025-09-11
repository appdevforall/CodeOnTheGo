package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.FileUtils
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import java.io.File

sealed class FileOpResult {
    data class Success(val messageRes: Int) : FileOpResult()
    data class Error(val messageRes: Int) : FileOpResult()
}

class FileManagerViewModel : ViewModel() {

    // Use a SharedFlow for one-time events like showing a toast.
    private val _operationResult = MutableSharedFlow<FileOpResult>()
    val operationResult = _operationResult.asSharedFlow()

    fun renameFile(file: File, newName: String) {
        viewModelScope.launch {
            val renamed = newName.length in 1..40 && FileUtils.rename(file, newName)

            if (renamed) {
                // Notify system of the rename
                val renameEvent = FileRenameEvent(file, File(file.parent, newName))
                EventBus.getDefault().post(renameEvent)
                _operationResult.emit(FileOpResult.Success(com.itsaky.androidide.resources.R.string.renamed))
            } else {
                _operationResult.emit(FileOpResult.Error(com.itsaky.androidide.resources.R.string.rename_failed))
            }
        }
    }
}