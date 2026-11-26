package org.appdevforall.sqlitedebugger

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import org.appdevforall.sqlitedebugger.engine.NativeDebugEngine
import org.appdevforall.sqlitedebugger.mcp.DatabaseTools
import org.appdevforall.sqlitedebugger.mcp.McpHttp
import org.appdevforall.sqlitedebugger.mcp.McpProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Starts the debugger when the app's process starts. Refuses to run in a build that is not
 * debuggable, so a release build that includes this library by mistake crashes at once instead of
 * opening a local server.
 */
class DebuggerStartup : ContentProvider() {
	override fun onCreate(): Boolean {
		val context = context ?: return false
		check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
			"The SQLite debugger is in a build that is not debuggable. Remove it from release builds."
		}
		val databaseDir = File(context.applicationInfo.dataDir, "databases")
		// Throwable, not Exception: a missing library is an UnsatisfiedLinkError, and an Error that
		// escapes here kills the host app. A debugger that cannot start is disabled, never a crash.
		try {
			// Synchronous: an app that opens its database in Application.onCreate or another
			// provider would otherwise be missed.
			val engine = NativeDebugEngine()
			engine.install()
			thread(name = "sqlite-debugger-start", isDaemon = true) {
				try {
					startServer(context, engine, databaseDir)
				} catch (t: Throwable) {
					Log.e(TAG, "could not start the server", t)
				}
			}
		} catch (t: Throwable) {
			Log.e(TAG, "the SQLite debugger is disabled", t)
		}
		return true
	}

	private fun startServer(
		context: Context,
		engine: NativeDebugEngine,
		databaseDir: File,
	) {
		val app = appInfo(context)
		val tools = DatabaseTools(engine, databaseDir, environment = { JSONObject().put("app", app).put("device", deviceInfo()) })
		val token = McpHttp.newToken()
		val protocol = McpProtocol(tools.tools(), "1", { UUID.randomUUID().toString() }, postTimeLimitMs = DatabaseTools.DEADLINE_MS)
		val http = McpHttp(protocol, { token }, log = { Log.w(TAG, it) })
		http.start()
		port = http.boundPort
		endpointToken = token
		Log.i(TAG, "listening on 127.0.0.1:${http.boundPort}")
		EndpointBroadcast.sendAndListen(context, token, http.boundPort)
	}

	/**
	 * get_template_variables' app object. No build type: AGP 8 generates no BuildConfig by default, and nothing else
	 * tells the library the app's variant. Only the package when the package manager fails: the server still starts.
	 */
	private fun appInfo(context: Context): JSONObject {
		val app = JSONObject().put("package", context.packageName)
		val info =
			try {
				context.packageManager.getPackageInfo(context.packageName, 0)
			} catch (e: Exception) {
				Log.w(TAG, "could not read the app's version", e)
				return app
			}
		val versionCode =
			if (Build.VERSION.SDK_INT >= 28) {
				info.longVersionCode
			} else {
				@Suppress("DEPRECATION")
				info.versionCode.toLong()
			}
		return app
			.put("version_name", info.versionName ?: JSONObject.NULL)
			.put("version_code", versionCode)
	}

	/** Read on each call: the user can change the locale while the app runs. */
	private fun deviceInfo(): JSONObject =
		JSONObject()
			.put("api_level", Build.VERSION.SDK_INT)
			.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: JSONObject.NULL)
			.put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
			.put("locale", Locale.getDefault().toLanguageTag())

	override fun query(
		uri: Uri,
		projection: Array<String>?,
		selection: String?,
		selectionArgs: Array<String>?,
		sortOrder: String?,
	): Cursor? = null

	override fun getType(uri: Uri): String? = null

	override fun insert(
		uri: Uri,
		values: ContentValues?,
	): Uri? = null

	override fun delete(
		uri: Uri,
		selection: String?,
		selectionArgs: Array<String>?,
	): Int = 0

	override fun update(
		uri: Uri,
		values: ContentValues?,
		selection: String?,
		selectionArgs: Array<String>?,
	): Int = 0

	companion object {
		private const val TAG = "SqliteDebugger"

		/** Set once the server listens. */
		@Volatile var port: Int = 0
			private set

		@Volatile var endpointToken: String? = null
			private set
	}
}
