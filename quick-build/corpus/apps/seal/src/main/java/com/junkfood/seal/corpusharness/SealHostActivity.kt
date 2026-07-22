package com.junkfood.seal.corpusharness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.junkfood.seal.util.SponsorData
import com.junkfood.seal.util.VideoInfo

/**
 * Corpus harness entry point: exercises a real Seal util subgraph (SponsorData,
 * VideoInfo -- both @Serializable data models yt-dlp/GraphQL parsing hangs off
 * of) with no Room/Hilt/Compose/native-yt-dlp wiring. See README.md.
 */
class SealHostActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val info = VideoInfo(id = "abc123", title = "Sample video")
		val hasFormats = info.formats?.isEmpty() ?: true

		val view = TextView(this)
		view.id = ID_SUMMARY
		view.text = "video=${info.title} noFormats=$hasFormats sponsorDataClass=${SponsorData::class.simpleName}"
		setContentView(view)
	}

	companion object {
		const val ID_SUMMARY = 5001
	}
}
