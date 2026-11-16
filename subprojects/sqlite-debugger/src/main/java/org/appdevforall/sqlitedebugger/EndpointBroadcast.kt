package org.appdevforall.sqlitedebugger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Tells Code on the Go which port and token this process's server uses (ADFA-7137). */
internal object EndpointBroadcast {
	private const val TAG = "SqliteDebugger"

	// ACTION, EXTRA_TOKEN, EXTRA_PORT and EXTRA_SENDER repeat the constants of the same names in Code on the
	// Go's DebuggerEndpointValidation (:app), which this library cannot depend on. EndpointBroadcastTest here
	// and DebuggerEndpointContractTest there pin the same values.
	const val ACTION = "com.itsaky.androidide.SQLITE_DEBUGGER_ENDPOINT"
	const val COGO_PACKAGE = "com.itsaky.androidide"
	const val EXTRA_TOKEN = "token"
	const val EXTRA_PORT = "port"
	const val EXTRA_SENDER = "sender"
	private const val IDENTITY_ACTION = "org.appdevforall.sqlitedebugger.IDENTITY"

	/** Never throws: the app must run whether or not Code on the Go is there to hear this. */
	fun send(
		context: Context,
		token: String,
		port: Int,
	) {
		try {
			// Code on the Go reads creatorPackage, which the framework sets and no other app can forge.
			// The wrapped intent is explicit and never sent; nothing handles its action.
			val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
			val sender =
				PendingIntent.getBroadcast(
					context,
					0,
					Intent(IDENTITY_ACTION).setPackage(context.packageName),
					flags,
				)
			val intent =
				Intent(ACTION)
					.setPackage(COGO_PACKAGE)
					.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
					.putExtra(EXTRA_TOKEN, token)
					.putExtra(EXTRA_PORT, port)
					.putExtra(EXTRA_SENDER, sender)
			context.sendBroadcast(intent)
		} catch (e: Exception) {
			Log.w(TAG, "could not send the endpoint to Code on the Go", e)
		}
	}
}
