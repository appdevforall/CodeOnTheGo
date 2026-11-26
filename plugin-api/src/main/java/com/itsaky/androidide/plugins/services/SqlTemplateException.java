package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A SQL template could not be rendered: the file could not be read, the template has a syntax error or uses a
 * variable it was not given, a {@link SqlTemplateFunction} failed, a filter was given a value it cannot quote, or
 * the time limit passed. The message says which, with the template's line where Pebble gives one, and is meant to be
 * shown to the user or the agent as it is.
 */
public class SqlTemplateException extends Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message
	 *            what went wrong (must not be null)
	 * @param cause
	 *            the underlying failure, or null
	 */
	public SqlTemplateException(@NonNull String message, @Nullable Throwable cause) {
		super(message, cause);
	}
}
