package com.itsaky.androidide.utils.ui

import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Monitors a [RecyclerView] to detect when the user scrolls to the bottom.
 * Once the bottom is reached, the state is locked to `true` until manually reset or the layout width changes.
 *
 * @param recyclerView The list to monitor.
 * @param onScrollStateChanged Callback invoked when the [hasReachedEnd] state changes.
 */
class TemplateScrollGateKeeper(
    private val recyclerView: RecyclerView,
    private var onScrollStateChanged: (() -> Unit)?
) {
    /**
     * `true` if the user has scrolled to the bottom of the list at least once.
     */
    var hasReachedEnd = false
        private set

    private var lastWidth = -1

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            checkIfReachedEnd()
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        val currentWidth = right - left

        if (lastWidth != -1 && lastWidth != currentWidth) {
            hasReachedEnd = false
            onScrollStateChanged?.invoke()
        }
        lastWidth = currentWidth
    }

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        checkIfReachedEnd()
    }

    /**
     * Attaches scroll and layout listeners to the [RecyclerView].
     */
    fun attach() {
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    /**
     * Detaches listeners from the [RecyclerView] to prevent memory leaks.
     */
    fun detach() {
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        if (recyclerView.viewTreeObserver.isAlive) {
            recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        }
        onScrollStateChanged = null
    }

    /**
     * Resets the gatekeeper state and notifies the callback.
     */
    fun reset() {
        hasReachedEnd = false
        lastWidth = -1
        onScrollStateChanged?.invoke()
    }

    /**
     * Evaluates the scroll position and updates [hasReachedEnd] if the bottom is reached.
     */
    fun checkIfReachedEnd() {
        if (hasReachedEnd) return

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val itemCount = layoutManager.itemCount
        if (itemCount == 0) return

        val lastVisibleItem = layoutManager.findLastCompletelyVisibleItemPosition()

        if (lastVisibleItem == RecyclerView.NO_POSITION) return

        val isAtBottom = !recyclerView.canScrollVertically(1)

        if (lastVisibleItem >= itemCount - 1 || isAtBottom) {
            hasReachedEnd = true
            onScrollStateChanged?.invoke()
        }
    }
}
