package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import java.util.List;
import java.util.Map;

/**
 * Renders the open project's SQL templates, for the SQLite debugger's {@code .read}: a plugin such as an MCP client passes the template's path, the variables it fetched from the app ({@code get_template_variables}) and the functions that read the app's data, and gets back the SQL. Rendering happens here because Code on the Go already has Pebble; the apps it builds do not.
 *
 * <p>
 * Templates are Pebble files in the project, such as {@code sqlite/report.sql.peb}, with the engine and settings of Code on the Go's project templates: the delimiters {@code ${{ }}}, {@code ${% %}} and {@code ${# #}}, strict variables (an unknown variable is an error) and Pebble's newline trimming (a closing <code>&#125;&#125;</code>, <code>%&#125;</code> or <code>#&#125;</code> eats the line break after it). One difference: autoescaping is off, as SQL is not HTML. A template quotes what it splices in with two filters: {@code sql_ident} writes an identifier in double quotes, doubling any inside, and {@code sql_literal} writes a literal (a string in single quotes, doubling any inside; a number as is, a negative one in parentheses so that {@code 1-x} cannot become a comment; a boolean as 1 or 0; null as NULL). A value the user supplies should not be spliced at all: write a parameter such as {@code :id} into the SQL and bind the value when running it.
 *
 * <p>
 * Every render also gets {@code project} ({@code name} and {@code path}, the project root) and {@code render_time} (ISO 8601, UTC). Caller variables may not use those names.
 *
 * <p>
 * The host registers this service for every plugin; a call needs the {@link com.itsaky.androidide.plugins.PluginPermission#DEBUGGER_ENDPOINTS} permission and throws {@link SecurityException} without it. The service outlives a disable, so it is closed instead: after the plugin is disabled or unloaded every call throws {@link java.util.concurrent.CancellationException} until the plugin is enabled again. Calls read the project's files, so call from a background thread: on the main thread they throw {@link IllegalStateException}.
 */
public interface SqlTemplateService {

	/**
	 * Contract revision, bumped whenever a member is added here. It marks the revision and does not negotiate one; see {@link ToolSourceRegistry#CONTRACT_VERSION}.
	 */
	int CONTRACT_VERSION = 1;

	/** The longest time limit {@link #render} accepts, in milliseconds. */
	long MAX_TIMEOUT_MILLIS = 120_000L;

	/**
	 * Gets Room's exported schemas in the open project: every {@code <class>/<version>.json} file with {@code database.version} and {@code database.identityHash}, outside build output and hidden folders. Room writes them only when {@code exportSchema} is on, so the list can be empty; a migration from version N exists when the file for N does.
	 *
	 * @return the schemas, ordered by class name and version, at most 2,000 (the first in that order; the host logs a warning when it stops there), in a list the caller cannot modify (never null; empty when no project is open). A file Room did not write, cannot be read or cannot be parsed (JSON nested more than 64 levels, say) is skipped
	 * @throws SecurityException
	 *             if the plugin lacks the {@code debugger.endpoints} permission
	 * @throws IllegalStateException
	 *             if called on the main thread
	 * @throws java.util.concurrent.CancellationException
	 *             if the plugin has been disabled or unloaded, until it is enabled again; also when a call already running finds it so at its end
	 * @throws InterruptedException
	 *             if the calling thread is interrupted while the project is read
	 */
	@NonNull
	List<RoomSchema> getRoomSchemas() throws InterruptedException;

	/**
	 * Lists the SQL templates in the open project: the files whose names end in {@code .sql.peb}, outside build output and hidden folders.
	 *
	 * @return their paths relative to the project root, with {@code /} separators, sorted, at most 2,000 (the first in that order; the host logs a warning when it stops there), in a list the caller cannot modify (never null; empty when no project is open)
	 * @throws SecurityException
	 *             if the plugin lacks the {@code debugger.endpoints} permission
	 * @throws IllegalStateException
	 *             if called on the main thread
	 * @throws java.util.concurrent.CancellationException
	 *             if the plugin has been disabled or unloaded, until it is enabled again; also when a call already running finds it so at its end
	 * @throws InterruptedException
	 *             if the calling thread is interrupted while the project is read
	 */
	@NonNull
	List<String> listTemplates() throws InterruptedException;

	/**
	 * Renders a template of the open project.
	 *
	 * <p>
	 * A variable's value is null, a {@link String}, a {@link Number}, a {@link Boolean}, a {@link Map} with {@link String} keys, a {@link List} or other {@link Iterable}, a {@link RoomSchema}, or a {@link java.util.function.Supplier} of one of these. A supplier is lazy: it is called the first time the template reads the value, at most once, and not at all when the template does not; maps and lists may hold suppliers too. A function, such as {@code row_count(db, table)}, is called each time the template calls it, with its arguments, which are passed by position: a named argument such as {@code f(a=1)} fails the render. A supplier that a function returns is read once, as a variable's is.
	 *
	 * <p>
	 * Pebble's {@code range} is limited here to 100,000 values: a larger one fails the render, as building it could exhaust the app's memory. A {@code for} loop checks for a stopped render on every pass.
	 *
	 * <p>
	 * The render runs on a thread of its own, with one time limit for the whole render, functions included. When it passes, that thread is interrupted (a function in progress should stop) and this throws. The template's own loops stop at once, but a function that ignores the interrupt keeps running on that thread after this throws, and whatever it does goes on; a function should check for the interrupt, or be short.
	 *
	 * @param templatePath
	 *            the template, relative to the project root (as {@link #listTemplates} gives it); its name must end in {@code .peb}, it must have no NUL character, and it must be inside the project once links are resolved; the file must be a regular file of at most 8 MiB
	 * @param variables
	 *            the variables, by name (not {@code project} or {@code render_time})
	 * @param functions
	 *            the functions, by name: letters, digits and {@code _}, not starting with a digit, and not a Pebble built-in function or one of the filter names
	 * @param timeoutMillis
	 *            the time limit, from 1 to {@link #MAX_TIMEOUT_MILLIS}
	 * @return the SQL, at most 4 Mi characters
	 * @throws SqlTemplateException
	 *             if the template cannot be read (it is not a regular file, such as a FIFO, or is larger than 8 MiB) or rendered, a function fails, or the time limit passes
	 * @throws IllegalArgumentException
	 *             for a path outside the project, not ending in {@code .peb} or with a NUL, a reserved or invalid name, a value of another type, or a time limit out of range
	 * @throws SecurityException
	 *             if the plugin lacks the {@code debugger.endpoints} permission
	 * @throws IllegalStateException
	 *             if called on the main thread, when no project is open, or when the project folder cannot be resolved
	 * @throws java.util.concurrent.CancellationException
	 *             if the plugin has been disabled or unloaded, until it is enabled again; also when a call already running finds it so at its end
	 * @throws InterruptedException
	 *             if the calling thread is interrupted while it waits for the render, which is then stopped
	 */
	@NonNull
	String render(@NonNull String templatePath, @NonNull Map<String, ?> variables, @NonNull Map<String, SqlTemplateFunction> functions, long timeoutMillis) throws SqlTemplateException, InterruptedException;
}
