package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;

/**
 * Service for Android resource operations.
 * Requires permission: project.structure
 */
public interface IdeResourceService {

    class ResourceOperationResult {
        public final boolean success;
        public final String message;
        public final String error;

        public ResourceOperationResult(boolean success, String message, String error) {
            this.success = success;
            this.message = message;
            this.error = error;
        }

        public static ResourceOperationResult success(String message) {
            return new ResourceOperationResult(true, message, null);
        }

        public static ResourceOperationResult failure(String error) {
            return new ResourceOperationResult(false, "Operation failed", error);
        }
    }

    /**
     * Add a string resource to strings.xml
     * @param name Resource name
     * @param value Resource value
     * @return Operation result
     */
    @NonNull
    ResourceOperationResult addStringResource(@NonNull String name, @NonNull String value);
}
