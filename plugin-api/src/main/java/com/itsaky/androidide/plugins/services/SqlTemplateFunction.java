package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/**
 * A function a SQL template calls, such as {@code row_count(db, "users")}, passed to {@link SqlTemplateService#render}. It
 * runs only when the template calls it, on the render's thread, once per call.
 */
@FunctionalInterface
public interface SqlTemplateFunction {

	/**
	 * Runs the function.
	 *
	 * @param args
	 *            the arguments in the order the template wrote them, as Pebble evaluated them: null, {@link String},
	 *            {@link Long}, {@link Double}, {@link Boolean}, {@link java.util.Map} or {@link List} (never null; empty
	 *            when there are none)
	 * @return the value the template sees: null, a {@link String}, a {@link Number}, a {@link Boolean}, a
	 *         {@link java.util.Map} or a {@link List}
	 * @throws Exception
	 *             to stop the render: {@link SqlTemplateService#render} then throws {@link SqlTemplateException} with
	 *             this function's name and the exception's message. Stop when the thread is interrupted: the render's
	 *             time limit interrupts it
	 */
	@Nullable
	Object call(@NonNull List<Object> args) throws Exception;
}
