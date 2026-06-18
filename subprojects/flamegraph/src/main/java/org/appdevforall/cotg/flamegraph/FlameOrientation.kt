package org.appdevforall.cotg.flamegraph

/**
 * Vertical growth direction.
 *
 * - [TopDown] (icicle): the root sits at the top and children grow downward. Reads naturally on a
 *   phone (scroll down to go deeper) and is the default.
 * - [BottomUp] (classic flame): the root sits at the bottom and the stack grows upward.
 */
enum class FlameOrientation {
    TopDown,
    BottomUp,
}
