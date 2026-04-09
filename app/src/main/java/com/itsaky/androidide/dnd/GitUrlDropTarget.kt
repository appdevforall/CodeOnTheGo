package com.itsaky.androidide.dnd

import android.content.ClipDescription
import android.content.Context
import android.view.DragEvent
import android.view.View
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import com.itsaky.androidide.git.core.parseGitRepositoryUrl
import com.itsaky.androidide.utils.DropHighlighter

internal class GitUrlDropTarget(
    private val context: Context,
    private val rootView: View,
    private val shouldAcceptDrop: () -> Boolean = { true },
    private val onRepositoryDropped: (String) -> Unit,
) {

    fun attach() {
        ViewCompat.setOnReceiveContentListener(
            rootView,
            supportedDropMimeTypes,
        ) { _, payload -> tryConsumeRepositoryPayload(payload) }

        bindRepositoryDropTarget(rootView)
    }

    fun detach() {
        ViewCompat.setOnReceiveContentListener(rootView, null, null)
        rootView.setOnDragListener(null)
    }

    /**
     * Attempts to consume a dropped repository URL from the given [payload].
     * Returns `null` on success to indicate consumption, or the original [payload] otherwise.
     */
    private fun tryConsumeRepositoryPayload(payload: ContentInfoCompat): ContentInfoCompat? {
        if (!shouldAcceptDrop()) {
            return payload
        }

        val repositoryUrl = extractRepositoryUrl(payload) ?: return payload

        onRepositoryDropped(repositoryUrl)
        return null
    }

    private fun bindRepositoryDropTarget(view: View) {
        val dropCallback = object : DropTargetCallback {
            override fun canAcceptDrop(event: DragEvent): Boolean {
                return shouldAcceptDrop() && event.clipDescription.isSupportedDropPayload()
            }

            override fun onDragStarted(view: View) {
                DropHighlighter.highlight(view, context)
            }

            override fun onDragEntered(view: View) {
                DropHighlighter.highlight(view, context)
            }

            override fun onDragExited(view: View) {
                DropHighlighter.clear(view)
            }

            override fun onDrop(event: DragEvent): Boolean {
                val contentInfo = ContentInfoCompat.Builder(
                    event.clipData,
                    ContentInfoCompat.SOURCE_DRAG_AND_DROP,
                ).build()

                return ViewCompat.performReceiveContent(view, contentInfo) == null
            }
        }

        view.setOnDragListener(DragEventRouter(dropCallback))
    }

    private fun extractRepositoryUrl(payload: ContentInfoCompat): String? {
        val clip = payload.clip
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val text = item.uri?.toString() ?: item.coerceToText(context)?.toString()

            if (text.isNullOrBlank()) continue

            parseGitRepositoryUrl(text)?.let { return it }
        }

        return null
    }

    private fun ClipDescription?.isSupportedDropPayload(): Boolean {
        if (this == null) {
            return false
        }

        return supportedDropMimeTypes.any(::hasMimeType)
    }

    private companion object {
        val supportedDropMimeTypes = arrayOf(
            ClipDescription.MIMETYPE_TEXT_PLAIN,
            ClipDescription.MIMETYPE_TEXT_URILIST,
        )
    }
}
