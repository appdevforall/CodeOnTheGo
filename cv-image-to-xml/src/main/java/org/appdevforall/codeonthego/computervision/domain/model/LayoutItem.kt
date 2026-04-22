package org.appdevforall.codeonthego.computervision.domain.model

sealed interface LayoutItem {
    data class SimpleView(val box: ScaledBox) : LayoutItem
    data class HorizontalRow(val row: List<ScaledBox>) : LayoutItem
    data class RadioGroup(val boxes: List<ScaledBox>, val orientation: String) : LayoutItem
}
