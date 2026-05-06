package com.codeonthego.xkcdrandom.ui

/**
 * Tiny state machine that turns a stream of taps into one of three
 * classifications: SINGLE / DOUBLE / TRIPLE.
 *
 * Why we don't reuse Android's [android.view.GestureDetector]:
 *   - GestureDetector exposes onSingleTapConfirmed + onDoubleTap, but
 *     no onTripleTap. The ticket explicitly calls for triple-tap.
 *   - A 25-line state machine reads more clearly in the demo's Tier-3
 *     walkthrough than rolling extra logic on top of GestureDetector.
 *
 * Contract:
 *   - taps within [windowMillis] of each other accumulate
 *   - the FIRST tap arms a TIMEOUT (host fragment posts a delayed
 *     callback to call [resolve] after [windowMillis])
 *   - additional taps before that deadline reset the deadline (every
 *     tap extends the window)
 *   - on the deadline OR on a fourth tap, [resolve] returns the
 *     classification and the state resets
 *   - 4+ taps clamp to TRIPLE — we don't want to silently drop them.
 *
 * The class itself is pure (no clocks, no Handler) — the host fragment
 * supplies the "now" via [onTap]'s default param and decides when to
 * call [resolve]. That's what makes it unit-testable in plain JUnit
 * without Robolectric.
 */
class TapCountClassifier(
    /** Inter-tap window: a tap within this many ms of the prior tap counts as part of the burst. */
    private val windowMillis: Long = DEFAULT_WINDOW_MS,
) {
    enum class Classification { SINGLE, DOUBLE, TRIPLE }

    private var count: Int = 0
    private var lastTapAt: Long = 0L

    /**
     * Record a tap. If the tap is within [windowMillis] of the previous
     * tap, it extends the burst; otherwise it starts a new burst (and
     * the host should treat any pending unresolved burst as expired —
     * [resolve] handles that idempotently).
     *
     * Returns true if this tap closed the burst (3+ taps reached the
     * triple-tap clamp), so the caller can resolve immediately instead
     * of waiting for the timeout. Returns false if more taps could
     * still arrive within the window.
     */
    fun onTap(now: Long): Boolean {
        if (count == 0 || now - lastTapAt > windowMillis) {
            // New burst — either this is the first tap, or the previous
            // burst has timed out and was never resolved (resolve()
            // missed; we recover gracefully).
            count = 1
        } else {
            count++
        }
        lastTapAt = now
        // 3+ taps clamp to TRIPLE. Resolve eagerly so the user gets
        // immediate feedback instead of waiting out the window.
        return count >= 3
    }

    /**
     * Resolve the current burst into a classification. Returns null if
     * no taps have happened (defensive — callers usually only call
     * this after at least one onTap or via a timeout). After resolving,
     * the state is reset for the next burst.
     */
    fun resolve(): Classification? {
        val c = count
        count = 0
        lastTapAt = 0L
        return when {
            c <= 0 -> null
            c == 1 -> Classification.SINGLE
            c == 2 -> Classification.DOUBLE
            else -> Classification.TRIPLE  // 3+ clamps to TRIPLE
        }
    }

    /** True iff a burst is in progress. Host uses this to know whether to schedule a timeout. */
    fun hasPendingBurst(): Boolean = count > 0

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 300L
    }
}
