package org.appdevforall.cotg.corpus.assetsapp

import android.content.res.AssetManager

/** Reads and formats the bundled data/message.txt asset for display. */
class AssetReader {

	fun readMessage(assets: AssetManager): String {
		val raw =
			assets.open("data/message.txt").bufferedReader().use { it.readText() }.trim()
		return "Reloaded reader formats it now: $raw"
	}
}
