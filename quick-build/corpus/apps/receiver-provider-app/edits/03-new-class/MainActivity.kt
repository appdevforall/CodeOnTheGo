package org.appdevforall.cotg.corpus.recvprov

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sends pings to [PingReceiver] and lists the notes [NotesProvider] serves.
 * The first refresh in onCreate shows the provider's seed note, proving the
 * provider initialized at process start, before this Activity.
 */
class MainActivity : Activity() {
	private lateinit var notesView: TextView
	private val badge = NoteBadge()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				gravity = Gravity.CENTER_HORIZONTAL
				setPadding(32, 64, 32, 32)
			}

		root.addView(TextView(this).apply { text = getString(R.string.main_title_label) })

		val pingButton =
			Button(this).apply {
				text = getString(R.string.ping_button_label)
				setOnClickListener {
					// Explicit component intent: implicit broadcasts don't reach
					// manifest receivers on API 26+.
					val ping = Intent(this@MainActivity, PingReceiver::class.java)
					ping.action = PingReceiver.ACTION_PING
					sendBroadcast(ping)
				}
			}
		root.addView(pingButton)

		val refreshButton =
			Button(this).apply {
				text = getString(R.string.refresh_button_label)
				// Broadcast delivery is async; the user refreshes to see the note land.
				setOnClickListener { refreshNotes() }
			}
		root.addView(refreshButton)

		notesView = TextView(this)
		root.addView(notesView)

		setContentView(root)
		refreshNotes()
	}

	private fun refreshNotes() {
		val lines = mutableListOf<String>()
		contentResolver.query(NotesProvider.CONTENT_URI, null, null, null, null)?.use { cursor ->
			val labelIndex = cursor.getColumnIndexOrThrow(NotesProvider.COL_LABEL)
			while (cursor.moveToNext()) {
				val line = getString(R.string.note_line_label, cursor.position + 1, cursor.getString(labelIndex))
				lines.add("${badge.format(cursor.position + 1)} $line")
			}
		}
		notesView.text = if (lines.isEmpty()) getString(R.string.no_notes_label) else lines.joinToString("\n")
	}
}
