package org.appdevforall.cotg.corpus.recvprov

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent

/**
 * Manifest-declared receiver: each ACTION_PING delivery inserts a note into
 * [NotesProvider]. The factory instantiates a fresh receiver per delivery, so a
 * quick-build code swap takes effect on the very next ping - no restart needed.
 */
class PingReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != ACTION_PING) return
		val values = ContentValues()
		values.put(NotesProvider.COL_LABEL, "ping received QB_PING_V2")
		context.contentResolver.insert(NotesProvider.CONTENT_URI, values)
	}

	companion object {
		const val ACTION_PING = "org.appdevforall.cotg.corpus.recvprov.ACTION_PING"
	}
}
