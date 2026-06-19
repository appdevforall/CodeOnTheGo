package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Service for Android resource operations.
 * Requires permission: project.structure
 */
public interface IdeResourceService {

    class ResourceOperationResult {
        public final boolean success;
        public final String message;
        @Nullable public final String data;
        @Nullable public final String error;

        public ResourceOperationResult(boolean success, String message,
                                      @Nullable String data, @Nullable String error) {
            this.success = success;
            this.message = message;
            this.data = data;
            this.error = error;
        }

        public static ResourceOperationResult success(String message, @Nullable String data) {
            return new ResourceOperationResult(true, message, data, null);
        }

        public static ResourceOperationResult success(String message) {
            return new ResourceOperationResult(true, message, null, null);
        }

        public static ResourceOperationResult failure(String error) {
            return new ResourceOperationResult(false, "Operation failed", null, error);
        }
    }

    /**
     * Get a string resource value
     * @param resourceName Resource name
     * @return Operation result with string value in data field
     */
    @NonNull
    ResourceOperationResult getString(@NonNull String resourceName);

    /**
     * Get a drawable resource path
     * @param resourceName Resource name
     * @return Operation result with drawable path in data field
     */
    @NonNull
    ResourceOperationResult getDrawable(@NonNull String resourceName);

    /**
     * Get a color resource value
     * @param resourceName Resource name
     * @return Operation result with color value in data field
     */
    @NonNull
    ResourceOperationResult getColor(@NonNull String resourceName);

    /**
     * Add a string resource to strings.xml
     * @param name Resource name
     * @param value Resource value
     * @return Operation result
     */
    @NonNull
    ResourceOperationResult addStringResource(@NonNull String name, @NonNull String value);
}
