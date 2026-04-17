package org.appdevforall.codeonthego.computervision.domain.model

import android.graphics.Rect

data class ScaledBox(
    val label: String, var text: String, val x: Int, val y: Int, val w: Int, val h: Int,
    val centerX: Int, val centerY: Int, val rect: Rect
)
