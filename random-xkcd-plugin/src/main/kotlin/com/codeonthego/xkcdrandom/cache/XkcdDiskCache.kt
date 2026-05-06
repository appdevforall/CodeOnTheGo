package com.codeonthego.xkcdrandom.cache

import android.content.Context
import com.codeonthego.xkcdrandom.net.XkcdComic
import org.json.JSONObject
import java.io.File

/**
 * Last-comic cache: a single comic on disk (one JSON file + one PNG
 * file) so the panel can render something on cold start without a
 * network round-trip.
 *
 * Deliberately *not* an LRU. The plan calls for "last comic only"
 * because the demo's job is to teach offline-fallback discipline, not
 * to ship a full image cache.
 *
 * Files live under context.cacheDir/xkcd/.
 */
class XkcdDiskCache(context: Context) {

    private val dir: File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    private val metaFile = File(dir, "last.json")
    private val imageFile = File(dir, "last.png")

    /** Persist a comic + its decoded PNG bytes. Best-effort; failures are logged by the caller. */
    fun save(comic: XkcdComic, pngBytes: ByteArray) {
        // Skip persistence if the image is suspiciously large. Cap is
        // generous (bigger than any xkcd in practice) but bounded so a
        // pathological response can't fill the cache dir.
        if (pngBytes.size > MAX_IMAGE_BYTES) return
        metaFile.writeText(toJson(comic))
        imageFile.writeBytes(pngBytes)
    }

    /** Load the last cached comic, or null if there's nothing cached / cache is corrupt. */
    fun loadComic(): XkcdComic? = try {
        if (!metaFile.exists()) null else fromJson(metaFile.readText())
    } catch (_: Exception) {
        null
    }

    /** Returns the cached PNG file, or null if not present. */
    fun loadImageFile(): File? = imageFile.takeIf { it.exists() && it.length() > 0 }

    private fun toJson(c: XkcdComic): String = JSONObject().apply {
        put("num", c.num)
        put("title", c.title)
        put("alt", c.alt)
        put("img", c.imageUrl)
    }.toString()

    private fun fromJson(s: String): XkcdComic = JSONObject(s).let {
        XkcdComic(
            num = it.getInt("num"),
            title = it.getString("title"),
            alt = it.getString("alt"),
            imageUrl = it.getString("img"),
        )
    }

    companion object {
        private const val DIR_NAME = "xkcd"
        // 5 MB cap — comfortably above the largest xkcd PNG, but bounded.
        // Ref: codeonthego-review-checklist.md theme #1 (resource bounds).
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    }
}
