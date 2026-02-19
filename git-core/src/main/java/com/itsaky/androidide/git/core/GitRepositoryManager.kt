package com.itsaky.androidide.git.core

import android.util.Log
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * Utility class for detecting and opening Git repositories.
 */
object GitRepositoryManager {

    /**
     * Checks if a directory is a Git repository.
     */
    fun isRepository(dir: File): Boolean {
        return try {
            FileRepositoryBuilder()
                .readEnvironment()
                .findGitDir(dir)
                .build().use { repository ->
                    repository.objectDatabase.exists()
                }
        } catch (e: Exception) {
            Log.e("GitRepositoryManager", "Error checking Git repository: ${e.message}")
            false
        }
    }

    /**
     * Opens a Git repository at the given directory.
     * Returns null if no repository is found.
     */
    fun openRepository(dir: File): GitRepository? {
        return if (isRepository(dir)) {
            JGitRepository(dir)
        } else {
            null
        }
    }
}
