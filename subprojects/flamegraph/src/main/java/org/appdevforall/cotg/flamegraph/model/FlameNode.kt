package org.appdevforall.cotg.flamegraph.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A node in a weighted hierarchy rendered by the flamegraph.
 *
 * The tree is fully general-purpose: it can describe CPU samples, allocation sizes, retained heap,
 * on-disk sizes, or any other quantity that aggregates up a hierarchy.
 *
 * [value] is the **inclusive** (total) weight of the node and determines its width. A node's "self"
 * weight is `value - children.sumOf { it.value }`. Children are laid out left-to-right within the
 * horizontal band the parent occupies. Callers should keep `value >= children.sumOf { it.value }`;
 * inconsistent input is tolerated (the layout clamps it) but never produces a negative self weight.
 *
 * @param T type of the opaque [payload] returned to click callbacks. Use [Nothing] (the default for
 *   trees built without payloads) when no payload is needed.
 */
@Immutable
data class FlameNode<out T>(
    val id: String,
    val label: String,
    val value: Double,
    val children: List<FlameNode<T>> = emptyList(),
    val color: Color? = null,
    val payload: T? = null,
)
