package com.codeonthego.xkcdrandom.net

/**
 * One xkcd comic. Mirrors the subset of fields we use from
 * https://xkcd.com/info.0.json.
 *
 * Kept as a plain data class with a hand-written JSON reader (see
 * [XkcdApiClient.parseComic]) so this plugin doesn't pull in a JSON
 * library — keeps the dependency graph small and the example readable.
 */
data class XkcdComic(
    val num: Int,
    val title: String,
    val alt: String,
    val imageUrl: String,
) {
    /** Canonical URL for sharing (`https://xkcd.com/<num>/`). */
    val pageUrl: String get() = "https://xkcd.com/$num/"
}
