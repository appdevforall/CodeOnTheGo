package com.itsaky.androidide.plugins.manager.services

import android.os.Looper
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.manager.services.sqltemplate.ProjectSqlFiles
import com.itsaky.androidide.plugins.manager.services.sqltemplate.SqlTemplateRenderer
import com.itsaky.androidide.plugins.services.RoomSchema
import com.itsaky.androidide.plugins.services.SqlTemplateException
import com.itsaky.androidide.plugins.services.SqlTemplateFunction
import com.itsaky.androidide.plugins.services.SqlTemplateService
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.EnumSet

/**
 * The [SqlTemplateService] the plugin manager registers for each plugin (ADFA-7302). It is gated on
 * [PluginPermission.DEBUGGER_ENDPOINTS], the permission a plugin already needs to reach the debugger whose variables
 * and functions a template uses: it reads only `.peb` templates and Room's schema files, inside the open project.
 */
internal class SqlTemplateServiceImpl(
	private val pluginId: String,
	permissions: Set<PluginPermission>,
	private val projectRootProvider: () -> File?,
	private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
	private val clock: () -> Instant = Instant::now,
) : SqlTemplateService {
	private val permissions: Set<PluginPermission> =
		Collections.unmodifiableSet(EnumSet.noneOf(PluginPermission::class.java).apply { addAll(permissions) })

	@Throws(InterruptedException::class)
	override fun getRoomSchemas(): List<RoomSchema> {
		checkCaller("getRoomSchemas")
		val root = projectRootProvider() ?: return emptyList()
		return Collections.unmodifiableList(ProjectSqlFiles.roomSchemas(root.canonicalFile))
	}

	@Throws(InterruptedException::class)
	override fun listTemplates(): List<String> {
		checkCaller("listTemplates")
		val root = projectRootProvider() ?: return emptyList()
		return Collections.unmodifiableList(ProjectSqlFiles.templates(root.canonicalFile))
	}

	@Throws(SqlTemplateException::class, InterruptedException::class)
	override fun render(
		templatePath: String,
		variables: Map<String, *>,
		functions: Map<String, SqlTemplateFunction>,
		timeoutMillis: Long,
	): String {
		checkCaller("render")
		val root = (projectRootProvider() ?: throw IllegalStateException("No project is open")).canonicalFile
		val file = templateFile(root, templatePath)
		for (name in SqlTemplateRenderer.RESERVED_VARIABLES) {
			require(name !in variables) { "\"$name\" is set by Code on the Go; use another name" }
		}
		if (file.length() > ProjectSqlFiles.MAX_FILE_BYTES) {
			throw SqlTemplateException("$templatePath is larger than ${ProjectSqlFiles.MAX_FILE_BYTES / (1024 * 1024)} MiB", null)
		}
		val text =
			try {
				file.readText()
			} catch (e: IOException) {
				throw SqlTemplateException("$templatePath could not be read: ${e.message}", e)
			}
		val all = LinkedHashMap<String, Any?>(variables)
		all["project"] = mapOf("name" to root.name, "path" to root.path)
		all["render_time"] = clock().toString()
		return SqlTemplateRenderer.render(templatePath, text, all, functions, timeoutMillis)
	}

	/** The template, which must name a `.peb` file inside [root] once links are resolved. */
	private fun templateFile(
		root: File,
		templatePath: String,
	): File {
		require(templatePath.endsWith(".peb")) { "A template's name ends in .peb: $templatePath" }
		require(!File(templatePath).isAbsolute) { "The template path is relative to the project root: $templatePath" }
		val file = File(root, templatePath).canonicalFile
		require(file.path.startsWith(root.path + File.separator)) { "$templatePath is outside the project" }
		return file
	}

	private fun checkCaller(method: String) {
		if (PluginPermission.DEBUGGER_ENDPOINTS !in permissions) {
			throw SecurityException(
				"Plugin $pluginId does not have required permissions: ${PluginPermission.DEBUGGER_ENDPOINTS.name}",
			)
		}
		check(!isMainThread()) { "SqlTemplateService.$method() reads the project's files; call it off the main thread" }
	}
}
