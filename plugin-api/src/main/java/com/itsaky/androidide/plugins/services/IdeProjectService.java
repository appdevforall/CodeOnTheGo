package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Service for project structure operations.
 * Requires permission: project.structure
 */
public interface IdeProjectService {

    class ProjectOperationResult {
        public final boolean success;
        public final String message;
        @Nullable public final String data;
        @Nullable public final String error;

        public ProjectOperationResult(boolean success, String message,
                                     @Nullable String data, @Nullable String error) {
            this.success = success;
            this.message = message;
            this.data = data;
            this.error = error;
        }

        public static ProjectOperationResult success(String message, String data) {
            return new ProjectOperationResult(true, message, data, null);
        }

        public static ProjectOperationResult failure(String error) {
            return new ProjectOperationResult(false, "Operation failed", null, error);
        }
    }

    /**
     * Add a dependency to a build file
     * @param buildFilePath Relative path to build.gradle or build.gradle.kts
     * @param dependencyString Full dependency string (e.g., "implementation 'group:artifact:version'")
     * @return Operation result
     */
    @NonNull
    ProjectOperationResult addDependency(@NonNull String buildFilePath,
                                        @NonNull String dependencyString);

    /**
     * Trigger Gradle sync
     * @return Operation result
     */
    @NonNull
    ProjectOperationResult triggerGradleSync();

    /**
     * Check if a build is currently running
     * @return Operation result with "true" or "false" in data field
     */
    @NonNull
    ProjectOperationResult isBuildRunning();

    /**
     * Run the current application
     * @return Operation result
     */
    @NonNull
    ProjectOperationResult runApp();

    /**
     * Get build output logs
     * @return Operation result with build output in data field
     */
    @NonNull
    ProjectOperationResult getBuildOutput();
}
