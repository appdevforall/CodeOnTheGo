package com.itsaky.androidide.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.FileUtils
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun renameFile(file: File, newName: String, context: Context? = null, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val destFile = File(file.parentFile, newName)
            val renamed = withContext(Dispatchers.IO) {
                if (file.name.equals(newName, ignoreCase = true)) {
                    val uniqueSuffix = System.currentTimeMillis()
                    val tempFile = File(file.parentFile, "$newName-$uniqueSuffix.cotg")
                    file.renameTo(tempFile) && tempFile.renameTo(destFile)
                } else {
                    FileUtils.rename(file, newName)
                }
            }

            if (newName.length in 1..40 && renamed) {
                // Notify system of the rename
                val renameEvent = FileRenameEvent(file, File(file.parent, newName))
                if (context != null) {
                    renameEvent.put(Context::class.java, context)
                }
                FileManager.onFileRenamed(renameEvent)
                EventBus.getDefault().post(renameEvent)
                _operationResult.emit(FileOpResult.Success(R.string.renamed))
            } else {
                _operationResult.emit(FileOpResult.Error(R.string.rename_failed))
            }
            onResult?.invoke(renamed)
        }
    }
}