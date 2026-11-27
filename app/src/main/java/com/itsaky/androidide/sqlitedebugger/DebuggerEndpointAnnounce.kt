package com.itsaky.androidide.sqlitedebugger

import android.content.Context
import android.content.Intent
import org.slf4j.LoggerFactory

/**
 * Asks a paired app's SQLite debugger to report its endpoint again (ADFA-7137). The app reports once, when its
 * server starts, and [DebuggerEndpointReceiver] drops a report from an app with no pairing row, so an app that was
 * already running when Code on the Go paired it would otherwise stay unknown until its process restarts. An app
 * that is not running gets nothing and starts nothing: the library listens only while its server runs, and reports
 * when it starts.
 */
object DebuggerEndpointAnnounce {
	/** Repeats the library's EndpointBroadcast.ANNOUNCE_ACTION; DebuggerEndpointContractTest pins it. */
	const val ACTION = "org.appdevforall.sqlitedebugger.ANNOUNCE"

	private val log = LoggerFactory.getLogger(DebuggerEndpointAnnounce::class.java)

	/** Never throws: pairing goes ahead whether or not the app is there to hear this. */
	fun send(
		context: Context,
		applicationId: String,
	) {
		try {
			context.sendBroadcast(Intent(ACTION).setPackage(applicationId))
		} catch (e: Exception) {
			log.warn("Could not ask {} to report its SQLite debugger endpoint", applicationId, e)
		}
	}
}
