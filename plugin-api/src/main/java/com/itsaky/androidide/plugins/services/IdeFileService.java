package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;

/**
 * Service for file system operations within the project directory.
 * Requires permission: filesystem.read, filesystem.write
 */
public interface IdeFileService {

    /**
     * Result of a file operation
     */
    class FileOperationResult {
        public final boolean success;
        public final String message;
        @Nullable public final String data;
        @Nullable public final String error;

        public FileOperationResult(boolean success, String message,
                                  @Nullable String data, @Nullable String error) {
            this.success = success;
            this.message = message;
            this.data = data;
            this.error = error;
        }

        public static FileOperationResult success(String message, String data) {
            return new FileOperationResult(true, message, data, null);
        }

        public static FileOperationResult failure(String error) {
            return new FileOperationResult(false, "Operation failed", null, error);
        }
    }

    /**
     * Read file contents
     * @param relativePath Path relative to project root
     * @return Operation result with file contents in data field
     */
    @NonNull
    FileOperationResult readFile(@NonNull String relativePath);

    /**
     * Create a new file with content
     * @param relativePath Path relative to project root
     * @param content File content
     * @return Operation result
     */
    @NonNull
    FileOperationResult createFile(@NonNull String relativePath, @NonNull String content);

    /**
     * Update existing file content
     * @param relativePath Path relative to project root
     * @param content New file content
     * @return Operation result
     */
    @NonNull
    FileOperationResult updateFile(@NonNull String relativePath, @NonNull String content);

    /**
     * Delete a file or directory
     * @param relativePath Path relative to project root
     * @return Operation result
     */
    @NonNull
    FileOperationResult deleteFile(@NonNull String relativePath);

    /**
     * List files in a directory
     * @param relativePath Path relative to project root (empty for project root)
     * @param recursive Whether to list recursively
     * @return Operation result with file list in data field (JSON array)
     */
    @NonNull
    FileOperationResult listFiles(@NonNull String relativePath, boolean recursive);

    /**
     * Get the project root directory
     */
    @NonNull
    File getProjectRoot();
}
