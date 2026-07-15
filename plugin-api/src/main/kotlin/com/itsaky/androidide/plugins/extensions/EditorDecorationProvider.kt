
package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin

/**
 * Additive editor decoration extension: contributes foreground-color spans for regions of editor
 * text, layered on top of the normal syntax highlighting.
 *
 * The provider owns all of its own logic — it decides which characters to color and how. The IDE
 * is feature-agnostic: it simply calls [decorate] for each analyzed region and merges the returned
 * spans as foreground overrides. Because the only output is a list of colored ranges, a provider
 * can never replace or suppress the editor's existing highlighting.
 *
 * Typical uses: depth-colored brackets, indent guides, TODO/marker tinting, semantic emphasis.
 */
interface EditorDecorationProvider : IPlugin {

    /**
     * Return additive, foreground-only color spans for the region `[start, end)` of [text].
     *
     * Called on a background analysis thread, once per analyzed region (typically a line), and may
     * be called frequently — keep it fast. [text] is the full, read-only document content so a
     * provider can use context outside the region (e.g. compute nesting depth by scanning the
     * prefix); only characters within `[start, end)` may be decorated.
     *
     * Returned [DecorationSpan]s use absolute character offsets into [text], must fall within
     * `[start, end)`, and should not overlap one another. Spans outside the region are ignored.
     *
     * @param text full document content (read-only).
     * @param start absolute character offset of the region start (inclusive).
     * @param end absolute character offset of the region end (exclusive).
     * @param isDark whether the editor is currently showing a dark theme.
     */
    fun decorate(text: CharSequence, start: Int, end: Int, isDark: Boolean): List<DecorationSpan>
}

/**
 * An additive foreground-color span. [start] and [end] are absolute character offsets into the
 * document ([end] exclusive); [argb] is the foreground color to apply, as a packed ARGB int.
 */
data class DecorationSpan(val start: Int, val end: Int, val argb: Int)
