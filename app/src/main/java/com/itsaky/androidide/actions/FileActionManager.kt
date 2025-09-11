package com.itsaky.androidide.actions


import com.itsaky.androidide.actions.observers.FileActionObserver
import com.itsaky.androidide.api.commands.CreateFileCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileActionManager {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun execute(command: CreateFileCommand, observer: FileActionObserver? = null) {
        scope.launch {
            val result = command.execute()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val file = result.getOrNull()
                    observer?.onActionSuccess("File '${file?.name}' created successfully.", file)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "An unknown error occurred."
                    observer?.onActionFailure("Error: $error")
                }
            }
        }
    }
}