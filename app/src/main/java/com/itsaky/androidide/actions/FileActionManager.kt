package com.itsaky.androidide.actions


import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.actions.observers.FileActionObserver
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.text.StringEscapeUtils
import org.greenrobot.eventbus.EventBus
import java.io.File

class FileActionManager {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun createFile(baseDir: File, path: String, content: String, observer: FileActionObserver? = null) {
        scope.launch {
            val result = try {
                val targetFile = File(baseDir, path)

                val formattedContent = StringEscapeUtils.unescapeJava(content)
                if (!FileIOUtils.writeFileFromString(targetFile, formattedContent)) {
                    Result.failure(Exception("Failed to write to file at path: $path"))
                } else {
                    EventBus.getDefault().post(FileCreationEvent(targetFile))
                    Result.success(targetFile)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

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
