package com.example.images

import android.graphics.RectF

/**
 * A hierarchical data structure to represent a UI element and its children.
 * This is the core of the new, robust tree-based architecture.
 */
data class UiNode(
    val detection: ComputerVisionActivity.DetectionResult,
    val children: MutableList<UiNode> = mutableListOf()
) {
    // Convenience properties to access the underlying detection data
    val boundingBox: RectF
        get() = detection.boundingBox

    val label: String
        get() = detection.label

    val text: String
        get() = detection.text
}
