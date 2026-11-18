package org.appdevforall.sqlitedebugger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

	// Code on the Go sends this when it pairs the app; its DebuggerEndpointAnnounce (:app) repeats the value, and
	// the same two tests pin it.
	const val ANNOUNCE_ACTION = "org.appdevforall.sqlitedebugger.ANNOUNCE"
	private const val IDENTITY_ACTION = "org.appdevforall.sqlitedebugger.IDENTITY"

	/**
	 * Sends the endpoint now, and again each time Code on the Go asks with [ANNOUNCE_ACTION]. Code on the Go drops
	 * a report from an app it has not paired, so an app already running when it pairs would otherwise stay unknown
	 * to it until the process restarts. Listens first, so a request that arrives before the first send is not lost.
	 */
	fun sendAndListen(
		context: Context,
		token: String,
		port: Int,
	) {
		listen(context, token, port)
		send(context, token, port)
	}

	/**
	 * Registered at run time, not in the manifest, so it lives as long as this process's server and a request never
	 * starts the app. Exported, because Code on the Go is another app, and not authenticated (ADFA-7131 D8): the
	 * reply goes only to Code on the Go, so a request from any other app learns nothing. Never throws.
	 */
	private fun listen(
		context: Context,
		token: String,
		port: Int,
	) {
		try {
			val receiver =
				object : BroadcastReceiver() {
					override fun onReceive(
						context: Context,
						intent: Intent,
					) {
						if (intent.action == ANNOUNCE_ACTION) send(context, token, port)
					}
				}
			val filter = IntentFilter(ANNOUNCE_ACTION)
			if (Build.VERSION.SDK_INT >= 33) {
				context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
			} else {
				context.registerReceiver(receiver, filter)
			}
		} catch (e: Exception) {
			Log.w(TAG, "could not listen for Code on the Go's requests to send the endpoint again", e)
		}
	}

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
